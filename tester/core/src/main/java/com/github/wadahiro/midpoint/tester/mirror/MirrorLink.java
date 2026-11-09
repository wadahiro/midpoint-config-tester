package com.github.wadahiro.midpoint.tester.mirror;

/**
 * A reference between object classes of a mirrored resource, as an association defines it: an attribute of the holder
 * class ({@code groupOfNames.member}) whose values refer to objects of the value class ({@code inetOrgPerson}) by
 * their name or by their uid.
 */
public final class MirrorLink {

    /** ConnId name of the class whose objects hold the references. */
    public final String holderClass;
    public final String holderAttribute;
    /** ConnId name of the class whose objects are referred to. */
    public final String valueClass;
    /** Whether values are uids ({@code __UID__}) rather than names ({@code __NAME__}). */
    public final boolean byUid;

    public MirrorLink(String holderClass, String holderAttribute, String valueClass, boolean byUid) {
        this.holderClass = holderClass;
        this.holderAttribute = holderAttribute;
        this.valueClass = valueClass;
        this.byUid = byUid;
    }

    /** How objects are referred to: their uid or their name. */
    String reference(MirrorObject object) {
        return byUid ? object.uid : object.name;
    }
}
