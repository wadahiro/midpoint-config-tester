package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.tester.CsvFile;

/**
 * The file of a resource on the real CSV connector, as a {@link CsvFile} and as a target system: accounts are the rows,
 * named by the name column ({@code nameAttribute}, else {@code uniqueAttribute}); the unique, name and password
 * columns are not attributes. The file is one object class: groups and other classes are not supported.
 */
final class CsvTargetImpl implements ManagedTarget, CsvFile {

    private final String resourceOid;
    private final SimulatedActivation simulatedActivation;
    private final Path path;
    private final Charset charset;
    private final char delimiter;
    private final char quote;
    private final String escape;
    private final String commentMarker;
    private final String recordSeparator;
    private final String uniqueColumn;
    private final String nameColumn;
    private final String passwordColumn;
    private final String multivalueDelimiter;

    CsvTargetImpl(RealResourceXml resource, Path path) {
        this.resourceOid = resource.oid;
        this.simulatedActivation = resource.simulatedActivation;
        this.path = path;
        this.charset = Charset.forName(resource.property("encoding", "utf-8"));
        // the connector's defaults
        this.delimiter = resource.property("fieldDelimiter", ";").charAt(0);
        this.quote = resource.property("quote", "\"").charAt(0);
        this.escape = resource.property("escape", "\\");
        this.commentMarker = resource.property("commentMarker", "#");
        this.recordSeparator = unescape(resource.property("recordSeparator", "\r\n"));
        this.uniqueColumn = resource.property("uniqueAttribute", null);
        if (uniqueColumn == null) {
            throw new IllegalArgumentException(resource.name + ": the CSV connector needs uniqueAttribute");
        }
        this.nameColumn = resource.property("nameAttribute", uniqueColumn);
        this.passwordColumn = resource.property("passwordAttribute", null);
        this.multivalueDelimiter = resource.property("multivalueDelimiter", null);
    }

    /**
     * The header the file starts with: unique, name, password columns, then the attributes of schemaHandling, then the
     * other attributes of the stored schema's account classes.
     */
    static List<String> header(RealResourceXml resource) {
        Set<String> columns = new LinkedHashSet<>();
        String unique = resource.property("uniqueAttribute", null);
        if (unique != null) {
            columns.add(unique);
        }
        columns.add(resource.property("nameAttribute", unique));
        String password = resource.property("passwordAttribute", null);
        if (password != null) {
            columns.add(password);
        }
        columns.addAll(resource.accountAttributes);
        columns.remove(null);
        return new ArrayList<>(columns);
    }

