package com.github.wadahiro.midpoint.tester.internal;

import static com.github.wadahiro.midpoint.tester.internal.Xml.NS_RI;
import static com.github.wadahiro.midpoint.tester.internal.Xml.child;
import static com.github.wadahiro.midpoint.tester.internal.Xml.children;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import org.w3c.dom.Element;

import com.github.wadahiro.midpoint.tester.TargetSystem;

/**
 * The simulated activation status of a resource ({@code capabilities/configured/activation/status} with an
 * {@code attribute}): midPoint writes the account's status as that attribute, with the first {@code enableValue} or
 * {@code disableValue}, and reads it back as midPoint's provisioning does ({@code TwoStateSimulatedToRealConverter}):
 * a disable value means disabled, an enable value (or an empty enable value, "any other value") enabled; no value is
 * disabled when a disable value is empty, enabled when an enable value is. Object type level capabilities are not
 * read.
 */
final class SimulatedActivation {

    /** Local name of the {@code ri:} attribute. */
    final String attribute;
    final List<String> enableValues;
    final List<String> disableValues;

    private SimulatedActivation(String attribute, List<String> enableValues, List<String> disableValues) {
        this.attribute = attribute;
        this.enableValues = enableValues;
        this.disableValues = disableValues;
    }

    /** The resource's simulated activation status, or null if it has none (or it is disabled). */
    static SimulatedActivation of(Element resource) {
        Element capabilities = child(resource, "capabilities");
        Element configured = capabilities != null ? child(capabilities, "configured") : null;
        Element activation = configured != null ? child(configured, "activation") : null;
        Element status = activation != null ? child(activation, "status") : null;
        if (status == null || disabled(activation) || disabled(status)) {
            return null;
        }
        Element attribute = child(status, "attribute");
        if (attribute == null) {
            return null;
        }
        QName name = Xml.qname(attribute);
        if (!NS_RI.equals(name.getNamespaceURI())) {
            return null;
        }
        return new SimulatedActivation(name.getLocalPart(), texts(status, "enableValue"), texts(status, "disableValue"));
    }

    private static boolean disabled(Element capability) {
        Element enabled = child(capability, "enabled");
        return enabled != null && "false".equals(enabled.getTextContent().trim());
    }

    private static List<String> texts(Element parent, String localName) {
        List<String> out = new ArrayList<>();
        for (Element e : children(parent, localName)) {
            out.add(e.getTextContent().trim());
        }
        return out;
    }

    boolean enabled(TargetSystem target, String account) {
        List<Object> values = target.attributeValues(account, attribute);
        if (values.isEmpty() || values.size() == 1 && "".equals(String.valueOf(values.get(0)))) {
            if (disableValues.contains("")) {
                return false;
            }
            if (enableValues.contains("")) {
                return true;
            }
            throw new AssertionError("Account " + account + " has no " + attribute + " (simulated activation status):"
                    + " neither enabled nor disabled (enable values " + enableValues + ", disable values "
                    + disableValues + ")");
        }
        String value = String.valueOf(values.get(0));
        if (disableValues.contains(value)) {
            return false;
        }
        if (enableValues.contains(value) || enableValues.contains("")) {
            return true;
        }
        throw new AssertionError("Account " + account + " has " + attribute + "=" + value + " (simulated activation"
                + " status): neither enabled nor disabled (enable values " + enableValues + ", disable values "
                + disableValues + ")");
    }

    void setEnabled(TargetSystem target, String account, boolean enabled) {
        List<String> values = enabled ? enableValues : disableValues;
        String value = values.isEmpty() ? "" : values.get(0);
        if (value.isEmpty()) {
            target.modifyAccount(account, attribute);
        } else {
            target.modifyAccount(account, attribute, value);
        }
    }
}
