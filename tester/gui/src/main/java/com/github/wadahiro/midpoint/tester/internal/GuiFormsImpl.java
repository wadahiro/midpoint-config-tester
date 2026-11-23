package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.xml.namespace.QName;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;
import org.apache.wicket.ajax.AbstractAjaxTimerBehavior;
import org.apache.wicket.ajax.AjaxEventBehavior;
import org.apache.wicket.feedback.FeedbackMessage;
import org.apache.wicket.markup.html.form.AbstractSingleSelectChoice;
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
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.PrismReferenceDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.schema.result.OperationResultStatus;
import com.evolveum.midpoint.util.DisplayableValue;
import com.evolveum.midpoint.web.application.AsyncWebProcess;
import com.evolveum.midpoint.web.application.AsyncWebProcessModel;
import com.evolveum.midpoint.web.security.MidPointApplication;
import com.evolveum.midpoint.web.util.OnePageParameterEncoder;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType;

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
 *       archetype's and the membership's assignments, an org's parentOrgRef) or opened by the object's OID;</li>
 *   <li>a value is typed into the item's field and the field's change event fires, as leaving the field does (the
 *       field's validator); a drop-down's choice is picked by its id, any other value typed as the field's converter
 *       shows it ({@link FieldValues}: a number, a date, a duration), a date of 4.0's and 4.4's DateInput into its
 *       date, hours and minutes; a value the field takes without a message is then in its model, or the form fails; a
 *       reference and a protected string are not typed (the form fails, naming what to use); an item the page does
 *       not show until "show empty fields" is pressed gets it pressed; the values of a multi-valued item into its
 *       value rows, which its add and remove buttons make;</li>
 *   <li>a Save that opens the GUI's error page did not save (the page's exception is a message);</li>
 *   <li>Save is the form's default (save) button; a save executed in the background (progress reporting) is waited
 *       for until its execution ends (at most {@code SAVE_TIMEOUT_SECONDS}: a save still running then is reported
 *       as not completed, not as not saved), then its progress timer fires, as the browser's poll;</li>
 *   <li>the tabs are read from the rendered page ({@link GuiLine#tabs}): each as the GUI decided its visibility.</li>
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
                    typeInto(tester, spec.type, other.getKey(), other.getValue(), true);
                }
            }
            return typeInto(tester, spec.type, item, value, false);
        });
    }

    @Override
    public Saved save(Spec spec) {
        return run(spec, "save", tester -> {
            // the page's own default button: a panel's search form made the default later (4.4 and later: the
            // Assignments tab's) is not the Save
            IFormSubmittingComponent opened = form(tester.getLastRenderedPage()).getDefaultButton();
            for (Map.Entry<String, Object> item : spec.items.entrySet()) {
                typeInto(tester, spec.type, item.getKey(), item.getValue(), true);
            }
            for (GuiForms.Removal removal : spec.removals) {
                unassign(tester, removal);
            }
            Page page = tester.getLastRenderedPage();
            Form<?> form = form(page);
            Component save = saveButton(form, opened);
            tester.getSession().getFeedbackMessages().clear();
            tester.executeAjaxEvent(save, "click");
            boolean completed = awaitSaving(tester, page);
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
            String failed = errorPage(tester.getLastRenderedPage());
            if (failed != null) {
                // the GUI failed saving (its error page): not a page left after saving
                other.add("Saving opened the GUI's error page " + failed);
                return new Saved(false, true, true, fields, other);
            }
            boolean left = tester.getLastRenderedPage().getClass() != page.getClass();
            return new Saved(completed && !errors && (success || left), completed, errors || !fields.isEmpty(),
                    fields, other);
        });
    }

    @Override
    public List<TabInfo> tabs(Spec spec) {
        // the page as opened: the items typed into change no tab
        return run(spec, "tabs", tester -> LINE.tabs(tester.getLastRenderedPage()));
    }

    @Override
    public Rows rows(Spec spec, boolean inducements) {
        return run(spec, inducements ? "inducements" : "assignments", tester -> {
            Component panel = LINE.openAssignments(tester, inducements);
            if (panel == null) {
                return new Rows(false, LINE.tabs(tester.getLastRenderedPage()), List.of(), false, false);
            }
            List<RowInfo> rows = new ArrayList<>();
            for (TableRow row : rowsOf(tester, panel)) {
                rows.add(row.info);
            }
            Component add = LINE.newAssignmentButton(panel);
            return new Rows(true, List.of(), rows, add != null && add.isVisibleInHierarchy(),
                    add != null && add.isVisibleInHierarchy() && add.isEnabledInHierarchy());
        });
    }

    /** A row of the Assignments or Inducements tab: what it shows, and its Unassign button (null: none). */
    private static final class TableRow {
        RowInfo info;
        Component unassign;
    }

    /**
     * The rows of the panel as rendered: the table's row items whose model is an assignment's value wrapper (a value the
     * user removed is no row: the provider leaves it out), all of them on one page (the table's page size raised and
     * the page rendered again, as paging through it shows them), each with the button of its menu column that is the
     * Unassign ({@link GuiLine#isUnassignButton}).
     */
    private static List<TableRow> rowsOf(WicketTester tester, Component panel) {
        List<org.apache.wicket.markup.repeater.AbstractPageableView<?>> tables = new ArrayList<>();
        ((MarkupContainer) panel).visitChildren(org.apache.wicket.markup.repeater.AbstractPageableView.class,
                (table, visit) -> {
                    org.apache.wicket.markup.repeater.AbstractPageableView<?> view =
                            (org.apache.wicket.markup.repeater.AbstractPageableView<?>) table;
                    if (view.getItemCount() > view.getItemsPerPage()) {
                        tables.add(view);
                    }
                });
        if (!tables.isEmpty()) {
            tables.forEach(view -> view.setItemsPerPage(Math.max(view.getItemCount(), 1)));
            tester.startPage(tester.getLastRenderedPage());
        }
        List<TableRow> out = new ArrayList<>();
        ((MarkupContainer) panel).visitChildren(org.apache.wicket.markup.repeater.Item.class, (component, visit) -> {
            Object wrapper = component.getDefaultModelObject();
            Object value = AssignmentTabs.call(wrapper, "getRealValue");
            if (!(value instanceof AssignmentType)) {
                return;
            }
            visit.dontGoDeeper();
            if (!component.isVisibleInHierarchy() || "DELETED".equals(String.valueOf(AssignmentTabs.call(wrapper,
                    "getStatus")))) {
                return;
            }
            AssignmentType assignment = (AssignmentType) value;
            ObjectReferenceType target = assignment.getTargetRef();
            TableRow row = new TableRow();
            List<Component> unassign = AssignmentTabs.visible((MarkupContainer) component, LINE::isUnassignButton);
            row.unassign = unassign.isEmpty() ? null : unassign.get(0);
            row.info = new RowInfo(target == null ? null : target.getOid(), target == null ? null : target.getType(),
                    target == null ? null : target.getRelation(), assignment, row.unassign != null,
                    row.unassign != null && row.unassign.isEnabledInHierarchy());
            out.add(row);
        });
        return out;
    }

    /**
     * The removal's row of the Assignments or Inducements tab, its Unassign pressed (the GUI's deleteItemPerformed:
     * the value marked deleted, saved with the page); fails when the user does not see the tab, the row or its
     * Unassign.
     */
    private void unassign(WicketTester tester, GuiForms.Removal removal) {
        String tab = removal.inducement ? "Inducements" : "Assignments";
        String relation = relationText(removal.relation);
        Component panel = LINE.openAssignments(tester, removal.inducement);
        if (panel == null) {
            throw new AssertionError("Cannot unassign " + removal.target + " (" + relation + "): the page shows no "
                    + tab + " tab; " + tabsText(LINE.tabs(tester.getLastRenderedPage())));
        }
        List<TableRow> rows = rowsOf(tester, panel);
        TableRow found = null;
        for (TableRow row : rows) {
            if (removal.targetOid.equals(row.info.targetOid) && sameRelation(removal.relation, row.info.relation)) {
                found = row;
                break;
            }
        }
        if (found == null) {
            List<String> shown = new ArrayList<>();
            rows.forEach(row -> shown.add(rowText(row.info)));
            throw new AssertionError("Cannot unassign " + removal.target + " (" + relation + "): the " + tab
                    + " tab shows no row of it; its rows: " + (shown.isEmpty() ? "none" : String.join(", ", shown)));
        }
        if (found.unassign == null || !found.unassign.isEnabledInHierarchy()) {
            throw new AssertionError("Cannot unassign " + removal.target + " (" + relation + "): its row on the " + tab
                    + " tab has no" + (found.unassign == null ? "" : " enabled") + " Unassign ("
                    + (found.unassign == null
                            ? "#adminUnassign is not allowed on the object: the security enforcer, request phase"
                            : "the changed rows reach roleManagement/assignmentApprovalRequestLimit") + ")");
        }
        tester.getSession().getFeedbackMessages().clear();
        AssignmentTabs.click(tester, found.unassign);
        String failed = errorPage(tester.getLastRenderedPage());
        if (failed != null) {
            throw new AssertionError("Pressing Unassign of " + removal.target + " opened the GUI's error page " + failed);
        }
    }

    /** {@code default}, or the relation's local name. */
    static String relationText(QName relation) {
        return relation == null ? "default" : relation.getLocalPart();
    }

    /** The same relation, a relation stored as none being default (the GUI shows both alike). */
    static boolean sameRelation(QName a, QName b) {
        QName one = a == null ? com.evolveum.midpoint.schema.constants.SchemaConstants.ORG_DEFAULT : a;
        QName two = b == null ? com.evolveum.midpoint.schema.constants.SchemaConstants.ORG_DEFAULT : b;
        return com.evolveum.midpoint.util.QNameUtil.match(one, two);
    }

    private static String rowText(RowInfo row) {
        if (row.targetOid == null) {
            return "(no target)";
        }
        return (row.targetType == null ? "" : row.targetType.getLocalPart() + " ") + row.targetOid + " ("
                + relationText(row.relation) + ")";
    }

    /** {@code its tabs: Basic, Members; hidden: Governance (visibility hidden)}. */
    private static String tabsText(List<TabInfo> tabs) {
        List<String> shown = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (TabInfo tab : tabs) {
            if (tab.shown) {
                shown.add(tab.label);
            } else {
                hidden.add(tab.label + " (" + tab.hiddenBecause + ")");
            }
        }
        return "its tabs: " + (shown.isEmpty() ? "none" : String.join(", ", shown))
                + (hidden.isEmpty() ? "" : "; hidden: " + String.join(", ", hidden));
    }

    /**
     * The side menu as the GUI builds it for the user: the GUI's error page (which any user opens) rendered as them in
     * a WicketTester of its own, its menu read as the line has it ({@link GuiLine#sideMenu}). The page itself hides the
     * menu (an error page shows none), but builds it as any page does, for the user logged in.
     */
    @Override
    public List<MenuInfo> menu(String user) {
        MidPointApplication app = application(mp);
        return mp.queryAs(user, null, "side menu of " + (user == null ? "the administrator" : user),
                (task, result) -> {
                    WicketTester tester = new WicketTester(app, initializer.getServletContext(), false);
                    tester.getSession().setLocale(Locale.getDefault());
                    tester.startPage(com.evolveum.midpoint.web.page.error.PageError.class);
                    return LINE.sideMenu(tester.getLastRenderedPage());
                });
    }

    private <T> T run(Spec spec, String what, java.util.function.Function<WicketTester, T> action) {
        String label = (spec.selfProfile ? "self-profile form of " : spec.oid == null ? "form of a new " : "form of ")
                + spec.type.getSimpleName() + (spec.oid == null ? "" : " " + spec.oid) + ": " + what;
        MidPointApplication app = application(mp);
        return mp.queryAs(spec.user, null, label, (task, result) -> {
            // the session first: the page the GUI picks reads the application and the user's GUI profile
            WicketTester tester = new WicketTester(app, initializer.getServletContext(), false);
            tester.getSession().setLocale(Locale.getDefault());
            Class<? extends Page> pageClass = spec.selfProfile ? LINE.selfProfilePage()
                    : spec.oid == null ? LINE.newObjectPageClass(spec.type) : LINE.detailsPage(spec.type);
            if (pageClass == null) {
                throw new IllegalArgumentException("The GUI has no details page of " + spec.type.getSimpleName());
            }
            decide(pageClass, LINE.mountUrl(pageClass) == null ? null : spec.oid != null && !spec.selfProfile
                    ? LINE.mountUrl(pageClass) + "/" + spec.oid : LINE.mountUrl(pageClass), spec.user);
            if (spec.selfProfile) {
                // the profile page takes no parameter: it loads the logged-in focus
                tester.startPage(pageClass);
            } else if (spec.oid == null) {
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

    /**
     * The user's own credentials page ({@link GuiLine#selfCredentialsPage}) in a WicketTester of its own as the user,
     * its URL decided first as the security filter decides it, then driven as the user drives it
     * ({@link SelfCredentials}).
     */
    @Override
    public Credentials credentials(CredentialsSpec spec, boolean save) {
        MidPointApplication app = application(mp);
        Class<? extends Page> pageClass = LINE.selfCredentialsPage();
        com.evolveum.midpoint.common.LocalizationService localization =
                mp.bean(com.evolveum.midpoint.common.LocalizationService.class);
        return mp.queryAs(spec.user, null, "credentials page of " + spec.user + (save ? ": save" : ""),
                (task, result) -> {
                    WicketTester tester = new WicketTester(app, initializer.getServletContext(), false);
                    tester.getSession().setLocale(Locale.getDefault());
                    decide(pageClass, LINE.mountUrl(pageClass), spec.user);
                    tester.startPage(pageClass);
                    if (!pageClass.isInstance(tester.getLastRenderedPage())) {
                        throw new AssertionError("The GUI did not open " + pageClass.getSimpleName() + " but "
                                + tester.getLastRenderedPage().getClass().getSimpleName() + messages(tester));
                    }
                    return new SelfCredentials(tester, message -> translate(localization, message))
                            .drive(spec, save);
                });
    }

    /** A localizable message or a poly string (its translation key) in the JVM's locale. */
    private static String translate(com.evolveum.midpoint.common.LocalizationService localization, Object message) {
        try {
            if (message instanceof com.evolveum.midpoint.util.LocalizableMessage) {
                return localization.translate((com.evolveum.midpoint.util.LocalizableMessage) message,
                        Locale.getDefault());
            }
            if (message instanceof com.evolveum.prism.xml.ns._public.types_3.PolyStringType) {
                return localization.translate(
                        ((com.evolveum.prism.xml.ns._public.types_3.PolyStringType) message).toPolyString(),
                        Locale.getDefault(), true);
            }
        } catch (RuntimeException e) {
            // its text as it is
        }
        return String.valueOf(message);
    }

    static String errorPageOf(Page page) {
        return errorPage(page);
    }

    static String textOf(Object message) {
        return text(message);
    }

    /** The page's URL as midPoint's security filter decides it: the user may open it, or the form fails. */
    private void decide(Class<? extends Page> pageClass, String url, String user) {
        if (url == null) {
            return;
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
                throw new AssertionError((user != null ? user : "The administrator") + " may not open "
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

    /**
     * The new object as the GUI makes it for a new object with references (initNewObjectWithReference): the
     * archetype's assignment, the membership's (its relation set as the members panel sets it, none for "create
     * child"), and for an org the parentOrgRef the line's GUI adds.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private PrismObject<?> newObject(Spec spec) throws Exception {
        PrismObject object = mp.prismContext().createObject((Class) spec.type);
        ObjectType created = (ObjectType) object.asObjectable();
        if (spec.archetypeOid != null || spec.memberOid != null) {
            if (!(created instanceof AssignmentHolderType)) {
                throw new IllegalArgumentException("A form with an archetype, a parent or a membership is of an"
                        + " assignment holder, not " + spec.type.getSimpleName());
            }
            AssignmentHolderType holder = (AssignmentHolderType) created;
            if (spec.archetypeOid != null) {
                holder.getAssignment().add(new AssignmentType().targetRef(
                        new ObjectReferenceType().oid(spec.archetypeOid).type(ArchetypeType.COMPLEX_TYPE)));
            }
            if (spec.memberOid != null) {
                ObjectReferenceType target = new ObjectReferenceType().oid(spec.memberOid)
                        .type(ObjectTypes.getObjectType(spec.memberType).getTypeQName()).relation(spec.relation);
                holder.getAssignment().add(new AssignmentType().targetRef(target));
                // without a relation always, as "create child" does; with one as the line's members panel decides
                if (OrgType.class.equals(spec.memberType)
                        && (spec.relation == null || LINE.storedIntoParentOrgRef(spec.relation))) {
                    holder.getParentOrgRef().add(target.clone());
                }
            }
        }
        return object;
    }

    /**
     * Types the value into the item's field and leaves it (the field's change event); the values of a multi-valued
     * item ({@link GuiForms.ValueRows}) into its value rows.
     */
    private Field typeInto(WicketTester tester, Class<? extends ObjectType> type, String item, Object value,
            boolean filling) {
        refuse(type, item);
        if (value instanceof GuiForms.ValueRows) {
            return typeValues(tester, item, (GuiForms.ValueRows) value);
        }
        Found found = shown(tester, item);
        if (found.input == null) {
            String absent = found.panel == null
                    ? "the page shows no field of " + item + " (not an item the user may see or add, or on another"
                            + " panel of the page)"
                    : readOnly(item, found.wrapper);
            if (filling) {
                throw new AssertionError("Cannot fill in " + item + ": " + absent);
            }
            return new Field(found.wrapper != null && LINE.validated(found.wrapper), List.of(), absent);
        }
        return new Field(LINE.validated(found.wrapper), enter(tester, item, found.input, value), null);
    }

    /**
     * The items set does not type, failing with what to use instead: a reference (its field is the name of the object
     * chosen, which the GUI's chooser popup sets: ValueChoosePanel) and a protected string (a password: the GUI types
     * it in a password panel of its own, twice).
     */
    private void refuse(Class<? extends ObjectType> type, String item) {
        ItemDefinition<?> definition = definition(type, item);
        if (definition instanceof PrismReferenceDefinition) {
            throw new AssertionError("Cannot fill in " + item + ": it is a reference, whose field the GUI sets by the"
                    + " object chosen in its chooser popup (ValueChoosePanel), which set does not drive; change it"
                    + " through the model instead (t.actor(user).modify(focus, m -> m.set(\"" + item + "\", ...)))");
        }
        if (definition != null && ProtectedStringType.COMPLEX_TYPE.equals(definition.getTypeName())) {
            throw new AssertionError("Cannot fill in " + item + ": it is a protected string (a password), which the"
                    + " GUI types in a password panel of its own (the value twice), not in a field of the form, and"
                    + " set does not type; change a password through the model instead (t.actor(user).modify(user,"
                    + " m -> m.password(\"...\"))) or the user's own credentials page (t.actor(user).selfCredentials())");
        }
    }

    /**
     * Why an item's panel has no input to type into: read-only to the user, or (an item the user may change) a type the
     * line's GUI has no field of (no panel factory: 4.0 and 4.4 show a double as nothing).
     */
    private static String readOnly(String item, Object wrapper) {
        Object readOnly = call(wrapper, "isReadOnly");
        if (Boolean.FALSE.equals(readOnly)) {
            return "the GUI shows no input of " + item + ", which the user may change: the line's GUI has no field of"
                    + " its type (" + call(wrapper, "getTypeName") + ")";
        }
        return "the field of " + item + " is read-only";
    }

    private static Object call(Object target, String method) {
        try {
            return target == null ? null : target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The definition of the item (its path of local names) in the type, or null. */
    private ItemDefinition<?> definition(Class<? extends ObjectType> type, String item) {
        PrismObjectDefinition<?> object = mp.prismContext().getSchemaRegistry()
                .findObjectDefinitionByCompileTimeClass(type);
        if (object == null) {
            return null;
        }
        List<Object> names = new ArrayList<>();
        for (String name : item.split("/")) {
            names.add(new QName(name));
        }
        try {
            return object.findItemDefinition(ItemPath.create(names));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The GUI's error page as a failure names it (PageError, with the exception it was made for), or null when the page
     * is not one.
     */
    private static String errorPage(Page page) {
        Class<?> error = null;
        for (Class<?> c = page.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals("PageError")) {
                error = c;
                break;
            }
        }
        if (error == null) {
            return null;
        }
        String exception = "";
        try {
            java.lang.reflect.Field exClass = error.getDeclaredField("exClass");
            java.lang.reflect.Field exMessage = error.getDeclaredField("exMessage");
            exClass.setAccessible(true);
            exMessage.setAccessible(true);
            if (exClass.get(page) != null) {
                exception = ": " + exClass.get(page) + ": " + exMessage.get(page);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // a page without them: its log has the exception
        }
        return page.getClass().getSimpleName() + exception + " (the log has its stack trace)";
    }

    /** The item's panel as the page shows it, "show empty fields" pressed when the page does not show it. */
    private static Found shown(WicketTester tester, String item) {
        Page page = tester.getLastRenderedPage();
        Found found = find(page, item);
        if (found.input == null && found.panel == null) {
            showEmpty(tester);
            if (tester.getLastRenderedPage().getClass() != page.getClass()) {
                // the GUI failed to render the fields it shows (its error page): not a page without the item
                String failed = errorPage(tester.getLastRenderedPage());
                throw new AssertionError("Cannot fill in " + item + ": pressing \"show empty fields\" to show its"
                        + " field opened " + (failed != null ? failed
                                : tester.getLastRenderedPage().getClass().getSimpleName()) + " instead of "
                        + page.getClass().getSimpleName() + " (the GUI failed rendering the empty fields)");
            }
            found = find(tester.getLastRenderedPage(), item);
        }
        return found;
    }

    /**
     * Types the value into the input and leaves it: the messages the item's panel shows then. The value is typed as the
     * field shows it ({@link #typed}); a value the field takes without a message is in the field's model then, or the
     * form fails ({@link #landed}).
     */
    private static List<String> enter(WicketTester tester, String item, FormComponent<?> input, Object value) {
        Form<?> form = form(tester.getLastRenderedPage());
        String formPath = form.getPageRelativePath();
        String inputPath = input.getPageRelativePath();
        tester.getSession().getFeedbackMessages().clear();
        Typed typed = typed(item, input, value);
        List<String> messages = new ArrayList<>();
        for (Map.Entry<String, String> part : typed.parts.entrySet()) {
            Component field = tester.getLastRenderedPage().get(part.getKey());
            // a browser sends the field's value with each of its change behaviors (4.0 has two); WicketTester's
            // executeAjaxEvent with the first only
            List<AjaxEventBehavior> changes = WicketTesterHelper.findAjaxEventBehaviors(field, "change");
            if (changes.isEmpty()) {
                throw new AssertionError("Cannot fill in " + item + ": its field (" + kind(field)
                        + ") has no change event to type " + part.getValue() + " into");
            }
            String relative = part.getKey().substring(formPath.length() + 1);
            for (AjaxEventBehavior change : changes) {
                tester.newFormTester(formPath, false).setValue(relative, part.getValue());
                tester.executeBehavior(change);
                String failed = errorPage(tester.getLastRenderedPage());
                if (failed != null) {
                    // the GUI failed on the value (4.0, 4.4: a duration's text it does not read)
                    throw new AssertionError("Typing " + part.getValue() + " into " + item + " opened the GUI's error"
                            + " page " + failed);
                }
                Component panel = find(tester.getLastRenderedPage(), item).panel;
                for (FeedbackMessage message : tester.getFeedbackMessages(m -> m.getLevel() >= FeedbackMessage.ERROR)) {
                    String text = text(message.getMessage());
                    if (panel != null && message.getReporter() != null && within(message.getReporter(), panel)
                            && !messages.contains(text)) {
                        messages.add(text);
                    }
                }
            }
        }
        if (messages.isEmpty()) {
            Component now = tester.getLastRenderedPage().get(inputPath);
            landed(item, now instanceof FormComponent ? (FormComponent<?>) now : input, value, typed);
        }
        return messages;
    }

    /**
     * A value as the field is typed into: the text of each of its inputs (by page-relative path, in the order typed),
     * and what the field's model is to hold then (null: not known, the GUI's converter decides; a choice: {@link
     * #choice}).
     */
    private static final class Typed {
        final Map<String, String> parts = new LinkedHashMap<>();
        Choice choice;
        Object expected;
        String text;
    }

    /**
     * The texts the value is typed as, as a user types it: a drop-down's choice by its id ({@link #choice}); a date
     * field of several inputs (4.0, 4.4: DateInput, a DateTimeField) its date, hours and minutes; any other field the
     * value as its converter shows it ({@link FieldValues#coerce}: a number, a date, a duration from their Java
     * values or texts), so a date is typed in the field's pattern and the JVM's time zone; a text the converter does
     * not read is typed as it is (the field's message says what is wrong with it).
     */
    private static Typed typed(String item, FormComponent<?> input, Object value) {
        Typed typed = new Typed();
        boolean none = value == null || String.valueOf(value).isEmpty();
        if (input instanceof AbstractSingleSelectChoice) {
            typed.choice = choice(item, (AbstractSingleSelectChoice<?>) input, value);
            typed.text = typed.choice.id;
            typed.parts.put(input.getPageRelativePath(), typed.choice.id);
            return typed;
        }
        if (FieldValues.isDateTimeField(input)) {
            java.util.Date date = none ? null : FieldValues.date(item, value, true);
            typed.expected = date;
            typed.parts.putAll(FieldValues.dateTimeParts(input, date));
            return typed;
        }
        if (none) {
            typed.text = "";
            typed.parts.put(input.getPageRelativePath(), "");
            return typed;
        }
        Object coerced = FieldValues.coerce(item, input.getType(), value);
        typed.expected = coerced;
        typed.text = coerced == null ? String.valueOf(value) : FieldValues.render(input, coerced);
        typed.parts.put(input.getPageRelativePath(), typed.text);
        return typed;
    }

    /**
     * The value is in the field's model after the field took it without a message: a drop-down holds the choice
     * typed (none for null), any other field the value typed (a date to the millisecond, a number by its value; a
     * text the field's converter read: some value), an emptied field none. Not: a value the GUI dropped or changed
     * silently.
     */
    private static void landed(String item, FormComponent<?> input, Object value, Typed typed) {
        Object held = input.getDefaultModelObject();
        Choice choice = typed.choice;
        if (choice != null) {
            // an enumeration's choice may be a displayable value of the value the model holds
            boolean same = choice.option == null ? held == null
                    : choice.option.equals(held) || choiceNames(choice.option).equals(choiceNames(held));
            if (!same) {
                throw new AssertionError("Typed " + value + " into " + item + ", but its field holds "
                        + (held == null ? "no value" : String.valueOf(held)) + " (" + kind(input) + ")");
            }
            return;
        }
        boolean none = value == null || String.valueOf(value).isEmpty();
        if (none) {
            if (!FieldValues.isEmpty(held)) {
                throw new AssertionError("Emptied the field of " + item + ", but it holds " + held + " ("
                        + kind(input) + ")");
            }
            return;
        }
        if (FieldValues.isEmpty(held)) {
            throw new AssertionError("Typed " + value + " into " + item + ", but its field holds no value ("
                    + kind(input) + ")");
        }
        if (typed.expected == null || FieldValues.same(typed.expected, held)) {
            return;
        }
        // a field whose converter shows the value as typed (a lookup table's label typed for its key)
        if (!(typed.expected instanceof java.util.Date) && typed.text != null
                && typed.text.equals(FieldValues.render(input, held))) {
            return;
        }
        throw new AssertionError("Typed " + value + " into " + item + ", but its field holds "
                + FieldValues.describe(held) + " (" + kind(input) + ")" + FieldValues.why(typed.expected, held));
    }

    /** The input's class as named in a message: an anonymous one by the class it extends. */
    private static String kind(Object input) {
        Class<?> type = input.getClass();
        while (type.isAnonymousClass()) {
            type = type.getSuperclass();
        }
        return type.getSimpleName();
    }

    /** A drop-down's choice: what the browser sends (its id), and the choice itself (null: the null choice). */
    private static final class Choice {
        final String id;
        final Object option;

        Choice(String id, Object option) {
            this.id = id;
            this.option = option;
        }
    }

    /**
     * The drop-down's choice of the value: the choice that is the value, or whose value, text or display text is it
     * (a boolean as {@code true} or {@code "true"}, an enumeration by its XML value or its name, case-insensitive);
     * none (null or empty): the null choice. A value no choice has fails, naming the choices, rather than leaving the
     * field empty.
     */
    private static Choice choice(String item, AbstractSingleSelectChoice<?> input, Object value) {
        @SuppressWarnings("unchecked")
        AbstractSingleSelectChoice<Object> select = (AbstractSingleSelectChoice<Object>) input;
        List<?> choices = select.getChoices();
        boolean none = value == null || String.valueOf(value).isEmpty();
        String wanted = String.valueOf(value);
        List<String> offered = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            Object option = choices.get(i);
            if (option == null) {
                // a null among the choices (a three-state boolean's "undefined"): its id is its index
                if (none) {
                    return new Choice(select.getChoiceRenderer().getIdValue(null, i), null);
                }
                continue;
            }
            Object display = select.getChoiceRenderer().getDisplayValue(option);
            String text = display == null ? "" : String.valueOf(display);
            List<String> names = choiceNames(option);
            if (!none && (option.equals(value) || text.equals(wanted)
                    || names.stream().anyMatch(name -> name.equalsIgnoreCase(wanted)))) {
                return new Choice(select.getChoiceRenderer().getIdValue(option, i), option);
            }
            String name = names.isEmpty() ? String.valueOf(option) : names.get(0);
            offered.add(name + (text.isEmpty() || text.equals(name) ? "" : " (" + text + ")"));
        }
        if (none) {
            // the drop-down's own null option (nullValid, or "Choose one")
            return new Choice("", null);
        }
        throw new AssertionError("Cannot fill in " + item + ": its field has no choice " + value + "; its choices: "
                + offered);
    }

    /**
     * The texts a choice is typed by: a displayable value's value, an enumeration's XML value ({@code value()}) and
     * its name, else its text.
     */
    private static List<String> choiceNames(Object option) {
        List<String> out = new ArrayList<>();
        if (option == null) {
            return out;
        }
        Object real = option instanceof DisplayableValue ? ((DisplayableValue<?>) option).getValue() : option;
        if (real instanceof Enum) {
            try {
                Object xml = real.getClass().getMethod("value").invoke(real);
                if (xml != null) {
                    out.add(String.valueOf(xml));
                }
            } catch (ReflectiveOperationException e) {
                // an enumeration without an XML value: its name
            }
            out.add(((Enum<?>) real).name());
        } else if (real instanceof QName) {
            // an object type's QName also by its local name (UserType)
            out.add(String.valueOf(real));
            out.add(((QName) real).getLocalPart());
        } else if (real != null) {
            out.add(String.valueOf(real));
        }
        return out;
    }

    /** What an input shows as its value: a drop-down its choice (as {@link #choiceNames} types it), else its text. */
    private static String shownValue(FormComponent<?> input) {
        if (input instanceof AbstractSingleSelectChoice) {
            List<String> names = choiceNames(input.getDefaultModelObject());
            return names.isEmpty() ? "" : names.get(0);
        }
        return input.getValue() == null ? "" : input.getValue();
    }

    /**
     * The values into a multi-valued item's value rows, as the user changes them: the rows of the values to remove
     * (exact values: of those not wanted) removed by their remove button, then each value to add typed into an empty
     * row, else into the row the item's add button adds; a value the item has is kept. The page re-renders the item's
     * panel on every button, so the rows are found anew each time. The item's buttons: {@link GuiLine#addValueButton},
     * {@link GuiLine#removeValueButton}.
     */
    private Field typeValues(WicketTester tester, String item, GuiForms.ValueRows values) {
        Found found = shown(tester, item);
        if (found.panel == null) {
            throw new AssertionError("Cannot fill in " + item + ": the page shows no field of " + item + " (not an"
                    + " item the user may see or add, or on another panel of the page)");
        }
        boolean multi = LINE.multiValue(found.wrapper);
        FormComponent<?> shownInput = found.input;
        // the values by the text their rows show
        Map<String, Object> typedAs = texts(item, shownInput, values.exactly != null ? values.exactly : values.added);
        List<String> wanted = new ArrayList<>(typedAs.keySet());
        if (!multi && (wanted.size() > 1 || values.exactly == null)) {
            throw new AssertionError("Cannot fill in " + item + " " + values + ": " + item + " is single-valued (its"
                    + " field has no add and remove buttons); set(\"" + item + "\", value) types its value");
        }
        if (found.input == null) {
            throw new AssertionError("Cannot fill in " + item + ": " + readOnly(item, found.wrapper));
        }
        if (!multi) {
            // one value or none: the field typed into
            return new Field(LINE.validated(found.wrapper),
                    enter(tester, item, found.input, wanted.isEmpty() ? "" : typedAs.get(wanted.get(0))), null);
        }
        List<String> removed = values.exactly != null ? null
                : new ArrayList<>(texts(item, shownInput, values.removed).keySet());
        if (removed != null) {
            List<String> present = values(rows(tester, item));
            for (String value : removed) {
                if (!present.contains(value)) {
                    throw new AssertionError("Cannot remove " + value + " from " + item + ": it has no such value;"
                            + " its values: " + present);
                }
            }
        }
        List<String> messages = new ArrayList<>();
        // removed first: a row of a value not wanted
        for (int guard = 0; guard < 100; guard++) {
            Component remove = null;
            for (Row row : rows(tester, item)) {
                boolean drop = removed != null ? removed.contains(row.value)
                        : !row.value.isEmpty() && !wanted.contains(row.value);
                if (drop) {
                    remove = LINE.removeValueButton(row.container);
                    if (remove == null || !remove.isVisibleInHierarchy() || !remove.isEnabledInHierarchy()) {
                        throw new AssertionError("Cannot remove " + row.value + " from " + item + ": its row has no"
                                + " remove button the user may press (read-only)");
                    }
                    break;
                }
            }
            if (remove == null) {
                break;
            }
            tester.executeAjaxEvent(remove, "click");
        }
        for (String value : wanted) {
            List<Row> rows = rows(tester, item);
            if (rows.stream().anyMatch(row -> row.value.equals(value))) {
                continue;
            }
            Row empty = rows.stream().filter(row -> row.value.isEmpty() && row.input != null).findFirst().orElse(null);
            if (empty == null) {
                Component add = LINE.addValueButton(shown(tester, item).panel);
                if (add == null || !add.isVisibleInHierarchy() || !add.isEnabledInHierarchy()) {
                    throw new AssertionError("Cannot add " + value + " to " + item + ": its field has no add button"
                            + " the user may press (" + (multi ? "read-only" : "single-valued") + "); its values: "
                            + values(rows));
                }
                tester.executeAjaxEvent(add, "click");
                empty = rows(tester, item).stream().filter(row -> row.value.isEmpty() && row.input != null)
                        .reduce((first, second) -> second).orElse(null);
                if (empty == null) {
                    throw new AssertionError("The add button of " + item + " added no empty row; its values: "
                            + values(rows(tester, item)));
                }
            }
            for (String message : enter(tester, item, empty.input, typedAs.get(value))) {
                if (!messages.contains(message)) {
                    messages.add(message);
                }
            }
        }
        return new Field(LINE.validated(found.wrapper), messages, null);
    }

    /** A value row of an item's panel: its container, its input (null: read-only), the value it shows. */
    private static final class Row {
        MarkupContainer container;
        FormComponent<?> input;
        String value;
    }

    /** The value rows the item's panel shows ({@link GuiLine#valueRows}), with what their inputs show. */
    private static List<Row> rows(WicketTester tester, String item) {
        Component panel = find(tester.getLastRenderedPage(), item).panel;
        List<Row> out = new ArrayList<>();
        if (panel == null) {
            return out;
        }
        for (MarkupContainer container : LINE.valueRows(panel)) {
            if (!container.isVisibleInHierarchy()) {
                continue;
            }
            // the row's first input shown (as find takes an item's); disabled: a read-only value
            FormComponent<?>[] first = new FormComponent<?>[1];
            container.visitChildren(FormComponent.class, (input, visit) -> {
                if (input.isVisibleInHierarchy() && !(input instanceof IFormSubmittingComponent)) {
                    first[0] = (FormComponent<?>) input;
                    visit.stop();
                }
            });
            if (first[0] == null) {
                continue;
            }
            Row row = new Row();
            row.container = container;
            row.input = first[0].isEnabledInHierarchy() ? first[0] : null;
            row.value = shownValue(first[0]);
            out.add(row);
        }
        return out;
    }

    private static List<String> values(List<Row> rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(row -> out.add(row.value));
        return out;
    }

    /** The values as the item's rows show them: a text field's as its converter shows them ({@link #typed}). */
    private static Map<String, Object> texts(String item, FormComponent<?> input, List<Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object value : values) {
            String text = value == null ? "" : String.valueOf(value);
            if (input != null && value != null && !(input instanceof AbstractSingleSelectChoice)
                    && !FieldValues.isDateTimeField(input)) {
                Object coerced = FieldValues.coerce(item, input.getType(), value);
                text = coerced == null ? text : FieldValues.render(input, coerced);
            }
            if (!text.isEmpty() && !out.containsKey(text)) {
                out.put(text, value);
            }
        }
        return out;
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
            // an earlier click may have replaced the panel that held this toggle (4.4's old design re-renders the
            // whole container): a toggle no longer on the page is not clickable
            if (toggle.findParent(Page.class) == null) {
                continue;
            }
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

    /**
     * The page's Save: the form's default button as the page opened (a panel's search form makes its search button the
     * root form's default: the Assignments tab of 4.4 and later), else the form's default button, else its button
     * {@code save} (pages without a default button).
     */
    private static Component saveButton(Form<?> form, IFormSubmittingComponent opened) {
        IFormSubmittingComponent button = opened instanceof Component && within((Component) opened, form.getPage())
                ? opened : form.getDefaultButton();
        Component save = null;
        if (button instanceof Component && ((Component) button).isVisibleInHierarchy()) {
            save = (Component) button;
        } else {
            List<Component> found = new ArrayList<>();
            form.visitChildren((component, visit) -> {
                if ("save".equals(component.getId()) && component instanceof IFormSubmittingComponent
                        && component.isVisibleInHierarchy()) {
                    found.add(component);
                    visit.stop();
                }
            });
            save = found.isEmpty() ? null : found.get(0);
        }
        if (save == null) {
            // 4.4 and later: hidden from a user without a #modify of the object (AssignmentHolderOperationalButtonsPanel)
            throw new AssertionError("The page shows no Save button (the object is read-only to the user, or the page"
                    + " saves with a preview)");
        }
        if (!save.isEnabledInHierarchy()) {
            // 4.0: shown, disabled for a user who may change nothing of the object
            throw new AssertionError("The page's Save button is disabled (the object is read-only to the user)");
        }
        return save;
    }

    /**
     * A save executed in the background (the progress panel's execution, 4.0's and 4.4's old design's too): waited for
     * until it ends, then the progress timer fires as the browser's poll does, which shows its result or leaves the
     * page. False: the execution still ran at {@link #SAVE_TIMEOUT_SECONDS} (the page reports nothing yet; the next
     * reset waits for it, {@link GuiSaves}).
     */
    private static boolean awaitSaving(WicketTester tester, Page page) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAVE_TIMEOUT_SECONDS);
        for (int round = 0; round < 50 && tester.getLastRenderedPage() == page; round++) {
            Future<?> saving = saving(page);
            if (saving != null) {
                try {
                    saving.get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    // the next reset waits for it, not to let it run into the restored database
                    GuiSaves.running(saving);
                    return false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (!saving.isDone()) {
                        GuiSaves.running(saving);
                        return false;
                    }
                } catch (ExecutionException | CancellationException e) {
                    // the execution's failure is in its result, which the page shows
                }
            }
            List<AbstractAjaxTimerBehavior> timers = new ArrayList<>();
            // the timers of the components shown (a browser has no script of a hidden one: 4.4's old design hides
            // its buttons panel, with its refresh timer, while saving)
            page.visitChildren((component, visit) -> {
                if (component.isVisibleInHierarchy()) {
                    component.getBehaviors(AbstractAjaxTimerBehavior.class).stream()
                            .filter(timer -> !timer.isStopped()).forEach(timers::add);
                }
            });
            if (timers.isEmpty()) {
                return true;
            }
            if (saving == null) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
            tester.executeBehavior(timers.get(0));
        }
        return true;
    }

    /**
     * The background execution of the changes a save started on the page, or null (none: a synchronous save): the
     * future of the process of a progress panel's {@code reporterModel}, of either panel a line has (4.0 and 4.4's old
     * design {@code web.component.progress.ProgressPanel}, its field private; 4.4 and later
     * {@code gui.impl.page.admin.component.ProgressPanel}).
     */
    private static Future<?> saving(Page page) {
        Future<?>[] found = new Future<?>[1];
        page.visitChildren((component, visit) -> {
            Object model = reporterModel(component);
            if (model instanceof AsyncWebProcessModel) {
                AsyncWebProcess<?> process = ((AsyncWebProcessModel<?>) model).getObject();
                if (process != null && process.getFuture() != null) {
                    found[0] = process.getFuture();
                    visit.stop();
                }
            }
        });
        return found[0];
    }

    /** The component's {@code reporterModel} field (a progress panel's), or null: none. */
    private static Object reporterModel(Component component) {
        for (Class<?> type = component.getClass(); type != null && type != Component.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field field;
            try {
                field = type.getDeclaredField("reporterModel");
            } catch (NoSuchFieldException e) {
                continue;
            }
            if (!AsyncWebProcessModel.class.isAssignableFrom(field.getType())) {
                return null;
            }
            try {
                field.setAccessible(true);
                return field.get(component);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Cannot read the progress of " + component.getClass().getName(), e);
            }
        }
        return null;
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
