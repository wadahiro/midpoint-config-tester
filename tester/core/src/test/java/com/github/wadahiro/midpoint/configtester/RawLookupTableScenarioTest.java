package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableRowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableType;

/**
 * A lookup table's rows written past the model ({@link Raw}), as an operator rewrites a row the deployment keeps its
 * state in: the rows are an incomplete item, which the repository returns only when asked to, so the write is read
 * back with them. midPoint's initial lookup table of languages is on every line.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class RawLookupTableScenarioTest {

    @Test
    void theRowsOfALookupTableAreSetAndReadBack(MidPointConfigTester t) {
        t.raw().object(LookupTableType.class, "Languages")
                .set("row", Struct.of("key", "position", "value", "1794927430735"));

        LookupTableType table = t.midpoint().find(LookupTableType.class, "Languages");
        List<String> rows = table.getRow().stream().map(r -> r.getKey() + "=" + r.getValue())
                .collect(Collectors.toList());
        assertThat(rows).containsExactly("position=1794927430735");
        assertThat(table.getRow()).extracting(LookupTableRowType::getKey).containsExactly("position");
    }
}
