package com.github.wadahiro.midpoint.configtester.assertj;

import org.assertj.core.presentation.StandardRepresentation;

import com.github.wadahiro.midpoint.configtester.Describe;

/**
 * AssertJ's representation with midPoint's values as {@link Describe} shows them: an assignment as
 * {@code assignment of role Dept Sales}, a reference as {@code role Dept Sales}. {@code MidPointConfigTestExtension} makes it
 * AssertJ's representation during every scenario test.
 */
public class MidPointRepresentation extends StandardRepresentation {

    @Override
    protected String fallbackToStringOf(Object object) {
        return Describe.describes(object) ? Describe.of(object) : super.fallbackToStringOf(object);
    }
}
