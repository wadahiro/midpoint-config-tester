# An authorization matrix (experimental)

Experimental: the file format and the API may still change. Each check is the one of [The GUI and
authorizations](gui-and-authorizations.md), made for every cell.

Who may open, find, get, change and assign what is usually a matrix: kinds of users by what they may do. Write it
as YAML; the tester checks every cell (one test each) and writes an HTML report of it, per line. Besides the pages
(the GUI's authorizations), the rows are midPoint's model authorizations: the actions on objects, their assignments
and the items of their forms; and what the personas' GUI profiles make of the side menu.

```java
@AuthzMatrix("classpath:authz-matrix.yaml")      // loads the configuration, as @MidPointConfigTest does
class AuthzMatrixCheck extends AuthzMatrixTest { // the cells are its tests
}
```

`@AuthzMatrix(value = ..., manifest = "classpath:...")` names another manifest.

```yaml
spec: authz-matrix/1
personas:                                  # the columns: users the tester creates with these roles, orgs, archetype
  end-user:  {label: End user, group: Self-service}                 # (the user template applies, as to any user)
  help-desk: {label: Help desk, group: Operations, roles: [Help desk]}
  it-manager: {label: IT manager, roles: [Department manager], orgs: [{org: IT, relation: manager}]}
objects:                                   # samples of what authorizations select as their object (and target)
  it-user: {user: jack, label: A user of IT (costCenter IT)}
  other-user: {user: ann, label: A user of another cost center}
  auditor: {role: Auditor, label: A requestable role}
assignment-targets: [auditor, {object: auditor, relation: owner}]  # what auto assign rows assign (default: every
                                                                   # role, org, service, archetype of the objects)
rows:                                      # the rows: a tree of checks, written or added with auto
  - {group: pages, label: Pages, auto: pages}                       # every page of the GUI, by URL
  - {group: gui, auto: [views, features, menu-links]}               # what the personas' GUI profiles have
  - {group: it-user, object: it-user, auto: [search, get, add, modify, delete, assign, unassign, items]}
  - group: mine
    object: self                           # the persona's own user
    rows: [{check: get}, {id: phone, check: modify telephoneNumber}]
default: N                                 # every cell no rule gives a value
rules:                                     # in order, the later wins
  - name: help-desk-users
    for: [help-desk]                       # or "*"
    rows: [pages/admin/users, it-user/search, it-user/items/modify/telephoneNumber]
    value: Y                               # or per line: {default: N, "4.8": Y}
    note: the users of IT; the telephone number is all they change
critical: [it-user/items/modify/assignment, it-user/delete]
```

- **Checks**: `open <url>` (as `canOpen`; `{it-user}` in a URL is that object's OID, `{self}` the persona's),
  `search`, `get`, `read` (both), `add`, `modify`, `delete` (midPoint's actions on the object, in both phases),
  `assign <object> [as <relation>]` and `unassign ...` (the row's object is the focus; as `Actor.canAssign`), `read
  <item>` and `modify <item>` (the object's form). An item of an object the persona does not get is `-`, not
  applicable: it matches N. From the persona's GUI profile (`Actor.guiProfile()`): `view <type>/<identifier>`,
  `feature <identifier>`, `menu-link <label>`.
- **The GUI's configuration** is the persona's GUI profile as midPoint compiled it at login (the system
  configuration's `adminGuiConfiguration`, then each of their roles', orgs' and archetypes'). A `view` row is Y when
  the side menu lists the object collection view: the profile has it, the line's menu lists it (`inMenu`: not hidden,
  not the default view), and the persona is allowed the actions the
  menu asks for a type's views (`#usersAll` or `#usersView` for users, not `#users`, which opens the users list; the
  same for orgs, roles, services, resources, cases, tasks; views of other types get no row, a warning). A `feature`
  row is midPoint's `isFeatureVisible`, a `menu-link` row whether the profile has the additional link (the page it
  opens is a page row). The rest of the menu (its sections and pages) is the page rows; a details page's panels
  are not rows: the tester does not start the GUI, whose default panels midPoint adds to the profile.
- **Assignments** are decided as midPoint's clockwork decides a request, then its execution: the request is allowed
  by `#modify` of the `assignment` item or of all items, else by `#assign` (`#unassign`) with the target and the
  relation (`#delegate` too for a deputy); the execution needs `#modify` of the change. An authorization's `target`,
  `relation` and `orgRelation` are thus checked with samples: a persona's `orgs` take a relation
  (`{org: IT, relation: manager}`), a row's target one too (`manager`, `org:manager` or `{namespace}name`).
- **`auto`** adds rows from the running midPoint: `pages`, every page of the GUI that needs an authorization (the
  line's index, grouped by the URL's first segment, ids as in the URL: `pages/admin/users`, `pages/admin/user-any` for
  `/admin/user/**`); on an object, the actions listed and with `items` every item of its type in its form
  (`it-user/items/read/emailAddress`, `it-user/items/modify/telephoneNumber`); `views`, `features` and `menu-links`,
  what any persona's GUI profile has (`gui/views/UserType/people`, `gui/features/orgTreeExpandAll`,
  `gui/menu-links/Service-desk`). So a page, an item or a view nobody thought of is checked too: it is N unless a
  rule says otherwise.
