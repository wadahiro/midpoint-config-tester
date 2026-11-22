# Supported lines

The long-term support (LTS) lines of midPoint, and its feature releases since the last LTS line. 4.8 is the latest LTS
line, the one the examples of this guide use.

| Line | Kind | midPoint | Artifact | JDK running the tests | Groovy | Repository |
|---|---|---|---|---|---|---|
| 4.8 | LTS | 4.8.14 | `midpoint-config-tester-4.8` | 17 / **21** | 4.0.24 | native |
| 4.4 | LTS | 4.4.11 | `midpoint-config-tester-4.4` | 11 / **17** | 3.0.19 | native (`sqale`) |
| 4.11 | feature | 4.11.0 | `midpoint-config-tester-4.11` | 21 / **25** | 4.0.31 | native |
| 4.10 | feature | 4.10.5 | `midpoint-config-tester-4.10` | 17 / **21** | 4.0.24 | native |
| 4.9 | feature | 4.9.9 | `midpoint-config-tester-4.9` | 17 / **21** | 4.0.24 | native |

The JDK in bold is the one midPoint recommends for the line. Maven runs on one of the line's JDKs; the parent checks it
before the tests, and the tests run on it. A config repository pins it where Maven runs: a `mise.toml` (`[tools]
java = "temurin-21"`) or a `.sdkmanrc` in the repository, or `JAVA_HOME`.

All artifacts have the group `io.github.wadahiro.midpoint` and the same API (packages
`com.github.wadahiro.midpoint.configtester` and, for the low-level API, `com.github.wadahiro.midpoint.tester`): a test
written for one line compiles and runs on the others. Each line has the parent `midpoint-config-tester-parent-<line>`
for config repositories. What differs
between lines is midPoint's behaviour, see [Line notes](#line-notes).

midPoint keeps static state, so a JVM holds one midPoint line. To test one configuration on two lines, run the same
tests in two Maven modules (or two runs) with different tester artifacts.

### Scripting on 4.11

midPoint 4.11 restricts scripting in two places, and the tester takes both as the server has them:

