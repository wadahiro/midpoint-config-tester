# Developing midpoint-config-tester

For the developers of the tester itself: how this repository is built, released and extended, and what its own tests
established about midPoint. Using the tester is the [README](../README.md).

## Building this repository

```
pom.xml               root POM: midPoint versions, repositories, plugin versions, pgmem profiles
tester/
  pom.xml             tester parent: adds core/ to every line module, surefire settings
  core/               line-neutral sources (public API, runtime, recorder) and the shared tests
  base-4.0/           parent of the JDK 11 lines: approvals, certification, script hook, notification transport
  midpoint-4.4/       4.4: dependencies, harness, Spring context, recorder hook, drivers, SQL, baseline objects
  base-4.8/           parent of 4.8+ (native repository): harness, Spring context, recorder hooks, drivers
  midpoint-4.8/       LineSupport, SQL and baseline objects of 4.8
  midpoint-4.9/       LineSupport, SQL and baseline objects of 4.9
  midpoint-4.10/      LineSupport, SQL and baseline objects of 4.10
  midpoint-4.11/      LineSupport, SQL and baseline objects of 4.11
  static/             static checks (own parent): core/ sources and tests, midpoint-<line>/ per line
  kotest/             the Kotest layer; scenarios/: its tests, specs built as a config repository on every line
parent/               parents of config repositories: common pom.xml, midpoint-<line>/ per line
examples/             example config repositories on the 4.8 parent, tested in the build: getting-started/ (the
                      README's, on mirror connectors, JUnit and Kotest), hr-ldap/ (real CSV and LDAP connectors)
docs/next-design.md   the `MidPointConfigTester` API: design, decisions, what each phase built
docs/redesign.md      the PoC: design, decisions, findings
```

The tests of a line module (`tester/core/src/test/java`) are the `@MidPointConfigTest` tests (`configtester`: the sample
deployments of `tester/core/src/test/resources/scenarios`) and the engine tests of the PoC API (`tester`: reset,
Quartz hold and task runs, time shift, file redirection, live sync, mirror options).

```bash
mvn test -pl tester/midpoint-4.8 -am                       # one line
mvn test -Pnon-lts -pl tester/midpoint-4.8,tester/midpoint-4.9,tester/midpoint-4.10,tester/midpoint-4.11 -am  # 4.8+ lines
mvn test -pl tester/static/midpoint-4.8 -am                # static checks of one line
```

Patch versions can be overridden: `-Dmidpoint44.version=...`, `-Dmidpoint48.version=4.8.11`,
`-Dmidpoint49.version=...`, `-Dmidpoint410.version=...`, `-Dmidpoint411.version=...` (`RuntimeTest` checks that the line matches the release the module is built against).

### Maven Build Cache

The build of this repository can use the Maven Build Cache (`.mvn/extensions.xml`, `.mvn/maven-build-cache-config.xml`),
off unless `-Dmaven.build.cache.enabled=true` (for example in `MAVEN_OPTS`): a module whose inputs did not change since
a passing build is not built and tested again. Inputs are the module's `src/` and effective POM plus `tester/core` for
every line and static module, the `base-*` sources for their lines and `static/core` for the static modules. The cache
does not see `-D` properties, so `static.dir`, `static.schemaDir`, `tester.*`, `pgmem.binary` and `-Dtest` switch it
off for the build (profiles `no-build-cache-*` of the root POM); a module served from the cache runs no tests and
writes no `target/tester/` reports. `-Dmaven.build.cache.skipCache=true` builds everything once without reading it.

### JDKs

Each line runs its tests on the JDK that runs Maven, one its midPoint supports (the release notes of the line); the
line's parent checks it before the tests (`tester.jdk`, maven-enforcer), not with `-DskipTests`:

| Line | Recommended | Supported |
|---|---|---|
| 4.4 | 17 | 11, 17 |
| 4.8, 4.9, 4.10 | 21 | 17, 21 |
| 4.11 | 25 | 21, 25 |

