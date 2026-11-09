package com.github.wadahiro.midpoint.tester;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Which mapping or expression of the configuration an evaluation is, in midPoint's terms and independent of how the
 * file is formatted: the owner object and the elements from its root down to the evaluated one, each with what tells
 * it apart from its siblings (a discriminator) instead of its position. Line numbers are not part of it, so editing a
 * file elsewhere does not change it.
 *
 * <pre>
 * resource:HR/schemaHandling/objectType[account/default]/attribute[ri:fullname]/inbound[hr-familyName]
 * resource:HR/schemaHandling/objectType[account/default]/attribute[ri:location]/outbound[hr-location]/expression
 * resource:HR/schemaHandling/objectType[account/default]/correlation/correlators/filter/ownerFilter/equal/expression
 * resource:CRM/schemaHandling/objectType[account/default]/attribute[icfs:name]/outbound      (unnamed, single)
 * objectTemplate:User template/mapping[fullName]
 * role:Employee/inducement[construction=HR/account/default]/construction/attribute[ri:title]/outbound[employee-title]
 * role:Mailer/inducement[focusMappings=description]/focusMappings/mapping[target=description]
 * role:Privileged Access/assignment[policyRule=approve-privileged-access]/policyRule[approve-privileged-access]
 *     /policyActions/approval/approvalSchema/stage[number=1]/approverExpression[description=the IT department]
 * accessCertificationDefinition:role-assignments/stageDefinition[number=1]/reviewerSpecification/reviewerExpression
 * systemConfiguration:SystemConfiguration/modelHooks/change/hook[forbid-test-users]
 * systemConfiguration:SystemConfiguration/notificationConfiguration/handler/simpleUserNotifier[welcome]/subjectExpression
 * systemConfiguration:SystemConfiguration/globalPolicyRule[forbidden-locality]/policyConstraints
 *     /objectState[locality-is-forbidden]/expression
 * report:users-by-cost-center/objectCollection/view/column[fullName]/export/expression
 * dashboard:admin-dashboard/widget[identifier=employees]/presentation/variation[condition=fully staffed]/condition
 * </pre>
 *
 * Discriminators: {@code name} (shown bare); {@code ref} of attributes, associations and items (bare); kind/intent of
 * resource object types (bare); {@code number=} of approval and certification stages (before their name);
 * {@code identifier=} of widgets; an unnamed mapping's {@code target=} path; an assignment's or inducement's
 * {@code target=} (the target's name), else its {@code construction=} (resource name/kind/intent) or
 * {@code policyRule=}; a reaction's {@code situation=}. Siblings these do not tell apart are told apart by what else
 * they have, only among them: an inducement's {@code order=} (as a qualifier after its target:
 * {@code inducement[target=Employee, order=2]}), {@code focusMappings=} or {@code targetFilter=}; a transition's
 * {@code targetState=}; a data field's {@code fieldType=}; a check expression's {@code failureMessage=}; a variation's
 * {@code condition=}; a message argument's position ({@code argument=1}); then {@code label=}, {@code description=},
 * {@code documentation=}. Only where nothing does is an element given its ordinal ({@code #2}, see
 * {@link Segment#isOrdinal()}); such an identity changes when siblings are added, removed or reordered.
 *
 * An evaluation whose element was not found in the files ({@link Location#isKnown()} false; {@link #isLocated()}
 * false) gets the owner and the position midPoint reported, with ordinals; an expression in it, the part
 * ({@code expression}, {@code condition}); an ad-hoc script without owner ({@code mp.script(...)}), its canonical
 * form: {@code ?:?/script[fingerprint=...]}.
 */
public final class Identity {

    /** One element of the path: its name and, when needed, what tells it apart. */
    public static final class Segment {
        private final String element;
        private final String key;
        private final String value;
        private final String qualifierKey;
        private final String qualifierValue;
        private final int ordinal;

        /**
         * @param key {@code name}, {@code ref}, {@code kind/intent}, {@code number}, {@code identifier},
         *        {@code target}, {@code construction}, {@code policyRule}, {@code situation}, or one of the
         *        discriminators used among siblings only ({@code focusMappings}, {@code targetFilter},
         *        {@code targetState}, {@code fieldType}, {@code failureMessage}, {@code condition},
         *        {@code argument}, {@code label}, {@code description}, {@code documentation}); null when none
         * @param ordinal the 1-based position among the same-named (and same-keyed) siblings, when needed; else 0
         */
        public Segment(String element, String key, String value, int ordinal) {
            this(element, key, value, null, null, ordinal);
        }

        /**
         * @param qualifierKey what tells the element apart from siblings with the same discriminator (e.g.
         *        {@code order} of two inducements of one target); null when not needed
         */
        public Segment(String element, String key, String value, String qualifierKey, String qualifierValue,
                int ordinal) {
            this.element = element;
            this.key = key;
            this.value = value;
            this.qualifierKey = qualifierKey;
            this.qualifierValue = qualifierValue;
            this.ordinal = ordinal;
        }

        public String element() {
            return element;
        }

        /** What kind of discriminator {@link #value()} is; null when the element has none. */
        public String key() {
            return key;
        }

        public String value() {
            return value;
        }

        /** What tells the element apart from siblings with the same {@link #value()}; null when not needed. */
        public String qualifierKey() {
            return qualifierKey;
        }

        public String qualifierValue() {
            return qualifierValue;
        }

        /** The position among siblings that cannot be told apart otherwise; 0 when not needed. */
        public int ordinal() {
            return ordinal;
        }

        /** Whether the element is told apart by its position (fragile: changes when siblings are added). */
        public boolean isOrdinal() {
            return ordinal > 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Segment)) {
                return false;
            }
            Segment s = (Segment) o;
            return element.equals(s.element) && Objects.equals(key, s.key) && Objects.equals(value, s.value)
                    && Objects.equals(qualifierKey, s.qualifierKey) && Objects.equals(qualifierValue, s.qualifierValue)
                    && ordinal == s.ordinal;
        }

        @Override
        public int hashCode() {
            return Objects.hash(element, key, value, qualifierKey, qualifierValue, ordinal);
        }

        @Override
        public String toString() {
            if (value == null && qualifierKey == null && ordinal == 0) {
                return element;
            }
            StringBuilder sb = new StringBuilder(element).append('[');
            if (value != null) {
                if (isBare(key)) {
                    sb.append(value);
                } else {
                    sb.append(key).append('=').append(value);
                }
            }
            if (qualifierKey != null) {
                sb.append(value != null ? ", " : "").append(qualifierKey).append('=').append(qualifierValue);
            }
            if (ordinal > 0) {
                sb.append(value != null || qualifierKey != null ? " " : "").append('#').append(ordinal);
            }
            return sb.append(']').toString();
        }

        private static boolean isBare(String key) {
            return "name".equals(key) || "ref".equals(key) || "kind/intent".equals(key);
        }
    }

    private final String ownerType;
    private final String ownerOid;
    private final String ownerName;
    private final List<Segment> segments;
    private final boolean located;

    public Identity(String ownerType, String ownerOid, String ownerName, List<Segment> segments, boolean located) {
        this.ownerType = ownerType;
        this.ownerOid = ownerOid;
        this.ownerName = ownerName;
        this.segments = List.copyOf(segments);
        this.located = located;
    }

    /** The owner's element name ({@code resource}, {@code role}, {@code objectTemplate}...); null when unknown. */
    public String ownerType() {
        return ownerType;
    }

    public String ownerOid() {
        return ownerOid;
    }

    public String ownerName() {
        return ownerName;
    }

    /** From the owner's first child element down to the evaluated element (the owner's root is not included). */
    public List<Segment> segments() {
        return segments;
    }

    /** The evaluated element (null for an identity without segments). */
    public Segment last() {
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    /** The innermost segment for this element, or null. */
    public Segment segment(String element) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).element().equals(element)) {
                return segments.get(i);
            }
        }
        return null;
    }

    /** Whether some element on the path is told apart by its position only (see {@link Segment#isOrdinal()}). */
    public boolean hasOrdinal() {
        return segments.stream().anyMatch(Segment::isOrdinal);
    }

    /** Whether the element was found in the configuration files (else: owner and midPoint's position only). */
    public boolean isLocated() {
        return located;
    }

    private String owner() {
        return ownerName != null ? ownerName : ownerOid;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Identity)) {
            return false;
        }
        Identity i = (Identity) o;
        return Objects.equals(ownerType, i.ownerType) && Objects.equals(ownerOid, i.ownerOid)
                && Objects.equals(ownerName, i.ownerName) && segments.equals(i.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerType, ownerOid, ownerName, segments);
    }

    /** {@code ownerType:ownerName/segment/segment...}; the owner's OID when it has no name. */
    @Override
    public String toString() {
        String head = (ownerType != null ? ownerType : "?") + ":" + (owner() != null ? owner() : "?");
        if (segments.isEmpty()) {
            return head;
        }
        return head + "/" + segments.stream().map(Segment::toString).collect(Collectors.joining("/"));
    }
}
