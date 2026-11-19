package com.github.wadahiro.midpoint.tester.internal;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;

/**
 * The GUI's part of a user's GUI profile (4.4 and later), compiled at every login as in production, where the GUI is
 * always there: its compiler (DefaultGuiConfigurationCompiler: the default collection views of the types, the details
 * pages' panels) is a bean of the tester's context, registered before the first login, and not of the GUI's (which
 * would register it only once a form loads the GUI, so that a profile compiled before differed from one after). None
 * when the GUI's classes are excluded from the tester.
 */
public final class GuiProfileCompilers implements BeanDefinitionRegistryPostProcessor {

    static final String COMPILER = "com.evolveum.midpoint.gui.api.DefaultGuiConfigurationCompiler";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        ClassLoader loader = GuiProfileCompilers.class.getClassLoader();
        if (ClassUtils.isPresent(COMPILER, loader)) {
            registry.registerBeanDefinition("defaultGuiConfigurationCompiler",
                    new RootBeanDefinition(ClassUtils.resolveClassName(COMPILER, loader)));
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }
}
