package com.github.wadahiro.midpoint.tester.internal;

import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.tester.TargetSystem;

/**
 * A target system whose content is not in midPoint's database (a mirror, a CSV file, an in-memory LDAP server):
 * snapshots capture it, resets restore it ({@link TesterRuntime}).
 */
interface ManagedTarget extends TargetSystem {

    /** The content (and definitions) at this moment. */
    Object capture();

    /** Back to captured content; null: the target is no longer used (emptied, its resources released). */
    void restore(Object captured);

    /**
     * The content as a serializable value for the deployment cache ({@link DeploymentCache}), to give back to
     * {@link #restorePersistent} in another JVM after the same deployment's resources were prepared again (the
     * definitions come from the preparation, only the content is kept).
     */
    java.io.Serializable persistentContent();

    void restorePersistent(java.io.Serializable content);

    /** The attribute that names accounts, as midPoint shows it ({@code icfs:name}, {@code ri:dn}...). */
    QName accountNamingAttribute();
}
