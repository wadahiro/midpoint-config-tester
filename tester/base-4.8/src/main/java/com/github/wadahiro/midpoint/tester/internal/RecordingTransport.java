package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.model.test.DummyTransport;
import com.evolveum.midpoint.notifications.api.transports.Message;
import com.evolveum.midpoint.notifications.api.transports.SendingContext;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.github.wadahiro.midpoint.tester.Notification;

/**
 * midPoint's {@code dummy} transport of model tests (messages kept per transport name), which also keeps every
 * message in the order sent, as a {@link Notification}.
 */
final class RecordingTransport extends DummyTransport {

    private final List<Notification> sent = new ArrayList<>();

    @Override
    public void send(Message message, String name, SendingContext ctx, OperationResult parentResult) {
        super.send(message, name, ctx, parentResult);
        synchronized (sent) {
            sent.add(new Notification(name, message.getTo(), message.getCc(), message.getBcc(), message.getSubject(),
                    message.getBody(), message.getContentType()));
        }
    }

    List<Notification> sent() {
        synchronized (sent) {
            return new ArrayList<>(sent);
        }
    }

    void clear() {
        synchronized (sent) {
            sent.clear();
        }
        clearMessages();
    }
}
