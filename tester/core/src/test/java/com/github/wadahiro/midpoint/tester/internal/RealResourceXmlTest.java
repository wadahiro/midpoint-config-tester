package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** A connector configuration property's value as deployed, also when it is given as an expression (no midPoint). */
class RealResourceXmlTest {

    @Test
    void aPropertysValueWithoutEvaluatingIt() {
        assertThat(RealResourceXml.propertyValue(property("<p> ; </p>"))).isEqualTo(";");
        assertThat(RealResourceXml.propertyValue(property("<p><expression><value>|</value></expression></p>")))
                .isEqualTo("|");
        assertThat(RealResourceXml.propertyValue(property(
                "<p><expression><script><code>return \"\\t\"</code></script></expression></p>"))).isEqualTo("\t");
        assertThat(RealResourceXml.propertyValue(property(
                "<p><expression><script><code>\n  ';'\n</code></script></expression></p>"))).isEqualTo(";");
        assertThatThrownBy(() -> RealResourceXml.propertyValue(property(
                "<p><expression><script><code>return x ? ',' : ';'</code></script></expression></p>")))
                .hasMessageContaining("cannot tell the value of the connector property p");
    }

    private static Element property(String xml) {
        return Xml.parse(xml).getDocumentElement();
    }
}
