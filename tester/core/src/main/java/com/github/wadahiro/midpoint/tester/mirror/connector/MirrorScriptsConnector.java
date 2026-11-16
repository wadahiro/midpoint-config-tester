package com.github.wadahiro.midpoint.tester.mirror.connector;

import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.ScriptContext;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.ScriptOnConnectorOp;
import org.identityconnectors.framework.spi.operations.ScriptOnResourceOp;
import org.identityconnectors.framework.spi.operations.TestOp;

import com.github.wadahiro.midpoint.tester.mirror.MirrorResource;
import com.github.wadahiro.midpoint.tester.mirror.MirrorStore;

/**
 * The stand-in of an additional connector of a resource on its real connector (an SSH connector that runs the
 * provisioning scripts on a file server): scripts and a connection test only, as such a connector has, so midPoint
 * takes the schema and the other operations from the resource's own connector. The scripts are accepted and not run,
 * as on the {@link MirrorConnector}; they fail when the test says so ({@link MirrorResource#scriptsFail(boolean)}).
 */
@ConnectorClass(displayNameKey = "mirror.scripts.display", configurationClass = MirrorConfiguration.class)
public class MirrorScriptsConnector implements Connector, TestOp, ScriptOnResourceOp, ScriptOnConnectorOp {

    private MirrorConfiguration configuration;
    private MirrorResource resource;

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void init(Configuration configuration) {
        this.configuration = (MirrorConfiguration) configuration;
        this.resource = MirrorStore.get(this.configuration.getInstanceId());
        if (resource == null) {
            throw new ConfigurationException("No mirror resource " + this.configuration.getInstanceId());
        }
    }

    @Override
    public void dispose() {
        resource = null;
    }

    @Override
    public void test() {
        // nothing to connect to
    }

    @Override
    public Object runScriptOnResource(ScriptContext request, OperationOptions options) {
        return MirrorConnector.script(resource, request);
    }

    @Override
    public Object runScriptOnConnector(ScriptContext request, OperationOptions options) {
        return MirrorConnector.script(resource, request);
    }
}
