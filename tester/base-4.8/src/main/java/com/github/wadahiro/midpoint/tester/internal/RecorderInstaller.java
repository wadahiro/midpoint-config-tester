package com.github.wadahiro.midpoint.tester.internal;

import jakarta.xml.bind.JAXBElement;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import com.evolveum.midpoint.model.common.expression.script.ScriptExpressionFactory;
import com.evolveum.midpoint.model.common.mapping.MappingFactory;
import com.evolveum.midpoint.model.common.mapping.RecordingMappingImpl;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.util.CloneUtil;
import com.evolveum.midpoint.repo.common.expression.ExpressionFactory;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * Installs the recorder hooks of 4.8 in the Spring context:
 * <ul>
 *   <li>mappings: the {@code mappingFactory} bean (ctx-model.xml) becomes a {@link RecordingMappingFactory}, whose
 *       mappings report the start and end of their evaluation;</li>
 *   <li>notification filters: the {@code expressionFilterHelper} bean becomes a
 *       {@link ScopedExpressionFilterHelper}, which tells which notifier a filter is evaluated for;</li>
 *   <li>expressions: once every singleton exists (the evaluator factories have registered themselves), the evaluator
 *       factories of each {@code ExpressionFactory} are wrapped ({@link EvaluatorHook}).</li>
 * </ul>
 */
public class RecorderInstaller implements BeanFactoryPostProcessor, BeanFactoryAware, SmartInitializingSingleton {

    private static final String MAPPING_FACTORY = "mappingFactory";
    private static final String EXPRESSION_FILTER_HELPER = "expressionFilterHelper";

    private ListableBeanFactory beanFactory;

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
        factory.getBeanDefinition(MAPPING_FACTORY).setBeanClassName(RecordingMappingFactory.class.getName());
        factory.getBeanDefinition(EXPRESSION_FILTER_HELPER)
                .setBeanClassName(ScopedExpressionFilterHelper.class.getName());
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = (ListableBeanFactory) beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        MappingFactory mappingFactory = beanFactory.getBean(MAPPING_FACTORY, MappingFactory.class);
        if (!(mappingFactory instanceof RecordingMappingFactory)) {
            throw new IllegalStateException("The mapping factory was not replaced: " + mappingFactory.getClass());
        }
        if (!(beanFactory.getBean(EXPRESSION_FILTER_HELPER) instanceof ScopedExpressionFilterHelper)) {
            throw new IllegalStateException("The expression filter helper was not replaced");
        }
        PrismContext prismContext = beanFactory.getBean(PrismContext.class);
        for (ExpressionFactory expressionFactory : beanFactory.getBeansOfType(ExpressionFactory.class).values()) {
            EvaluatorHook.install(expressionFactory, element -> serialize(prismContext, element));
        }
        ScriptHook48.install(beanFactory.getBean(ScriptExpressionFactory.class),
                element -> serialize(prismContext, element));
        Recorder48 recorder = new Recorder48();
        EvaluatorHook.listener(recorder);
        RecordingMappingImpl.listener(recorder);
    }

    /** An evaluator element as prism writes it (for its fingerprint); a copy, so the original stays as parsed. */
    @SuppressWarnings("unchecked")
    private static String serialize(PrismContext prismContext, Object element) {
        JAXBElement<Object> original = (JAXBElement<Object>) element;
        try {
            return prismContext.xmlSerializer().serialize(new JAXBElement<>(original.getName(),
                    original.getDeclaredType(), CloneUtil.clone(original.getValue())));
        } catch (SchemaException e) {
            throw new IllegalStateException(e);
        }
    }
}