A config repository pins the JDK of its line where Maven runs: a `mise.toml` (`[tools] java = "temurin-21"`) or a
`.sdkmanrc` in the repository, or `JAVA_HOME`; nothing per machine. The tester's own build needs JDK 17 or later to
compile (4.8+ and the Kotest layer), so a line is built and tested on its own, with Maven on its JDK, as CI does (one job
per line):

```bash
JAVA_HOME=<jdk-17> mvn test -pl tester/midpoint-4.4,tester/static/midpoint-4.4 -am
JAVA_HOME=<jdk-21> mvn test -pl tester/midpoint-4.8,tester/static/midpoint-4.8 -am
```

A build that only compiles and packages every line (`-DskipTests`, as the release does) runs on JDK 21.

### Publishing

[semantic-release](https://semantic-release.gitbook.io/) releases on every push to `main`
(`.github/workflows/release.yml`, `.releaserc.json`):

1. The tests of all lines (`ci.yml` with `-Pnon-lts`).
2. The next version from the [Conventional Commits](https://www.conventionalcommits.org/) since the last tag: `fix:`
   a patch, `feat:` a minor, and `BREAKING CHANGE:` a minor too while the tester is 0.x (the `releaseRules` of the
   commit analyzer in `.releaserc.json`; remove the rule to release 1.0.0); nothing for only `build:`, `docs:`,
   `chore:`, `test:` and the like. `next` goes to `main` as one squashed commit: its message gives the release type
   and the release notes (`feat:` with the changes in its body and a `BREAKING CHANGE:` footer when there is one, not
   `chore:`). After the release, `next` is reset to `main`. The tag `v0.0.0` is the baseline, so the first release was
   0.1.0.
3. `@terrestris/maven-semantic-release` sets the version in every module and runs `mvn deploy -DskipTests` (all lines,
   through `MAVEN_ARGS`). The example is not published.
4. The release commit (the versions and `CHANGELOG.md`, `[skip ci]`), the tag `v<version>`, a GitHub release, and a
   commit of the next snapshot version.

The deployment goes to the branch `mvn-repo`: a Maven repository (deployed into its checkout, then pushed), read at
`https://raw.githubusercontent.com/wadahiro/midpoint-config-tester/mvn-repo` without credentials.

With the repository variable `RELEASE_TO_CENTRAL` set to `true` it goes to Maven Central instead (Central Portal,
namespace `io.github.wadahiro`): the profile `release` adds the sources and javadoc jars, signs with GPG and uploads
the bundle with the `central-publishing-maven-plugin`, which waits until Central has published it. Its secrets:
`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (the Central Portal's user token), `GPG_PRIVATE_KEY` (armored,
its public key on a key server such as `keys.openpgp.org`) and `GPG_PASSPHRASE`. By hand: the token as server
`central` in `settings.xml`, `mvn versions:set -DnewVersion=<version> -DprocessAllModules -DgenerateBackupPoms=false`,
`mvn deploy -P release,non-lts -DskipTests`, then publish the deployment in the Central Portal;
`-Dgpg.skip -Dmaven.install.skip -DskipPublishing` builds the staging (`target/central-staging`) only.

### Adding a midPoint line

The procedure is the Agent Skill [`.claude/skills/add-midpoint-line`](../.claude/skills/add-midpoint-line/SKILL.md),
which an AI coding agent (or a person) follows: API research with a probe script, the module layout, the recorder hook
and identity placement, the line drivers, and the verification (shared tests, static checks, CI). [`docs/line-anatomy.md`](line-anatomy.md) lists the files
of every line and the behaviour differences found; adding a line adds its column there.

## Line differences and the tests behind them

The README's [Line notes](guide/lines.md#line-notes) were found by the PoC tests: each pinned both behaviours
(`Lines.atLeast(mp, "4.9")`) with a comment naming the midPoint code. Those tests were removed in phase 4; the findings
stand (`git show 1e39281^:tester/core/src/test/java/com/github/wadahiro/midpoint/tester/<Test>.java`). Read them before
porting expectations between lines. The test of each row:

| Difference | Pinned in |
|---|---|
| A report's CSV export | `ReportScenarioTest` |
| Correlation | `RecorderTest.correlationExpression` |
| `focus` of a correlation or reaction condition without an owner | `SynchronizationTest.unmatchedStaffIsAdded` |
| Import of an account no object type classifies | `SynchronizationTest.serviceAccountsAreNotSynchronized` |
| The 4.4 format: an object type's `baseContext`, the `synchronization` section (condition, correlation, confirmation, reaction conditions), `notificationConfiguration/mail` | `LegacyFormatTest` |
| Import of an account midPoint created (already classified) | `SynchronizationTest.directoryMayDisableButNotEnable` |
| Inbound `focus` variable of a new user | `RecorderTest.inboundMapping` |
| Preview (`mp.preview`) | `RecorderTest.previewThenExecute` |
| Approvers of stage 1, in order | `ApprovalTest.preview` |
| Certification stage opening and closing | `CertificationTest.campaign` |
| An expression in a certification scope's `searchFilter` | `CertificationScopeTest.theSearchFilterSelectsTheCertifiedUsers` |
| Recipient expression returning a string | `HookAndNotificationTest.notificationsPerRunAndPerTest` |
| Mail transports | `MailNotificationTest.htmlMailWithAnAttachmentThroughAnotherTransport` |
| Result of a notifier's or message template's attachment expression | `MailNotificationTest.anAttachmentComputedPerUser` |
| Effective status of a user whose lifecycle state is `archived` (MID-9026: `ARCHIVED` deprecated, never an effective status) | `HrLifecycleScenarioTest.anArchivedUsersEffectiveStatus` (`User.isArchived()`) |
| A notification handler given as `xsi:type="CustomNotifierType"` (its `expression` builds the message) | `HandlerNotifierTest` |
| `recipientAddressExpression` of a transport | `TransportRecipientsTest.theMailTransportAddressesTheUser` |
| A notifier with two recipients | `MailNotificationTest.oneMailPerRecipientFrom48` |
| Dashboards | `ReportTest.dashboardVariation` |
| `schemaHandling/associationType` (`resource-ldap-association-types.xml`) | `AssociationTypeTest` |
| Base contexts of a reference simulated by the `references` capability (real LDAP) | `ReferenceBaseContextTest` |
| Value of a legacy association outbound | `EntitlementTest.associationFromLinkAddsTheMemberToTheGroupOfTheRole` |
| An association added to an account by a primary change (approved by `addAssociationAspect`) | `AssociationApprovalTest.onlyAdminGroupsNeedTheOwnersApproval` |
| `assignmentTargetSearch` mapping, minus set when the target changes | `SearchTest.assignmentTargetSearchFollowsTheCostCenter` |
| Import of a `targetRef` filter with an expression | `VariablesTest.importOfATargetFilterWithAnExpression` |
| `SpringApplicationContextHolder` for scripts | `SampleConfigTest.bulkActionScriptWithASpringBean` |
| A function of a missing library | `SearchTest.functionOfAMissingLibrary` |
| Task archetypes in the baseline | `RuntimeTest.baselineAndWiring` |
| A custom policy constraint (`policyConstraints/custom`) | `PolicyTest.contractorsNeedAnEndDate` |
| Provenance metadata on the value of an object type's association outbound | `ObjectTypeAssociationTest.theAccountJoinsTheGroupOfItsCostCenter` |
| Simulation metrics (domain, value and selection expressions; aggregated metrics with a domain restriction) | `SimulationTest` |
| Approval comments of an approved assignment | `ApprovalCommentsTest.theApprovedAssignmentKeepsTheFormattedComment` |
| Certification comments of a certified assignment (`reviewerCommentsFormatting`, when the campaign closes) | `CertificationCommentsTest.theCertifiedAssignmentKeepsTheFormattedComment` |
| Provenance feed populators of a resource object type (metadata and acquisition items) | `ValueMetadataTest.valuesFromHrNoteTheirRecord`, `valuesOfContractorsComeFromTheAgency` |
| Shadow cleanup of a gone account | `RepositoryTaskQueryTest.theShadowsOfAShadowCleanupQuery` |
| Role membership management, shadow reclassification tasks | `RepositoryTaskQueryTest`, `ResourceTaskQueryTest` |
| A report parameter with several values (a task's `reportParam`) | `ReportTest.severalParameterValues` |
| A correlation confirmation returning no value | `MisconfigurationTest.confirmationMustReturnOneValue` |
| Focus archetype of an object type, `items` and `expression` correlators, confidence thresholds and expressions, correlation cases, indexed items, multi-source items | `CorrelationTest`, `IndexingTest`, `MultiSourceTest` |
| A value policy without `minUniqueChars` | `PasswordReadingTest.uniqueCharactersDefaultToTheMinimalLength` |
| `archetypeRef` of a user of a sub-archetype | `ArchetypeTest.aSubArchetypeTakesThePolicyAndInducementsOfItsSuperArchetype` |
| A user's lifecycle state without a state model: `archived`; `suspended` | `LifecycleStateModelTest.withoutAModelOnlyActiveAndDeprecatedUsersAreEnabled` |
| A model's state with `activeAssignments` false (an existing account) | `LifecycleStateModelTest.anArchivedStateWithoutAForcedStatusLeavesTheContractorEnabled` |
| Security policy of an archetype (`securityPolicyRef`) | `PasswordPolicyTest.privilegedUsersFollowTheSecurityPolicyOfTheirArchetype` |
| Lifecycle state outbound of an account (`lifecycle/lifecycleState/outbound`) | `AccountLifecycleStateTest.anAccountIsDraftUntilTheUserHasAnEmployeeNumber` |
| Import of an entry with an auxiliary object class no construction gives (mappings not `tolerant`) | `AuxiliaryObjectClassTest.withoutToleranceTheImportRemovesTheClass` |
| Confidence expressions of filter and items correlators, of their items' searches, of attribute correlators and template items; an expression in a delineation filter | `CorrelationConfidenceTest`, `ResourceMisconfigurationTest.anExpressionInADelineationFilterIsNeverEvaluated` |
| A script without `<language>` (the initial system configuration) | `FreshInstallationTest` |
| Groovy in a resource, report, role... or an administrator's bulk action (the initial system configuration) | `FreshInstallationTest` |
| The evaluator elements (`c:expressionEvaluator`'s substitution group) | `StaticCheckTest.evaluatorsOfTheLine` |
| prism's message for an item without a definition | `StaticCheckTest.schema` |
| The members panel's "+": shown to every user (4.0); midPoint's assignment holder specification without an archetype (4.0: no relations, 4.4+: none); the org of a new member in `parentOrgRef` (4.0: any relation) | `CreateMemberButtonScenarioTest`, `NewMemberOffersScenarioTest` |
| A new object without a name saved by a user who may not add it (4.0: refused for the name first) | `CreateMemberButtonScenarioTest.theModelRefusesAMemberTheUserMayNotAdd` |

Fixtures are written in the newest format; `tester/core/src/test/resources/fixtures-<line>/` holds variants for that
line and older ones (e.g. resources without `delineation` for 4.4). A configuration you test on several lines
needs the same: midPoint of an older line cannot read a newer format. A newer line still reads parts of an older one
(deprecated): `LegacyFormatTest` imports the 4.4 variants on 4.8+ too (`Fixtures.legacy`).

Checked on every line with no difference to pin: the timed actions of an approval stage run by the trigger scanner, a
delegation's `approverExpression` adding the delegate, the conditions of timed actions never evaluated
(`TimedActionsTest`); an approval case named by the triggered constraint's short message when the action has no
`approvalDisplayName`, a process specification's display name evaluated with `object` after the change, long messages
never evaluated (`ApprovalNamesTest`); the approvers of `addAssociationAspect` from its `approverExpression`, its
`automaticallyApproved` never read (`AssociationApproversTest`); a mail transport's `recipientFilterExpression`
effective only with `redirectToFile`, a file transport's never evaluated (`TransportRecipientsTest`); a merge
configuration's `default` value expression (`MergeDefaultTest`); a collection stats constraint counting the collection
that holds it (`CollectionStatsTest`); a simulation metric's `selectionRestriction`, a domain filter with an
expression matching nothing, 4.8+ (`SimulationPredicatesTest`); an import task's query of resource objects replaces the
activity's own by default (`queryApplication` `replace`: it must then name the resource, else "Resource not defined in a
search query"; `append` adds it), a skipped bucket is not searched, auto-scaling takes only the running tasks of its
query, an internal operations report without a fast filter reports nothing, an expression in a record filter of an
activity report is never evaluated and the filter matches no record (`ResourceTaskQueryTest`, `RepositoryTaskQueryTest`,
`ActivityTracingTest`); reports and dashboards take the columns of a view, not its collection (whose filter is never
evaluated), and a dashboard report cannot be imported (`ReportViewTest`); an object state constraint decided by a bulk
action or a message expression (`ObjectStateConstraintTest`); the lockout status outbound of a simulated lockout
attribute, and the existence, lockout status and lifecycle inbounds never evaluated (`LockoutTest`); the base context
filter of an object type, evaluated with no variables when provisioning searches (`BaseContextTest`, real LDAP;
`objectType/baseContext` in the 4.4 format, `delineation/baseContext` in 4.6+); the buckets of a tailored segmentation of
the explicit type (`ResourceTaskQueryTest`); the synchronization sorter classifying
accounts (`SynchronizationSorterTest`); an inbound in a construction refused
(`ResourceMisconfigurationTest.anInboundInAConstructionIsRefused`); the auxiliary object class inbound of a tolerant
object type, the outbound never evaluated (`AuxiliaryObjectClassTest`); an expression in the filter of an object state
constraint or of a lifecycle state's forced assignment is never evaluated, the filter matches nothing
(`MisconfigurationTest`; forced assignments themselves are applied); certification reviewer comments formatted when the
campaign closes, the formatting evaluated for every decision, also one without a comment (`CertificationCommentsTest`);
an `asIs` password inbound gets the password of a connector that returns it, the projection's own password policy
(`objectType/securityPolicyRef`) fails a short password with a partial error (`PasswordReadingTest`, on the mirror and
the real CSV connector); the LDAP connector's `passwordReadStrategy` as midPoint reads the account (`LdapPasswordTest`,
mirror and real); extension items in mappings, changes and the merged `assignment` variable (`ExtensionTest`); a
collection filter with `$focus` fails ("No variable with name focus in collection filter",
`MisconfigurationTest.collectionFilterHasNoFocus`); a task's bulk action search applies its owner's authorizations,
filters evaluated with the owner as `subject` (`AccessTest.taskSearchesWithItsOwnersAuthorizations`); activation
(`ActivationTest`, `ActivationScenarioTest`, on mirrors and the real CSV and LDAP connectors): the status outbound's
input (`effectiveStatus`), no status provisioned without a status outbound, `validFrom` / `validTo` of users and
assignments applied by the validity scanner, a simulated status attribute, disable instead of delete (`disableReason`
`#deprovision`), the delayed delete's trigger on the shadow and its deletion by the trigger scanner; the JVM's wall
clock moving with `t.clock()` for scripts and Java code, Quartz on the real time (`ClockTest`, on JDK 17 for 4.4,
JDK 21 for 4.8 and later).

What the tester does differently per line (see also [line-anatomy.md](line-anatomy.md)):

- **4.4**: there is no `ConfigurationItemOrigin`: a mapping is placed by finding its value in the origin object (two
  prism-equivalent mappings in one object fall back to the name or the canonical form).
- **4.9 / 4.10**: the tester clears a repository cache 4.9+ does not clear after a database rollback
  (`ExtItemCache.nameToExtItem`; production never rolls back). 4.9 has the API of 4.10 at every place the tester
  touches; its initial objects are in `repo/system-init/src/main/resources/initial-objects` of the sources.
- **4.10**: resource mappings are copies of single attribute definitions.
- **4.11**: the script executors are renamed (`ScriptFactory`, `ScriptExecutor`; the hook wraps MEL's too), the cache
  invalidation has its own dispatcher, and an expression runs only with a trust descriptor: the tester's ad-hoc
  scripts and the post-initial bulk actions are trusted with the full profile (as `PostInitialDataImport`), a test's
  bulk action as the logged-in user (as the GUI's bulk action page). The ConnId connector factory is injected by its
  class, so the tester's proxy of a connector factory is a subclass on every line.

## How the tester works

What the [user guide](guide/) leaves out because a test does not depend on it.

**The GUI's pages.** `canOpen(url)` matches against an index the build writes from the line's GUI jars
(`tester/gui-pages.tsv` in the line's jar, `GuiPagesIndex`: the page descriptors read from the class files, the URL
mappings loaded), so the GUI is not on the test classpath.

**Typed names of midPoint's initial objects.** `configtester.names.midpoint.Initial*` and `UserItems` are written by
the add-midpoint-line skill's `scripts/initial-names.py` from the initial objects of every line; `InitialNamesTest`
checks on every line that each name resolves after the initial import, that each initial object of these types has a
name, and that each user item is in the line's schema exactly on its lines.

**The file mapping.** At its start the tester attaches a Java agent (Byte Buddy) that instruments three JDK classes:

- every constructor of `java.io.File`: at its end, a path under a mapped directory is replaced by the physical one;
- every constructor of `sun.nio.fs.UnixPath`, the default file system's `Path` on Linux and macOS: the same for its
  bytes (`Path.of`, `Paths.get`, `File.toPath`, `Path.resolve` and thus all of `java.nio.file.Files` follow);
- `java.lang.ProcessBuilder.start()`, which every process the JVM starts goes through: a mapped directory written in
  the command's arguments is replaced where it starts a path (at the start of an argument or after a space, a quote,
  `=`, `;`...). The process's environment is not rewritten; a mapping in the process itself (a bind mount, a
  container) would need root or a container runtime.

The table of mapped directories lives in a class on the bootstrap class path (`FileRemap`), which the instrumented
constructors consult: one volatile read when nothing is mapped, a prefix comparison per mapped directory otherwise; a
physical path is never rewritten again. The line modules of 4.8 and later give the agent with `-javaagent`; 4.4 attaches
it itself.

**The clock.** Byte Buddy advice adds the offset where the JDK reads the wall clock (`Date()`,
`GregorianCalendar(TimeZone, Locale)`, the calendar provider, `Clock.currentInstant()` on JDK 21 or the system clock's
`instant()` on 11 and 17, `millis()`), and midPoint's `Clock` keeps its own override at the same offset (it adds it to
`System.currentTimeMillis()`). Quartz's scheduler thread waits on `System.currentTimeMillis()`, so a trigger made on a
shifted `new Date()` would wait for the real time to catch up: Quartz's own reads of `Date` and `Calendar` are left
unshifted. From the first `advance` until the reset a listener vetoes every firing of the standard trigger of a loosely
bound recurring task (not a pause, which midPoint undoes whenever it synchronizes a task), except the one "Run now"
re-creates to start the task (`TaskRunsTest`).

**Quartz after a reset.** The reset restores the task rows, not Quartz: it holds the scheduler, has midPoint
synchronize the jobs with the restored rows (`TaskManager.synchronizeTasks`), puts every recurring trigger back on the
schedule of the restored task (its next run after its last one) and drops the other triggers. A trigger the test
resumed stays resumed for a task the restore made suspended again; the due tasks check the task's state, so it does not
run. The JDBC job store would not spare that work: its rows hold the fire times of the snapshot on the real time, past
by the restore, so every trigger would misfire on the release. A task suspended after it was runnable keeps its standard
trigger paused; a trigger midPoint builds anew on the resume (4.4 re-creates the one of an activity task) starts now,
whatever the misfire action. Quartz's misfire threshold (60 s) is not applied.

**The tester's own tests on 4.11.** The shared tests and fixtures script in Groovy, as a deployment upgraded to 4.11:
the module passes `tester.safeScriptingLanguagesOnly=false`, and the deployments without a system configuration of
their own take, on 4.11 only, the bulk action of `tester/core/src/test/resources/scenarios/groovy-4.11`, which removes
the default script language and the expression profiles of the initial system configuration. The extension schema of
the shared tests is `tester/core/src/test/resources/schema`; their sample deployments are
`tester/core/src/test/resources/scenarios` and the tests of the low-level API run on `scenarios/bare`.
