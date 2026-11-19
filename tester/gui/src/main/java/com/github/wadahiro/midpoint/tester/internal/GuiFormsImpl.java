package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;
import org.apache.wicket.ajax.AbstractAjaxTimerBehavior;
import org.apache.wicket.ajax.AjaxEventBehavior;
import org.apache.wicket.feedback.FeedbackMessage;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.FormComponent;
import org.apache.wicket.markup.html.form.IFormSubmittingComponent;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.util.tester.WicketTester;
import org.apache.wicket.util.tester.WicketTesterHelper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericXmlApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterInvocation;

import com.evolveum.midpoint.gui.api.component.result.OpResult;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.result.OperationResultStatus;
import com.evolveum.midpoint.web.security.MidPointApplication;
import com.evolveum.midpoint.web.util.OnePageParameterEncoder;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;

/**
 * The GUI's object forms run by midPoint's GUI ({@link GuiForms}). The GUI's beans are a child of the tester's Spring
 * context ({@code tester/ctx-gui.xml} of the line: its component scans and the beans its web application declares,
 * not its initial import, web server or security filters), started on the first form of the JVM; its Wicket
 * application is initialized once, by the first WicketTester. Each form is a WicketTester of its own (a new session,
 * in the JVM's locale) as the form's user, logged in as the tester logs a user in:
 * <ul>
 *   <li>the page's URL is decided first by the GUI's authorization evaluator, as midPoint's security filter decides
 *       it (WicketTester renders any page for anyone);</li>
 *   <li>the page is the details page the GUI dispatches the type to, made as the GUI makes it for a new object (the
 *       archetype's and the parent's assignments, the parent's parentOrgRef) or opened by the object's OID;</li>
 *   <li>a value is typed into the item's field and the field's change event fires, as leaving the field does (the
 *       field's validator); an item the page does not show until "show empty fields" is pressed gets it pressed;</li>
 *   <li>Save is the form's default (save) button; a save executed in the background (progress reporting) is waited
 *       for, then its progress timer fires once, as the browser's poll.</li>
 * </ul>
 */
final class GuiFormsImpl implements GuiForms {

    private static final GuiLine LINE = new GuiLineImpl();
    private static final long SAVE_TIMEOUT_SECONDS = 120;

    private static GenericXmlApplicationContext gui;
    private static MidPointApplication application;
    private static WicketTester initializer;

    private final MidPointImpl mp;

    GuiFormsImpl(MidPointImpl mp) {
        this.mp = mp;
    }

    @Override
    public Field type(Spec spec, String item, Object value) {
        return run(spec, "type " + item, tester -> {
            for (Map.Entry<String, Object> other : spec.items.entrySet()) {
                if (!other.getKey().equals(item)) {
                    typeInto(tester, other.getKey(), other.getValue(), true);
                }
            }
            return typeInto(tester, item, value, false);
        });
    }

    @Override
    public Saved save(Spec spec) {
        return run(spec, "save", tester -> {
            for (Map.Entry<String, Object> item : spec.items.entrySet()) {
                typeInto(tester, item.getKey(), item.getValue(), true);
            }
            Page page = tester.getLastRenderedPage();
            Form<?> form = form(page);
            Component save = saveButton(form);
            tester.getSession().getFeedbackMessages().clear();
            tester.executeAjaxEvent(save, "click");
            awaitSaving(tester, page);
            Map<String, List<String>> fields = new LinkedHashMap<>();
            List<String> other = new ArrayList<>();
            boolean errors = false;
            boolean success = false;
            for (FeedbackMessage message : tester.getFeedbackMessages(m -> true)) {
                if (message.getLevel() >= FeedbackMessage.ERROR) {
                    errors = true;
                }
                if (message.getMessage() instanceof OpResult
                        && succeeded(((OpResult) message.getMessage()).getStatus())) {
                    success = true;
                }
                String path = itemOf(message.getReporter());
                String text = text(message.getMessage());
                if (path != null) {
                    fields.computeIfAbsent(path, p -> new ArrayList<>()).add(text);
                } else {
                    other.add(text);
                }
            }
            boolean left = tester.getLastRenderedPage().getClass() != page.getClass();
            return new Saved(!errors && (success || left), fields, other);
        });
    }

