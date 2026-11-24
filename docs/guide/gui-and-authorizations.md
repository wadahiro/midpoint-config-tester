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

// the object as the user gets it: the items they may not read are not there
t.actor("name-reader").read(t.user("jack")).hasItem("givenName", "Jack").hasNoItem("costCenter");

// the work items of their "My work items", decided as them
t.actor(Users.ROLE_OWNER).workItems().forUser(Users.JACK).single().approve();

// an object's form: what it shows and what it lets the user change
Form form = t.actor(Users.HELPDESK).form(Users.JACK);
assertThat(form.modifiableItems()).containsExactly("telephoneNumber");
assertThat(form.canRead("emailAddress")).isTrue();
```

- The names follow midPoint's actions: `#search` lets a search find an object, `#get` lets the user get it by its OID,
  `#read` allows both. `search(type)` searches as the user through `ModelService` (all objects of the type, as the
  GUI's list does before its own filters) and gives typed refs, equal to the generated names; `canSearch(object)`
  is whether it finds the object, `canGet(object)` whether getting it succeeds, `canRead(object)` both.
- `read(object)` (a typed ref, a focus such as `t.user("jack")`, or a type and a name or OID) is the object as the
  user gets it: midPoint's `ModelService.getObject` logged in as them, which removes the items their `#read` (or
  `#get`) authorizations do not let them read, as the GUI's details page has them. `ObjectView` reads it once:
  `values(item)`, `hasItem(item, values...)` (exactly these, compared as `Focus.hasItem` does), `hasNoItem(item)`
  (empty, or not readable: the two look the same to the user), `asObject()`. A failed `hasItem` shows what the object
  has for the administrator; a user who may not get the object at all fails `read` (`canGet` false). Every
  authorization that lets the user get the object adds its items: midPoint's End user role (below) lets them get
  every user with all items.
