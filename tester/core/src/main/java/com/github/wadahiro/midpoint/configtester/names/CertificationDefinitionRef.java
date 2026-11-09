package com.github.wadahiro.midpoint.configtester.names;

/** A certification definition of the deployment (generated constant). */
public final class CertificationDefinitionRef extends ObjectRef {

    public CertificationDefinitionRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "certification definition";
    }

    @Override
    public String type() {
        return "AccessCertificationDefinitionType";
    }
}