    /** Creates the file with the header. */
    void create(List<String> header) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        writeTable(header, List.of());
    }

    // ---- CsvFile ------------------------------------------------------------------------------------------------

    @Override
    public Path path() {
        return path;
    }

    @Override
    public List<String> header() {
        List<List<String>> records = records();
        return records.isEmpty() ? List.of() : records.get(0);
    }

    @Override
    public List<Map<String, String>> rows() {
        List<List<String>> records = records();
        List<Map<String, String>> rows = new ArrayList<>();
        if (records.isEmpty()) {
            return rows;
        }
        List<String> header = records.get(0);
        for (List<String> record : records.subList(1, records.size())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < record.size() ? record.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public void write(List<? extends Map<String, ?>> rows) {
        List<String> header = header();
        List<Map<String, String>> converted = new ArrayList<>();
        for (Map<String, ?> row : rows) {
            converted.add(convert(header, row));
        }
        writeRows(header, converted);
    }

    @Override
    public void addRow(Map<String, ?> row) {
        List<Map<String, String>> rows = rows();
        rows.add(convert(header(), row));
        writeRows(header(), rows);
    }

    @Override
    public String text() {
        try {
            return Files.readString(path, charset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeText(String text) {
        try {
            Files.writeString(path, text, charset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- TargetSystem -------------------------------------------------------------------------------------------

    @Override
    public String resourceOid() {
        return resourceOid;
    }

    @Override
    public QName accountNamingAttribute() {
        // the connector gives __NAME__ the column's name
        return new QName(Xml.NS_RI, nameColumn);
    }

    @Override
    public void addAccount(String name, Map<String, ?> attributes) {
        if (account(name) != null) {
            throw new IllegalArgumentException("Row " + name + " already exists in " + path);
        }
        Map<String, Object> row = new LinkedHashMap<>(attributes);
        row.put(nameColumn, name);
        if (!row.containsKey(uniqueColumn)) {
            throw new IllegalArgumentException("Row " + name + " needs a value of the unique column " + uniqueColumn);
        }
        addRow(row);
    }

    @Override
    public void modifyAccount(String name, String attribute, Object... values) {
        List<Map<String, String>> rows = rows();
        Map<String, String> row = existing(rows, name);
        checkColumn(header(), attribute);
        row.put(attribute, join(List.of(values)));
        writeRows(header(), rows);
    }

    @Override
    public void renameAccount(String name, String newName) {
        List<Map<String, String>> rows = rows();
        existing(rows, name).put(nameColumn, newName);
        writeRows(header(), rows);
    }

    @Override
    public void deleteAccount(String name) {
        List<Map<String, String>> rows = rows();
        rows.remove(existing(rows, name));
        writeRows(header(), rows);
    }

    @Override
    public Map<String, List<Object>> account(String name) {
        for (Map<String, String> row : rows()) {
            if (name.equals(row.get(nameColumn))) {
                Map<String, List<Object>> out = new TreeMap<>();
                row.forEach((column, value) -> {
                    if (!column.equals(nameColumn) && !column.equals(uniqueColumn) && !column.equals(passwordColumn)
                            && !value.isEmpty()) {
                        out.put(column, split(value));
                    }
                });
                return out;
            }
        }
        return null;
    }

    @Override
    public Object attribute(String account, String attribute) {
        List<Object> values = attributeValues(account, attribute);
        if (values.size() > 1) {
            throw new AssertionError("Column " + attribute + " of " + account + " has several values: " + values);
        }
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public List<Object> attributeValues(String account, String attribute) {
        String value = existing(rows(), account).get(attribute);
        return value == null || value.isEmpty() ? new ArrayList<>() : split(value);
    }

    @Override
    public List<String> accountNames() {
        List<String> names = new ArrayList<>();
        rows().forEach(row -> names.add(row.get(nameColumn)));
        names.sort(null);
        return names;
    }

    @Override
    public boolean enabled(String account) {
        if (simulatedActivation == null) {
            throw new IllegalStateException("The CSV connector has no activation capability, and resource "
                    + resourceOid + " simulates none (capabilities/configured/activation/status/attribute)");
        }
        return simulatedActivation.enabled(this, account);
    }

    @Override
    public void setEnabled(String account, boolean enabled) {
        if (simulatedActivation == null) {
            throw new IllegalStateException("The CSV connector has no activation capability, and resource "
                    + resourceOid + " simulates none (capabilities/configured/activation/status/attribute)");
        }
        simulatedActivation.setEnabled(this, account, enabled);
    }

    @Override
    public String password(String account) {
        if (passwordColumn == null) {
            throw new IllegalStateException("Resource " + resourceOid + " has no passwordAttribute");
        }
        String value = existing(rows(), account).get(passwordColumn);
        return value == null || value.isEmpty() ? null : value;
    }

    @Override
    public void setPassword(String account, String password) {
        if (passwordColumn == null) {
            throw new IllegalStateException("Resource " + resourceOid + " has no passwordAttribute");
        }
        List<Map<String, String>> rows = rows();
        existing(rows, account).put(passwordColumn, password == null ? "" : password);
        writeRows(header(), rows);
    }

    @Override
    public void addGroup(String name, Map<String, ?> attributes) {
        throw oneClass();
    }

    @Override
    public void modifyGroup(String name, String attribute, Object... values) {
        throw oneClass();
    }

    @Override
    public void deleteGroup(String name) {
        throw oneClass();
    }

    @Override
    public Map<String, List<Object>> group(String name) {
        throw oneClass();
    }

    @Override
    public List<Object> groupValues(String group, String attribute) {
        throw oneClass();
    }

    @Override
    public List<String> groupNames() {
        throw oneClass();
    }

    @Override
    public void addObject(String objectClass, String name, Map<String, ?> attributes) {
        addAccount(name, attributes);
    }

    @Override
    public void modifyObject(String objectClass, String name, String attribute, Object... values) {
        modifyAccount(name, attribute, values);
    }

    @Override
    public void renameObject(String objectClass, String name, String newName) {
        renameAccount(name, newName);
    }

    @Override
    public void deleteObject(String objectClass, String name) {
        deleteAccount(name);
    }

    @Override
    public Map<String, List<Object>> find(String objectClass, String name) {
        return account(name);
    }

    @Override
    public String identifier(String objectClass, String name) {
        return existing(rows(), name).get(uniqueColumn);
    }

    @Override
    public List<String> objectNames(String objectClass) {
        return accountNames();
    }

    // ---- snapshots ----------------------------------------------------------------------------------------------

    /**
     * The connector's folder for temporary files ({@code tmpFolder}): live synchronization keeps there a copy of the
     * file per token ({@code <file>.sync.<token>}), the content the next run compares the file with.
     */
    Path tmpFolder() {
        return path.resolveSibling(resourceOid + "-tmp");
    }

    /** The file and its live synchronization copies: the token midPoint keeps in the database names one of them. */
    private static final class Content implements java.io.Serializable {
        final byte[] file;
        final Map<String, byte[]> syncFiles;

        Content(byte[] file, Map<String, byte[]> syncFiles) {
            this.file = file;
            this.syncFiles = syncFiles;
        }
    }

    @Override
    public java.io.Serializable persistentContent() {
        return (Content) capture();
    }

    @Override
    public void restorePersistent(java.io.Serializable content) {
        restore(content);
    }

    @Override
    public Object capture() {
        try {
            Map<String, byte[]> syncFiles = new TreeMap<>();
            for (Path file : syncFiles()) {
                syncFiles.put(file.getFileName().toString(), Files.readAllBytes(file));
            }
            return new Content(Files.exists(path) ? Files.readAllBytes(path) : null, syncFiles);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void restore(Object captured) {
        try {
            for (Path file : syncFiles()) {
                Files.delete(file);
            }
            Content content = (Content) captured;
            if (content == null || content.file == null) {
                Files.deleteIfExists(path);
            } else {
                Files.write(path, content.file);
            }
            if (content != null && !content.syncFiles.isEmpty()) {
                Files.createDirectories(tmpFolder());
                for (Map.Entry<String, byte[]> file : content.syncFiles.entrySet()) {
                    Files.write(tmpFolder().resolve(file.getKey()), file.getValue());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The live synchronization copies of the file in the temporary folder. */
    private List<Path> syncFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(tmpFolder())) {
            return files;
        }
        String prefix = path.getFileName() + ".sync.";
        try (java.util.stream.Stream<Path> list = Files.list(tmpFolder())) {
            list.filter(f -> {
                String name = f.getFileName().toString();
                return name.startsWith(prefix) && name.substring(prefix.length()).matches("[0-9]+");
            }).forEach(files::add);
        }
        return files;
    }

    // ---- reading and writing --------------------------------------------------------------------------------------

    private Map<String, String> existing(List<Map<String, String>> rows, String name) {
        for (Map<String, String> row : rows) {
            if (name.equals(row.get(nameColumn))) {
                return row;
            }
        }
        throw new AssertionError("No row " + name + " in " + path + "; existing: " + accountNames());
    }

    private Map<String, String> convert(List<String> header, Map<String, ?> row) {
        Map<String, String> out = new LinkedHashMap<>();
        header.forEach(column -> out.put(column, ""));
        row.forEach((column, value) -> {
            checkColumn(header, column);
            out.put(column, value instanceof Collection<?> ? join((Collection<?>) value)
                    : value == null ? "" : String.valueOf(value));
        });
        return out;
    }

    private void checkColumn(List<String> header, String column) {
        if (!header.contains(column)) {
            throw new IllegalArgumentException("No column " + column + " in " + path + "; columns: " + header
                    + " (the tester's header has the columns the resource names; give the file's own header, columns"
                    + " midPoint does not read among them, as the target's initialData or with writeText)");
        }
    }

    private String join(Collection<?> values) {
        if (values.size() > 1 && multivalueDelimiter == null) {
            throw new IllegalArgumentException("Several values " + values + ", but the resource has no "
                    + "multivalueDelimiter");
        }
        List<String> strings = new ArrayList<>();
        values.forEach(v -> strings.add(String.valueOf(v)));
        return String.join(multivalueDelimiter != null ? multivalueDelimiter : "", strings);
    }

    private List<Object> split(String value) {
        List<Object> out = new ArrayList<>();
        if (multivalueDelimiter == null) {
            out.add(value);
        } else {
            out.addAll(List.of(value.split(java.util.regex.Pattern.quote(multivalueDelimiter), -1)));
        }
        return out;
    }

    private void writeRows(List<String> header, List<Map<String, String>> rows) {
        List<List<String>> records = new ArrayList<>();
        for (Map<String, String> row : rows) {
            List<String> record = new ArrayList<>();
            header.forEach(column -> record.add(row.getOrDefault(column, "")));
            records.add(record);
        }
        writeTable(header, records);
    }

    private void writeTable(List<String> header, List<List<String>> records) {
        StringBuilder sb = new StringBuilder();
        appendRecord(sb, header);
        records.forEach(r -> appendRecord(sb, r));
        writeText(sb.toString());
    }

    private void appendRecord(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            String value = values.get(i);
            if (value.indexOf(delimiter) >= 0 || value.indexOf(quote) >= 0 || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.contains(escape)
                    || i == 0 && value.startsWith(commentMarker)) {
                sb.append(quote).append(value.replace(escape, escape + escape)
                        .replace(String.valueOf(quote), "" + quote + quote)).append(quote);
            } else {
                sb.append(value);
            }
        }
        sb.append(recordSeparator);
    }

    /** RFC 4180 records with the resource's delimiter and quote; empty lines are skipped. */
    private List<List<String>> records() {
        String text = text();
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (escape.length() == 1 && ch == escape.charAt(0) && i + 1 < text.length()) {
                    field.append(text.charAt(++i));
                } else if (ch == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                        field.append(quote);
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (!fieldStarted && record.isEmpty() && text.startsWith(commentMarker, i)) {
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n') {
                    i++;
                }
            } else if (ch == quote) {
                quoted = true;
                fieldStarted = true;
            } else if (ch == delimiter) {
                record.add(field.toString());
                field.setLength(0);
                fieldStarted = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (fieldStarted || field.length() > 0 || !record.isEmpty()) {
                    record.add(field.toString());
                    records.add(record);
                }
                record = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
            } else {
                field.append(ch);
                fieldStarted = true;
            }
        }
        if (fieldStarted || field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    private static String unescape(String separator) {
        return separator.replace("\\r", "\r").replace("\\n", "\n");
    }

    private IllegalStateException oneClass() {
        return new IllegalStateException("The CSV file of resource " + resourceOid + " has one object class (rows):"
                + " use the account calls");
    }
}
