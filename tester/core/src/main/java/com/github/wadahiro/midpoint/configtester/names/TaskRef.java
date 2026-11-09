package com.github.wadahiro.midpoint.configtester.names;

/** A task of the deployment (generated constant). */
public final class TaskRef extends ObjectRef {

    public TaskRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "task";
    }

    @Override
    public String type() {
        return "TaskType";
    }
}
