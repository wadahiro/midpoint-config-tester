package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The calls midPoint made to its repository in a block ({@link Effects#repositoryCalls()}), for looking into what a
 * configuration costs, e.g. {@code effects.repositoryCalls().reads().ofType("UserType").count()}. They are the calls
 * below midPoint's repository cache: every write, and the reads the cache did not answer. The tester's own reads
 * (assertions, waits) are not among them. Not part of a verification: {@link Effects#changes()} says what was
 * written.
 */
public final class RepositoryCalls {

    private final List<Call> selected;

    private RepositoryCalls(List<Call> selected) {
        this.selected = selected;
    }

    static RepositoryCalls of(List<Call> calls) {
        return new RepositoryCalls(Collections.unmodifiableList(new ArrayList<>(calls)));
    }

    /** The calls about objects of the type, by its simple name: {@code UserType}, {@code ShadowType}... */
    public RepositoryCalls ofType(String type) {
        return select(c -> type.equals(c.type()));
    }

    /** The calls of the repository method: {@code getObject}, {@code searchObjects}, {@code modifyObject}... */
    public RepositoryCalls method(String method) {
        return select(c -> method.equals(c.method()));
    }

    public RepositoryCalls reads() {
        return select(c -> !c.isWrite());
    }

    public RepositoryCalls writes() {
        return select(Call::isWrite);
    }

    /** How many of the selected calls each method made per type ({@code "searchObjects UserType" -> 3}), sorted. */
    public Map<String, Integer> perMethod() {
        Map<String, Integer> out = new TreeMap<>();
        selected.forEach(c -> out.merge(c.method() + (c.type() != null ? " " + c.type() : ""), 1, Integer::sum));
        return Collections.unmodifiableMap(out);
    }

    public List<Call> list() {
        return selected;
    }

    public int count() {
        return selected.size();
    }

    private RepositoryCalls select(Predicate<Call> keep) {
        List<Call> out = new ArrayList<>();
        for (Call c : selected) {
            if (keep.test(c)) {
                out.add(c);
            }
        }
        return new RepositoryCalls(out);
    }

    /** One repository call: the method, the object type and, for a call about one object, its OID. */
    public static final class Call {

        private final String method;
        private final String type;
        private final String oid;
        private final boolean write;

        Call(String method, String type, String oid, boolean write) {
            this.method = method;
            this.type = type;
            this.oid = oid;
            this.write = write;
        }

        public String method() {
            return method;
        }

        public String type() {
            return type;
        }

        public String oid() {
            return oid;
        }

        public boolean isWrite() {
            return write;
        }

        @Override
        public String toString() {
            return method + (type != null ? " " + type : "") + (oid != null ? " " + oid : "");
        }
    }
}
