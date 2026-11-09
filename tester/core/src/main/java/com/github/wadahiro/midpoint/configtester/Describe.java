package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import javax.xml.datatype.XMLGregorianCalendar;

import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * midPoint's values as the failure messages show them, for any assertion library ({@code configtester.assertj}
 * registers it with AssertJ): an assignment as {@code assignment of role Dept Sales}, a reference as
 * {@code role Dept Sales}, a polystring as its original, a date as an instant, an object as its type and name. A
 * reference is named from its target name, else read by its OID in the running test's midPoint.
 */
public final class Describe {

    private Describe() {
    }

    /** Whether {@link #of} has its own text for the value (else the library's own is fine). */
    public static boolean describes(Object value) {
        return value instanceof AssignmentType || value instanceof ObjectReferenceType || value instanceof PolyString
                || value instanceof PolyStringType || value instanceof XMLGregorianCalendar
                || value instanceof ActivationType || value instanceof ObjectType;
    }

    public static String of(Object value) {
        if (value instanceof AssignmentType) {
            AssignmentType assignment = (AssignmentType) value;
            if (assignment.getTargetRef() != null) {
                String relation = relation(assignment.getTargetRef());
                return "assignment of " + of(assignment.getTargetRef()) + (relation != null ? " as " + relation : "");
            }
            return assignment.getConstruction() != null ? "assignment of a construction"
                    : "assignment" + (assignment.getId() != null ? " " + assignment.getId() : "");
        }
        if (value instanceof ObjectReferenceType) {
            ObjectReferenceType ref = (ObjectReferenceType) value;
            String type = ref.getType() != null ? kind(ref.getType().getLocalPart()) : "object";
            MidPointConfigTester tester = MidPointConfigTester.current();
            String name = tester != null ? tester.nameOf(ref)
                    : ref.getTargetName() != null ? ref.getTargetName().getOrig() : ref.getOid();
            return type + " " + name;
        }
        if (value instanceof ActivationType) {
            ActivationType a = (ActivationType) value;
            List<String> parts = new ArrayList<>();
            add(parts, "effectiveStatus", a.getEffectiveStatus());
            add(parts, "administrativeStatus", a.getAdministrativeStatus());
            add(parts, "validFrom", a.getValidFrom());
            add(parts, "validTo", a.getValidTo());
            return "activation (" + String.join(", ", parts) + ")";
        }
        if (value instanceof ObjectType) {
            ObjectType object = (ObjectType) value;
            return kind(object.getClass().getSimpleName()) + " " + Values.text(object.getName());
        }
        return Values.text(value);
    }

    private static void add(List<String> parts, String name, Object value) {
        if (value != null) {
            parts.add(name + " " + Values.text(value));
        }
    }

    /** The relation of a reference, when not the default one ({@code owner}, {@code approver}). */
    private static String relation(ObjectReferenceType ref) {
        if (ref.getRelation() == null || "default".equals(ref.getRelation().getLocalPart())) {
            return null;
        }
        return ref.getRelation().getLocalPart();
    }

    /** {@code RoleType} -> {@code role}, {@code ObjectCollectionType} -> {@code object collection}. */
    static String kind(String type) {
        String name = type.endsWith("Type") ? type.substring(0, type.length() - 4) : type;
        return name.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
    }
}
