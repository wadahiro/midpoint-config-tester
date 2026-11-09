package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.evolveum.midpoint.model.api.ActivitySubmissionOptions;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import com.evolveum.midpoint.schema.util.ReportParameterTypeUtil;
import com.evolveum.midpoint.test.RepoSimpleObjectResolver;
import com.evolveum.midpoint.test.ReportTestUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivityDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ClassicReportExportWorkDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ClassicReportImportWorkDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportDataType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportParameterType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ThreadStopActionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.WorkDefinitionsType;
import com.github.wadahiro.midpoint.tester.OperationFailed;
import com.github.wadahiro.midpoint.tester.Reports;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * {@link Reports} on 4.8: the task {@code ReportManager.runReport} submits (a classic report export activity), whose
 * OID the driver needs, so it is submitted here the same way; likewise the import task of
 * {@code ReportManager.importReport}.
 */
final class ReportsImpl implements Reports {

    private final MidPointImpl mp;

    ReportsImpl(MidPointImpl mp) {
        this.mp = mp;
    }

    @Override
    public Output export(String report) {
        return export(report, Map.of());
    }

    @Override
    public Output export(String report, Map<String, Object> parameters) {
        ReportType bean = mp.existing(ReportType.class, report);
        Run run = mp.runAs(null, "export report " + bean.getName().getOrig(), (task, result) -> {
            ClassicReportExportWorkDefinitionType work = new ClassicReportExportWorkDefinitionType()
                    .reportRef(ObjectTypeUtil.createObjectRef(bean));
            if (!parameters.isEmpty()) {
                ReportParameterType params = new ReportParameterType();
                for (Map.Entry<String, Object> e : parameters.entrySet()) {
                    // a collection gives the parameter several values (as a task's reportParam may have them)
                    Object[] values = e.getValue() instanceof Collection
                            ? ((Collection<?>) e.getValue()).toArray() : new Object[] { e.getValue() };
                    ReportParameterTypeUtil.addParameter(params, e.getKey(), values);
                }
                work.reportParam(params);
            }
            TaskType template = new TaskType()
                    .name("Export task for " + bean.getName().getOrig() + " (tester)")
                    .threadStopAction(ThreadStopActionType.CLOSE);
            String oid = mp.bean(ModelInteractionService.class).submit(
                    new ActivityDefinitionType().work(new WorkDefinitionsType().reportExport(work)),
                    ActivitySubmissionOptions.create().withTaskTemplate(template), task, result);
            return new MidPointImpl.Outcome(oid, null);
        });
        PrismObject<TaskType> task = ReportImports.task(mp, run).asPrismObject();
        OperationResultStatusType status = task.asObjectable().getResultStatus();
        if (status != OperationResultStatusType.SUCCESS) {
            throw new OperationFailed("Export of " + report + " ended with " + status + ": "
                    + ReportImports.failureMessage(task.asObjectable()), run, null);
        }
        List<String> lines = mp.query("report output", (t, result) -> {
            File file = ReportTestUtil.findOutputFile(task, RepoSimpleObjectResolver.get(), new OperationResult("output"));
            if (file == null) {
                throw new IllegalStateException("No output file of " + report);
            }
            return Files.readAllLines(file.toPath());
        });
        return new Output(run, run.oid(), lines);
    }

    @Override
    public Import importFrom(String report, File csv) {
        return importFrom(report, ReportImports.read(csv));
    }

    /**
     * The task {@code ReportManager.importReport} submits (a classic report import activity on a report data object
     * pointing to the file), submitted here the same way for its OID.
     */
    @Override
    public Import importFrom(String report, List<String> lines) {
        return importFrom(report, lines, null);
    }

    @Override
    public Import importFrom(String report, List<String> lines, String username) {
        ReportType bean = mp.existing(ReportType.class, report);
        String name = bean.getName().getOrig();
        File file = ReportImports.writeCsv(name, lines);
        String dataOid = mp.query("report data", (t, result) -> {
            ReportDataType data = new ReportDataType()
                    .name(name + "-IMPORT " + file.getName())
                    .filePath(file.getPath())
                    .reportRef(new ObjectReferenceType().oid(bean.getOid()).type(ReportType.COMPLEX_TYPE));
            mp.prismContext().adopt(data);
            return mp.bean(RepositoryService.class).addObject(data.asPrismObject(), null, result);
        });
        Map<String, String> before = ReportImports.versions(mp);
        Run run = mp.runAs(username, "import report " + name, (task, result) -> {
            ClassicReportImportWorkDefinitionType work = new ClassicReportImportWorkDefinitionType()
                    .reportRef(ObjectTypeUtil.createObjectRef(bean))
                    .reportDataRef(new ObjectReferenceType().oid(dataOid).type(ReportDataType.COMPLEX_TYPE));
            TaskType template = new TaskType()
                    .name("Import task for " + name + " (tester)")
                    .threadStopAction(ThreadStopActionType.CLOSE);
            String oid = mp.bean(ModelInteractionService.class).submit(
                    new ActivityDefinitionType().work(new WorkDefinitionsType().reportImport(work)),
                    ActivitySubmissionOptions.create().withTaskTemplate(template), task, result);
            return new MidPointImpl.Outcome(oid, null);
        });
        return ReportImports.outcome(mp, run, report, before);
    }
}
