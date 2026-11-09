package com.github.wadahiro.midpoint.configtester.names;

/**
 * An object of a type no verb takes by name (an object collection, a dashboard, a security or value policy, a mark,
 * an object template): its name and OID, e.g. for {@code s.midpoint().object(ValuePolicyType.class, ref.oid())}.
 */
public final class MidPointObjectRef extends ObjectRef {

    private final String type;
    private final String kind;

    /**
     * @param type the local name of the object's type ({@code ValuePolicyType})
     * @param kind what it is, for messages ({@code value policy})
     */
    public MidPointObjectRef(String type, String kind, String name, String oid, Lines lines) {
        super(name, oid, lines);
        this.type = type;
        this.kind = kind;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String kind() {
        return kind;
    }
}
