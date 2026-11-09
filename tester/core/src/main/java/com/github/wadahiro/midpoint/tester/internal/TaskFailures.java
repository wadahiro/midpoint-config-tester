package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

/**
 * The last item a task failed on, and why, as the task's statistics keep it: midPoint's result of a task run only
 * counts the failures ("Processed 1 account(s), got 1 error(s)"). Read from the task's XML by element names, the same
 * on every line: 4.0's {@code operationStats/iterativeTaskInformation} ({@code lastFailureObjectName},
 * {@code lastFailureExceptionMessage}; a property there, not a container), 4.4+'s activity statistics (a set of
 * processed items whose outcome is a failure: its {@code lastItem}'s {@code name} and {@code message}).
 */
final class TaskFailures {

    private TaskFailures() {
    }

    /** {@code name: message} of each last failed item of the task (its activities, its parts), in document order. */
    static List<String> of(String taskXml) {
        List<String> out = new ArrayList<>();
        walk(Xml.parse(taskXml).getDocumentElement(), out);
        return out;
    }

    private static void walk(Element element, List<String> out) {
        String message = text(element, "lastFailureExceptionMessage");
        if (message != null) {
            add(out, text(element, "lastFailureObjectName"), message);
        }
        Element outcome = Xml.child(element, "outcome");
        Element lastItem = Xml.child(element, "lastItem");
        if (outcome != null && lastItem != null && "failure".equalsIgnoreCase(text(outcome, "outcome"))) {
            add(out, text(lastItem, "name"), text(lastItem, "message"));
        }
        for (org.w3c.dom.Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                walk((Element) n, out);
            }
        }
    }

    private static void add(List<String> out, String name, String message) {
        String line = (name != null ? name : "?") + ": " + (message != null ? message : "(no message)");
        if (!out.contains(line)) {
            out.add(line);
        }
    }

    private static String text(Element parent, String name) {
        Element child = Xml.child(parent, name);
        return child != null ? child.getTextContent().trim() : null;
    }
}
