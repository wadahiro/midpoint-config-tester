package com.github.wadahiro.midpoint.tester;

import java.util.List;

/**
 * Where an evaluated mapping or expression is in the configuration files given to the runtime (imported objects and
 * resources): the file as given, the line of the element's start tag and its element path.
 *
 * How it was found ({@link #resolvedBy()}):
 * <ul>
 *   <li>{@code origin}: from where midPoint says the mapping is in its owner object (its position in the object as
 *       loaded, matched element by element in the file);</li>
 *   <li>{@code name}: the owner's only mapping with that {@code <name>};</li>
 *   <li>{@code fingerprint}: the expression's canonical form, within the enclosing mapping or owner when known.
 *       Identical expressions are told apart by a hook's name, the element the driver evaluates in (a dashboard
 *       widget), the name of the notifier whose filter is evaluated and midPoint's description of a populate
 *       expression; when that leaves several,
 *       {@link #alternatives()} lists the other places.</li>
 * </ul>
 */
public final class Location {

    public static final Location UNKNOWN = new Location(null, 0, null, null, null, null, "unknown", List.of());

    private final String file;
    private final int line;
    private final String path;
    private final String objectType;
    private final String objectOid;
    private final String objectName;
    private final String resolvedBy;
    private final List<Location> alternatives;

    public Location(String file, int line, String path, String objectType, String objectOid, String objectName,
            String resolvedBy, List<Location> alternatives) {
        this.file = file;
        this.line = line;
        this.path = path;
        this.objectType = objectType;
        this.objectOid = objectOid;
        this.objectName = objectName;
        this.resolvedBy = resolvedBy;
        this.alternatives = List.copyOf(alternatives);
    }

    public boolean isKnown() {
        return file != null;
    }

    /** The file as it was given to the runtime (e.g. {@code src/test/resources/config/role-employee.xml}). */
    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    /** Element names from the object's root, e.g. {@code role/inducement/construction/attribute/outbound}. */
    public String path() {
        return path;
    }

    /** The object in the file that holds the element: its root element name ({@code role}), OID and name. */
    public String objectType() {
        return objectType;
    }

    public String objectOid() {
        return objectOid;
    }

    public String objectName() {
        return objectName;
    }

    /** {@code origin}, {@code name}, {@code fingerprint} or {@code unknown}. */
    public String resolvedBy() {
        return resolvedBy;
    }

    /** Other places an expression with the same canonical form is at (fingerprint resolution only). */
    public List<Location> alternatives() {
        return alternatives;
    }

    /** Whether the file is {@code file} or ends with {@code /file}. */
    public boolean isIn(String file) {
        return this.file != null && (this.file.equals(file) || this.file.endsWith("/" + file));
    }

    @Override
    public String toString() {
        if (!isKnown()) {
            return "(unknown location)";
        }
        return file + ":" + line + " " + path + (alternatives.isEmpty() ? "" : " (+" + alternatives.size() + " identical)");
    }
}