- `workItems()` are the work items the user's "My work items" lists: the page's query (assigned to the user, or to a
  user whose deputy they are with work items not excluded by the deputy assignment's limitations; not closed) searched
  as the user (`ModelService.searchContainers`), so their authorizations apply; newest first. Narrowed and claimed as
  `t.workItems()`'s (`forUser`, `forRole`, `inStage`, `single()`, `none()`), each item read as the administrator
  (assignees, stage); `approve()`, `reject()`, `forward(users)` and `delegate(users)` of an item (or of `single()`)
  found there decide as that user. A failure lists every open work item. Not the page itself: its columns, filters
  and the GUI's claimable items ("Work items to claim") are not read.
- `collection(name)` is an object collection's list as the user sees it (a "my requests" page): the collection's
  definition read and compiled privileged, as the GUI does, its filter's expressions evaluated for the user
  (`midpoint.principalOid`), its base collection, then searched as the user.
- `requestAccess()` / `requestAccess(forUser)` is the request-access page ("Request a role" on 4.0 and 4.4, "Request
  access" on 4.8 and later) as the user sees it (`RequestAccessPage`), **computed** as the line's GUI computes it,
  logged in as them (nothing rendered, nothing requested; a user who may not open the page fails:
  `jack may not open /self/requestAccess`). The page of the line:

  | | 4.0, 4.4 (both designs) | 4.8 and later |
  |---|---|---|
  | page | `PageAssignmentShoppingCart`, `/self/assignmentShoppingCart` | the wizard of `PageRequestAccess`, `/self/requestAccess` |
  | configuration | the system configuration's `roleManagement`: `roleCatalogRef`, `roleCatalogCollections` | the requester's compiled GUI profile's `adminGuiConfiguration/accessRequest` (each section replaced whole by the last one merged); `roleManagement/roleCatalogRef` is not read |
  | entries (`entries()`) | tabs: the role catalog (key `roleCatalog`, the catalog org itself, its org tree below at any depth) when `roleCatalogRef` is set; All roles, All organizations, All services, User's assignments (`allRoles`, `allOrgs`, `allServices`, `userAssignments`), each shown when `roleCatalogCollections` is empty or names its URI | the menu: the orgs under `roleCatalog/roleCatalogRef`, one level each, down to `roleCatalogDepth` (default 3; a deeper org is no menu item, its roles not listed); the `collection` items (an object collection, by its type and filter as written, or `allRoles`, `allOrgs`, `allServices`); Roles of teammate unless `showRolesOfTeammate` (`rolesOfTeammate/enabled`) is false |
  | an org folder lists (`CatalogEntry.roles()`) | its roles and services one level in it together, by name (`RoleCatalogTabPanel`: searched as `AbstractRoleType`, `type RoleType` OR `type ServiceType` of the org, one level); the search panel has no type item: `withType(RoleType::class.java)` or `withType(ServiceType::class.java)` narrows the list to that type, as an Advanced query of the search panel (a `type` filter) does; any other type fails | the objects one level in it of the search panel's type, roles at first; `withType(ServiceType::class.java)` (or `OrgType`, `AbstractRoleType`) switches it |
  | target users (`canRequestFor`) | the requester, or any user their search finds | Myself unless `targetSelection/allowRequestForMyself` is false; another user unless `allowRequestForOthers` is false and when the requester's search with the object part of their `#assign` authorizations finds them; `inGroup(group, user)` a group tile's autocomplete (that filter AND the group's collection, its expressions evaluated) |
  | a role held (`offers`) | the target's assignments (`AssignmentEditorDto.isAssignable`) | the target's `roleMembershipRef` (`canAddTemplateAssignment`) |

  Every entry lists its objects AND what the requester may assign to the target with the relation
  (`getAssignableRoleSpecification(target, type, 0)`: `#modify` of the target's assignments allows all, else the
  `#assign` authorizations' global filter OR the filter of the relation), searched as the requester (their `#search`
  / `#read` narrow it). The tree and the lists are read across all of the GUI's pages (it shows 20 at a time by
  default, by name; a folder's children by display name, then name), a folder's children when first asked, as the
  user expands it, so `entry(name)` (the shallowest level first) and `children()` reach a folder at any depth the
  line shows; `roles()`, the claims and `explain` read every folder the line shows (for a large catalog,
  `entry(name).offers(role)` reads less). An org folder lists as the table says (`type()`: 4.0 and 4.4
  `AbstractRoleType`, 4.8 and later `RoleType` until `withType`); collections and the fixed entries keep their own
  type. `withRelation(relation)` picks the relation (default: `relationSelection/defaultRelation`, else `default`);
  `relations()` are the relations of the `#assign` authorizations for the target when none is global, else the
  self-service relations (4.8+: only the default when `allowOtherRelations` is false). With a relation that neither a
  global filter nor a relation's filter covers the GUI's query has no assignment filter at all and lists every object
  (the request is refused at Submit).
  Claims: `showsRole(role)` / `showsNoRole(role)` (listed, maybe with the add disabled), `offersRole(role)` /
  `offersNoRole(role)` (listed and its add enabled: a held role is offered again unless
  `roleManagement/defaultAssignmentConstraints` forbid the same target or relation, on every line by default),
  `offersTarget(user)` / `offersNoTarget(user)`; reads `roles()` (the union of the entries; roles of teammate only
  through `entry("rolesOfTeammate").rolesOfTeammate(teammate)`), `entry(name)` (by label, org or collection name, or
  key), `CatalogEntry.roles()`, `children()`, `explain(role)` (`RoleVisibility`: `listedIn()`, `assignable()`,
  `foundByRequester()`, `addEnabled()`, `heldAs()`). A failure says which step hides the role: no entry lists it
  (with the entries and where the line reads its configuration), the requester's `#assign` authorizations do not
  allow it for the target, or the requester's search does not find it. The request itself is the page's Submit,
  `t.actor(u).request(forUser).assign(role[, relation])` (a role offered may still open a case or be refused by a
  policy rule at Submit).

  **The Submit** (`Request`): the cart goes as the line's page submits it, for one target as one change of the user
  holding all its additions, with the page's execute options; for several targets (`request(user1, user2, ...)`) as
  the task the page starts, owned by the requester, which the call waits for. One role (`assign(role)`), several in
  one Submit (`assign(roleA, roleB)`) or a cart with relations and validities (`submit(c -> c.assign(roleA)
  .assign(roleB, a -> a.validTo(end)))`), and the page's comment (`comment(text)`, the request's business context,
  kept as the `requestorComment` of the assignments' metadata). The options are the GUI's
  `ExecuteChangeOptionsDto.createFromSystemConfiguration()` (force and reconcile false) with
  `executeImmediatelyAfterApproval` the negation of the system configuration's
  `roleManagement/defaultExecuteAfterAllApprovals` (default true: every role of the Submit, approved or needing no
  approval, is applied once all its approvals are in; false: each role as soon as it is approved, a role needing no
  approval at Submit), and inbound and projections skipped in the first pass (`initialPartialProcessing`; the result
  is the same, the first pass only computes the approvals). The approvals go through `workItems()` as for any request.
  `unassign(role)` is not the cart's (the GUI's cart only adds): a change of the target as the actor without the
  page's options, one target only. The task of several targets, per line:

  | | 4.0 | 4.4 | 4.8 and later |
  |---|---|---|---|
  | the task | an execute-changes task (`TaskCategory.EXECUTE_CHANGES`): the extension's query of the targets, type, the one delta of the additions, the options | an iterative change execution activity over the targets' query with the one delta and the options | an explicit change execution with one request per target (its delta and options) |
  | named | `com.evolveum.midpoint.web.page.self.PageAssignmentsList.requestAssignments` (a key no localization has) | the same | "Requesting access for N users"; a target's request "Requesting access for jack" |
  | started | added through `ModelService.executeChanges` as the requester: `#add` of `TaskType` needed (midPoint's End user role grants it for a self-owned execute-changes task), else refused at Submit | `TaskManager.switchToBackground`: no check | `ModelInteractionService.submit`, which runs privileged: no allow needed, but a deny of the requester's (`#add` of `TaskType`) still refuses the Submit (`runPrivileged` adds `#all` to their own authorizations) |
  | runs | as its owner, the requester: their `#assign` authorizations decide each target; a target refused fails the task on it, the others go through | the same | the same |
  | `initialPartialProcessing` | none in the task | none | in each request |

  A refusal at the start fails the call with midPoint's message (`expectFailure(...).isNotAuthorized()`); a task
  that failed on a target fails it with `the task "<name>" (owned by <requester>) failed: ...` and the last failure.

  ```kotlin
  t.actor(Users.JACK).requestAccess().offersRole(Roles.VPN).showsNoRole(Roles.ADMIN_TOOLS)
  t.actor(Users.JACK).requestAccess().entry("it").roles() shouldContain Roles.VPN
  t.actor(Users.JACK).requestAccess().withType(ServiceType::class.java).entry("it").roles() shouldContain Services.WIKI
  t.actor(Users.JACK).requestAccess().entry("it").roles() shouldContain Services.WIKI  // 4.0, 4.4
  t.actor(Users.MANAGER).requestAccess(Users.JACK).offersTarget(Users.JACK)
      .withRelation(SchemaConstants.ORG_MANAGER).offersRole(Roles.PROJECT_X)
  t.actor(Users.JACK).request().assign(Roles.VPN)
  t.actor(Users.JACK).request().comment("for the project").assign(Roles.VPN, Roles.WIKI)   // one Submit, two cases
  t.workItems().forRole(Roles.VPN).single().approve(t.actor(Users.OWNER))   // applied at once only with
  t.user(Users.JACK).hasAssignment(Roles.VPN)                              // defaultExecuteAfterAllApprovals=false
  t.actor(Users.MANAGER).request(Users.JACK, Users.BOB).assign(Roles.VPN)   // the task owned by the manager
  ```

  An `#assign` authorization's `target` of `AbstractRoleType` with a filter selects nothing for the lists searched
  as `RoleType` or `ServiceType` (All roles, All services, 4.8+ folders): name the types (`RoleType` and
  `ServiceType` targets), as midPoint's End user role does.
  Not modeled: the search box's text and subtree scope, the cart's conflicts (the Submit's cart is `Request`: the
  roles, their relations and validities, the comment, several targets).
- `canOpen(url)` decides a URL as the GUI's authorization evaluator does: the path after the GUI's context, matched
  (case-insensitive Ant patterns) against every page's `@PageDescriptor` and the GUI's own URL mappings
  (`EndPointsUrlMapping` on 4.8, `PageUrlMapping` on 4.4), the actions of all that match together, with
  `#guiAll`; a page any user opens (login pages, `permitAll`) is allowed, a URL no page matches is denied. The patterns
  overlap as in the GUI: `/admin/user/**` (a user's details, `#userDetails`) also matches the new user page
  `/admin/user`. `canOpen(actions...)` (more than one argument, or one not starting with `/`) takes a page's actions
  instead (`PageUsers`: `#usersAll`, `#users`, `#usersView`). Either way the security enforcer decides
  (`decideAccess`): one allowed and none denied. A failed assertion shows what the URL matched and needed. The GUI
  decides some of its buttons by Wicket's roles instead (any authorization naming the action, a deny too): the "+"
  of a members panel is `createMemberButton`, its Assign and Unassign `membersPanel` and the like (below).
- The tester knows the line's GUI pages from an index it ships: `canOpen` does not load the GUI.
- `form(object)` is the object's definition as the GUI's form gets it (`getEditObjectDefinition`, request phase):
  `canRead(path)`, `canModify(path)`, and the top-level `readableItems()` and `modifiableItems()`; `opens()` is false
  when the user may not read the object at all.
- The "+" of a role's, org's or service's members panel: what it offers is configuration, what a user sees and
  creates with it is theirs.
  - `t.org(x).newMemberOffers()` (`t.role(x)`, `t.service(x)`) are its buttons as the administrator reads the
    configuration: what the `assignmentRelation`s of the object's archetype (of its inducements) say a new member
    is, divided as the GUI divides them: one button per relation, type and archetype (`NewMemberOffer`: `type()`,
    `archetype()`, `relation()`, `description()`), an `assignmentRelation` with neither a `holderType` nor a
    `relation` ignored, each button once (4.0's GUI shows a repeated one twice). A button without a `relation` is
    a member with `default`; one without a `holderType` opens the panel's default type (`type()` null). The claims
    `offersNewMember(type, archetype[, relation])` and `offersNoNewMember(...)` compare the three (the archetype by
    name, OID or `ArchetypeRef`; none: a button without an archetype). Not a button: the panel's generic "+", whose
    dialog creates any type of the panel with any relation and no archetype, so `offersNoNewMember` does not mean
    the user cannot create one. The archetype is the object's own: a super-archetype's `assignmentRelation`s are not
    read (on no line), and on 4.4 and later only a structural archetype's.
  - `t.actor(user).createMemberButton(t.org(x))` is the "+" as the user sees it. `shown()`, `isShown()`,
    `isHidden()`: on 4.4 and later the GUI shows it when an authorization of the user names the panel's action
    (`#adminAddOrgMember` for an org, `#adminAddMember` for a role or a service), `#all` or `#guiAll`, whatever its
    decision: the GUI asks Wicket's roles of the session (every action of the user's authorizations), not the
    security enforcer, so a deny of the action shows it too (where `canOpen` of the action is false). 4.0 shows it
    to every user who opens the page (its header menu's Create needs the action): `isHidden()` fails there.
    `offersNewMember` and `offersNoNewMember` are the configuration's buttons as the user sees them (shown and
    offered). `click(type, archetype[, relation])` is the button's form: the user's
    `newObjectForm(type).archetype(archetype).memberOf(x, relation)` (below), to fill in and submit; it fails when
    the user does not see the "+" or it has no such button. A button without a type is clicked with the type given.
    Whether the click creates the member is the form's: the page opens as the user's GUI authorizations allow, the
    fields are the items they may add, then the model authorizes the new object as it does a request, its
    assignments first (`#assign` of the role or org, with the relation, unless `#add` allows the assignments
    already), then `#add`. A refusal is the page's message (`submit().saved()` false, `messages()`), after the
    fields pass their validations.

  ```kotlin
  t.org("site-1").offersNewMember(OrgType::class.java, "org-category").offersNoNewMember(OrgType::class.java, "org-site")
  t.actor("site-admin").createMemberButton(t.org("site-1")).isShown()
      .click(OrgType::class.java, "org-category").set("name", "cat-1").submit().saves()
  t.org("cat-1").hasParent("site-1").hasAssignment("org-category")
  t.actor("helpdesk").createMemberButton(t.org("site-1")).isHidden()
  t.actor("site-admin").createMemberButton(t.org("site-1"))
      .click(UserType::class.java, "employee", SchemaConstants.ORG_MANAGER).set("name", "mgr-1").submit().saves()
  ```
- The Assign and Unassign of a members panel, as a user sees and uses them: `t.actor(user).orgTreeMembersPanel(org)`
  (the members panel of an org selected in the organization tree), `.membersPanel(x)` and `.governancePanel(x)` (the
  Members and Governance panels of a role's, org's or service's details page; `x` a `t.org(...)`, a role, a service or
  a generated name). The three run the same GUI code with different relations and actions (`MembersPanel`):
  - `relations()`: Members the `ADMINISTRATION` relations without the `GOVERNANCE` ones (`default`), Governance the
    `GOVERNANCE` ones (`manager, approver, owner`), the organization tree the `ORGANIZATION` ones (`default, manager,
    approver, owner`), from the deployment's relation definitions, in the GUI's order. `memberTypes()`: the tabs of
    the Assign popup (User, Role, Org, Service; 4.9 and later Policy).
  - `assignButton()`, `unassignButton()` (`shown()`, `isShown()`, `isHidden()`, `action()`): shown, as the "+", when
    an authorization of the user names the panel's action, `#all` or `#guiAll`, whatever its decision and object
    (Wicket's roles of the session). The action: `#adminAssignOrgMember` / `#adminUnassignOrgMember` on an org's
    panels, `#adminAssignMember` / `#adminUnassignMember` on a role's or service's; 4.0's Governance panel has its own,
    `#adminAssignGovernance` / `#adminUnassignGovernance` (4.4 and later: the Members panel's). On 4.0 the toolbar's
    Assign (the icon right of the "+") is shown to every user, as the "+" is; only its header menu's Assign needs the
    action, so `isHidden()` fails there and a user without the authorizations fails when the task starts. Unassign
    is shown by the action on every line.
  - `assign(relation, members...)` (no relation: `default`) is the Assign popup: each member selected on the tab of
    its type, the relation picked, Add. The GUI starts a task owned by the user per tab, which runs the bulk action
    `assign` over the selection as the user; the call waits for it. The line checks the task when it starts: 4.0 and
    4.4 `#executeScript`, 4.8 and later `authorization-bulk-3#assign` (or `authorization-bulk-3#all`, which 4.8 also
    makes of an `#executeScript`) in the execution phase; the task then needs the model's `#assign` of each member
    (with the relation) and the execution's `#modify`. 4.11's initial system configuration gives a user's bulk action
    the `prohibitive` expression profile (`expressions/defaults/principal`): there the task fails on every member
    ("Access to action 'assign' denied"), unless the deployment's system configuration says otherwise.
  - `unassign(relation, member)` (no relation: the one the member has in the panel) is a member's row. On 4.4 and
    later its menu's Unassign, confirmed, deletes the member's assignments to the object through the model as the
    user, no task: every one of them, unless the member has several memberships (its `roleMembershipRef` as the user
    reads it) and one of them is the panel's (then that one); when it has several of the panel's relations the GUI
    asks which and starts a task over the row instead. The row has an Unassign only for a member with an assignment
    to the object as the user reads it. 4.0 has no row menu on a members panel: the row is selected and the panel's
    Unassign starts a task with the relation (as `unassignSelected`).
  - `unassignSelected(relation, members...)` is the panel's Unassign with the rows selected, confirmed: a task over
    the selection. 4.4 and later unassign every relation of the panel unless one selected member has several of them
    (then the relation given); 4.0 the dialog's relations (the one given, else every one of the panel).
    `unassignAll(type, relations...)` is the panel's Unassign with nothing selected: a task over the direct members
    of the type with an assignment of the relations (no relation: every one of the panel).
  - Each returns the call's `Effects` (the members' changes, provisioning, mails), without the GUI's task. What
    midPoint refuses fails the call (`expectFailure`): the task's start (`... failed: Not authorized for operation
    ...#executeScript`), or the task on a member (`... failed: the task "Add members to Organization site-1" (owned by
    jdoe) failed: PARTIAL_ERROR ...` and `last failure: jack: ...`). What the GUI does not offer is an
    `AssertionError`: a hidden button, a relation or type the panel has not, a member the user's search does not find
    (the popup lists only those; the task searches as the user too), a row Unassign that would delete other
    relations than the one given.
  - `t.actor(user).isAuthorized(AUTZ_UI_ADMIN_ASSIGN_ORG_MEMBER_ACTION_URI, ref)` stays the security enforcer's
    decision with the object: it differs from `assignButton().shown()` for a deny or an object-limited allow naming
    the action.

  ```kotlin
  val tree = t.actor("site-admin").orgTreeMembersPanel(t.org("site-1"))
  tree.assign(SchemaConstants.ORG_MANAGER, t.user("jack"))
  t.user("jack").hasAssignment("site-1", SchemaConstants.ORG_MANAGER)
  tree.unassign(SchemaConstants.ORG_MANAGER, t.user("jack"))
  t.actor("helpdesk").governancePanel(t.org("site-1")).unassignButton().isHidden()
  val uiOnly = t.actor("ui-only").orgTreeMembersPanel(t.org("site-1"))
  t.expectFailure { uiOnly.assign(SchemaConstants.ORG_MANAGER, t.user("jack")) }
  ```

  Not modeled: the panel's `searchBoxConfiguration` (4.4 and later; relations and types it may narrow), the Assign
  popup's relation-specific buttons (an archetype's `assignmentRelation`) and an org's "Other types" tab, indirect
  members and the organization tree's subtree scope, an approval the task's change triggers (its case is in the
  effects), 4.4's old design (`useNewDesign` false).
- `t.newObjectForm(type)` and `t.editForm(type, nameOrOid)` are the object's form in the GUI, **run by the GUI
  itself**: the tester opens the type's details page in the JVM (Wicket's tester: no browser, no web server), types
  the values into its fields and presses Save, as a user does. `newObjectForm` is a new object's page as the GUI opens
  it from the "+" of an archetype's members: `.archetype(name)` (its object template gives the page its items and
  validations), `.parent(org)` (as "create child" of the organization tree: the parent's assignment and
  `parentOrgRef`), `.memberOf(roleOrOrg, relation)` (as the "+" of its members: the assignment with the relation;
  for an org its `parentOrgRef` as the line's GUI adds it, on 4.0 with any relation, on 4.4 and later with a
  relation stored into `parentOrgRef`, by default `default` and `manager`). `editForm` is an existing object's page,
  opened by its OID as from a list: the fields show the stored values (`editForm(ref)` takes a generated name,
  `Orgs.SITE_1`, its type from the ref). `t.actor(u).selfProfileForm()` is the user's own profile page (the side
  menu's Profile: `/self/profile`, `#selfAll` or `#selfProfile`), the same form API with the user themself as the
  object, for a user who may not open the details page (`/admin/user/{oid}` needs `#userDetails`): the line's page
  (4.0 `PageSelfProfile`; 4.4 `PageSelfProfile` sends to the design's page, `/self/profile/user` or
  `/self/profile/userNew`; 4.8+ `PageUserSelfProfile`, `/self/profile/user`), the same details page with the
  logged-in user loaded (as them: `#read` of self), its Save the details page's Save (the model's request and
  execution phase both), the execute options panel hidden (the defaults go), no Delete, Change archetype or Edit raw
  (4.4's new design and 4.8+), no archetype's details configuration (4.4's new design and 4.8+: the type's
  `objectDetails` only), and on 4.8+ the panels of the GUI profile's `adminGuiConfiguration/selfProfilePage` when one
  is configured (4.0 and 4.4 have no such element). Its Assignments tab is `.assignments()` as on `editForm`: a row's
  Unassign is shown by the security enforcer with the user themself as the object (`#adminUnassign` of
  `authorization-ui-3`, request phase), and `.unassign(role).submit().saves()` removes the user's own role: the Save's
  delta is authorized as an unassignment (`#unassign` of the target), then the execution-phase `#modify` of one's
  `assignment`, which midPoint's End user role grants. An item allowed in the request phase only
  (`#modify` with `<phase>request</phase>`) is typed into on 4.0 and 4.8+ and refused at Save ("Not authorized");
  4.4's GUI shows its field read-only. On either, `.set(item, value)` types a value in first (by path of local
  names: `identifier`, `extension/site`). The value is typed as a user types it into the item's field, which the GUI
  picks by the item's type (its panel factories); a value the field takes without a message that is not in the
  field's model afterwards fails the form (`Typed 2026-03-05T10:15:30Z into extension/opened, but its field holds
  2026-03-05T10:15:00Z`), rather than saving without it or with another value, and so does a Save that opens the
  GUI's error page (`Saving opened the GUI's error page PageError: <exception>`: `saved()` is false; typing a
  value that opens it fails the form). By the item's
  type, on every line unless the row says otherwise:

  | Item type | Field | `set` takes | Fails |
  |---|---|---|---|
  | string, PolyString (`name`, `displayName`), multi-line text (`description`) | text field, text area | any value as its text; a PolyString or PolyStringType as its orig; `null` empties it | |
  | `xsd:int`, `xsd:long` | text field | a number or its text (`42`, `"42"`), typed as the field's converter shows it | a fraction (`its field takes a whole number`); a text the field does not read is the field's message (`rejects(item)`) |
  | `xsd:double` | text field (4.8 and later) | a number or its text | 4.0 and 4.4 have no field of `xsd:double` (`the line's GUI has no field of its type`) |
  | `xsd:dateTime` (an extension date, 4.0's `activation/validTo`) | 4.8 and later a date-time picker's text field, 4.0 and 4.4 a date field with hours and minutes (`DateInput`) | a `java.util.Date`, `Instant`, `ZonedDateTime`, `OffsetDateTime`, `XMLGregorianCalendar`, `Calendar`, a `LocalDateTime` / `LocalDate` (in the JVM's time zone) or the ISO text of one; typed in the field's pattern in the JVM's time zone, so the stored instant is the one set, whatever its zone; `null` clears it | a time with seconds (`takes a time to the minute, without seconds; pass ...`): the field has none; a value of another type |
  | `xsd:duration` | text field (`DurationPanel`) | a `javax.xml.datatype.Duration`, a `java.time.Duration` or the ISO text (`"P2D"`) | a text the field does not read is its message; on 4.0 and 4.4 Save opens the GUI's error page (`Saving opened the GUI's error page PageError: java.lang.IllegalArgumentException: soon`) |
  | boolean | three-state drop-down | `true`, `false` (or `"true"`, `"false"`); `null` its undefined choice, which clears the item | a value no choice has, naming the choices |
  | enumeration | drop-down | its XML value (`"enabled"`), its name or the text the drop-down shows | as a boolean's |
  | `xsd:QName` | drop-down of the object types (`DropDownChoicePanelFactory`: any QName item) | an object type's QName, its local name (`"UserType"`) or the text shown (`"User"`) | any other value; a stored value no choice has fails the Save (the GUI's error page) |
  | string of a lookup table (`valueEnumerationRef`) | autocomplete text field | a row's key (`"south"`) or its label (`"South wing"`): the key is stored | |
  | reference (`parentOrgRef`, an extension reference) | the chosen object's name and a chooser popup (`ValueChoosePanel`) | | always (`it is a reference, whose field the GUI sets by the object chosen in its chooser popup`): change it through the model, `t.actor(user).modify(...)` |
  | protected string (`credentials/password/value`) | the GUI's own password panel | | always (`it is a protected string (a password)`): `t.actor(user).modify(user) { it.password("...") }`, or the user's own credentials page, `t.actor(user).selfCredentials()` |

  An item on another panel of the page than the one it opens on (4.4 and later: `activation`, the credentials) has no
  field (`the page shows no field of activation/validTo`). A drop-down field takes the value as the choice a user
  picks: a value no choice has fails the form, naming the choices (`its field has no choice maybe; its choices: [true
  (...), false (...)]`, with the texts the drop-down shows). A multi-valued item takes several values in one form, as a user enters them in its value rows:
  `.set(item, listOf(a, b))` makes its values exactly these (the rows of the other values removed by their remove
  button, a missing value typed into an empty row or a row the item's add button adds, the values already there kept;
  an empty list removes them all), and on `editForm` `.add(item, value)` and `.remove(item, value)` change one value,
  the others kept (removals first; removing a value the item does not have fails). The rules of `set` hold for each
  row: no field, a read-only field (or no add or remove button the user may press) fails the form; several values for
  a single-valued item fail it too. Then:
  - `.validate(item, value)` types the value into the item's field and leaves it: what the field shows, `passes()`,
    `fails()`, `fails(message)` (that message exactly), `failsWithMessageContaining(text)` (a message containing
    the text); also `validated()`: the item has a validation, `passed()`, `message()`,
    `messages()`, `absent()`: why the page has no field to type into.
  - `.submit()` presses Save: `saves()`, `rejects(item)` (not saved, and the item's field shows a message),
    `rejectsWith(message)` (that message exactly, on a field or on the form), `rejectsWithMessageContaining(text)`
    (a message containing the text: midPoint's refusals name objects by OID, `... on role:<oid>(team-1) ...`, so a
    test states the part it knows), `saved()`, `rejected()`, `messages()`, `item(item)`. Both fail listing the
    page's messages. Saving executes the changes through the model as the user: what their authorizations or a
    policy rule refuse is a message of the page too. With progress reporting (midPoint's default, on every line and
    design) the GUI executes the changes in the background; `submit()` waits for that execution to end (however long
    the object template, the policy rules or the connectors take, up to 120 seconds), then lets the page show its
    result or leave, as the browser's poll does, so the object is saved, or the error that failed it is a message,
    by the time `submit()` returns. `outcome()` tells how it ended: `SAVED` (`saved()` true), `REJECTED` (a field's
    message, an error on the page: the validation on saving, the operation's error, a partial error such as
    `Error executing changes` with the result of the failed part, the GUI's error page), `INCOMPLETE` (the execution
    still ran after 120 seconds: no result yet; the next test's reset waits for it, then interrupts it) or
    `UNDETERMINED` (the page neither reported a result nor left, and shows no error). A failed `saves()` names the
    last two (`The form does not save (INCOMPLETE: ...): []`). A form is immutable (each method recording a change
    returns a new one): `submit()` of a form whose derived form nothing used fails, naming the dropped changes (see
    `.unassign` below).
  - `.tabs()` reads the page's tabs as the GUI has them for the user, from the page it renders (once; the claims
    chain on it): `names()` the labels of the tabs shown, in the GUI's order; `hasTab(tab)`, `hasNoTab(tab)`;
    `tab(tab)` one tab (`label()`, `key()`, `identifier()`, `shown()`, `hiddenBecause()`, `subtabs()`); `all()` every
    tab the page has for the object, the hidden ones too. A tab is named by its label as shown (`Governance`, in the
    JVM's locale), its label key (`pageRole.governance`, the same on every line for the built-in tabs: a test that
    names the key reads the same in any locale) or its identifier (4.4 and later the panel's, `orgGovernance`; 4.0 the
    local name of the tab's URI, `focusTabGovernance`). What hides a tab is the line's:
    - 4.4 and later: the details navigation lists every panel of the type's `objectDetails` as the page merges them
      (the GUI's panels, the system configuration's, the user's roles', the object's archetype's), and shows a panel
      unless its `visibility` is `hidden` or `vacant` (`hiddenBecause()`: `visibility hidden`) or its
      `applicableForOperation` is not the page's (`applicableForOperation modify: not on a new object`: Governance,
      Members, History and Cases are not on a new object's page). `subtabs()` are a panel's sub-panels (Assignments:
      All, Role, Organization...), by the same rule.
    - 4.0 (and 4.4's old design, `useNewDesign` false): the user's `adminGuiConfiguration/objectForms` of the type.
      None: every tab; one with `includeDefaultForms`: every tab; else only the tabs whose URI a
      `formSpecification/panelUri` names (`no objectForm of OrgType names focusTabGovernance`). Then the tab's own
      rule: Governance and Members not on a new object (`not on a new object`), Members only for a user who may read
      the members, History only with `#auditRead`. A custom `objectForm` with a `panelClass` is a tab of its
      `title`. No sub-tabs.
    Not authorizations: a tab shown is not a tab whose content the user may change. Whether the configuration hides
    a panel, without opening the page, is `t.actor(user).guiProfile().objectDetails()` (4.4 and later; without the
    object's archetype's part). The rows of the Assignments and Inducements tabs, their Unassign and the tab's "+"
    are `assignments()` / `inducements()`.
  - `.assignments()` and `.inducements()` (`editForm` only) read the rows of the page's Assignments or Inducements
    tab as the GUI renders them for the user (`AssignmentRows`; once, the claims chain on it): the page opened as
    `tabs()` opens it (the items set and the rows removed on the form are not applied), the tab clicked on its "All"
    sub-panel (4.4 and later `allAssignments` / `allInducements`, else the first sub-panel shown; 4.0 and 4.4's old
    design the tab, whose type switch opens on All), the rows of every page of its table. `all()` the rows
    (`AssignmentRow`: `target()`, null for a row without a target such as a construction or a policy rule; `relation()`
    as stored; `label()`, the target's name or what the row holds, `Policy rule ar-rule`; `unassignShown()`);
    `row(target[, relation])`, `hasRow(...)`, `hasNoRow(...)` by the target (a generated name) and relation, none
    meaning `default` (a relation stored as `default` or as none, which the GUI shows alike); `row.hasUnassign()`,
    `row.hasNoUnassign()`; `assignButton()` the tab's "+" (`shown()`, `isShown()`, `isHidden()`, `action()`). A row
    is an assignment as the user reads the object: a user who may not read `assignment` / `inducement` sees no rows,
    and `hasRow`'s failure lists the rows the administrator sees. Archetype assignments and delegations are no rows
    (the GUI's query). The page shows no such tab: `AssertionError` with its tabs.
    - The rule of the row's Unassign and of the "+" is the **security enforcer with the object, request phase**, on
      every line (`#adminUnassign`, `#adminAssign`; the GUI falls back to Wicket's roles only when that call throws):
      an authorization limited to some objects shows them on those objects only, not the members panels' rule (any
      authorization naming the action). `t.actor(user).isAuthorized(action, ref)` asks the enforcer in any phase:
      the same unless an authorization is limited to a phase. Both are disabled once the changed rows reach
      `roleManagement/assignmentApprovalRequestLimit`.
    - `.unassign(target[, relation])` / `.uninduce(target[, relation])` (also `row.unassign()`) press the row's
      Unassign before Save: recorded on the form like `set`, replayed by `submit()` after the items typed in, then Save
      (one Save for both, as a user does). The row is marked deleted and Save's delta deletes the whole assignment, so
      the model authorizes it as an unassignment of the target: `#unassign` with the target and relation in the
      request phase (an inducement's with `orderConstraints` order 1, midPoint's order of an inducement, unless the
      authorization has another) or a `#modify` of the item, then the execution's `#modify`. A refusal is the page's
      message (`saved()` false: `User 'ui-only' not authorized for operation with inducement on
      role:<oid>(team-1) with target role:<oid>(meta-1)`; `rejectsWithMessageContaining("(meta-1)")` without the
      OIDs, `rejectsWith(...)` the whole message). `submit()` fails (`AssertionError`) when the page shows
      the user no such tab, no row of the target (`its rows: ...`) or no Unassign on it (`#adminUnassign is not
      allowed on the object: the security enforcer, request phase`). Several rows in one Save are chained, each
      row's Unassign pressed in turn and Save once: `.unassign(a).unassign(b).unassign(c).submit()` (in a loop
      `form = form.unassign(role)`). The form is immutable: `set`, `add`, `remove`, `unassign` and `uninduce` (and
      `archetype`, `parent`, `memberOf` of `newObjectForm`) return a new form and leave the one they were called on
      as it was. A dropped result (`form.unassign(role)` in a loop, then `form.submit()`) would press Save without
      the rows: on 4.0 the Save of a user with a `#modify` of the object reports `There were no changes to be saved.`
      (a warning: `saved()` true, nothing removed), and a user without one finds Save disabled. So `submit()` of a
      form from which a form was derived that nothing used (no `submit`, `validate`, `tabs`, `assignments` or
      `inducements` of it or of a form derived from it) fails with `IllegalStateException`: `Submitting the form of
      the profile of jack, but 3 changes derived from it were dropped: unassign(Role a), unassign(Role b),
      unassign(Role c). ... This form records no change.` Verified on every line (4.0, 4.4, 4.8, 4.9, 4.10, 4.11) by
      `SelfProfileFormScenarioTest`: three rows of the profile page removed one per Save and in one Save, as a user
      with a `#modify` of an item of themself and as one with the End user role and the Unassign only. Adding a row (the "+" and its popup) is not
      driven: assign through the model (`t.actor(user).modify(...)`) or a members panel.
    - Save itself: 4.4 and later show it only to a user with a `#modify` of the object (`The page shows no Save
      button`, from MID-9380), 4.0 shows it disabled (`The page's Save button is disabled`) until a row of the
      Assignments or Inducements tab is removed (the form removing none, the message says so); a user with only some
      `#modify` (an item, the execution phase) sees it, and the model decides the rest.

  ```kotlin
  t.actor(Users.ROLE_ADMIN).editForm(Roles.TEAM_1).inducements().hasRow(Roles.META_1).row(Roles.META_1).hasUnassign()
  t.actor(Users.ROLE_ADMIN).editForm(Roles.TEAM_1).uninduce(Roles.META_1).submit().saves()
  t.actor(Users.ROLE_ADMIN).editForm(Roles.TEAM_1).set("description", "x").unassign(Orgs.SITE_1).submit().saves()
  t.actor(Users.HELPDESK).editForm(Roles.TEAM_1).assignments().row(Orgs.SITE_1).hasNoUnassign()
  t.actor(Users.HELPDESK).editForm(Roles.TEAM_1).inducements().assignButton().isHidden()
  t.actor(Users.UI_ONLY).editForm(Roles.TEAM_1).uninduce(Roles.META_1).submit()
      .rejectsWithMessageContaining("not authorized for operation with inducement on role:")
      .rejectsWithMessageContaining("with target role:").rejectsWithMessageContaining("(meta-1)")
  ```

  midPoint evaluates an object template's item validation (`item/validation/server`) only in the GUI's form, never in
  the model's operations: an object added through the model API is not validated, so a test of the validation asks
  the form. `validate` records nothing and changes nothing; `submit` stores the object when it saves.

  ```kotlin
  t.newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1")
      .validate("identifier", "a1").fails("The identifier is upper case letters and digits.")
  t.actor("site-admin").newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1")
      .set("name", "cat-1").set("identifier", "CAT1").submit().saves()
  t.editForm(OrgType::class.java, "cat-1").set("costCenter", "CC2").submit().rejects("costCenter")
  t.actor("tagger").editForm(Orgs.SITE_1).set("extension/tags", listOf("a", "b")).submit().saves()
  t.actor("tagger").editForm(Orgs.SITE_1).add("extension/tags", "c").remove("extension/tags", "a").submit().saves()
  t.actor(Users.HELPDESK).editForm(Orgs.SITE_1).tabs().hasTab("Members").hasNoTab("pageRole.governance")
  t.actor("site-admin").newObjectForm(OrgType::class.java).parent("site-1").tabs().hasNoTab("Governance")
  t.editForm(Orgs.SITE_1).tabs().names() shouldContainAll listOf("Basic", "Assignments", "Members", "Governance")
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
  - A hidden empty field ("show empty fields") is shown before typing into it. When pressing it opens the GUI's error
    page instead, the form fails saying so (`pressing "show empty fields" ... opened PageError`): on 4.8.14 for a user
    who may change only some items, whose read-only reference fields fail to render empty.

  The tester brings midPoint's GUI for them, nothing to declare:
  [Classes of the GUI](environment.md#classes-of-the-gui). The GUI's beans load once per JVM, on its first form (not
  midPoint's web application: no initial import, filters or web server). Not covered: the browser's own validations and scripts,
  wizards (a resource's, an object type's), and pages other than the objects' details pages and the credentials page
  below.
- `t.actor(u).selfCredentials()` is the user's own credentials page (the side menu's Credentials,
  `/self/credentials`, `#selfAll` or `#selfCredentials`; a user who may not open it fails:
  `nobody may not open /self/credentials`), **run by the GUI itself** as the forms are (`SelfCredentialsPage`).
  `.currentPassword(p)`, `.newPassword(p)` (typed into both fields; `newPassword(p, repeated)` for a mismatch),
  `.select(target)` / `.deselect(target)` (a row by its resource, name or OID: `"LDAP"`, the user's own name) are
  typed and clicked as the user does; `.open()` reads the page after them: `asksCurrentPassword()` (the security
  policy's `passwordChangeSecurity`: unset, it asks on every line), `targets()` / `target("LDAP")` (the user
  themself, then each linked account with its resource; `selected()`, `selectable()`, `note()` as the page shows
  them; a row's own policy in its `requirements()`), `requirements()` / `unmetRequirements()` (the user's password
  policy's limitations the page lists for the typed password; 4.0 lists none). `.submit()` presses the page's Save,
  which executes the page's own deltas through the model as the user (the user's `#changeCredentials` / `#modify`, the
  password policy and history, the self-service channel). A save the password policy or the authorizations refuse
  changes nothing; a partial error (4.4+: one target failed) may still have changed the user's own password with
  `saved()` false, and 4.0 reports `saved()` false when the home page it opens after saving is the GUI's error page.
  `saves()`, `rejects()`, `rejectsWithMessageContaining(part)` (recommended: it reads alike on every line) and
  `rejectsWith(m)` (exact: a 4.8+ toast is `Save password: Password not changed. Old password is incorrect.`, 4.0
  and 4.4 show the body only, which 4.8+ also list, line by line) are over `messages()`: what the browser shows, the page's feedback, its toasts,
  each row's result when the browser shows the table (`Identity Manager Repository (jdoe): FATAL_ERROR: Provided
  password does not satisfy the policies: The value was recently used.`). 4.8+ with the table not listed, the
  model's refusal shows only the toast `There were some errors during processing, see result table for details.`
  and the browser renders no table (`rowResultsShown()` false): the policy's message is then in `targetResults()`
  only, not in `messages()`. `page()` is the page after saving as the tester renders it again. By line:

  | | 4.0 | 4.4 | 4.8 to 4.11 |
  |---|---|---|---|
  | Page, Save | `web.page.self.PageSelfCredentials`, its Save; a saved password goes to the home page | as 4.0, the page stays with each row's result | `gui.impl.page.self.credentials.PageSelfCredentials` (`PropagatePasswordPanel`), its Change password |
  | Current password | `oldPassword`, asked when `passwordChangeSecurity` is unset or `oldPassword` | as 4.0, and `oldPasswordIfExists` when the user has a password | `currentPassword`, as 4.4 |
  | Targets | rows selected by their icon: the user selected, an account not selected (or by the mapping, with a password outbound: `deselect` fails while the user's row is selected) | check boxes, the rows selected; disabled (and not selected) without the password capability, or as `propagationUserControl` says | as 4.4, the table listed only with "Change password on individual systems" ticked (`propagationUserControl` `userChoice` or `identityManagerMandatory`, or a `select`/`deselect`, which ticks it); not listed, its rows are still saved as selected (`listsTargets()` false) |
  | Messages | feedback | feedback (the result in the rows' column) | toasts and the rows' column |

  Not covered: the security questions tab, 4.8's password hint field (shown with `ALWAYS_CONFIGURE`), the rows'
  policy validation column beyond its requirements, Back, and 4.8+'s `propagationUserControl` `mapping` (no
  "Change password on individual systems" check box, so `select` / `deselect` fail); the page's texts are in the
  JVM's locale, not the user's.

  ```kotlin
  t.actor("jdoe").selfCredentials().currentPassword("Initial.12345").newPassword("Changed.12345").submit().saves()
  t.actor("jdoe").selfCredentials().currentPassword("Wrong.12345").newPassword("Changed.12345").submit()
      .rejectsWithMessageContaining("Old password is incorrect")
  // 4.4 shows the policy's message in the table; 4.8+ without the table listed, in targetResults() only
  t.actor("jdoe").selfCredentials().select("LDAP").currentPassword("Initial.12345").newPassword("Short.1").submit()
      .rejectsWithMessageContaining("Minimal size (10) is not met")
  t.actor("jdoe").selfCredentials().open().target("LDAP").selected() shouldBe true
  t.actor("jdoe").selfCredentials().deselect("LDAP").currentPassword("Initial.12345").newPassword("Changed.12345")
      .submit().saves()
  ```
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
  (the users list) is `canOpen`. The rest of the menu (its sections and pages, as the user sees them) is
  [`sideMenu()`](#the-side-menu).
- A failed assertion after it shows the user's views, features and links.

### The side menu

`sideMenu()` is the GUI's side menu (the left sidebar) as the user sees it, built by midPoint's GUI itself for the user
logged in (a page rendered in the JVM, as the forms are: no browser, nothing to declare):

```java
SideMenu menu = t.actor(Users.JACK).sideMenu();
menu.hasSection("SELF SERVICE").hasNoSection("ADMINISTRATION")
        .hasItem(MenuPage.REQUEST_ACCESS)                        // "Request a role" (4.0, 4.4) / "Request access" (4.8+)
        .hasNoItem(MenuPage.USERS);
assertThat(menu.labels()).containsExactly("Home", "Profile", "Credentials", "Request access");
assertThat(menu.item(MenuPage.REQUEST_ACCESS).url()).isEqualTo("/self/requestAccess");
t.actor(Users.HELPDESK).sideMenu().hasItem("/admin/users").hasNoItem(MenuPage.ROLES);
assertThat(t.actor(Users.HELPDESK).canOpen("/admin/users")).isTrue();                  // the item's page
```

- The menu is read as the GUI built it: its sections (`SELF SERVICE`, `ADMINISTRATION`, the configuration section
  (`Configuration` or `CONFIGURATION`, by the line's texts: name it by its key `PageAdmin.menu.top.configuration`),
  `ADDITIONAL`; 4.11 `INTEGRATION`), each section's main items ("Users", "Request access") and their sub-items ("All
  users", a collection view, "Organization tree"), in the GUI's order (`sections()`, `items()`, `labels()`,
  `section(x).items()`). A section none of whose items the user sees is not shown; a heading whose sub-items are all
  hidden neither.
- An item is named by its page, the same name on every line (`MenuPage.REQUEST_ACCESS`: `/self/assignmentShoppingCart`
  on 4.0 and 4.4, `/self/requestAccess` on 4.8+; `MenuPage.USERS`, `ORG_TREE`, `AUDIT_LOG`...), by any URL its page is
  mounted at (`/admin/users`), by its label as the GUI renders it in the JVM's locale (`"All users"`) or by its label
  key (`PageAdmin.menu.top.users.list`). An item has `label()`, `key()`, `url()`, `page()`, `params()` (a collection
  view's `collectionName`), `actions()`, `section()`, `parent()`, `items()`.
- **The menu's rule is not `canOpen`'s.** The GUI lists an item when *any* of the user's authorizations names one of
  its page's actions (the `@PageDescriptor` actions, the users list `#usersAll` or `#users`, a type's views `#usersAll`
  or `#usersView`), `#guiAll` or `#all`, whatever the authorization's decision, phase or object (Wicket's roles of the
  session are every action of every authorization). Opening the page is the security enforcer's decision
  (`canOpen`: one allowed and none denied). So a page a `deny` authorization names is listed and refused; pair
  `hasItem` with `canOpen`. 4.11 and later first hide an item whose action a `deny` authorization names, whatever
  its object or phase (`SecurityEnforcer.isAuthorizationDenied`). On every line also: a page an experimental descriptor marks (Consents) needs the GUI
  profile's `enableExperimentalFeatures` (4.4+; 4.0 has no Consents item); the `ADDITIONAL` section is the profile's
  `additionalMenuLink`s: a link to a GUI page when the user may open it (the page's actions), an external link to
  anyone who has it (4.4: never, its item needs a page); the Cases items need the case (workflow) manager enabled; a
  type's "All users" needs its default view not hidden (4.8+); "New user" needs `#user` or `#usersAll` (4.0: and the
  enforcer's `#add` of a new user, request phase).
- A failed claim says why: the page's actions, which of the user's authorizations name one (or that none does), or
  that the page is not an item of this line's menu, and lists the menu; the test's failure then lists the user's
  authorizations. [Line notes](lines.md#line-notes) has the lines' labels, URLs and sections.

All of these for kinds of users at once, as a matrix: [An authorization matrix](authz-matrix.md) (experimental).
