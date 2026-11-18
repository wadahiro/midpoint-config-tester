package com.github.wadahiro.midpoint.tester.internal;

import static com.github.wadahiro.midpoint.tester.internal.Xml.child;
import static com.github.wadahiro.midpoint.tester.internal.Xml.children;

import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import org.w3c.dom.Element;

/**
 * The simulated reference types of a resource (4.9 and later: {@code capabilities/configured/references/type}), which
 * the {@code associationType}s of its schemaHandling use in place of an object type's {@code association}: which
 * object class holds the references, in which attribute, and which attribute of the other class they are.
 * <pre>{@code
 * <cap:type>
 *     <cap:name>ri:groupMembership</cap:name>
 *     <cap:subject>                                       <!-- the account -->
 *         <cap:delineation><cap:objectClass>ri:inetOrgPerson</cap:objectClass></cap:delineation>
 *         <cap:primaryBindingAttributeRef>ri:dn</cap:primaryBindingAttributeRef>
 *         <cap:localItemName>ri:group</cap:localItemName>
 *     </cap:subject>
 *     <cap:object>                                        <!-- the group -->
 *         <cap:delineation><cap:objectClass>ri:groupOfNames</cap:objectClass></cap:delineation>
 *         <cap:primaryBindingAttributeRef>ri:member</cap:primaryBindingAttributeRef>
 *     </cap:object>
 *     <cap:direction>objectToSubject</cap:direction>      <!-- the object holds the subject's binding -->
 * </cap:type>
 * }</pre>
 * By direction, as an object type's legacy association: {@code objectToSubject}, the object's binding attribute holds
 * the subject's (groupOfNames.member = inetOrgPerson.dn); {@code subjectToObject}, the subject's holds the object's.
 */
final class ReferenceTypes {

    /** A reference type, by direction: the holder's attribute holds values of the value side's attribute. */
    static final class ReferenceType {
        final boolean objectToSubject;
        final List<String> holderClasses;
        final QName holderAttribute;
        final List<String> valueClasses;
        final QName valueAttribute;

        ReferenceType(boolean objectToSubject, List<String> holderClasses, QName holderAttribute,
                List<String> valueClasses, QName valueAttribute) {
            this.objectToSubject = objectToSubject;
            this.holderClasses = holderClasses;
            this.holderAttribute = holderAttribute;
            this.valueClasses = valueClasses;
            this.valueAttribute = valueAttribute;
        }

        String direction() {
            return objectToSubject ? "objectToSubject" : "subjectToObject";
        }
    }

    private ReferenceTypes() {
    }

    /** The resource's simulated reference types; none before 4.9 (or without the capability). */
    static List<ReferenceType> of(Element resource) {
        List<ReferenceType> out = new ArrayList<>();
        Element capabilities = child(resource, "capabilities");
        Element configured = capabilities != null ? child(capabilities, "configured") : null;
        Element references = configured != null ? child(configured, "references") : null;
        if (references == null) {
            return out;
        }
        for (Element type : children(references, "type")) {
            Element subject = child(type, "subject");
            Element object = child(type, "object");
            QName subjectBinding = binding(subject);
            QName objectBinding = binding(object);
            List<String> subjectClasses = classes(subject);
            List<String> objectClasses = classes(object);
            if (subjectBinding == null || objectBinding == null || subjectClasses.isEmpty() || objectClasses.isEmpty()) {
                continue;
            }
            Element direction = child(type, "direction");
            boolean objectToSubject = direction == null || "objectToSubject".equals(direction.getTextContent().trim());
            out.add(objectToSubject
                    ? new ReferenceType(true, objectClasses, objectBinding, subjectClasses, subjectBinding)
                    : new ReferenceType(false, subjectClasses, subjectBinding, objectClasses, objectBinding));
        }
        return out;
    }

    private static QName binding(Element participant) {
        Element ref = participant != null ? child(participant, "primaryBindingAttributeRef") : null;
        return ref != null ? Xml.qname(ref) : null;
    }

    /** The participant's object classes (local names), by its delineations. */
    private static List<String> classes(Element participant) {
        List<String> out = new ArrayList<>();
        if (participant != null) {
            for (Element delineation : children(participant, "delineation")) {
                Element objectClass = child(delineation, "objectClass");
                if (objectClass != null) {
                    out.add(Xml.qname(objectClass).getLocalPart());
                }
            }
        }
        return out;
    }
}
