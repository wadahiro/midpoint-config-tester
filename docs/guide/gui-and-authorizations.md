# The GUI and authorizations

## What the GUI shows a user

What a user sees and may change in the GUI is decided by their authorizations, and asked the same way as a request,
logged in as them:

```java
// the objects a search finds (the GUI's lists): midPoint narrows it by the user's #read authorizations
assertThat(t.actor(Users.HELPDESK).search(UserType.class))
        .containsExactlyInAnyOrder(Users.JACK, Users.ROLE_OWNER, Users.HELPDESK);
assertThat(t.actor(Users.HELPDESK).canSearch(Users.ANN)).isFalse();   // #search: the list shows it
assertThat(t.actor(Users.HELPDESK).canGet(Users.ANN)).isTrue();    // #get: its details open
assertThat(t.actor(Users.HELPDESK).canRead(Users.JACK)).isTrue();  // #read: both

// a page opens, as the GUI decides it: by its URL, or by the actions of its @PageDescriptor
assertThat(t.actor(Users.HELPDESK).canOpen("/admin/users")).isTrue();
assertThat(t.actor(Users.HELPDESK).canOpen("/admin/user/" + Users.JACK.oid())).isTrue();   // #userDetails
assertThat(t.actor(Users.HELPDESK).canOpen(AuthorizationConstants.AUTZ_UI_USERS_ALL_URL,
        AuthorizationConstants.AUTZ_UI_USERS_URL, AuthorizationConstants.AUTZ_UI_USERS_VIEW_URL)).isTrue();

// an object's form: what it shows and what it lets the user change
Form form = t.actor(Users.HELPDESK).form(Users.JACK);
assertThat(form.modifiableItems()).containsExactly("telephoneNumber");
assertThat(form.canRead("emailAddress")).isTrue();
```

