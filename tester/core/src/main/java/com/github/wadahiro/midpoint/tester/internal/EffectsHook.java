package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.namespace.QName;

import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.util.ClassUtils;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.provisioning.ucf.api.ConnectorFactory;
import com.evolveum.midpoint.provisioning.ucf.api.ConnectorInstance;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.tester.internal.EffectsCapture.ConnectorCall;
import com.github.wadahiro.midpoint.tester.internal.EffectsCapture.RepositoryCall;

/**
 * Records, while an {@link EffectsCapture} is open, the calls midPoint makes to its repository and to the connectors.
 * The {@code repositoryService} bean (the repository below the cache: every write, and the reads the cache does not
 * answer) and each {@code ConnectorFactory} bean are wrapped in a proxy; a factory's proxy wraps the connector
 * instances it creates, so mirror and real connectors are recorded alike, as midPoint called them. The proxies
 * implement every interface of the object they wrap and pass every call through; the methods are told apart by
 * name, so the hook is the same on every line.
 *
 * <p>Calls of the tester itself (an assertion reading a user, a wait reading a task) are not recorded: the first
 * frame outside the proxies, the repository cache and Spring's AOP is then one of the tester's classes.</p>
 */
public class EffectsHook implements SmartInstantiationAwareBeanPostProcessor {

    private static final String REPOSITORY = "repositoryService";
    private static final Set<String> REPOSITORY_WRITES = Set.of("addObject", "modifyObject", "modifyObjectDynamically",
            "deleteObject");
    /** The repository methods that read data (not its configuration or diagnostics). */
    private static final Set<String> REPOSITORY_READS = Set.of("getObject", "getVersion", "searchObjects",
            "searchObjectsIterative", "searchContainers", "searchContainersIterative", "countObjects",
            "countContainers", "searchReferences", "searchReferencesIterative", "countReferences",
            "searchShadowOwner", "listAccountShadowOwner", "isDescendant", "isAncestor");
    private static final Set<String> CONNECTOR_WRITES = Set.of("addObject", "modifyObject", "deleteObject",
            "executeScript");
    private static final Map<String, String> CONNECTOR_OPERATIONS = Map.of("addObject", "add", "modifyObject",
            "modify", "deleteObject", "delete", "executeScript", "executeScript", "fetchObject", "fetch", "search",
            "search", "count", "count", "fetchChanges", "fetchChanges", "fetchCurrentToken", "fetchCurrentToken",
            "test", "test");
    private static final String TESTER = "com.github.wadahiro.midpoint.";

    /** The proxies made for an early reference of a bean (a circular dependency). */
    private final Map<String, Object> early = new ConcurrentHashMap<>();

    @Override
    public Object getEarlyBeanReference(Object bean, String beanName) {
        return early.computeIfAbsent(beanName, name -> wrap(bean, name));
    }