    private <T> T run(Spec spec, String what, java.util.function.Function<WicketTester, T> action) {
        String label = (spec.oid == null ? "form of a new " : "form of ") + spec.type.getSimpleName()
                + (spec.oid == null ? "" : " " + spec.oid) + ": " + what;
        MidPointApplication app = application(mp);
        return mp.queryAs(spec.user, null, label, (task, result) -> {
            // the session first: the page the GUI picks reads the application and the user's GUI profile
            WicketTester tester = new WicketTester(app, initializer.getServletContext(), false);
            tester.getSession().setLocale(Locale.getDefault());
            Class<? extends Page> pageClass = spec.oid == null ? LINE.newObjectPageClass(spec.type)
                    : LINE.detailsPage(spec.type);
            if (pageClass == null) {
                throw new IllegalArgumentException("The GUI has no details page of " + spec.type.getSimpleName());
            }
            decide(pageClass, spec);
            if (spec.oid == null) {
                tester.startPage(LINE.newObjectPage(pageClass, newObject(spec)));
            } else {
                tester.startPage(pageClass, new PageParameters().add(OnePageParameterEncoder.PARAMETER, spec.oid));
            }
            if (!pageClass.isInstance(tester.getLastRenderedPage())) {
                throw new AssertionError("The GUI did not open " + pageClass.getSimpleName() + " but "
                        + tester.getLastRenderedPage().getClass().getSimpleName() + messages(tester));
            }
            return action.apply(tester);
        });
    }

    /** The page's URL as midPoint's security filter decides it: the user may open it, or the form fails. */
    private void decide(Class<? extends Page> pageClass, Spec spec) {
        String url = LINE.mountUrl(pageClass);
        if (url == null) {
            return;
        }
        if (spec.oid != null) {
            url = url + "/" + spec.oid;
        }
        // the GUI's AccessDecisionManager (whose interface the lines have in different jars): decide(authentication,
        // the request, its attributes) throws AccessDeniedException
        Object evaluator = gui.getBean("accessDecisionManager");
        try {
            evaluator.getClass().getMethod("decide", org.springframework.security.core.Authentication.class,
                    Object.class, java.util.Collection.class).invoke(evaluator,
                    SecurityContextHolder.getContext().getAuthentication(), request(url), Collections.emptyList());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable denied = e.getCause();
            if (denied != null && denied.getClass().getSimpleName().equals("AccessDeniedException")) {
                throw new AssertionError((spec.user != null ? spec.user : "The administrator") + " may not open "
                        + url + " (" + pageClass.getSimpleName() + "): " + denied.getMessage(), denied);
            }
            throw new IllegalStateException("The GUI's authorization evaluator failed on " + url + ": " + denied, denied);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No decide() on the GUI's authorization evaluator: " + e, e);
        }
    }

    /** A GET of the URL as midPoint's security filter sees it: the whole path is the servlet path. */
    private static FilterInvocation request(String url) {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", url);
        request.setServletPath(url);
        return new FilterInvocation(request, new org.springframework.mock.web.MockHttpServletResponse(),
                new org.springframework.mock.web.MockFilterChain());
    }

    /** The new object as the GUI makes it for a new object with references (initNewObjectWithReference). */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private PrismObject<?> newObject(Spec spec) throws Exception {
        PrismObject object = mp.prismContext().createObject((Class) spec.type);
        ObjectType created = (ObjectType) object.asObjectable();
        if (spec.archetypeOid != null || spec.parentOid != null) {
            if (!(created instanceof AssignmentHolderType)) {
                throw new IllegalArgumentException("A form with an archetype or a parent is of an assignment holder,"
                        + " not " + spec.type.getSimpleName());
            }
            AssignmentHolderType holder = (AssignmentHolderType) created;
            if (spec.archetypeOid != null) {
                holder.getAssignment().add(new AssignmentType().targetRef(
                        new ObjectReferenceType().oid(spec.archetypeOid).type(ArchetypeType.COMPLEX_TYPE)));
            }
            if (spec.parentOid != null) {
                ObjectReferenceType parent = new ObjectReferenceType().oid(spec.parentOid).type(OrgType.COMPLEX_TYPE);
                holder.getAssignment().add(new AssignmentType().targetRef(parent));
                holder.getParentOrgRef().add(parent.clone());
            }
        }
        return object;
    }