- The names follow midPoint's actions: `#search` lets a search find an object, `#get` lets the user get it by its OID,
  `#read` allows both. `search(type)` searches as the user through `ModelService` (all objects of the type, as the
  GUI's list does before its own filters) and gives typed refs, equal to the generated names; `canSearch(object)`
  is whether it finds the object, `canGet(object)` whether getting it succeeds, `canRead(object)` both.
- `collection(name)` is an object collection's list as the user sees it (a "my requests" page): the collection's
  definition read and compiled privileged, as the GUI does, its filter's expressions evaluated for the user
  (`midpoint.principalOid`), its base collection, then searched as the user.
- `canOpen(url)` decides a URL as the GUI's authorization evaluator does: the path after the GUI's context, matched
  (case-insensitive Ant patterns) against every page's `@PageDescriptor` and the GUI's own URL mappings
  (`EndPointsUrlMapping` on 4.8, `PageUrlMapping` on 4.4), the actions of all that match together, with
  `#guiAll`; a page any user opens (login pages, `permitAll`) is allowed, a URL no page matches is denied. The patterns
  overlap as in the GUI: `/admin/user/**` (a user's details, `#userDetails`) also matches the new user page
  `/admin/user`. `canOpen(actions...)` (more than one argument, or one not starting with `/`) takes a page's actions
  instead (`PageUsers`: `#usersAll`, `#users`, `#usersView`). Either way the security enforcer decides
  (`decideAccess`): one allowed and none denied. A failed assertion shows what the URL matched and needed.
- The tester knows the line's GUI pages from an index it ships: `canOpen` does not load the GUI.
- `form(object)` is the object's definition as the GUI's form gets it (`getEditObjectDefinition`, request phase):
  `canRead(path)`, `canModify(path)`, and the top-level `readableItems()` and `modifiableItems()`; `opens()` is false
  when the user may not read the object at all.
- `t.newObjectForm(type)` and `t.editForm(type, nameOrOid)` are the object's form in the GUI, **run by the GUI
  itself**: the tester opens the type's details page in the JVM (Wicket's tester: no browser, no web server), types
  the values into its fields and presses Save, as a user does. `newObjectForm` is a new object's page as the GUI opens
  it from the "+" of an archetype's members: `.archetype(name)` (its object template gives the page its items and
  validations), `.parent(org)` (as "create child" of the organization tree: the parent's assignment and
  `parentOrgRef`). `editForm` is an existing object's page, opened by its OID as from a list: the fields show the
  stored values. On either, `.set(item, value)` types a value in first (by path of local names: `identifier`,
  `extension/site`), then:
  - `.validate(item, value)` types the value into the item's field and leaves it: what the field shows, `passes()`,
    `fails()`, `fails(message)` (also `validated()`: the item has a validation, `passed()`, `message()`,
    `messages()`, `absent()`: why the page has no field to type into).
  - `.submit()` presses Save: `saves()`, `rejects(item)` (not saved, and the item's field shows a message),
    `rejectsWith(message)` (on a field or on the form), `saved()`, `rejected()`, `messages()`, `item(item)`. Saving
    executes the changes through the model as the user: what their authorizations or a policy rule refuse is a
    message of the page too.

  midPoint evaluates an object template's item validation (`item/validation/server`) only in the GUI's form, never in
  the model's operations: an object added through the model API is not validated, so a test of the validation asks
  the form. `validate` records nothing and changes nothing; `submit` stores the object when it saves.

  ```kotlin
  t.newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1")
      .validate("identifier", "a1").fails("The identifier is upper case letters and digits.")
  t.actor("site-admin").newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1")
      .set("name", "cat-1").set("identifier", "CAT1").submit().saves()
  t.editForm(OrgType::class.java, "cat-1").set("costCenter", "CC2").submit().rejects("costCenter")
  ```

  What the page does is the GUI's:
  - As the user: `t.actor(user).newObjectForm(type)` / `.editForm(...)` logs them in as the tester logs a user in. The
    page's URL is decided first as midPoint's security filter decides it: a user who may not open the page fails the
    form (`form-nobody may not open /admin/org/...`). The page shows the items as the GUI shows them to the user: an
    item they may not see, add or change has no field (`absent()`). Without an actor, the administrator's form.
  - The field's validator sees the value as `input` (a PolyString item's text) and the object **before** the value
    in `object`: a new object's other items typed in, an existing object's stored values. Leaving the field puts the
    value into the object, so on saving a validation that reads its own item from `object` sees it there. 4.0
    validates a text field twice on leaving it, the second time with the value in `object`.
  - Saving validates every field again (an empty one with `input` null: a required item left empty), then on 4.4
    and later the validation on saving, with the values in `object` (its messages are shown on the field when the GUI
    reports them there, else on the form; on 4.4 a PolyString item's `input` is then the PolyString itself).
  - The first server expression of the item, its output an `OperationResultType`: any status but success rejects. Its
    message is the result's user-friendly message, translated in the JVM's locale, or else its message; an expression
    midPoint cannot make or evaluate rejects with "Cannot make expression: ..." / "Cannot evaluate expression: ...".
  - A hidden empty field ("show empty fields") is shown before typing into it.

  The tester brings midPoint's GUI for them, nothing to declare:
  [Classes of the GUI](environment.md#classes-of-the-gui). The GUI's beans load once per JVM, on its first form (not
  midPoint's web application: no initial import, filters or web server). Not covered: the browser's own validations and scripts,
  wizards (a resource's, an object type's), and pages other than the objects' details pages.
- A failed assertion after any of them lists the user's authorizations, by the role they come from:
  `'read-it-users': allow authorization-model-3#read of UserType with a filter; items name, givenName...`.
- midPoint's initial End user role (every user of the sample has it) lets a user get **any** user, with all items
  (`assignment-target-get`, for the GUI's selection windows): a search finds only what `#read` allows, but every user's
  details open for everyone, and a narrower `#read` shows no more than that. The test shows it; a deployment that
  keeps the role as shipped has it too.
- The execution phase is not asked on its own: a request or a change through the API goes through both
  phases, as in the GUI.

## Changes and deletions as a user

A user's own change or deletion in the GUI goes through the model with the user logged in, request and execution
phase; the actor does it the same way:

```java
// as the user saves a form: what they may not change, or a policy rule's violation, fails the call
t.actor(Users.HELPDESK).modify(t.user(Users.JACK), m -> m.set("telephoneNumber", "+1 555 0100"));
t.expectFailure(() -> t.actor(Users.HELPDESK).modify(t.user(Users.JACK), m -> m.set("costCenter", "Sales")));
// as the user deletes from a list
t.actor(Users.IT_MANAGER).delete(t.user(Users.ANN));
```

- `modify` takes the same `Modification` as `Focus.modify` (without its reconcile option); both return `Effects`.
- The operations of an actor (a request, a work item's decision, delegation, forwarding, claim and release, a
  certification decision, a change, a deletion) run in the GUI's channel, `MidPointConfigTester.GUI_CHANNEL`
  (`gui/channels-3#user`), as the GUI's own task for an operation has it: a mapping or a hook that looks at the channel
  acts as for the user's click. `t.inChannel(...)` around the call keeps its channel instead.
- An operation runs synchronously in a lightweight task of its own, as the GUI's does (`createSimpleTask`: not stored,
  owned by the logged-in user); only that task gets the channel. The tasks midPoint starts from it are midPoint's,
  as in production (the 4.0, 4.4 and 4.8 sources):
  - a task runs with the authorizations of its owner: the task manager logs the owner in before it runs it
    (`JobExecutor`);
  - the execution of an approved request (`CaseOperationExecutionTaskHandler`) runs in the request's channel (the GUI's,
    for an actor's request) and is owned by the requester, or by the owner the system configuration's
    `workflowConfiguration` names;
  - a background task runs in the channel stored in it (on 4.4+ the handler's default when none is); the GUI's "execute
    in background" stores the GUI's channel; reconciliation, import, live sync and asynchronous update switch to their
    own channel while they run.
- A server task the test runs (`t.task(...).run()`) runs as the task defines, not as an actor: to test what a user
  starts in the background, run the task they would start, owned by them.

: `guiProfile()` is the user's GUI profile as
midPoint compiled it at login:

```java
GuiProfile manager = t.actor(Users.IT_MANAGER).guiProfile();
assertThat(manager.views(UserType.COMPLEX_TYPE)).extracting(GuiProfile.CollectionView::identifier)
        .containsExactlyInAnyOrder("people", "department-members", "allUsers");   // the system's, the induced role's, the GUI's
assertThat(manager.view(UserType.COMPLEX_TYPE, "department-members").inMenu()).isTrue();
assertThat(manager.featureVisibility("orgTreeExpandAll")).isEqualTo(UserInterfaceElementVisibilityType.HIDDEN);
assertThat(manager.defaultTimezone()).isEqualTo("Asia/Tokyo");
```

- midPoint merges the system configuration's `adminGuiConfiguration`, then each one of the roles, orgs and archetypes
  the user has (assigned or induced, with their conditions), by its rules: a later time zone wins, a hidden feature
  stays hidden. `guiProfile()` is that result (`CompiledGuiProfile`), not a merge of the tester's own: the object collection views (`views()`, by type), the
  additional menu links, the dashboard links, the features, the details pages' configuration (`objectDetails()`),
  the time zone and the data language; `compiled()` is midPoint's object for the rest.
- On 4.4 and later the GUI adds its own part, as in production: the default view of each type (`allUsers`,
  `allRoles`...: the type's default view, in no menu) and the details pages' panels. It is in every profile, whether or
  not a test has opened a form.
- `inMenu()` is whether the line's side menu lists a view under its type, by the line's rule: on 4.8, not the
  default view, not hidden or vacant, for no operation or modify (4.4: the same with a collection, the default view
  too). The menu item's own page
  (the users list) is `canOpen`. The rest of the menu (which sections and pages it shows) is not modeled.
- A failed assertion after it shows the user's views, features and links.

All of these for kinds of users at once, as a matrix: [An authorization matrix](authz-matrix.md) (experimental).
