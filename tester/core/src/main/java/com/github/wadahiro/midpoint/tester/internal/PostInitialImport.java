package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.model.scripting_3.ExecuteScriptType;
import com.evolveum.midpoint.xml.ns._public.model.scripting_3.ScriptingExpressionType;

/**
 * midPoint's post-initial import ({@code PostInitialDataImport} and {@code DataImport.sortFiles} of
 * {@code gui/admin-gui}, the same loop on 4.0, 4.4 and 4.8), on a directory of the tester instead of
 * {@code midpoint.home/post-initial-objects}. That class cannot be used as is: it lives in the GUI module, reads the
 * directory from the midPoint home, renames the files it has done and only logs its failures.
 *
 * <ol>
 *   <li>The files of the directory and its subdirectories ({@code listFiles}), sorted by the number the first three
 *       characters of the file name make when they are all digits, else 0 ({@code sortFiles}: a stable sort). midPoint
 *       leaves files with the same number in the file system's order; here they are in the order of their paths.</li>
 *   <li>{@code .done} files are skipped, other files that are not {@code .xml} are skipped with a warning.</li>
 *   <li>A file whose single item is a bulk action ({@code executeScript} or a scripting expression) is executed
 *       ({@link LineDrivers#executePostInitialScript}); any other file (an object, {@code <objects>}, a file that does
 *       not parse as one item) is imported by {@code ModelService.importObjectsFromFile} with the line's options
 *       ({@link LineDrivers#importPostInitialFile}: raw on 4.0, full processing on 4.4+), in a task of the line's
 *       init channel.</li>
 *   <li>The first file that fails stops the import. midPoint stops only when the call throws; an object of the file
 *       that fails is recorded in the result and the file still counts as done. Here a file fails when its result is
 *       an error, so a broken object cannot pass unnoticed.</li>
 * </ol>
 * Nothing is renamed.
 */
final class PostInitialImport {

    private static final String SUFFIX_FOR_IMPORTED_FILE = "done";
    private static final String XML_SUFFIX = "xml";

    /** What the import did, file by file. */
    static final class Outcome {
        /** The files done, in order; the bulk actions among them. */
        final List<File> done = new ArrayList<>();
        final List<File> executed = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        /** The file that failed (null: none), its result, and the exception if the call threw. */
        File failedFile;
        OperationResult failedResult;
        Throwable failure;
    }

    private PostInitialImport() {
    }

    /** The files in the order the post-initial import takes them. */
    static List<File> files(File root) {
        List<File> files = new ArrayList<>();
        listFiles(root, files);
        // DataImport.sortFiles: Arrays.sort (stable) by the number of the name's first three characters
        files.sort(Comparator.comparingInt(PostInitialImport::numberFromName));
        return files;
    }

    /** {@code PostInitialDataImport.listFiles}, each directory's entries in name order (midPoint: file system order). */
    private static void listFiles(File folder, List<File> out) {
        File[] entries = folder.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File file : entries) {
            if (file.isFile()) {
                out.add(file);
            } else if (file.isDirectory()) {
                listFiles(file, out);
            }
        }
    }

    /** {@code DataImport.getNumberFromName}. */
    static int numberFromName(File file) {
        String name = file.getName();
        String number = name.length() > 3 ? name.substring(0, 3) : name;
        if (number.matches("[\\d]+")) {
            return Integer.parseInt(number);
        }
        return 0;
    }

    /** {@code FilenameUtils.getExtension}. */
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot > separator ? name.substring(dot + 1) : "";
    }

    /**
     * Runs the import of the directory with the line's drivers, logged in as the administrator; stops at the first
     * failure (see {@link Outcome#failedFile}). Quartz is the caller's business.
     */
    static Outcome run(Harness harness, LineDrivers drivers, File root) {
        Outcome outcome = new Outcome();
        PrismContext prismContext = harness.bean(PrismContext.class);
        Task task = harness.createTask("post-initial import (tester)");
        task.setChannel(drivers.postInitialChannel());
        OperationResult mainResult = task.getResult();
        for (File file : files(root)) {
            String fileExtension = extension(file.getName());
            if (fileExtension.equals(SUFFIX_FOR_IMPORTED_FILE)) {
                continue;
            }
            if (!fileExtension.equals(XML_SUFFIX)) {
                outcome.warnings.add("Post-initial import support only xml files. Actual file: " + file.getName());
                continue;
            }
            Object item = null;
            try {
                item = prismContext.parserFor(file).parseItem();
            } catch (Exception e) {
                // as midPoint: noted, then the file goes to the import (e.g. <objects>, which is not one item)
            }
            Object bulkAction = null;
            if (item instanceof PrismProperty) {
                Object value = ((PrismProperty<?>) item).getRealValue();
                if (value instanceof ScriptingExpressionType || value instanceof ExecuteScriptType) {
                    bulkAction = value;
                }
            }
            OperationResult result = mainResult.createSubresult(bulkAction != null
                    ? "com.evolveum.midpoint.init.DataImport.executeScript"
                    : "com.evolveum.midpoint.init.DataImport.importObject");
            result.addParam("file", file.getPath());
            Throwable failure = null;
            try {
                Object action = bulkAction;
                harness.inOperationContext("post-initial import " + file.getName(), task, () -> {
                    if (action != null) {
                        String console = drivers.executePostInitialScript(action, task, result);
                        result.addReturn("console", console);
                    } else {
                        drivers.importPostInitialFile(file, task, result);
                    }
                    return null;
                });
            } catch (Exception | AssertionError e) {
                failure = e;
                result.recordFatalError(e);
            }
            result.computeStatusIfUnknown();
            result.computeStatus();
            if (failure != null || result.isError()) {
                outcome.failedFile = file;
                outcome.failedResult = result;
                outcome.failure = failure;
                return outcome;
            }
            outcome.done.add(file);
            if (bulkAction != null) {
                outcome.executed.add(file);
            }
        }
        return outcome;
    }

    /** The errors of the result as an indented tree: operation, status, message (successful branches left out). */
    static String errors(OperationResult result) {
        StringBuilder sb = new StringBuilder();
        errors(result, 0, sb);
        return sb.toString();
    }

    private static void errors(OperationResult result, int depth, StringBuilder sb) {
        if (depth > 12 || !result.isError() && !result.isWarning() && depth > 0) {
            return;
        }
        sb.append("  ".repeat(depth)).append(result.getOperation()).append(": ").append(result.getStatus());
        if (result.getMessage() != null) {
            sb.append(": ").append(result.getMessage());
        }
        sb.append('\n');
        for (OperationResult sub : result.getSubresults()) {
            errors(sub, depth + 1, sb);
        }
    }
}
