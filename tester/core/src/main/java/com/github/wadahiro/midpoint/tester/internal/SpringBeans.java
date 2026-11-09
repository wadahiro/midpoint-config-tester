package com.github.wadahiro.midpoint.tester.internal;

import java.beans.Introspector;

import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.ApplicationContext;

/** Bean lookup by type that also works where several beans implement it (e.g. RepositoryService on 4.0). */
final class SpringBeans {

    private SpringBeans() {
    }

    static <T> T get(ApplicationContext context, Class<T> type) {
        try {
            return context.getBean(type);
        } catch (NoUniqueBeanDefinitionException e) {
            // midPoint names the main implementation after the interface: repositoryService, modelService, ...
            String name = Introspector.decapitalize(type.getSimpleName());
            if (context.containsBean(name)) {
                return context.getBean(name, type);
            }
            throw e;
        }
    }
}
