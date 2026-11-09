package com.github.wadahiro.midpoint.configtester.names;

/** A report of the deployment (generated constant). */
public final class ReportRef extends ObjectRef {

    public ReportRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "report";
    }

    @Override
    public String type() {
        return "ReportType";
    }
}