    /** A bean already handed out as an early proxy stays as it is: Spring then exposes that proxy. */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return early.remove(beanName) != null ? bean : wrap(bean, beanName);
    }

    private static Object wrap(Object bean, String beanName) {
        if (Proxy.isProxyClass(bean.getClass()) && Proxy.getInvocationHandler(bean) instanceof Handler) {
            return bean;
        }
        if (REPOSITORY.equals(beanName) && bean instanceof RepositoryService) {
            return proxy(bean, new RepositoryHandler((RepositoryService) bean));
        }
        if (bean instanceof ConnectorFactory) {
            return proxy(bean, new FactoryHandler(bean));
        }
        return bean;
    }

    private static Object proxy(Object target, Handler handler) {
        Class<?>[] interfaces = ClassUtils.getAllInterfacesForClass(target.getClass(), target.getClass().getClassLoader());
        return Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, handler);
    }

    private abstract static class Handler implements InvocationHandler {

        final Object target;

        Handler(Object target) {
            this.target = target;
        }

        Object call(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    // ---- repository ------------------------------------------------------------------------------------------

    private static final class RepositoryHandler extends Handler {

        private final RepositoryService repository;

        RepositoryHandler(RepositoryService repository) {
            super(repository);
            this.repository = repository;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            boolean write = REPOSITORY_WRITES.contains(name);
            boolean read = REPOSITORY_READS.contains(name);
            if (!(write || read) || !EffectsCapture.capturing() || calledByTester()) {
                return call(method, args);
            }
            Class<?> type = argument(args, Class.class);
            String oid = write || name.equals("getObject") || name.equals("getVersion") ? secondString(args) : null;
            if (!write) {
                Object result = call(method, args);
                record(name, type, oid, null, null, Collections.emptyList());
                return result;
            }
            switch (name) {
                case "addObject": {
                    PrismObject<?> object = argument(args, PrismObject.class);
                    Object result = call(method, args);
                    record(name, object != null ? object.getCompileTimeClass() : null,
                            result instanceof String ? (String) result : object != null ? object.getOid() : null,
                            object != null ? name(object) : null, RepositoryCall.Change.ADDED,
                            Collections.emptyList());
                    return result;
                }
                case "deleteObject": {
                    String objectName = nameOf(type, oid);
                    Object result = call(method, args);
                    record(name, type, oid, objectName, RepositoryCall.Change.DELETED, Collections.emptyList());
                    return result;
                }
                default: {
                    Collection<?> deltas = argument(args, Collection.class);
                    PrismObject<?> before = objectOf(type, oid);
                    Object result = call(method, args);
                    PrismObject<?> after = objectOf(type, oid);
                    List<Object> modifications = effective(copies(modifications(result, deltas)), before, after);
                    record(name, type, oid, after != null ? name(after) : before != null ? name(before) : null,
                            RepositoryCall.Change.MODIFIED, modifications);
                    return result;
                }
            }
        }

        private static void record(String method, Class<?> type, String oid, String name,
                RepositoryCall.Change change, List<Object> modifications) {
            EffectsCapture.record(new RepositoryCall(method, type != null ? type.getSimpleName() : null, oid, name,
                    change, modifications));
        }

        private String nameOf(Class<?> type, String oid) {
            PrismObject<?> object = objectOf(type, oid);
            return object != null ? name(object) : null;
        }

        /** The object as stored now, read past the proxy (not recorded), or null. */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private PrismObject<?> objectOf(Class<?> type, String oid) {
            if (type == null || oid == null || !ObjectType.class.isAssignableFrom(type)) {
                return null;
            }
            try {
                return repository.getObject((Class) type, oid, null, new OperationResult("effects"));
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * The deltas that changed the object: a delta whose item has the same values before and after (a replace
         * with the value already there) did nothing. Without both objects, every delta.
         */
        private static List<Object> effective(List<Object> deltas, PrismObject<?> before, PrismObject<?> after) {
            if (before == null || after == null) {
                return deltas;
            }
            List<Object> out = new ArrayList<>();
            for (Object delta : deltas) {
                if (!(delta instanceof ItemDelta)) {
                    out.add(delta);
                    continue;
                }
                ItemPath path = ((ItemDelta<?, ?>) delta).getPath();
                if (!realValues(before.findItem(path)).equals(realValues(after.findItem(path)))) {
                    out.add(delta);
                }
            }
            return out;
        }

        private static List<Object> realValues(Item<?, ?> item) {
            List<Object> out = new ArrayList<>();
            if (item != null) {
                for (Object value : item.getValues()) {
                    out.add(value instanceof PrismValue ? ((PrismValue) value).getRealValue() : value);
                }
            }
            return out;
        }

        /** The modifications midPoint computed ({@code modifyObjectDynamically}), else the ones passed. */
        private static Collection<?> modifications(Object result, Collection<?> passed) {
            Object computed = getter(result, "getModifications");
            return computed instanceof Collection ? (Collection<?>) computed : passed;
        }

        private static List<Object> copies(Collection<?> deltas) {
            List<Object> out = new ArrayList<>();
            if (deltas != null) {
                for (Object delta : deltas) {
                    out.add(delta instanceof ItemDelta ? ((ItemDelta<?, ?>) delta).clone() : delta);
                }
            }
            return out;
        }

        private static String secondString(Object[] args) {
            return args != null && args.length > 1 && args[1] instanceof String ? (String) args[1] : null;
        }
    }

    /** Whether the repository is called by the tester's own code rather than by midPoint. */
    private static boolean calledByTester() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(f -> f.getClassName())
                .filter(c -> !c.startsWith(EffectsHook.class.getName()) && !c.startsWith("java.")
                        && !c.startsWith("jdk.") && !c.startsWith("com.sun.proxy") && !c.startsWith("org.springframework.")
                        && !c.startsWith("com.evolveum.midpoint.repo.cache.")
                        && !c.startsWith("com.evolveum.midpoint.util.aspect."))
                .findFirst()
                .map(c -> c.startsWith(TESTER))
                .orElse(false));
    }

    // ---- connectors ------------------------------------------------------------------------------------------

    private static final class FactoryHandler extends Handler {

        FactoryHandler(Object factory) {
            super(factory);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = call(method, args);
            if ("createConnectorInstance".equals(method.getName()) && result instanceof ConnectorInstance
                    && args != null && args.length >= 3) {
                // (connector, [namespace,] resource name, description) on every line
                Object resource = args[args.length - 2];
                return proxy(result, new ConnectorHandler(result, resource != null ? resource.toString() : null));
            }
            return result;
        }
    }

    private static final class ConnectorHandler extends Handler {

        private final String resource;

        ConnectorHandler(Object connector, String resource) {
            super(connector);
            this.resource = resource;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String operation = CONNECTOR_OPERATIONS.get(method.getName());
            if (operation == null || !EffectsCapture.capturing()) {
                return call(method, args);
            }
            boolean failed = true;
            try {
                Object result = call(method, args);
                failed = false;
                return result;
            } finally {
                try {
                    EffectsCapture.record(new ConnectorCall(resource, operation, objectClass(args), account(args),
                            CONNECTOR_WRITES.contains(method.getName()) ? attributes(method.getName(), args)
                                    : Collections.emptyList(),
                            failed));
                } catch (RuntimeException e) {
                    System.err.println("Effects recorder failed: " + e);
                }
            }
        }

        private static String objectClass(Object[] args) {
            PrismObject<?> shadow = argument(args, PrismObject.class);
            if (shadow != null) {
                Object objectClass = getter(shadow.asObjectable(), "getObjectClass");
                if (objectClass instanceof QName) {
                    return ((QName) objectClass).getLocalPart();
                }
            }
            if (args != null) {
                for (Object arg : args) {
                    Object typeName = arg != null && arg.getClass().getName().contains("Definition")
                            ? getter(arg, "getTypeName") : null;
                    if (typeName instanceof QName) {
                        return ((QName) typeName).getLocalPart();
                    }
                }
            }
            return null;
        }

        /** The account's name ({@code icfs:name}, or the shadow's name), else its identifiers as text. */
        private static String account(Object[] args) {
            PrismObject<?> shadow = argument(args, PrismObject.class);
            if (shadow != null) {
                String name = namingValue(shadow);
                if (name == null) {
                    name = name(shadow);
                }
                if (name != null) {
                    return name;
                }
            }
            if (args != null) {
                for (Object arg : args) {
                    if (arg != null && arg.getClass().getSimpleName().contains("Identification")) {
                        // the secondary identifier is the one people know (a DN), the primary one a UUID
                        for (String getter : List.of("getSecondaryIdentifiers", "getPrimaryIdentifiers",
                                "getAllIdentifiers")) {
                            String text = identifiersText(getter(arg, getter));
                            if (text != null) {
                                return text;
                            }
                        }
                    }
                }
                for (Object arg : args) {
                    if (arg instanceof Collection && !((Collection<?>) arg).isEmpty()
                            && ((Collection<?>) arg).iterator().next() instanceof Item) {
                        return identifiersText(arg);
                    }
                }
            }
            return null;
        }

        private static String identifiersText(Object identifiers) {
            if (!(identifiers instanceof Collection)) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (Object identifier : (Collection<?>) identifiers) {
                if (identifier instanceof Item) {
                    for (Object value : ((Item<?, ?>) identifier).getValues()) {
                        out.add(text(((PrismValue) value).getRealValue()));
                    }
                }
            }
            return out.isEmpty() ? null : String.join(",", out);
        }

        /** The attributes an {@code add} sets (the shadow's attributes) or a {@code modify} changes. */
        private static List<String> attributes(String method, Object[] args) {
            List<String> out = new ArrayList<>();
            if ("addObject".equals(method)) {
                PrismObject<?> shadow = argument(args, PrismObject.class);
                PrismContainer<?> attributes = shadow != null ? shadow.findContainer(ItemPath.create("attributes"))
                        : null;
                if (attributes != null && attributes.getValue() != null) {
                    for (Item<?, ?> item : attributes.getValue().getItems()) {
                        out.add(item.getElementName().getLocalPart());
                    }
                }
                return out;
            }
            if ("modifyObject".equals(method)) {
                for (Object arg : args) {
                    if (arg instanceof Collection) {
                        for (Object operation : (Collection<?>) arg) {
                            Object delta = getter(operation, "getPropertyDelta");
                            if (delta instanceof ItemDelta) {
                                out.add(EffectsCapture.pathText(((ItemDelta<?, ?>) delta).getPath(), true));
                            } else if (operation != null && operation.getClass().getName().contains("Operation")) {
                                out.add(operation.getClass().getSimpleName());
                            }
                        }
                    }
                }
            }
            return out;
        }

        /**
         * The value of the shadow's naming attribute ({@code icfs:name}, an LDAP {@code dn}...), as its attribute
         * container defines it, else of its secondary identifiers.
         */
        private static String namingValue(PrismObject<?> shadow) {
            PrismContainer<?> attributes = shadow.findContainer(ItemPath.create("attributes"));
            if (attributes == null) {
                return attributeValue(shadow, "name");
            }
            Object naming = getter(attributes, "getNamingAttribute");
            if (naming instanceof Item && !((Item<?, ?>) naming).getValues().isEmpty()) {
                return text(((PrismValue) ((Item<?, ?>) naming).getValues().get(0)).getRealValue());
            }
            String secondary = identifiersText(getter(attributes, "getSecondaryIdentifiers"));
            return secondary != null ? secondary : attributeValue(shadow, "name");
        }

        private static String attributeValue(PrismObject<?> shadow, String localName) {
            PrismContainer<?> attributes = shadow.findContainer(ItemPath.create("attributes"));
            if (attributes == null || attributes.getValue() == null) {
                return null;
            }
            for (Item<?, ?> item : attributes.getValue().getItems()) {
                if (localName.equals(item.getElementName().getLocalPart()) && !item.getValues().isEmpty()) {
                    return text(((PrismValue) item.getValues().get(0)).getRealValue());
                }
            }
            return null;
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static String name(PrismObject<?> object) {
        PolyString name = object.getName();
        return name != null ? name.getOrig() : null;
    }

    private static String text(Object value) {
        if (value instanceof PolyString) {
            return ((PolyString) value).getOrig();
        }
        return value != null ? value.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T argument(Object[] args, Class<T> type) {
        if (args != null) {
            for (Object arg : args) {
                if (type.isInstance(arg)) {
                    return (T) arg;
                }
            }
        }
        return null;
    }

    private static Object getter(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

}