- `config.xml`, `expressions/safeScriptingLanguagesOnly`: the required `tester.safeScriptingLanguagesOnly` (see
  [Getting started](../../README.md#getting-started)).
- The system configuration. The tester imports the line's initial objects as a fresh installation has them; 4.11's
  system configuration makes MEL the default language of a script without `<language>`
  (`expressions/defaultScriptLanguage`) and gives every object type it names an expression profile without Groovy:
  `configuration` (resources, object templates, function libraries, marks, the system configuration), `reporting`
  (reports, message templates), `safe` (roles, orgs, services, archetypes, policies, collections, dashboards,
  certification); an administrator's bulk action runs in `configuration` (`expressions/defaults`). A deployment
  without a system configuration of its own therefore scripts in MEL only.

A deployment upgraded to 4.11 keeps the system configuration of its database, without these. Its repository puts its
own `000-system-configuration.xml` among the post-initial objects (it replaces the initial one as a whole, as in
production). A script that names no language runs as MEL wherever the
system configuration says so: give Groovy scripts their `<language>`. `mp.script(...)` is Groovy unless
`language(...)` says otherwise (`language(null)`: the system configuration's default). The static checks take a script
without `<language>` in the deployment's default the same way: its own system configuration's
`expressions/defaultScriptLanguage` (Groovy without one), else the line's initial one's; the report says which.

## Line notes

What midPoint does differently between the supported lines, where a test sees it. A test is written for the line its
server runs; one configuration tested on several lines meets these differences.

| Difference | 4.4 | 4.8 | 4.9 | 4.10 | 4.11 |
|---|---|---|---|---|---|
| A report's CSV export | no byte order mark | 4.8.14: starts with a UTF-8 byte order mark (4.8.12: none); `ReportOutput` reads the header without it | not checked | not checked | not checked |
| Correlation | legacy `synchronization` format (fixtures-4.4), no correlation context | `correlation` in the object type; `correlationContext`, `correlatorState` | as 4.8 | as 4.8 | not checked |
| `focus` of a correlation or reaction condition without an owner | null | an empty pre-focus (`UserType` without a name) | as 4.8 | as 4.8 | not checked |
| Import of an account no object type classifies | fails ("No object class found for the shadow") | `classificationCondition` false: imported without a user | as 4.8 | as 4.8 | not checked |
| The 4.4 format: an object type's `baseContext`, the `synchronization` section (condition, correlation, confirmation, reaction conditions), `notificationConfiguration/mail` | the line's format | still read (deprecated) with the same outcomes; the synchronization condition is the classification condition: a service account is imported without a user instead of failing | as 4.8 | as 4.8 | not checked |
| Import of an account midPoint created (already classified) | the synchronization condition is evaluated again | the classification condition is not evaluated | as 4.8 | as 4.8 | not checked |
| Inbound `focus` variable of a new user | not pinned | a `PrismObject` | the `UserType` bean; also the variables `association`, `object` | as 4.9 | not checked |
| Preview (`mp.preview`) | the user is not changed, but sequences advance and `createOnDemand` really creates  | `SIMULATED_PRODUCTION`: nothing written, no simulation result kept | `previewChanges` is `executeChanges` in `SIMULATED_PRODUCTION` mode; as 4.8 | as 4.9 | not checked |
| Approvers of stage 1, in order | the org (candidates) first | in the order of the approver expressions | in the order of the approver expressions | in the order of the approver expressions | not checked |
| Certification stage opening and closing | in the call | in the call | a certification task (waited for; `Evaluation.task()` set) | as 4.9 | not checked |
| An expression in a certification scope's `searchFilter` | never evaluated: the repository gets the filter as it is, an `equal` without a value matches the objects without the item (other users are certified) | never evaluated: the repository gets the filter as it is, an `equal` without a value matches the objects without the item (other users are certified) | evaluated (the stage opens in a search-based activity) | as 4.9 | not checked |
| Recipient expression returning a string | stays a string | a `RecipientExpressionResultType` | as 4.8 | as 4.8 | not checked |
| Mail transports | one, `notificationConfiguration/mail`; `<transport>mail:hr</transport>` is that transport (`TransportRegistry` drops what follows `:`) | named `messageTransportConfiguration/mail` transports, each with its own servers and `defaultFrom` (an unknown `x:y` falls back to `x`) | as 4.8 | as 4.8 | not checked |
| Result of a notifier's or message template's attachment expression | a `NotificationMessageAttachmentType` bean (normalized text: its properties) | a `NotificationMessageAttachmentType` bean (normalized text: its properties) | a container (normalized text: its type only) | as 4.9 | not checked |
| Effective status of a user whose lifecycle state is `archived` (MID-9026: `ARCHIVED` deprecated, never an effective status) | `archived` (also for `proposed`, `draft`), `archiveTimestamp` set | `disabled`, `disableTimestamp` set, no `archiveTimestamp` (a filter on it finds no one) | as 4.8 | as 4.8 | as 4.10 |
| A notification handler given as `xsi:type="CustomNotifierType"` (its `expression` builds the message) | run as a custom notifier (`EventHandlerRegistry` dispatches by class) | every operation that notifies fails (`ClassCastException`: no `EventHandlerType`) | as 4.8 | as 4.8 | not checked |
| `recipientAddressExpression` of a transport | none | the address of a recipient that is a user (e.g. a notifier's default recipient) | as 4.8 | as 4.8 | not checked |
| A notifier with two recipients | one mail to both | one mail per recipient (`notificationSendingStrategy` default `separateNotificationToEachRecipient`) | as 4.8 | as 4.8 | not checked |
| Dashboards | as 4.8 | variations applied in turn: a later false one resets the display | as 4.8 | a true variation's display is kept | not checked |
| `schemaHandling/associationType` (`resource-ldap-association-types.xml`) | rejected (`delineation`) | rejected (`associationType`) | read and run | as 4.9 | not checked |
| Base contexts of a reference simulated by the `references` capability (real LDAP) | none | none | the object's filter evaluated with no variables when the account's associations are read (groups of another unit are not the account's); the subject's never evaluated; a reference type in an object type's `configuredCapabilities` is not read and an association on the object side of an association type is refused ("Association type references attribute ... that does not exist in object type ...") | as 4.9 | not checked |
| Value of a legacy association outbound | `ShadowAssociationType` | `ShadowAssociationType` | `ShadowAssociationValueType` | as 4.9 | not checked |
| An association added to an account by a primary change (approved by `addAssociationAspect`) | a `ShadowAssociationType` in `association`; the condition's `association` is that | a `ShadowAssociationType` in `association`; the condition's `association` is that | a `ShadowAssociationValueType` in `associations/ri:<name>` (seen on 4.10: a legacy `association` delta is ignored) | as 4.9 | not checked |
| `assignmentTargetSearch` mapping, minus set when the target changes | the old assignment once | the old assignment once | twice: as computed (no ID) and as stored | as 4.9 | not checked |
| Import of a `targetRef` filter with an expression | skipped, left to the evaluation | skipped, left to the evaluation | fails: "filter matches no object" (use `resolutionTime=run`) | as 4.9 | not checked |
| `SpringApplicationContextHolder` for scripts | `com.evolveum.midpoint.wf.impl.processes.common` | `com.evolveum.midpoint.model.impl.expr` | as 4.8 | as 4.8 | not checked |
| A function of a missing library | `ObjectNotFoundException` | `ConfigurationException` ("No function library with OID") | as 4.8 | as 4.8 | not checked |
| Task archetypes in the baseline | 507, 508, 509, 511, 520, 521, 530, 531 | as 4.4 | as 4.4 | as 4.4 | as 4.10 |
| A custom policy constraint (`policyConstraints/custom`) | none: an object state constraint with the same expression (`fixtures-4.4/policy`) | `custom` (4.7+) | as 4.8 | as 4.8 | not checked |
| Provenance metadata on the value of an object type's association outbound | none | none | present | none | not checked |
| Simulation metrics (domain, value and selection expressions; aggregated metrics with a domain restriction) | none | computed for the objects a preview task processes, summed in the simulation result | as 4.8 | as 4.8 | not checked |
| Approval comments of an approved assignment | `assignment/metadata/createApprovalComment` and the user's `metadata/modifyApprovalComment` | `assignment/metadata/createApprovalComment` and the user's `metadata/modifyApprovalComment` | the same items in value metadata (`process`) | as 4.9 | not checked |
| Certification comments of a certified assignment (`reviewerCommentsFormatting`, when the campaign closes) | `assignment/metadata/certifierComment` | `assignment/metadata/certifierComment` | in value metadata (`process`) | as 4.9 | not checked |
| Provenance feed populators of a resource object type (metadata and acquisition items) | evaluated: inbound values carry the metadata (their `provenance`, e.g. the `originRef` of an acquisition populator, when the template keeps it: `meta/item[provenance]`) | as 4.4 | as 4.4 | not evaluated: values of resource objects get no value metadata (MID-10514) | not checked |
| Shadow cleanup of a gone account | the deletion of every shadow of its query is notified without reading the resource: situation deleted, the shadow kept, not dead | each shadow is refreshed from the resource: situation deleted, dead | as 4.8 | as 4.8 | not checked |
| Role membership management, shadow reclassification tasks | none | membership management | both | as 4.9 | not checked |
| A report parameter with several values (a task's `reportParam`) | the export fails ("Attempt to get single value from item ... with multiple values") | the expressions get a list: a script filter returning it matches any value, a `path` expression fails ("Unexpected variable value") | as 4.8 | as 4.8 | not checked |
| A correlation confirmation returning no value | fails: "... Couldn't confirm user user" (cause: "Expression returned no value (0) in confirmation expression ...") | fails: "... Couldn't confirm candidate owner: Expression returned no value in confirmation expression for filter correlator '...'" | as 4.8 | as 4.8 | not checked |
| Focus archetype of an object type, `items` and `expression` correlators, confidence thresholds and expressions, correlation cases, indexed items, multi-source items | none (4.6+; indexing and multi-source 4.7+): skipped | candidates restricted to the archetype; tiers; a candidate below `definite` makes the owner uncertain and opens a correlation case; an item indexed by a custom normalization is searched by its normalized value; multi-source items are selected from the authoritative identity | as 4.8 | as 4.8 | not checked |
| A value policy without `minUniqueChars` | unique characters not checked | unique characters not checked | their minimum is `minLength` (StringPolicy) | as 4.9 | not checked |
| `archetypeRef` of a user of a sub-archetype | the assigned archetype | as 4.4 | also its super-archetypes | as 4.9 | not checked |
| A user's lifecycle state without a state model: `archived`; `suspended` | effective status ARCHIVED; suspended: DISABLED, assignments inactive (no account) | DISABLED; suspended: DISABLED, assignments active (the account is kept, disabled) | as 4.8 | as 4.8 | not checked |
| A model's state with `activeAssignments` false (an existing account) | the account is kept | the assignments are inactive: the account is deleted | as 4.8 | as 4.8 | not checked |
| Security policy of an archetype (`securityPolicyRef`) | none: the global policy applies (`fixtures-4.4/security`) | the archetype's password policy replaces the global one | as 4.8 | as 4.8 | not checked |
| Lifecycle state outbound of an account (`lifecycle/lifecycleState/outbound`) | evaluated: the account's lifecycle state (draft, then active; a draft account is provisioned) | refused before the mapping: "Outbound mappings for 'lifecycleState' are no longer supported. Please migrate them to 'purpose' mappings." | as 4.8 | as 4.8 | not checked |
| Import of an entry with an auxiliary object class no construction gives (mappings not `tolerant`) | the class is removed from the entry; the user keeps the inbound's value | the class is removed from the entry; the user keeps the inbound's value | the class is removed; the inbound is evaluated again without it, the user has no value | as 4.9 | not checked |
| Confidence expressions of filter and items correlators, of their items' searches, of attribute correlators and template items; an expression in a delineation filter | none (4.6+): skipped | evaluated per candidate (a correlator's own) or per match metric (a search's, e.g. the Levenshtein distance); the delineation filter's expression is never evaluated, its branch matches nothing | as 4.8 | as 4.8 | not checked |
| A script without `<language>` (the initial system configuration) | Groovy | Groovy | Groovy | Groovy | MEL (`expressions/defaultScriptLanguage`) |
| Groovy in a resource, report, role... or an administrator's bulk action (the initial system configuration) | runs | runs | runs | runs | refused: the object types' expression profiles (`configuration`, `reporting`, `safe`) have no Groovy |
| The evaluator elements (`c:expressionEvaluator`'s substitution group) | | | + association ones | + `filter` | + `complexAttributeSynchronization`, `javaMethodReference`, `null` |
| prism's message for an item without a definition | `Item {ns}descripton has no definition` | `Item {ns}descripton has no definition` | `Item descripton has no definition` | as 4.9 | `Item 'descripton' has no definition` |

A configuration tested on several lines needs a format each of them reads: midPoint of an older line cannot read a newer
format (e.g. resources without `delineation` for 4.4), and a newer line still reads parts of an older one (deprecated).
Overlays in the manifest hold what a line needs differently.

Other line notes:

- **4.0**: the "+" of a members panel is shown to every user who opens the page (only its header menu's Create needs
  `#adminAddOrgMember` or `#adminAddMember`), a button its archetype repeats is shown twice (`newMemberOffers()` has
  it once), and a new member's org is in its `parentOrgRef` with any relation (4.4 and later: with a relation stored
  into `parentOrgRef`, by default `default` and `manager`). A new object without a name is refused for its name
  before its request is authorized.
- **4.0**: the tabs of a details page (`tabs()`) follow the user's `adminGuiConfiguration/objectForms` of the type
  (only the tabs a `panelUri` names, unless `includeDefaultForms`), not an authorization per tab nor `objectDetails`; a
  tab is identified by its URI's local name (`focusTabGovernance`), and has no sub-tabs. 4.4 and later: the panels of
  `objectDetails` (identifier `orgGovernance`), shown by their `visibility` and `applicableForOperation`. 4.4 has both
  designs: the old one (`useNewDesign` false) reads `objectForms` as 4.0 does.
- **4.0, 4.4**: the request-access page (`requestAccess()`) is the shopping cart of the system configuration's
  `roleManagement` (its tabs, the role catalog's org tree), any user the requester's search finds may be the target,
  and a role held is told by the target's assignments. 4.8 and later: the wizard of the GUI profile's
  `accessRequest` (`roleManagement/roleCatalogRef` is not read), targets narrowed by the object part of the
  requester's `#assign` authorizations, a role held told by `roleMembershipRef`. 4.4's wizard does not exist (4.6+).
- **4.0**: the request-access page's Submit for several targets (`request(user1, user2).assign(role)`) adds its
  execute-changes task through the model as the requester, who needs `#add` of `TaskType` for it: midPoint's End user
  role grants it for a self-owned execute-changes task (`self-owned-task-add-execute-changes`); a requester without
  that (or with a deny) is refused at Submit. 4.4 persists its iterative change execution task with no check; 4.8 and
  later submit their explicit change execution privileged (`runPrivileged` adds `#all` to the requester's own
  authorizations: no allow is needed, but a deny of theirs, `#add` of `TaskType`, still refuses the Submit). On every
  line the task runs as the requester and applies the cart's
  additions to each target with the page's options (`executeImmediatelyAfterApproval` from
  `roleManagement/defaultExecuteAfterAllApprovals`, the comment; `initialPartialProcessing` only in 4.8+'s
  requests), named `com.evolveum.midpoint.web.page.self.PageAssignmentsList.requestAssignments` (4.0, 4.4) or
  "Requesting access for N users" (4.8+).
- **4.0**: the user's own profile page (`selfProfileForm()`) is `PageSelfProfile` (`/self/profile`), the user
  details page of the logged-in user with the execute options hidden; 4.4's `PageSelfProfile` sends to the design's
  `PageUserSelfProfile` (`/self/profile/user`, the new design's `/self/profile/userNew`); 4.8 and later open
  `PageUserSelfProfile` (`/self/profile/user`). 4.4's new design and 4.8+ hide Delete, Change archetype and Edit raw
  and apply no archetype's details configuration; 4.8 and later show the panels of the GUI profile's
  `adminGuiConfiguration/selfProfilePage` when configured (no such element on 4.0 and 4.4).
- **4.0, 4.4**: the user's own credentials page (`selfCredentials()`) is `web.page.self.PageSelfCredentials` with its
  Save; 4.0 selects a target by its icon (an account not selected by default) and goes to the home page after a saved
  password, 4.4 by its check box (every target selected by default). 4.8 and later: `PropagatePasswordPanel` and its
  Change password, the targets listed only with "Change password on individual systems" ticked (not listed, a refused
  save shows only the toast pointing to the table: the policy's message is in `targetResults()`, not `messages()`),
  its messages toasts; 4.4+ list the password policy's requirements for the typed password and each target's result.
- **4.4**: a form's field of an item the user may modify in the request phase only is read-only in its GUI; 4.0 and
  4.8+ let the user type into it and refuse the Save in the execution phase ("Not authorized").
- **4.0, 4.4**: the side menu (`sideMenu()`) lists "Request a role" (`PageAdmin.menu.request`,
  `/self/assignmentShoppingCart`); 4.8 and later "Request access" (`PageRequestAccess.title`, `/self/requestAccess`).
  The configuration section's text is the line's localization's ("Configuration", `CONFIGURATION`: name it by its
  key `PageAdmin.menu.top.configuration`); 4.0's and 4.4's "Audit Log Viewer" is a sub-item of Reports (4.8+: an item
  of its own); 4.0 has no Consents item (4.4+: an experimental page, listed with the GUI profile's
  `enableExperimentalFeatures`). 4.9 and later add Policies, Analytics and Schemas; 4.11 an `INTEGRATION` section
  (Applications, Resources, Connectors) and Applications under ADMINISTRATION. The rule is the same on every line: an
  item is listed when any of the user's authorizations names one of its page's actions, `#guiAll` or `#all`, whatever
  the decision (a denied page is listed and refused, `canOpen` false), except that 4.11 and later first hide an item
  whose action a `deny` authorization names (`SecurityEnforcer.isAuthorizationDenied`, whatever its object or phase);
  4.0 decides it when the menu renders (the items the GUI hides are read with why), 4.4 and later when it builds the
  menu. An `additionalMenuLink` to an external URL is listed on 4.0 and 4.8+, not on 4.4 (its item needs a GUI page);
  a link to a GUI page needs the page's actions on every line. 4.0 shows "New user" by its page's
  actions (`#user`, `#usersAll`) and the enforcer's `#add` of a new user; 4.4 and later by the page's actions alone.
- **4.0**: a details page's Save is shown and disabled for a user without a `#modify` of the object; 4.4 and later
  hide it (MID-9380). The rows of the Assignments and Inducements tabs (`assignments()`, `inducements()`) are read
  from the tab itself (its type switch opens on All); 4.4 and later from the `allAssignments` / `allInducements`
  sub-panel. The rule of a row's Unassign and of the "+" is the same on every line (the security enforcer with the
  object, request phase).
- **4.0**: a members panel's Assign and Unassign (`membersPanel`, `governancePanel`, `orgTreeMembersPanel`): the
  Governance panel's actions are its own (`#adminAssignGovernance`, `#adminUnassignGovernance`; 4.4 and later the
  Members panel's, `#adminAssignOrgMember`...), the toolbar's Assign (the icon right of the "+") is shown to every user
  (only the header menu's Assign needs the action), a row has no menu (a row's `unassign` is the selection's task, its
  relation the dialog's), the task is an iterative scripting task (`Add.members`, `Remove.SELECTED.members`) checked
  for `#executeScript` when it starts. 4.4: an activity task switched to the background, `#executeScript` too; the
  row's Unassign deletes the assignments through the model, no task. 4.8 and later: the task is submitted
  (`ModelInteractionService.submit`) after `authorization-bulk-3#assign` or `#unassign` in the execution phase; 4.11
  gives it the user channel, and a fresh 4.11 system configuration's `prohibitive` profile of a user's bulk actions
  refuses `assign` and `unassign` on every member.
- **4.4**: accounts are imported by searching the shadow and `importFromResource`; scanners and report exports are
  tasks switched to the background. `why()` places a mapping by finding its value in its object, so two equal mappings
  in one object are told apart by their name only.
- **4.11**: the default home page (`/home/default`, `#selfAll` among its actions), `displayName` of users, and the
  restrictions of scripting ([Scripting on 4.11](#scripting-on-411)).
