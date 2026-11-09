package com.github.wadahiro.midpoint.scan;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A canonical form of one evaluator element ({@code <path>}, {@code <assignmentTargetSearch>}, {@code <script>}, ...),
 * computed the same way from the configuration file ({@link ConfigScanner}) and from the element midPoint evaluates,
 * serialized by the line's prism (the tester's {@code EvaluatorHook}). It identifies expressions that have no code to
 * match.
 *
 * The form keeps what both sides certainly agree on, and drops what prism may write differently from the file:
 * <ul>
 *   <li>the element's local name, then its leaves as {@code relative/path=text}, sorted (prism writes items in schema
 *       order, a file may not);</li>
 *   <li>local names only, and namespace prefixes removed from the text ({@code $c:focus/c:name} is
 *       {@code $focus/name}, {@code c:RoleType} is {@code RoleType});</li>
 *   <li>whitespace collapsed; empty elements dropped (prism omits empty containers);</li>
 *   <li>of the attributes only {@code oid} (prism may add {@code type} to a reference, {@code xsi:type} to a value);</li>
 *   <li>a polystring as its {@code orig} (a parsed literal is written with {@code orig} and {@code norm}).</li>
 * </ul>
 * Two different elements may therefore have the same form (e.g. differing only in a namespace or a reference type),
 * and identical elements in several places always do: all of them count as evaluated when one is.
 */
public final class EvaluatorFingerprint {

    /** A prefix before a name or a variable, not a URI scheme ({@code http://}) nor part of a longer token. */
    private static final Pattern PREFIX = Pattern.compile("(?<![\\w.:/-])[A-Za-z_][\\w.-]*:(?=[A-Za-z_$])");

    private final String root;
    private final List<String> leaves = new ArrayList<>();
    private final Deque<Node> open = new ArrayDeque<>();

    private static final class Node {
        final String path;
        final StringBuilder text = new StringBuilder();

        Node(String path) {
            this.path = path;
        }
    }

    /** Starts the form of an evaluator element; feed it the element's content, then {@link #end()} the element. */
    EvaluatorFingerprint(String rootName, String oid) {
        this.root = rootName;
        open.push(new Node(""));
        attribute("", oid);
    }

    /** A start tag inside the evaluator element. */
    void start(String name, String oid) {
        Node parent = open.peek();
        String path = parent.path.isEmpty() ? name : parent.path + "/" + name;
        open.push(new Node(path));
        attribute(path, oid);
    }

    void text(String text) {
        open.peek().text.append(text);
    }

    /** An end tag; returns true when it closed the evaluator element itself. */
    boolean end() {
        Node node = open.pop();
        String text = normalize(node.text.toString());
        if (!text.isEmpty()) {
            String path = node.path;
            if (path.equals("norm") || path.endsWith("/norm")) {
                // polystring: the norm is derived from the orig
            } else {
                if (path.equals("orig")) {
                    path = "";
                } else if (path.endsWith("/orig")) {
                    path = path.substring(0, path.length() - "/orig".length());
                }
                leaves.add(path + "=" + text);
            }
        }
        return open.isEmpty();
    }

    private void attribute(String path, String oid) {
        if (oid != null && !oid.isBlank()) {
            leaves.add(path + "@oid=" + oid.strip());
        }
    }

    /** The canonical form: {@code name{leaf;leaf;...}}. */
    String form() {
        List<String> sorted = new ArrayList<>(leaves);
        Collections.sort(sorted);
        return root + "{" + String.join(";", sorted) + "}";
    }

    static String normalize(String text) {
        return PREFIX.matcher(text.strip().replaceAll("\\s+", " ")).replaceAll("");
    }

    /** The canonical form of the root element of an XML document, e.g. an evaluator element prism serialized. */
    public static String of(String xml) {
        try {
            XMLStreamReader reader = ConfigScanner.inputFactory().createXMLStreamReader(new StringReader(xml));
            try {
                EvaluatorFingerprint fingerprint = null;
                while (reader.hasNext()) {
                    switch (reader.next()) {
                        case XMLStreamConstants.START_ELEMENT:
                            if (fingerprint == null) {
                                fingerprint = new EvaluatorFingerprint(reader.getLocalName(), oid(reader));
                            } else {
                                fingerprint.start(reader.getLocalName(), oid(reader));
                            }
                            break;
                        case XMLStreamConstants.CHARACTERS:
                        case XMLStreamConstants.CDATA:
                            if (fingerprint != null) {
                                fingerprint.text(reader.getText());
                            }
                            break;
                        case XMLStreamConstants.END_ELEMENT:
                            if (fingerprint.end()) {
                                return fingerprint.form();
                            }
                            break;
                        default:
                            break;
                    }
                }
                throw new IllegalArgumentException("No element in " + xml);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Cannot read " + xml + ": " + e.getMessage(), e);
        }
    }

    /** The {@code oid} attribute of the current start tag (no namespace), or null. */
    static String oid(XMLStreamReader reader) {
        return reader.getAttributeValue(null, "oid");
    }
}