    /** Types the value into the item's field and leaves it (the field's change event). */
    private Field typeInto(WicketTester tester, String item, Object value, boolean filling) {
        Page page = tester.getLastRenderedPage();
        Found found = find(page, item);
        if (found.input == null && found.panel == null) {
            showEmpty(tester);
            page = tester.getLastRenderedPage();
            found = find(page, item);
        }
        if (found.input == null) {
            String absent = found.panel == null
                    ? "the page shows no field of " + item + " (not an item the user may see or add, or on another"
                            + " panel of the page)"
                    : "the field of " + item + " is read-only";
            if (filling) {
                throw new AssertionError("Cannot fill in " + item + ": " + absent);
            }
            return new Field(found.wrapper != null && LINE.validated(found.wrapper), List.of(), absent);
        }
        Form<?> form = form(page);
        String relative = found.input.getPageRelativePath().substring(form.getPageRelativePath().length() + 1);
        tester.getSession().getFeedbackMessages().clear();
        // a browser sends the field's value with each of its change behaviors (4.0 has two); WicketTester's
        // executeAjaxEvent with the first only
        List<String> messages = new ArrayList<>();
        for (AjaxEventBehavior change : WicketTesterHelper.findAjaxEventBehaviors(found.input, "change")) {
            tester.newFormTester(form.getPageRelativePath(), false)
                    .setValue(relative, value == null ? "" : String.valueOf(value));
            tester.executeBehavior(change);
            Component panel = find(tester.getLastRenderedPage(), item).panel;
            for (FeedbackMessage message : tester.getFeedbackMessages(m -> m.getLevel() >= FeedbackMessage.ERROR)) {
                String text = text(message.getMessage());
                if (panel != null && message.getReporter() != null && within(message.getReporter(), panel)
                        && !messages.contains(text)) {
                    messages.add(text);
                }
            }
        }
        return new Field(LINE.validated(found.wrapper), messages, null);
    }

    /** "Show empty fields" of the containers shown. */
    private static void showEmpty(WicketTester tester) {
        List<Component> toggles = new ArrayList<>();
        tester.getLastRenderedPage().visitChildren((component, visit) -> {
            if ("showEmptyButton".equals(component.getId()) && component.isVisibleInHierarchy()) {
                toggles.add(component);
            }
        });
        for (Component toggle : toggles) {
            tester.executeAjaxEvent(toggle, "click");
        }
    }

    /** The item's panel, its wrapper and its first visible input. */
    private static final class Found {
        Component panel;
        Object wrapper;
        FormComponent<?> input;
    }

    private static Found find(Page page, String item) {
        Found found = new Found();
        page.visitChildren((component, visit) -> {
            Object wrapper = LINE.wrapper(component);
            if (wrapper == null) {
                return;
            }
            if (!item.equals(names(LINE.path(wrapper)))) {
                return;
            }
            if (found.panel == null || !found.panel.isVisibleInHierarchy()) {
                found.panel = component.isVisibleInHierarchy() ? component : found.panel;
                found.wrapper = wrapper;
            }
            if (component.isVisibleInHierarchy() && found.input == null) {
                ((MarkupContainer) component).visitChildren(FormComponent.class, (input, inner) -> {
                    if (input.isVisibleInHierarchy() && input.isEnabledInHierarchy()
                            && !(input instanceof IFormSubmittingComponent)) {
                        found.input = (FormComponent<?>) input;
                        inner.stop();
                    }
                });
            }
            visit.dontGoDeeper();
        });
        return found;
    }

