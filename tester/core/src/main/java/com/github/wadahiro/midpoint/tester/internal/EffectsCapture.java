package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.evolveum.midpoint.prism.path.ItemPath;
import com.github.wadahiro.midpoint.tester.AuditRecord;
import com.github.wadahiro.midpoint.tester.LogEntry;
import com.github.wadahiro.midpoint.tester.Mail;
import com.github.wadahiro.midpoint.tester.MidPoint;

/**
 * What midPoint did while a block of the test ran ({@code MidPointConfigTester#effectsOf}): the calls it made to its
 * repository and to the connectors ({@link EffectsHook} records them while a capture is open), and the mails, logs
 * and audit records the tester already keeps, cut to the block.
 */
public final class EffectsCapture {

    /** The captures open now (nested blocks each get theirs). */
    private static final List<EffectsCapture> OPEN = new CopyOnWriteArrayList<>();

    private final List<RepositoryCall> repositoryCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<ConnectorCall> connectorCalls = Collections.synchronizedList(new ArrayList<>());
    private List<Mail> mails = Collections.emptyList();
    private List<LogEntry> logs = Collections.emptyList();
    private List<AuditRecord> audit = Collections.emptyList();

    private EffectsCapture() {
    }

    /** Runs the block and returns what midPoint did meanwhile; the block's exception propagates. */
    public static EffectsCapture capture(MidPoint mp, Runnable block) {
        EffectsCapture capture = new EffectsCapture();
        int mailsBefore = mp.mails().size();
        int logsBefore = LogCapture.count();
        OPEN.add(capture);
        try {
            capture.audit = mp.auditRecords(block);
        } finally {
            OPEN.remove(capture);
        }
        capture.mails = after(mp.mails(), mailsBefore);
        capture.logs = after(LogCapture.entries(), logsBefore);
        return capture;
    }

    private static <T> List<T> after(List<T> all, int before) {
        return Collections.unmodifiableList(new ArrayList<>(all.subList(Math.min(before, all.size()), all.size())));
    }

    /**
     * An item path as the tester shows it: the names' local parts, without container IDs
     * ({@code assignment/activation/validTo}); with {@code dropAttributes}, a shadow's {@code attributes/} prefix is
     * left out.
     */
    public static String pathText(ItemPath path, boolean dropAttributes) {
        List<String> names = new ArrayList<>();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment).getLocalPart());
            }
        }
        if (dropAttributes && names.size() > 1 && "attributes".equals(names.get(0))) {
            names = names.subList(1, names.size());
        }
        return String.join("/", names);
    }

    static boolean capturing() {
        return !OPEN.isEmpty();
    }

    static void record(RepositoryCall call) {
        for (EffectsCapture capture : OPEN) {
            capture.repositoryCalls.add(call);
        }
    }

    static void record(ConnectorCall call) {
        for (EffectsCapture capture : OPEN) {
            capture.connectorCalls.add(call);
        }
    }

    public List<RepositoryCall> repositoryCalls() {
        synchronized (repositoryCalls) {
            return Collections.unmodifiableList(new ArrayList<>(repositoryCalls));
        }
    }

    public List<ConnectorCall> connectorCalls() {
        synchronized (connectorCalls) {
            return Collections.unmodifiableList(new ArrayList<>(connectorCalls));
        }
    }

    public List<Mail> mails() {
        return mails;
    }

    public List<LogEntry> logs() {
        return logs;
    }

    public List<AuditRecord> audit() {
        return audit;
    }

    /**
     * A call midPoint made to its repository ({@code repositoryService}, below the cache): the method, the object
     * type ({@code UserType}) and, for a call about one object, its OID and name. A write also has what it did to the
     * object ({@link #change}) and, for a modification, the item deltas (copies).
     */
    public static final class RepositoryCall {

        public enum Change { ADDED, MODIFIED, DELETED }

        public final String method;
        public final String type;
        public final String oid;
        public final String name;
        public final Change change;
        public final List<Object> modifications;

        RepositoryCall(String method, String type, String oid, String name, Change change, List<Object> modifications) {
            this.method = method;
            this.type = type;
            this.oid = oid;
            this.name = name;
            this.change = change;
            this.modifications = modifications;
        }
    }

    /**
     * A call midPoint made to a connector of a resource: the operation ({@code add}, {@code modify}, {@code delete},
     * {@code fetch}, {@code search}...), the object class, the account (its name, or its identifiers) and, for
     * {@code add} and {@code modify}, the attributes written.
     */
    public static final class ConnectorCall {

        public final String resource;
        public final String operation;
        public final String objectClass;
        public final String account;
        public final List<String> attributes;
        public final boolean failed;

        ConnectorCall(String resource, String operation, String objectClass, String account, List<String> attributes,
                boolean failed) {
            this.resource = resource;
            this.operation = operation;
            this.objectClass = objectClass;
            this.account = account;
            this.attributes = attributes;
            this.failed = failed;
        }
    }
}
