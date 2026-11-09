package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.MutablePrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.report.api.ReportConstants;
import com.evolveum.midpoint.report.api.ReportManager;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractReportWorkDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportDataType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportParameterType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.github.wadahiro.midpoint.tester.OperationFailed;
import com.github.wadahiro.midpoint.tester.Reports;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * {@link Reports} on 4.4: {@code ReportManager.runReport} on a task of its own (it switches that task to the
 * background as a classic report export activity); the output is the file of the report's newest
 * {@code ReportDataType}.
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
            String name = "Export task for " + bean.getName().getOrig() + " (tester)";
            Task export = mp.harness.createTask(name);
            export.setName(name);
            mp.bean(ReportManager.class).runReport(bean.asPrismObject(), parameters(parameters), export, result);
            return new MidPointImpl.Outcome(export.getOid(), null);
        });
        PrismObject<TaskType> task = ReportImports.task(mp, run).asPrismObject();
        OperationResultStatusType status = task.asObjectable().getResultStatus();
        if (status != OperationResultStatusType.SUCCESS) {
            throw new OperationFailed("Export of " + report + " ended with " + status + ": "
                    + ReportImports.failureMessage(task.asObjectable()), run, null);
        }
        List<String> lines = mp.query("report output", (t, result) -> {
            ObjectQuery query = mp.prismContext().queryFor(ReportDataType.class)
                    .item(ReportDataType.F_REPORT_REF).ref(bean.getOid())
                    .build();
            PrismObject<ReportDataType> data = mp.bean(RepositoryService.class)
                    .searchObjects(ReportDataType.class, query, null, result).stream()
                    .max(Comparator.comparing(o -> o.asObjectable().getMetadata() != null
                            && o.asObjectable().getMetadata().getCreateTimestamp() != null
                            ? o.asObjectable().getMetadata().getCreateTimestamp().toGregorianCalendar().getTimeInMillis()
                            : 0L))
                    .orElseThrow(() -> new IllegalStateException("No output of " + report));
            return Files.readAllLines(new File(data.asObjectable().getFilePath()).toPath());
        });
        return new Output(run, run.oid(), lines);
    }

    @Override
    public Import importFrom(String report, File csv) {
        return importFrom(report, ReportImports.read(csv));
    }

    /**
     * {@code ReportManager.importReport} on a task of its own (it switches that task to the background as a classic
     * report import activity), with a report data object pointing to the file, as the GUI's "Import report" does.
     */
    @Override
    public Import importFrom(String report, List<String> lines) {
        ReportType bean = mp.existing(ReportType.class, report);
        String name = bean.getName().getOrig();
        File file = ReportImports.writeCsv(name, lines);
        PrismObject<ReportDataType> data = mp.query("report data", (t, result) -> {
            ReportDataType reportData = new ReportDataType()
                    .name(name + "-IMPORT " + file.getName())
                    .filePath(file.getPath())
                    .reportRef(new ObjectReferenceType().oid(bean.getOid()).type(ReportType.COMPLEX_TYPE));
            mp.prismContext().adopt(reportData);
            mp.bean(RepositoryService.class).addObject(reportData.asPrismObject(), null, result);
            return reportData.asPrismObject();
        });
        Map<String, String> before = ReportImports.versions(mp);
        Run run = mp.runAs(null, "import report " + name, (task, result) -> {
            String taskName = "Import task for " + name + " (tester)";
            Task importTask = mp.harness.createTask(taskName);
            importTask.setName(taskName);
            mp.bean(ReportManager.class).importReport(bean.asPrismObject(), data, importTask, result);
            return new MidPointImpl.Outcome(importTask.getOid(), null);
        });
        return ReportImports.outcome(mp, run, report, before);
    }

    /** The parameters as the 4.4 GUI builds them (dynamic properties in the report extension namespace). */
    private PrismContainer<ReportParameterType> parameters(Map<String, Object> parameters) throws Exception {
        if (parameters.isEmpty()) {
            return null;
        }
        PrismContext prismContext = mp.prismContext();
        PrismContainerDefinition<ReportParameterType> definition = prismContext.getSchemaRegistry()
                .findContainerDefinitionByElementName(ReportConstants.REPORT_PARAMS_PROPERTY_NAME);
        PrismContainer<ReportParameterType> container = definition.instantiate();
        PrismContainerValue<ReportParameterType> value = new ReportParameterType().asPrismContainerValue();
        value.revive(prismContext);
        container.add(value);
        String namespace = ReportConstants.NS_EXTENSION + "/" + AbstractReportWorkDefinitionType.F_REPORT_PARAM;
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            // a collection gives the parameter several values (as a task's reportParam may have them; the 4.4 GUI
            // gives one)
            List<?> values = e.getValue() instanceof Collection
                    ? List.copyOf((Collection<?>) e.getValue()) : List.of(e.getValue());
            QName type = prismContext.getSchemaRegistry().determineTypeForClass(values.get(0).getClass());
            MutablePrismPropertyDefinition<Object> property = prismContext.definitionFactory()
                    .createPropertyDefinition(new QName(namespace, e.getKey()), type);
            property.setDynamic(true);
            property.setRuntimeSchema(true);
            property.setMaxOccurs(1);
            PrismProperty<Object> item = property.instantiate();
            for (Object v : values) {
                item.addRealValue(v);
            }
            value.add(item);
        }
        return container;
    }
}