    /** The path's names, without container ids: {@code extension/site}. */
    private static String names(ItemPath path) {
        StringBuilder out = new StringBuilder();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isName(segment)) {
                if (out.length() > 0) {
                    out.append('/');
                }
                out.append(ItemPath.toName(segment).getLocalPart());
            }
        }
        return out.toString();
    }

    /** The item whose panel the component is in, or null. */
    private static String itemOf(Component component) {
        String item = null;
        for (Component c = component; c != null; c = c.getParent()) {
            Object wrapper = LINE.wrapper(c);
            if (wrapper != null) {
                item = names(LINE.path(wrapper));
                break;
            }
        }
        return item == null || item.isEmpty() ? null : item;
    }

    private static boolean within(Component component, Component panel) {
        for (Component c = component; c != null; c = c.getParent()) {
            if (c == panel) {
                return true;
            }
        }
        return false;
    }

    private static Form<?> form(Page page) {
        List<Form<?>> forms = new ArrayList<>();
        page.visitChildren(Form.class, (form, visit) -> {
            if (form.isVisibleInHierarchy() && ((Form<?>) form).getDefaultButton() != null) {
                forms.add((Form<?>) form);
                visit.stop();
            }
        });
        if (forms.isEmpty()) {
            page.visitChildren(Form.class, (form, visit) -> {
                forms.add((Form<?>) form);
                visit.stop();
            });
        }
        if (forms.isEmpty()) {
            throw new AssertionError(page.getClass().getSimpleName() + " has no form");
        }
        return forms.get(0);
    }

    /** The form's default button (Save), or its button "save" (pages without a default button). */
    private static Component saveButton(Form<?> form) {
        IFormSubmittingComponent button = form.getDefaultButton();
        if (button instanceof Component && ((Component) button).isVisibleInHierarchy()) {
            return (Component) button;
        }
        List<Component> found = new ArrayList<>();
        form.visitChildren((component, visit) -> {
            if ("save".equals(component.getId()) && component instanceof IFormSubmittingComponent
                    && component.isVisibleInHierarchy()) {
                found.add(component);
                visit.stop();
            }
        });
        if (found.isEmpty()) {
            throw new AssertionError("The page shows no Save button (the object is read-only to the user, or the page"
                    + " saves with a preview)");
        }
        return found.get(0);
    }

    /** A save executed in the background: waited for, then the progress timer fires as the browser's poll does. */
    private static void awaitSaving(WicketTester tester, Page page) {
        for (int round = 0; round < 50 && tester.getLastRenderedPage() == page; round++) {
            Future<?> saving = LINE.saving(page);
            if (saving != null) {
                try {
                    saving.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // the execution's failure is in its result, which the page shows
                }
            }
            List<AbstractAjaxTimerBehavior> timers = new ArrayList<>();
            page.visitChildren((component, visit) -> component.getBehaviors(AbstractAjaxTimerBehavior.class)
                    .stream().filter(timer -> !timer.isStopped()).forEach(timers::add));
            if (timers.isEmpty()) {
                return;
            }
            if (saving == null) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            tester.executeBehavior(timers.get(0));
        }
    }

    private static String text(Object message) {
        if (message instanceof OpResult) {
            OpResult result = (OpResult) message;
            StringBuilder out = new StringBuilder(String.valueOf(result.getStatus()));
            if (result.getMessage() != null) {
                out.append(": ").append(result.getMessage());
            }
            for (OpResult sub : result.getSubresults()) {
                if (!succeeded(sub.getStatus()) && sub.getMessage() != null
                        && !sub.getMessage().equals(result.getMessage())) {
                    out.append("; ").append(sub.getMessage());
                }
            }
            return out.toString();
        }
        return String.valueOf(message);
    }

    private static boolean succeeded(OperationResultStatus status) {
        return status == OperationResultStatus.SUCCESS || status == OperationResultStatus.WARNING
                || status == OperationResultStatus.HANDLED_ERROR;
    }

    private static String messages(WicketTester tester) {
        List<String> out = new ArrayList<>();
        for (FeedbackMessage message : tester.getFeedbackMessages(m -> true)) {
            out.add(text(message.getMessage()));
        }
        return out.isEmpty() ? "" : ": " + out;
    }

    /** The GUI's application, its Spring beans a child of the tester's: made and initialized on the first form. */
    private static synchronized MidPointApplication application(MidPointImpl mp) {
        if (application == null) {
            long t = Timing.start();
            if (System.getProperty("wicket.ioc.useByteBuddy") == null) {
                // Wicket's proxies of the injected beans: cglib does not run on JDK 15 and later
                System.setProperty("wicket.ioc.useByteBuddy", "true");
            }
            GenericXmlApplicationContext context = new GenericXmlApplicationContext();
            context.setParent(mp.bean(ApplicationContext.class));
            context.load("classpath:tester/ctx-gui.xml");
            context.refresh();
            gui = context;
            MidPointApplication app = context.getBean(MidPointApplication.class);
            initializer = new WicketTester(app, true);
            application = app;
            Timing.end("gui.application", t);
        }
        return application;
    }
}
