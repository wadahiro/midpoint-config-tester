package com.github.wadahiro.midpoint.tester.check;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.schema.PrismSchema;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;
import com.evolveum.midpoint.schema.constants.SchemaConstants;

/**
 * What the static checks need from midPoint 4.0. Every static line module has a class with this name and these
 * members; the line-neutral sources call only these. 4.0 has no upgrade validator (ninja verify is 4.4+).
 */
public final class StaticLine {

    public static final String LINE = "4.0";

    private StaticLine() {
    }

    /** midPoint's schemas, plus the extension schemas ({@code *.xsd}) of the directory unless null. */
    static PrismContext prismContext(File extensionSchemaDir) throws Exception {
        return new MidPointPrismContextFactory(extensionSchemaDir).createInitializedPrismContext();
    }

    /** The members of {@code c:expressionEvaluator}'s substitution group: the evaluator elements this line knows. */
    static Set<String> evaluators(PrismContext prismContext) {
        QName head = new QName(SchemaConstants.NS_C, "expressionEvaluator");
        PrismSchema common = prismContext.getSchemaRegistry().findSchemaByNamespace(SchemaConstants.NS_C);
        Set<String> out = new TreeSet<>();
        for (ItemDefinition<?> definition : common.getDefinitions(ItemDefinition.class)) {
            if (head.equals(definition.getSubstitutionHead())) {
                out.add(definition.getItemName().getLocalPart());
            }
        }
        return out;
    }

    /** ninja verify's findings for the object ({@code ObjectUpgradeValidator}); null where the line has none. */
    static List<UpgradeItem> upgradeFindings(PrismContext prismContext, PrismObject<?> object) {
        return null;
    }
}
