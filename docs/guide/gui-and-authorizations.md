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
  `Orgs.SITE_1`, its type from the ref). On either, `.set(item, value)` types a value in first (by path of local
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
  | protected string (`credentials/password/value`) | the GUI's own password panel | | always (`it is a protected string (a password)`): `t.actor(user).modify(user) { it.password("...") }` |

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
    `fails()`, `fails(message)` (also `validated()`: the item has a validation, `passed()`, `message()`,
    `messages()`, `absent()`: why the page has no field to type into).
  - `.submit()` presses Save: `saves()`, `rejects(item)` (not saved, and the item's field shows a message),
    `rejectsWith(message)` (on a field or on the form), `saved()`, `rejected()`, `messages()`, `item(item)`. Saving
    executes the changes through the model as the user: what their authorizations or a policy rule refuse is a
    message of the page too.
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
      message (`rejectsWith(...)`, `saved()` false: `User 'ui-only' not authorized for operation with inducement on
      role:<oid>(team-1) with target role:<oid>(meta-1)`). `submit()` fails (`AssertionError`) when the page shows
      the user no such tab, no row of the target (`its rows: ...`) or no Unassign on it (`#adminUnassign is not
      allowed on the object: the security enforcer, request phase`). Adding a row (the "+" and its popup) is not
      driven: assign through the model (`t.actor(user).modify(...)`) or a members panel.
    - Save itself: 4.4 and later show it only to a user with a `#modify` of the object (`The page shows no Save
      button`, from MID-9380), 4.0 shows it disabled (`The page's Save button is disabled`); a user with only some
      `#modify` (an item, the execution phase) sees it, and the model decides the rest.

  ```kotlin
  t.actor(Users.ROLE_ADMIN).editForm(Roles.TEAM_1).inducements().hasRow(Roles.META_1).row(Roles.META_1).hasUnassign()
  t.actor(Users.ROLE_ADMIN).editForm(Roles.TEAM_1).uninduce(Roles.META_1).submit().saves()
  t.actor(Users.ROLE_ADMIN).editForm(Roles.TEAM_1).set("description", "x").unassign(Orgs.SITE_1).submit().saves()
  t.actor(Users.HELPDESK).editForm(Roles.TEAM_1).assignments().row(Orgs.SITE_1).hasNoUnassign()
  t.actor(Users.HELPDESK).editForm(Roles.TEAM_1).inducements().assignButton().isHidden()
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
