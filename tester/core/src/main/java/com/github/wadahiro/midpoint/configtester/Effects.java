package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.github.wadahiro.midpoint.tester.AuditRecord;
import com.github.wadahiro.midpoint.tester.LogEntry;
import com.github.wadahiro.midpoint.tester.Mail;
import com.github.wadahiro.midpoint.tester.internal.EffectsCapture;

/**
 * What midPoint did while a block of the test ran ({@link MidPointConfigTester#effectsOf(Runnable)}): the objects it
 * changed in its repository, the calls it made to the connectors of its resources, the mails it sent, and the audit
 * records and logs it wrote, including the work the block waited for (a task's background work). Taken when the block
 * returns; later calls of the test do not add to it.
 *
 * <p>Read it ({@code effects.mails().to("jdoe@example.com").single()}), or verify it exhaustively: in
 * {@link #claims()} (Kotlin: {@code effects.verifyAll { ... }}) each check claims what it selects, and
 * {@link Claims#verifyAll()} fails if a changed object, a connector write, a mail or an error logged was not
 * claimed, so a check of what should happen also proves nothing else did. {@link #none()} is a verification
 * without checks: the block changed nothing.</p>
 *
 * <p>Not among the changes: midPoint's housekeeping ({@code metadata} and {@code operationExecution} at any depth,
 * the modifications of task and node objects, a shadow's {@code synchronizationTimestamp},
 * {@code fullSynchronizationTimestamp}, {@code synchronizationSituationDescription} and {@code cachingMetadata}). A
 * time-triggered follow-up (a trigger, a task's next run) is not part of the block unless the block runs it
 * ({@link TestClock#runDue()}); data a test adds to a source system belongs before the block.</p>
 */
public final class Effects {

    /** Item names that are midPoint's bookkeeping wherever they are. */
    private static final Set<String> HOUSEKEEPING = Set.of("metadata", "operationExecution");
    /** A shadow's synchronization bookkeeping. */
    private static final Set<String> SHADOW_HOUSEKEEPING = Set.of("synchronizationTimestamp",
            "fullSynchronizationTimestamp", "synchronizationSituationDescription", "cachingMetadata");
    /** Types whose modifications are the runtime's own (a task's progress, a node's heartbeat). */
    private static final Set<String> RUNTIME_TYPES = Set.of("TaskType", "NodeType");

    private final List<ObjectChange> changes;
    private final List<ConnectorCall> connectorCalls;
    private final List<RepositoryCalls.Call> repositoryCalls;
    private final List<Mail> mails;
    private final List<LogEntry> logs;
    private final List<AuditRecord> audit;

    private Effects(List<ObjectChange> changes, List<ConnectorCall> connectorCalls,
            List<RepositoryCalls.Call> repositoryCalls, List<Mail> mails, List<LogEntry> logs, List<AuditRecord> audit) {
        this.changes = changes;
        this.connectorCalls = connectorCalls;
        this.repositoryCalls = repositoryCalls;
        this.mails = mails;
        this.logs = logs;
        this.audit = audit;
    }

    static Effects of(EffectsCapture capture) {
        List<RepositoryCalls.Call> repositoryCalls = new ArrayList<>();
        for (EffectsCapture.RepositoryCall call : capture.repositoryCalls()) {
            repositoryCalls.add(new RepositoryCalls.Call(call.method, call.type, call.oid, call.change != null));
        }
        List<ConnectorCall> connectorCalls = new ArrayList<>();
        for (EffectsCapture.ConnectorCall call : capture.connectorCalls()) {
            connectorCalls.add(new ConnectorCall(call.resource, call.operation, call.objectClass, call.account,
                    call.attributes, call.failed));
        }
        return new Effects(changes(capture.repositoryCalls()), Collections.unmodifiableList(connectorCalls),
                Collections.unmodifiableList(repositoryCalls), capture.mails(), capture.logs(), capture.audit());
    }

