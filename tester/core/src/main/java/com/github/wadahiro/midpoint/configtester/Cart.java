package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.configtester.names.AssignableRef;

/**
 * The shopping cart of a {@link Request} ({@link Request#submit(Consumer)}): the roles (orgs, services) the request
 * adds in one Submit, each with its relation and validity as the request-access page lets the user choose them. The
 * GUI's cart only adds: {@code t.actor(u).request().submit(c -> c.assign("VPN Access").assign("Wiki", a ->
 * a.validTo(end)))}.
 */
public final class Cart {

    /** One item of the cart: a role to assign, with its relation and validity. */
    static final class Item {
        final String role;
        final QName relation;
        final java.time.Instant validFrom;
        final java.time.Instant validTo;

        Item(String role, QName relation, java.time.Instant validFrom, java.time.Instant validTo) {
            this.role = role;
            this.relation = relation;
            this.validFrom = validFrom;
            this.validTo = validTo;
        }
    }

    private final MidPointConfigTester tester;
    final List<Item> items = new ArrayList<>();

    Cart(MidPointConfigTester tester) {
        this.tester = tester;
    }

    /** Adds the role (or org, service) of this name with the default relation. */
    public Cart assign(String role) {
        items.add(new Item(role, null, null, null));
        return this;
    }

    /** Adds the role with the relation ({@code SchemaConstants.ORG_OWNER}). */
    public Cart assign(String role, QName relation) {
        items.add(new Item(role, relation, null, null));
        return this;
    }

    /** Adds the role with a relation or a validity ({@link NewAssignment}). */
    public Cart assign(String role, Consumer<NewAssignment> spec) {
        NewAssignment a = NewAssignment.of(tester, spec);
        items.add(new Item(role, a.relation, a.validFrom, a.validTo));
        return this;
    }

    public Cart assign(AssignableRef role) {
        return assign(role.on(tester.line()));
    }

    public Cart assign(AssignableRef role, QName relation) {
        return assign(role.on(tester.line()), relation);
    }

    public Cart assign(AssignableRef role, Consumer<NewAssignment> spec) {
        return assign(role.on(tester.line()), spec);
    }

    /** Adds these roles, each with the default relation. */
    public Cart assign(String... roles) {
        for (String role : roles) {
            assign(role);
        }
        return this;
    }

    public Cart assign(AssignableRef... roles) {
        for (AssignableRef role : roles) {
            assign(role);
        }
        return this;
    }

    /** The names of the roles in the cart, in order. */
    public List<String> roles() {
        List<String> out = new ArrayList<>();
        for (Item item : items) {
            out.add(item.role);
        }
        return out;
    }

    @Override
    public String toString() {
        return "cart " + roles();
    }
}
