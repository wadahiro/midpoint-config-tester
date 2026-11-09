package com.github.wadahiro.midpoint.tester;

/**
 * Who makes the driver calls of a block ({@link MidPoint#callAs}), as a service that calls midPoint on a user's behalf
 * presents them: the user logged in (the principal, by name), optionally with all authorizations on top of the user's
 * own ({@link #privileged()}, midPoint's {@code runPrivileged}, as a service account's "run privileged" option does),
 * the owner of the tasks the calls create (the requester of the approval cases they start; the principal unless
 * {@link #owner} says otherwise) and the channel the model sees ({@link #channel}). Immutable.
 *
 * <pre>{@code
 * mp.callAs(Caller.user("jdoe").privileged().channel(GRPC_CHANNEL), () -> mp.addObjectXml(serviceXml));
 * mp.callAs(Caller.user("api-client").owner("jdoe"), () -> mp.bulkAction(script));
 * }</pre>
 */
public final class Caller {

    private final String user;
    private final boolean privileged;
    private final String owner;
    private final String channel;

    private Caller(String user, boolean privileged, String owner, String channel) {
        this.user = user;
        this.privileged = privileged;
        this.owner = owner;
        this.channel = channel;
    }

    /** The user (name or OID) logged in for the calls, with the user's own authorizations. */
    public static Caller user(String user) {
        if (user == null || user.isEmpty()) {
            throw new IllegalArgumentException("A caller needs a user");
        }
        return new Caller(user, false, null, null);
    }

    /** This caller with all authorizations added to the user's own, as midPoint's {@code runPrivileged}. */
    public Caller privileged() {
        return new Caller(user, true, owner, channel);
    }

    /** This caller with another owner (name or OID) of the tasks the calls create. */
    public Caller owner(String owner) {
        return new Caller(user, privileged, owner, channel);
    }

    /** This caller in the channel (its URI), as the model sees a change that came through it. */
    public Caller channel(String channelUri) {
        return new Caller(user, privileged, owner, channelUri);
    }

    public String user() {
        return user;
    }

    public boolean isPrivileged() {
        return privileged;
    }

    /** The owner of the tasks: {@link #owner(String)}, else the user. */
    public String owner() {
        return owner != null ? owner : user;
    }

    /** The channel, or null (the tasks' default). */
    public String channel() {
        return channel;
    }

    @Override
    public String toString() {
        return user + (privileged ? " (privileged)" : "") + (owner != null ? " for " + owner : "")
                + (channel != null ? " in " + channel : "");
    }
}
