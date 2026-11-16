package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.tester.RealSystem;
import com.github.wadahiro.midpoint.tester.ResourceOptions;
import com.github.wadahiro.midpoint.tester.TargetSystem;

/**
 * The target of a resource on its real connector that reaches a system the test provides ({@code connector: real}):
 * the tester's snapshots and resets go to the {@link RealSystem}, a test's reads and writes to its
 * {@link RealSystem#view()}, the provisioning scripts to the mirrors of the resource's additional connectors.
 */
final class ProvidedTarget implements InvocationHandler {

    /** What the tester asks of a provided target besides {@link ManagedTarget}. */
    interface Provided {
        RealSystem system();
    }

    private final String resourceOid;
    private final String resourceName;
    private final RealSystem system;
    private List<String> scriptMirrors = List.of();

    private ProvidedTarget(String resourceOid, String resourceName, RealSystem system) {
        this.resourceOid = resourceOid;
        this.resourceName = resourceName;
        this.system = system;
    }

    /** Starts the system for the resource and wraps it. */
    static ManagedTarget create(String resourceOid, String resourceName, RealSystem system) {
        system.start(resourceOid, resourceName);
        if (system == ResourceOptions.NO_RESTORE) {
            System.err.println("[tester] Resource " + resourceName + " is on its real connector with restore: none:"
                    + " its system is not restored between tests, each test starts from it as the earlier ones left it");
        }
        return (ManagedTarget) Proxy.newProxyInstance(ProvidedTarget.class.getClassLoader(),
                new Class<?>[] {ManagedTarget.class, RealTarget.class, Provided.class},
                new ProvidedTarget(resourceOid, resourceName, system));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "resourceOid":
                return resourceOid;
            case "system":
                return system;
            case "capture":
                return system.capture();
            case "restore":
                system.restore(args[0]);
                return null;
            case "persistentContent":
                java.io.Serializable content = system.persistentContent();
                if (content == null) {
                    throw new IllegalStateException("resource " + resourceName + ": " + system.getClass().getName()
                            + " keeps no content for the deployment cache (persistentContent() is null)");
                }
                return content;
            case "restorePersistent":
                system.restorePersistent((java.io.Serializable) args[0]);
                return null;
            case "accountNamingAttribute":
                String naming = system.accountNamingAttribute();
                return naming == null ? new QName(Xml.NS_ICFS, "name") : new QName(Xml.NS_RI, naming);
            case "scriptMirrors":
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) args[0];
                scriptMirrors = List.copyOf(ids);
                return null;
            case "scriptsFail":
                RealTarget.scriptsFail(resourceOid, scriptMirrors, (Boolean) args[0]);
                return null;
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "real system " + system.getClass().getName() + " of " + resourceName;
            default:
                TargetSystem view = system.view();
                if (view == null) {
                    throw new UnsupportedOperationException("Resource " + resourceName + " reaches a system the test"
                            + " provides, " + system.getClass().getName() + ", which gives the test no view of its"
                            + " data (RealSystem.view())");
                }
                try {
                    return method.invoke(view, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
        }
    }
}
