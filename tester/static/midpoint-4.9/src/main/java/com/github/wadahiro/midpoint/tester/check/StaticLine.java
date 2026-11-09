package com.github.wadahiro.midpoint.tester.check;

import java.io.File;
import java.util.ArrayList;
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
import com.evolveum.midpoint.schema.validator.ObjectUpgradeValidator;
import com.evolveum.midpoint.schema.validator.UpgradeValidationItem;
import com.evolveum.midpoint.schema.validator.UpgradeValidationResult;
import com.evolveum.midpoint.schema.validator.ValidationItem;

/**
 * What the static checks need from midPoint 4.9. Every static line module has a class with this name and these
 * members; the line-neutral sources call only these. 4.9 (as 4.10): the upgrade validator takes no prism context,
 * {@code ValidationItem} is a record.
 */
public final class StaticLine {

    public static final String LINE = "4.9";

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
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static List<UpgradeItem> upgradeFindings(PrismContext prismContext, PrismObject<?> object) throws Exception {
        ObjectUpgradeValidator validator = new ObjectUpgradeValidator();
        validator.showAllWarnings();
        UpgradeValidationResult result = validator.validate((PrismObject) object);
        List<UpgradeItem> out = new ArrayList<>();
        for (UpgradeValidationItem item : result.getItems()) {
            ValidationItem<?> validation = item.getItem();
            out.add(new UpgradeItem(item.getPriority() != null ? item.getPriority().name() : null,
                    String.valueOf(validation.status()), validation.path(),
                    validation.message() != null ? validation.message().getFallbackMessage() : null));
        }
        return out;
    }
}