- **Rows in rules** are paths of the tree: `*` is one segment or part of it (`pages/admin/roleNew*`), `**` any, a
  group selects all its rows. The lines have other pages (4.4 has the new GUI's `userNew` beside `user`, 4.11 a
  default home page): a pattern no row of the line matches is a warning when rows are automatic, an
  error otherwise.
- **The file is checked** when read and when run, with its line: values only `Y` or `N` (not YAML's `yes`), unknown
  keys, personas, objects, checks, roles, orgs, items, URLs no page matches.
- **The report** (`<workDir>/authz-matrix/<file>-<line>.html`, one file, nothing loaded from elsewhere): personas as
  columns grouped by `group`, the rows' tree foldable (a folded group shows its count and what is not as expected),
  the cells marked as expected or not (✓ · – ✗ !), a rule's note on its cells (※), critical rows framed when allowed;
  filters: not as expected, with a note, critical allowed, personas differ. A cell shows the rule that gave its
  value (and its line), the note and the persona's authorizations.
- A cell not as expected fails its test with the rule, the note, the persona's authorizations and the report's path.
- **Every check is made as the persona logged in**: with their authorizations as midPoint compiles them at login,
  as the GUI's login does (from every role, org and archetype, assigned or induced, with relations, order
  constraints and conditions as midPoint evaluates them). The report and the failure messages list these, grouped
  by where they come from (`role Department manager`, also when an org induces it).
- **The samples are checked against the selectors.** For each persona, each selector of their compiled
  authorizations (`object` and `target`) that selects more than a type (a filter, `special: self`, `orgRelation`,
  `orgRef`, `roleRelation`, `archetypeRef`), of a type a sample may have: which samples it selects for the persona
  and which not, as midPoint selects them (a search by the filter midPoint makes of the selector alone,
  `Access.selectedBy`; the persona's own user is the sample `self`). With no sample inside or none outside, the
  matrix does not show where the selector ends: a warning (printed, and at the top of the report). The persona's
  details in the report list every such selector with its samples inside and outside; a selector with other clauses
  (`owner`, `assignee`, `delegator`, `tenant`) is listed as not checked.

A matrix of a small sample configuration (about 1700-2000 cells per line, a few seconds) shows what it finds in
midPoint's own initial roles, each kept as a rule with a note: the End user role gets any user with all items, opens
a role's details and the new role page (`#roleDetails`); `#userDetails` opens the new user page too
(`/admin/user/**` matches `/admin/user`); `#users` opens the shadows list (`PageShadows`); a service's details need
`#serviceDetails`; every End user assigns themself the requestable roles but unassigns
none. The Department manager role of the sample assigns the members of the orgs its user manages the requestable
roles, with the default relation only; its `#usersView` shows the managers the users' views in the side menu (the help
desk, with `#users`, opens the users list but sees no views), and its GUI configuration hides a feature the system configuration shows.
