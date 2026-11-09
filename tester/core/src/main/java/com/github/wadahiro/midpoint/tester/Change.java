package com.github.wadahiro.midpoint.tester;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * A modification of one existing object, built step by step and then run ({@link MidPoint#change}):
 *
 * <pre>{@code
 * mp.change(UserType.class, "jdoe")
 *         .add("organization", "Sales")              // item paths by name ("activation/administrativeStatus")
 *         .delete(UserType.F_ORGANIZATIONAL_UNIT, "IT") // or as ItemPath
 *         .assign("Employee")                          // a role, org or service by name or OID
 *         .unassign("Dept IT", SchemaConstants.ORG_MANAGER)
 *         .options(ModelExecuteOptions.create().reconcile())
 *         .execute();                                  // or tryExecute(), preview()
 * }</pre>
 *
 * Values are real values; plain strings are accepted for polystring items and for enumerations (by their XML value).
 * Path segments given by name are matched against the object's definition (namespaces are taken from it).
 */
public interface Change {

    Change replace(String path, Object... values);

    Change replace(ItemPath path, Object... values);

    Change add(String path, Object... values);

    Change add(ItemPath path, Object... values);

    Change delete(String path, Object... values);

    Change delete(ItemPath path, Object... values);

    /** Adds an assignment of the role, org or service (by name or OID) with the default relation. */
    Change assign(String target);

    Change assign(String target, QName relation);

    /** Deletes the object's assignments of the target (by name or OID) with the default relation. */
    Change unassign(String target);

    Change unassign(String target, QName relation);

    /** {@code ModelExecuteOptions} for {@link #execute()} and {@link #tryExecute()} (e.g. reconcile, raw). */
    Change options(ModelExecuteOptions options);

    /** The delta built so far. */
    ObjectDelta<? extends ObjectType> delta();

    /** Executes the change; throws {@link OperationFailed} if midPoint reports an error. */
    Run execute();

    /** Executes the change; a failure is in the returned run ({@link Run#isSuccess()}, {@link Run#failure()}). */
    Run tryExecute();

    /** Computes the change without executing it (see {@link MidPoint#preview}). */
    Run preview();
}
