package com.github.wadahiro.midpoint.tester.mirror.connector;

import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

/** Configuration of the mirror connector: just which in-memory resource to serve. */
public class MirrorConfiguration extends AbstractConfiguration {

    private String instanceId;

    @ConfigurationProperty(order = 1, required = true, displayMessageKey = "instanceId.display",
            helpMessageKey = "instanceId.help")
    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    public void validate() {
        if (StringUtil.isBlank(instanceId)) {
            throw new ConfigurationException("instanceId is required");
        }
    }
}
