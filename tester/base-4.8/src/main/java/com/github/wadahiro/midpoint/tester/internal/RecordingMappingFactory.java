package com.github.wadahiro.midpoint.tester.internal;

import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.model.common.ModelCommonBeans;
import com.evolveum.midpoint.model.common.mapping.MappingBuilder;
import com.evolveum.midpoint.model.common.mapping.MappingFactory;
import com.evolveum.midpoint.model.common.mapping.MappingImpl;
import com.evolveum.midpoint.model.common.mapping.RecordingMappingImpl;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismValue;

/**
 * midPoint's {@code mappingFactory} bean with builders that build {@link RecordingMappingImpl}s
 * ({@link RecorderInstaller} sets this class on the bean definition). Every other {@code createMappingBuilder}
 * variant starts from the no-argument one.
 */
public class RecordingMappingFactory extends MappingFactory {

    private ModelCommonBeans beans;

    @Override
    public void setBeans(ModelCommonBeans beans) {
        super.setBeans(beans);
        this.beans = beans;
    }

    @Override
    public <V extends PrismValue, D extends ItemDefinition<?>> MappingBuilder<V, D> createMappingBuilder() {
        return new RecordingMappingBuilder<V, D>()
                .beans(beans)
                .profiling(isProfiling());
    }

    static final class RecordingMappingBuilder<V extends PrismValue, D extends ItemDefinition<?>>
            extends MappingBuilder<V, D> {

        @Override
        public @NotNull MappingImpl<V, D> build() {
            return new RecordingMappingImpl<>(this);
        }
    }
}
