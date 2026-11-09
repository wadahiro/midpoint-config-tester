package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.github.wadahiro.midpoint.tester.OperationFailed;
import com.github.wadahiro.midpoint.tester.Reports;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * What the report drivers of the lines share: the file the GUI would upload, the focus objects before and after, the
 * task's outcome and its failure message. The {@code ReportDataType} and the task are line-specific ({@code ReportsImpl}).
 */
final class ReportImports {

    private ReportImports() {
    }

    /**
     * Writes the lines (UTF-8) to a new file under {@code <midPoint home>/import}, where the GUI puts uploaded
     * files; returns it with an absolute path, as the report data needs.
     */
    static File writeCsv(String report, List<String> lines) {
        String home = System.getProperty("midpoint.home");
        File dir = new File(home != null ? home : TesterRuntime.get().workDir().toString(), "import");
        File file = new File(dir, report.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + UUID.randomUUID() + ".csv")
                .getAbsoluteFile();
        try {
            Files.createDirectories(dir.toPath());
            Files.writeString(file.toPath(), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
        return file;
    }

    static List<String> read(File csv) {
        try {
            return Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + csv, e);
        }
    }

    /**
     * The report task (of the run's OID) with its operation result, which the repository returns only on request.
     */
    static TaskType task(MidPointImpl mp, Run run) {
        return mp.query("report task", (t, result) -> mp.bean(RepositoryService.class).getObject(TaskType.class,
                run.oid(), SelectorOptions.createCollection(mp.prismContext().toUniformPath(TaskType.F_RESULT),
                        GetOperationOptions.createRetrieve()), result)).asObjectable();
    }

    /**
     * What a failed task reports: the message of its result, or else the first message of a failed result below it
     * (e.g. the exception of the activity run, which the task's own result does not repeat).
     */
    static String failureMessage(TaskType task) {
        String message = firstMessage(task.getResult());
        return message != null ? message : "";
    }

    private static String firstMessage(OperationResultType result) {
        if (result == null) {
            return null;
        }
        if (result.getMessage() != null && !result.getMessage().isEmpty()) {
            return result.getMessage();
        }
        for (OperationResultType partial : result.getPartialResults()) {
            if (partial.getStatus() == OperationResultStatusType.FATAL_ERROR
                    || partial.getStatus() == OperationResultStatusType.PARTIAL_ERROR) {
                String message = firstMessage(partial);
                if (message != null) {
                    return message;
                }
            }
        }
        return null;
    }

    /** The version of every focus object, by OID. */
    static Map<String, String> versions(MidPointImpl mp) {
        return mp.query("focus versions", (task, result) -> {
            Map<String, String> versions = new HashMap<>();
            for (PrismObject<FocusType> o : mp.bean(RepositoryService.class)
                    .searchObjects(FocusType.class, null, null, result)) {
                versions.put(o.getOid(), o.getVersion());
            }
            return versions;
        });
    }

    /**
     * The outcome of the import task of the run: fails when the task failed as a whole (or did not close); a partial
     * error (rows that failed) is returned.
     */
    static Reports.Import outcome(MidPointImpl mp, Run run, String report, Map<String, String> before) {
        TaskType task = task(mp, run);
        OperationResultStatusType status = task.getResultStatus();
        String message = task.getResult() != null ? task.getResult().getMessage() : null;
        if (status == null || status == OperationResultStatusType.FATAL_ERROR
                || status == OperationResultStatusType.IN_PROGRESS) {
            throw new OperationFailed("Import of " + report + " ended with " + status + ": " + failureMessage(task),
                    run, null);
        }
        List<ObjectType> created = new ArrayList<>();
        List<ObjectType> modified = new ArrayList<>();
        mp.query("focus after import", (t, result) -> {
            for (PrismObject<FocusType> o : mp.bean(RepositoryService.class)
                    .searchObjects(FocusType.class, null, null, result)) {
                String version = before.get(o.getOid());
                if (version == null) {
                    created.add(o.asObjectable());
                } else if (!version.equals(o.getVersion())) {
                    modified.add(o.asObjectable());
                }
            }
            return null;
        });
        return new Reports.Import(run, run.oid(), status, message, created, modified);
    }
}
