package com.github.wadahiro.midpoint.tester;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType;

/**
 * Reports ({@link MidPoint#reports()}): exports run as the GUI's "Run report" does, in a report export task; the run
 * includes the task's evaluations (collection filter and condition, subreports, column expressions). Imports (4.4+)
 * run as the GUI's "Import report" does, in a report import task (column import expressions, import script).
 *
 * <pre>{@code
 * Reports.Output out = mp.reports().export("users-by-cost-center", Map.of("costCenter", "IT-100"));
 * assertThat(out.lines()).contains("\"tanaka\";\"Taro Tanaka\";...");
 * Selection fullName = out.run().select().within("column", "name", "fullName");
 * }</pre>
 */
public interface Reports {

    /** Exports the report (by name or OID) with its default output (CSV for a collection report). */
    Output export(String report);

    /**
     * Exports the report with these parameter values (parameter name to real value). A {@code Collection} value gives
     * the parameter several values, as a report task's {@code reportParam} may hold them (4.8+ pass them to the
     * expressions as a list; 4.4 fails the export).
     */
    Output export(String report, Map<String, Object> parameters);

    /**
     * Imports CSV lines with an import report (by name or OID; {@code behavior/direction} {@code import}), as the GUI's
     * "Import report" does (4.4+): the lines are written to a file, a {@code ReportDataType} points to it, and a report
     * import task runs. The run holds the task's evaluations: the columns' {@code import/expression} and the
     * {@code behavior/importScript}.
     *
     * <ul>
     *   <li>With an {@code objectCollection}, each row becomes an object of the view's type, imported by
     *       {@code ModelService.importObject} with {@code behavior/importOptions}. midPoint skips the header row and
     *       takes the columns by their position in the view ({@code previousColumn} order, the collection's
     *       {@code defaultView} columns first), not by the header's labels. A column's import expression gets the
     *       cell as {@code input} (a list for a cell with the multi-value delimiter).</li>
     *   <li>Without one, each row runs the import script with one variable per header name.</li>
     * </ul>
     *
     * The import continues after a row fails: such an import ends with a partial error, which is an outcome to assert
     * ({@link Import#status()}), not an exception. The driver throws {@link OperationFailed} only when the task fails
     * as a whole (unlike {@link #export}, which throws on any status but success).
     *
     * @param lines the file's lines, the header first (UTF-8, {@code ;} and {@code "} unless the report's
     *        {@code fileFormat} says otherwise)
     */
    Import importFrom(String report, List<String> lines);

    /** {@link #importFrom(String, List)} with the lines of a file (UTF-8). */
    Import importFrom(String report, File csv);

    /** The outcome of a report import. */
    final class Import {
        private final Run run;
        private final String taskOid;
        private final OperationResultStatusType status;
        private final String message;
        private final List<ObjectType> created;
        private final List<ObjectType> modified;

        public Import(Run run, String taskOid, OperationResultStatusType status, String message,
                List<ObjectType> created, List<ObjectType> modified) {
            this.run = run;
            this.taskOid = taskOid;
            this.status = status;
            this.message = message;
            this.created = List.copyOf(created);
            this.modified = List.copyOf(modified);
        }

        public Run run() {
            return run;
        }

        /** The import task. */
        public String taskOid() {
            return taskOid;
        }

        /** The import task's result: {@code SUCCESS}, or {@code PARTIAL_ERROR} when some rows failed. */
        public OperationResultStatusType status() {
            return status;
        }

        /** The task result's message (what failed), or null. */
        public String message() {
            return message;
        }

        /** Focus objects (users, roles, orgs, services...) the import added, as stored after it. */
        public List<ObjectType> created() {
            return created;
        }

        /** Focus objects that existed before and changed during the import, as stored after it. */
        public List<ObjectType> modified() {
            return modified;
        }

        @Override
        public String toString() {
            return "import " + status + ": " + created.size() + " created, " + modified.size() + " modified";
        }
    }

    final class Output {
        private final Run run;
        private final String taskOid;
        private final List<String> lines;

        public Output(Run run, String taskOid, List<String> lines) {
            this.run = run;
            this.taskOid = taskOid;
            this.lines = List.copyOf(lines);
        }

        public Run run() {
            return run;
        }

        /** The export task. */
        public String taskOid() {
            return taskOid;
        }

        /** The lines of the output file (for CSV: the header, then one line per row). */
        public List<String> lines() {
            return lines;
        }

        @Override
        public String toString() {
            return String.join("\n", lines);
        }
    }
}
