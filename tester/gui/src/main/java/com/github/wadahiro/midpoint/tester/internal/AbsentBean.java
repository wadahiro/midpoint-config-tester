package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Proxy;

import org.springframework.beans.factory.FactoryBean;

/**
 * A bean the GUI's pages inject that the tester does not have, and the forms do not use (4.0's ReportManager: the
 * tester has no report-impl, whose JasperReports libraries are left out; 4.11's SmartIntegrationService): a proxy whose
 * every call fails.
 */
public final class AbsentBean<T> implements FactoryBean<T> {

    private final Class<T> type;

    public AbsentBean(Class<T> type) {
        this.type = type;
    }

    @Override
    public T getObject() {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                case "toString":
                    return "absent " + type.getSimpleName();
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
                }
            }
            throw new UnsupportedOperationException(type.getSimpleName() + " is not in the tester");
        }));
    }

    @Override
    public Class<?> getObjectType() {
        return type;
    }
}
