package com.github.wadahiro.midpoint.tester.internal;

import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ValuePolicyType;
import com.github.wadahiro.midpoint.tester.BulkActionOutput;
import com.github.wadahiro.midpoint.tester.Mail;
import com.github.wadahiro.midpoint.tester.Notification;

/**
 * The drivers whose midPoint API differs between lines (notifications, security enforcer, value policies, scripts,
 * bulk actions); see {@code LineSupport.drivers}.
 */
public interface LineDrivers {

    /** The messages sent to {@code dummy} transports since {@link #clearNotifications()}, in the order sent. */
    List<Notification> notifications();

    void clearNotifications();

    /** The mails the in-memory SMTP server received since {@link #clearMails()}, in the order received. */
    List<Mail> mails();

    void clearMails();

    /** The port of the in-memory SMTP server on {@code 127.0.0.1}. */
    int smtpPort();

    /** Whether the logged-in user may do the action on the object, in any phase. */
    boolean isAuthorized(String actionUrl, PrismObject<? extends ObjectType> object, Task task, OperationResult result)
            throws Exception;

    /**
     * Whether the logged-in user is allowed any of the actions with no object, as the GUI decides a page
     * ({@code SecurityEnforcer.decideAccess}: one allowed and none denied).
     */
    boolean decideAccess(java.util.Collection<String> actionUrls, Task task, OperationResult result) throws Exception;

    /**
     * Whether the logged-in user may assign the target to the focus with the relation (null: the default), or
     * unassign it, as the clockwork authorizes it. The request: {@code #modify} of the {@code assignment} item or of
     * all items, else {@code #assign} or {@code #unassign} with the target and relation ({@code #delegate} for a
     * delegation); then the execution phase's {@code #modify} of the delta. Not the rule of an assignment with a
     * policy rule, nor the changes the projector adds to the delta ({@code roleMembershipRef}).
     */
    boolean isAuthorizedToAssign(PrismObject<? extends ObjectType> focus, PrismObject<? extends ObjectType> target,
            QName relation, boolean assign, Task task, OperationResult result) throws Exception;

    /**
     * The filter of what the selector (an authorization's object or target) selects of the type for the logged-in
     * user, as midPoint turns it into a search's: {@code preProcessObjectFilter} with a principal that has only a
     * {@code #read} of the selector. Null: anything; a none filter: nothing (also a selector midPoint cannot search
     * by, e.g. {@code delegator}).
     */
    com.evolveum.midpoint.prism.query.ObjectFilter selectorFilter(
            com.evolveum.midpoint.xml.ns._public.common.common_3.OwnedObjectSelectorType selector,
            Class<? extends ObjectType> type, Task task, OperationResult result) throws Exception;

    /**
     * The logged-in user's GUI profile as midPoint compiled it at login (the principal's
     * {@code CompiledGuiProfile}; 4.0: {@code CompiledUserProfile}), with each view's place in the line's side menu.
     */
    com.github.wadahiro.midpoint.tester.GuiProfile guiProfile(Task task, OperationResult result) throws Exception;

    /** Limitation name to whether the value meets it ({@code ModelInteractionService.validateValue}). */
    Map<String, Boolean> validateValue(String value, ValuePolicyType policy, PrismObject<? extends ObjectType> object,
            Task task, OperationResult result) throws Exception;

    String generateValue(ValuePolicyType policy, PrismObject<? extends ObjectType> object, Task task,
            OperationResult result) throws Exception;

    /** A script evaluated as an expression through the {@code ExpressionFactory}; types may be null (inferred). */
    List<Object> evaluateScript(String languageUri, String code, Map<String, Object> variables,
            Map<String, Class<?>> types, QName returnType, Task task, OperationResult result) throws Exception;

    BulkActionOutput executeBulkAction(String xml, Task task, OperationResult result) throws Exception;

    // ---- midPoint's initial import (InitialDataImport of gui/admin-gui), see InitialImport ----------------------

    /**
     * The Spring resource pattern of the line's initial objects: 4.0 and 4.4 ship them in the GUI module (the build
     * unpacks them from its {@code classes} jar to {@code tester/initial-objects}), 4.8+ in {@code system-init}, where
     * midPoint itself finds them on the classpath.
     */
    String initialObjectsPattern();

    /**
     * How InitialDataImport orders the files: true, by the number of the name's first three characters
     * ({@code DataImport.sortFiles}, 4.0); false, by the file name (4.4+).
     */
    boolean initialObjectsSortedByNumber();

    /**
     * Imports an initial object as {@code InitialDataImport.importObject} does on this line: an add delta executed
     * with {@code isImport} (full processing) and the given overwrite. Returns why the object is skipped (4.8: a type
     * the repository does not support; 4.0: a Jasper report, whose libraries the tester has not), null when imported.
     */
    String importInitialObject(PrismObject<? extends ObjectType> object, boolean overwrite, Task task,
            OperationResult result) throws Exception;

    // ---- midPoint's post-initial import (PostInitialDataImport of gui/admin-gui), see PostInitialImport ---------

    /** The channel of the task PostInitialDataImport imports with on this line. */
    String postInitialChannel();

    /**
     * Imports a post-initial file as PostInitialDataImport does on this line: {@code ModelService.importObjectsFromFile}
     * with its options (4.0: overwrite only, so midPoint imports raw; 4.4+: overwrite, full processing).
     */
    void importPostInitialFile(java.io.File file, Task task, OperationResult result) throws Exception;

    /**
     * Executes the bulk action of a post-initial file (the real value of its single item: an {@code ExecuteScriptType}
     * or a {@code ScriptingExpressionType}) as PostInitialDataImport does on this line; returns the console output.
     */
    String executePostInitialScript(Object bulkAction, Task task, OperationResult result) throws Exception;
}