    /** The writes of the block, one change per object, without the housekeeping. */
    private static List<ObjectChange> changes(List<EffectsCapture.RepositoryCall> calls) {
        Map<String, List<EffectsCapture.RepositoryCall>> byObject = new LinkedHashMap<>();
        int unknown = 0;
        for (EffectsCapture.RepositoryCall call : calls) {
            if (call.change != null) {
                String key = call.oid != null ? call.type + "/" + call.oid : "?" + unknown++;
                byObject.computeIfAbsent(key, k -> new ArrayList<>()).add(call);
            }
        }
        List<ObjectChange> out = new ArrayList<>();
        for (List<EffectsCapture.RepositoryCall> writes : byObject.values()) {
            EffectsCapture.RepositoryCall first = writes.get(0);
            EffectsCapture.RepositoryCall last = writes.get(writes.size() - 1);
            ObjectChange.Kind kind = first.change == EffectsCapture.RepositoryCall.Change.ADDED
                    ? ObjectChange.Kind.ADDED
                    : last.change == EffectsCapture.RepositoryCall.Change.DELETED ? ObjectChange.Kind.DELETED
                            : ObjectChange.Kind.MODIFIED;
            List<ObjectChange.Item> items = new ArrayList<>();
            String name = null;
            for (EffectsCapture.RepositoryCall write : writes) {
                name = write.name != null ? write.name : name;
                for (Object modification : write.modifications) {
                    ObjectChange.Item item = item(first.type, modification);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            if (kind == ObjectChange.Kind.MODIFIED && (items.isEmpty() || RUNTIME_TYPES.contains(first.type))) {
                continue;
            }
            out.add(new ObjectChange(first.type, first.oid, name, kind, items));
        }
        return Collections.unmodifiableList(out);
    }

    /** The item delta as text, or null if it is housekeeping. */
    private static ObjectChange.Item item(String type, Object modification) {
        if (!(modification instanceof ItemDelta)) {
            return null;
        }
        ItemDelta<?, ?> delta = (ItemDelta<?, ?>) modification;
        String path = EffectsCapture.pathText(delta.getPath(), false);
        for (String name : path.split("/")) {
            if (HOUSEKEEPING.contains(name)) {
                return null;
            }
        }
        if ("ShadowType".equals(type) && SHADOW_HOUSEKEEPING.contains(path.split("/")[0])) {
            return null;
        }
        boolean replace = delta.getValuesToReplace() != null;
        return new ObjectChange.Item(path, texts(delta.getValuesToAdd()), texts(delta.getValuesToDelete()),
                texts(delta.getValuesToReplace()), replace);
    }

    private static List<String> texts(Collection<? extends PrismValue> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (PrismValue value : values) {
                out.add(text(value));
            }
        }
        return out;
    }

    /**
     * A value as text: an assignment and the activation as {@link Describe} shows them, another container value as
     * its items ({@code {name=group, shadowRef=<oid>}}), anything else as {@link Values#text}.
     */
    private static String text(PrismValue value) {
        if (!(value instanceof PrismContainerValue)) {
            return Values.text(value.getRealValue());
        }
        PrismContainerValue<?> container = (PrismContainerValue<?>) value;
        Object containerable = container.asContainerable();
        if (containerable instanceof AssignmentType || containerable instanceof ActivationType) {
            return Values.text(value);
        }
        List<String> items = new ArrayList<>();
        for (Item<?, ?> item : container.getItems()) {
            List<String> values = new ArrayList<>();
            for (Object v : item.getValues()) {
                values.add(v instanceof PrismValue ? text((PrismValue) v) : String.valueOf(v));
            }
            items.add(item.getElementName().getLocalPart() + "="
                    + (values.size() == 1 ? values.get(0) : values.toString()));
        }
        return "{" + String.join(", ", items) + "}";
    }

    /** The objects the block added, modified or deleted in the repository, one change per object. */
    public Changes changes() {
        return Changes.of(changes, null);
    }

    /** The calls midPoint made to the connectors: writes and reads. */
    public ConnectorCalls connectorCalls() {
        return ConnectorCalls.of(connectorCalls, null);
    }

    /** The calls midPoint made to its repository (below the cache): for looking into what a configuration costs. */
    public RepositoryCalls repositoryCalls() {
        return RepositoryCalls.of(repositoryCalls);
    }

    public Mails mails() {
        return Mails.ofBlock(mails, null);
    }

    /** What the configuration's scripts and the captured loggers logged in the block (INFO and up). */
    public Logs logs() {
        return Logs.ofBlock(logs, null);
    }

    public Audit audit() {
        return Audit.ofBlock(audit, null);
    }

    /**
     * Starts an exhaustive verification: check through the returned {@link Claims}, then call
     * {@link Claims#verifyAll()}. In Kotlin, {@code effects.verifyAll { ... }} does both.
     */
    public Claims claims() {
        return new Claims(this, new Ledger());
    }

    /** Checks that the block changed nothing: no object, no connector write, no mail and no error logged. */
    public Effects none() {
        claims().verifyAll();
        return this;
    }

    List<ObjectChange> changeList() {
        return changes;
    }

    List<ConnectorCall> connectorCallList() {
        return connectorCalls;
    }

    List<Mail> mailList() {
        return mails;
    }

    List<LogEntry> logList() {
        return logs;
    }

    static String ignoredNote() {
        return "(not counted: metadata, operationExecution, task and node modifications, a shadow's synchronization"
                + " timestamps and situation description, connector reads, logs below ERROR, audit records)";
    }
}
