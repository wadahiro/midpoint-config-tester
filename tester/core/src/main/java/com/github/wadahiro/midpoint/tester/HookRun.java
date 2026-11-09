package com.github.wadahiro.midpoint.tester;

/**
 * One run of a scripting model hook of the system configuration ({@code modelHooks/change/hook}): which hook, in which
 * Clockwork state, for which focus, and the script's evaluation.
 */
public final class HookRun {

    private final String name;
    private final String state;
    private final String focusType;
    private final String focusName;
    private final Evaluation evaluation;

    public HookRun(String name, String state, String focusType, String focusName, Evaluation evaluation) {
        this.name = name;
        this.state = state;
        this.focusType = focusType;
        this.focusName = focusName;
        this.evaluation = evaluation;
    }

    /** The hook's {@code <name>}; null for a hook without one. */
    public String name() {
        return name;
    }

    /** The Clockwork state when the hook ran ({@code primary}, {@code secondary}, {@code final}), as midPoint names it. */
    public String state() {
        return state;
    }

    /** The focus's type when the hook ran, e.g. {@code UserType}; null when there was no focus. */
    public String focusType() {
        return focusType;
    }

    /** The focus's name when the hook ran; null when there was no focus (or it had no name). */
    public String focusName() {
        return focusName;
    }

    /** The evaluation of the hook's script (variables {@code modelContext}, {@code focus}; error). */
    public Evaluation evaluation() {
        return evaluation;
    }

    @Override
    public String toString() {
        return "hook " + name + " " + state + " " + focusName;
    }
}
