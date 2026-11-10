package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.namespace.QName;

import org.w3c.dom.Element;

import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.DeploymentLoadException;
import com.github.wadahiro.midpoint.configtester.names.AttributeRef;
import com.github.wadahiro.midpoint.configtester.names.DeploymentName;
import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;

/**
 * Checks the typed names generated from a deployment's configuration ({@code names.package} of the manifest, class
 * {@code Names}) against the loaded deployment, once per load: every name the configuration has on this line must be
 * there (objects by name and OID, attributes in the resource's schema as midPoint stored it, extension items in the
 * schema midPoint runs with). A generated class from an older configuration, or a stored schema attribute the actual
 * resource does not have, fails the load with the list. Without generated names nothing is checked.
 */
final class NamesCheck {

    private static final Map<String, Class<? extends ObjectType>> TYPES = new HashMap<>();

    static {
        TYPES.put("RoleType", RoleType.class);
        TYPES.put("OrgType", OrgType.class);
        TYPES.put("ServiceType", ServiceType.class);
        TYPES.put("ArchetypeType", ArchetypeType.class);
        TYPES.put("UserType", UserType.class);
        TYPES.put("ResourceType", ResourceType.class);
        TYPES.put("TaskType", TaskType.class);
        TYPES.put("ReportType", ReportType.class);
        TYPES.put("AccessCertificationDefinitionType", AccessCertificationDefinitionType.class);
    }

    /** Generated names the loaded deployment does not have. */
    static final class Failure extends DeploymentLoadException {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }

    private NamesCheck() {
    }

    /** The generated names of the manifest, or null when none are on the classpath. */
    static List<DeploymentName> generated(Manifest manifest) {
        if (manifest.namesPackage == null) {
            return null;
        }
        String className = manifest.namesPackage + ".Names";
        Class<?> names;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            names = Class.forName(className, true, loader != null ? loader : NamesCheck.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
        try {
            List<DeploymentName> out = new ArrayList<>();
            for (Object name : (List<?>) names.getMethod("all").invoke(null)) {
                out.add((DeploymentName) name);
            }
            return out;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new DeploymentLoadException(className + " cannot be read: " + e, e);
        }
    }

    /** Fails the load with every generated name the loaded deployment does not have on this line. */
    static void check(Deployment deployment, MidPointImpl mp, String line) {
        List<DeploymentName> names = generated(deployment.manifest());
        if (names == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        Map<String, Set<String>> schemas = new HashMap<>();
        for (DeploymentName name : names) {
            if (!name.existsOn(line)) {
                continue;
            }
            String problem;
            if (name instanceof ObjectRef) {
                problem = object(mp, (ObjectRef) name);
            } else if (name instanceof AttributeRef) {
                problem = attribute(deployment, mp, (AttributeRef) name, schemas);
            } else if (name instanceof ItemRef) {
                problem = item(mp, (ItemRef) name);
            } else {
                problem = "unknown kind of name";
            }
            if (problem != null) {
                missing.add(name + ": " + problem);
            }
        }
        if (!missing.isEmpty()) {
            throw new Failure(deployment.manifest().file + ": " + missing.size() + " generated name(s)"
                    + " of " + deployment.manifest().namesPackage + " are not in the deployment loaded on midPoint "
                    + line + " (generate them again from the configuration, or fix the configuration):\n  "
                    + String.join("\n  ", missing));
        }
    }

    private static String object(MidPointImpl mp, ObjectRef ref) {
        Class<? extends ObjectType> type = TYPES.get(ref.type());
        if (type == null) {
            return "unknown type " + ref.type();
        }
        ObjectType object = mp.find(type, ref.oid() != null ? ref.oid() : ref.name());
        if (object == null) {
            return "no such object" + (ref.oid() != null ? " with OID " + ref.oid() : "");
        }
        String actual = object.getName() != null ? object.getName().getOrig() : null;
        if (!ref.name().equals(actual)) {
            return "the object " + ref.oid() + " is named " + actual;
        }
        return null;
    }

    private static String attribute(Deployment deployment, MidPointImpl mp, AttributeRef ref,
            Map<String, Set<String>> schemas) {
        String oid = deployment.resourceOid(ref.resource());
        if (oid == null) {
            return "no resource " + ref.resource();
        }
        Set<String> attributes = schemas.computeIfAbsent(ref.resource(), r -> storedSchema(mp, oid));
        if (attributes.isEmpty()) {
            // no stored schema (the resource was never reached): nothing to check against
            return null;
        }
        String naming = deployment.namingAttribute(ref.resource());
        for (String attribute : attributes) {
            if (attribute.equalsIgnoreCase(ref.name())) {
                return null;
            }
        }
        if (ref.name().equalsIgnoreCase(naming)) {
            return null;
        }
        return "not in the resource's schema (" + String.join(", ", new TreeSet<>(attributes)) + ")";
    }

    /** The attribute names of the object classes of the schema midPoint stored in the resource; empty if none. */
    private static Set<String> storedSchema(MidPointImpl mp, String oid) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ResourceType resource = mp.find(ResourceType.class, oid);
        if (resource == null || resource.getSchema() == null || resource.getSchema().getDefinition() == null) {
            return out;
        }
        Element schema = resource.getSchema().getDefinition().getSchema();
        if (schema == null) {
            return out;
        }
        for (Element element : Xml.descendants(schema, "element")) {
            String name = element.getAttribute("name");
            if (!name.isEmpty() && element.getParentNode() instanceof Element
                    && "sequence".equals(((Element) element.getParentNode()).getLocalName())) {
                out.add(name);
            }
        }
        return out;
    }

    private static String item(MidPointImpl mp, ItemRef ref) {
        if (!"UserType".equals(ref.ownerType())) {
            return "items of " + ref.ownerType() + " are not checked";
        }
        PrismObjectDefinition<UserType> definition = mp.prismContext().getSchemaRegistry()
                .findObjectDefinitionByCompileTimeClass(UserType.class);
        String[] segments = ref.path().split("/");
        List<Object> path = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            path.add(i == 0 && "extension".equals(segments[i]) ? UserType.F_EXTENSION
                    : new QName(ref.namespace() != null ? ref.namespace() : "", segments[i]));
        }
        return definition.findItemDefinition(ItemPath.create(path.toArray())) != null ? null
                : "not in the extension schema midPoint runs with";
    }
}
