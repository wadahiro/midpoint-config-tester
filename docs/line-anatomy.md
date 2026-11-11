# Anatomy of a line

What each line of the tester (`tester/`) consists of, how the lines differ, and where. Paths are relative to the
repository root; Java classes are in package `com.github.wadahiro.midpoint.tester.internal` unless noted. Adding a line
(the skill [`.claude/skills/add-midpoint-line`](../.claude/skills/add-midpoint-line/SKILL.md)) starts from this file and
ends by adding the line's column to every table here.

Most behaviour differences below were found and pinned by the PoC tests of `tester/core/src/test/java/.../tester`.
Phase 4 of the next API removed those tests, except the engine tests (`RuntimeTest`, `ResetTest`, `TaskRunsTest`,
`ClockTest`, `FilesTest`, `LiveSync*Test`, `MembershipTest`, `GoldenTest`); the scenario tests (`configtester`) are
the suite now. A test named here that no longer exists is in git history:
`git show 1e39281^:tester/core/src/test/java/com/github/wadahiro/midpoint/tester/<Test>.java` (its fixtures likewise
under `tester/core/src/test/resources/fixtures*`).

## Build

| File | Role |
|---|---|
| `pom.xml` | `midpointXY.version` properties, Evolveum repository, plugin versions, pgmem profiles (`pgmem.classifier`), `tester.dir` |
| `tester/pom.xml` | tester parent: `<modules>`; build-helper adds `tester/core/src/main` as sources and `tester/core/src/test` as tests of **every** line module; surefire: one JVM, `-Xmx3g -Duser.language=en --add-exports java.management/sun.management=ALL-UNNAMED`, `tester.fixtures`, `tester.midpointVersion` |
| `tester/base-4.0/pom.xml` | parent of 4.0 and 4.4: `maven.compiler.release` 11, `-Xshare:off`, adds `base-4.0/src/main/java` |
| `tester/base-4.8/pom.xml` | parent of 4.8+: BOM import of `${midpoint.version}`, all dependencies (model-test, model-impl, notifications, report, cases, workflow, certification, provisioning, task-quartz, audit, security, repo-sqale, repo-sql-impl-test, system-init, connector-framework, pgmem-native, GreenMail 2.1.15 without `jakarta.mail-api` and `junit`, JUnit, AssertJ), adds `base-4.8/src/main/{java,resources}` |
| `pom.xml`, `tester/pom.xml` (file redirection agent of `mp.files()`) | Byte Buddy `${bytebuddy.version}` managed and depended on in `base-4.8`, `midpoint-4.0`, `midpoint-4.4`; 4.8+: `-javaagent:${net.bytebuddy:byte-buddy-agent:jar}` in `tester/pom.xml`'s argLine (`dependency:properties`); 4.0 and 4.4 (JDK 11): no `-javaagent`, the tester attaches the agent itself |
| `tester/midpoint-X.Y/pom.xml` | artifact `midpoint-config-tester-X.Y`; under `base-4.8` only `midpoint.version`; under `base-4.0` its own BOM import and dependency list (4.0: a JUnit BOM first, Jasper/itext/olap4j exclusions), with GreenMail 1.6.15 without `junit` (4.0 also without `com.sun.mail:jakarta.mail`) |
| `tester/static/pom.xml`, `tester/static/midpoint-X.Y/pom.xml` | static checks: own parent (no runtime sources), compiler includes `check/**`, `Identity`, `ConfigFiles`, `ConfigScanner`, `EvaluatorFingerprint`, `common/**`; a module per line (4.0, 4.4, 4.8, 4.9, 4.10) |
| `.github/workflows/ci.yml` | `test` (all modules, JDK 11 + 17/21) |

## What a line module provides

The shared code (`tester/core`) calls the line through `LineSupport` and through classes with fixed simple names. A
line gets each of them from its own module or from its base.

| Part | 4.0 | 4.4 | 4.8 | 4.9 | 4.10 |
|---|---|---|---|---|---|
| `LineSupport` (`LINE`, `CONFIG_FILE`, `SCHEMA_SCRIPTS`, `BASELINE`, `config`, `startHarness`, `drivers`, `serializeRealValue`) | `midpoint-4.0` | `midpoint-4.4` | `midpoint-4.8` (delegates to `Line48`) | `midpoint-4.9` (a copy of 4.10's) | `midpoint-4.10` (delegates to `Line48`) |
| Base-only adapters on `LineSupport` (`outputDefinition`, `mappingTargetName`, `clearRepositoryCaches`) | none | none | `toMutable()`, `getMappingQName()`, `clearCaches()` | as 4.10 | `mutator()`, `getTargetItemName()`, clears `ExtItemCache.nameToExtItem` then `clearCaches()` |
| `Harness<XY>` (`Harness` interface, extends `AbstractModelIntegrationTest`, `@ContextConfiguration("classpath:tester/ctx-tester.xml")`) | `Harness40` | `Harness44` | `Harness48` (`base-4.8`) | as 4.8 | as 4.8 |
| `tester/ctx-tester.xml` | model-intest imports + workflow + certification (no reports, no cases) | + `ctx-report.xml` | + `ctx-cases.xml` | as 4.8 | as 4.8 |
| `RecorderInstaller` (`BeanFactoryPostProcessor`) | replaces bean `mappingEvaluator` with `RecordingMappingEvaluator`, bean `expressionFilterHelper` with `ScopedExpressionFilterHelper` | replaces bean `mappingFactory` with `RecordingMappingFactory`, `expressionFilterHelper` as 4.0 | as 4.4 (`base-4.8`) | as 4.8 | as 4.8 |
| `ScopedExpressionFilterHelper` (a notifier's expression filters in its scope, `Recorder.inNotifierScope`) | `base-4.0`: `processEvent(Event, EventHandlerType, Task, OperationResult)` | as 4.0 | `base-4.8`: `processEvent(ConfigurationItem<? extends BaseEventHandlerType>, EventProcessingContext, OperationResult)` | as 4.8 | as 4.8 |
| Mapping subclass | none (`MappingImpl` has a private constructor and a final builder) | `com.evolveum.midpoint.model.common.mapping.RecordingMappingImpl` (`midpoint-4.4`) | the same class in `base-4.8` (adds `recordedOrigin()`) | as 4.8 | as 4.8 |
| `Recorder<XY>` (fills `Record`s) | `Recorder40`: reads `MappingImpl.variables/sources/outputPath` by reflection, computes the condition from the triple and masks | `Recorder44`: `AbstractMappingImpl.conditionResultOld/New` by reflection | `Recorder48` (`base-4.8`) | as 4.8 | as 4.8 |
| Mapping placement (see "Mapping placement" below) | `MappingPlacement` (no `ConfigurationItemOrigin`) | `MappingPlacement` | `ConfigurationItemOrigin` path, copies by container ID (`Recorder48.identify`) | as 4.8 | role mappings are their own beans; resource mappings copies of single attribute definitions, placed by the copy's container ID |
| Script hook (`ScriptExpressionFactory.evaluatorMap`) | `ScriptHook40` (`base-4.0`) | `ScriptHook40` | `ScriptHook48` (also `ScriptExpressionEvaluationContext.scriptBean`) | as 4.8 | as 4.8 |
| `RecordingTransport` (`DummyTransport` subclass) | `base-4.0`; `NotificationManager.registerTransport` | as 4.0 | `base-4.8`; `TransportService.registerTransport` | as 4.8 | as 4.8 |
| `SmtpServer` (GreenMail; `Harness<XY>.smtpServer()`, `LineDrivers.mails`/`clearMails`/`smtpPort`) | `base-4.0` (`javax.mail`), GreenMail 1.6.15 on midPoint's `javax.mail:mail` 1.4.5 | `base-4.0`, GreenMail 1.6.15 on `com.sun.mail:jakarta.mail` 1.6.7 (the same as midPoint's) | `base-4.8` (`jakarta.mail`), GreenMail 2.1.15 on midPoint's `org.eclipse.angus:jakarta.mail` 2.0.5 | as 4.8 | as 4.8 |
| `TaskThreadListener` (`TaskListener`) | `midpoint-4.0` (methods without `OperationResult`) | `midpoint-4.4` (with `OperationResult`, as 4.8+) | `base-4.8` | as 4.8 | as 4.8 |
| `Drivers<XY>` (`LineDrivers`: notifications, `isAuthorized`, value policies, scripts, bulk actions) | `Drivers40`: `ValuePolicyProcessor`, `ScriptingService` | `Drivers44`: `ModelInteractionService.validateValue`, `ScriptingService` | `Drivers48`: `BulkActionsService` | as 4.8 | as 4.8 |
| `ApprovalsImpl` (complete, delegate/forward, claim/release, cancel; cases oldest first) | `base-4.0`: `WorkflowService`; case creation time in `metadata` | as 4.0 | `base-4.8`: `CaseService`; creation time in `metadata` | as 4.8; creation time in the case's value metadata (`storage`), no `metadata` | as 4.9 |
| `CertificationImpl` | `base-4.0`: `CertificationManager` | as 4.0 | `base-4.8`: `AccessCertificationService` | as 4.10 | as 4.8 (stages and reiteration through a task) |
| `ReportsImpl` | throws `UnsupportedOperationException` (export and import) | `ReportManager.runReport`, newest `ReportDataType` file; import: `ReportManager.importReport` on a task of its own | `ModelInteractionService.submit` of a classic export activity, `ReportTestUtil.findOutputFile`; import: submit of a classic import activity (`reportImport`) | as 4.8 | as 4.8 |
| `DashboardsImpl` | `createWidgetData(widget, task, result)` | `createWidgetData(widget, true, task, result)` | as 4.4 (`base-4.8`) | as 4.8 | as 4.8 |
| `TimeImpl` | `Clock` override; scanners as tasks with the scanner handler URIs, `switchToBackground` | `Clock`; scanner activities, `switchToBackground` | `Clock`; scanner activities via `ModelInteractionService.submit` | as 4.8 | as 4.8 |
| `tester/sql/` | `postgresql-4.0-all.sql` (`config/sql`) | `postgres-new*.sql` (`config/sql/native-new`, PostgreSQL 16+ changes marked `clockwork:`) | `postgres*.sql` (`config/sql/native`) | as 4.8, of 4.9 | as 4.8, of 4.10 |
| `tester/baseline/` | 021, 023-025, 041, 042 of `config/initial-objects` (no task archetypes) | + 507, 508, 509, 511, 520, 521, 530, 531 | as 4.4, + 027 (correlation case archetype; `config/initial-objects/{archetype,role}`) | as 4.8, of 4.9 (`repo/system-init/src/main/resources/initial-objects/{archetype,role}`; `config/initial-objects` holds a README) | as 4.8, of 4.10 (in both places) |

`tester/core` adds `010-system-configuration.xml`, `020-role-superuser.xml` and `030-user-administrator.xml` to every
line's baseline.
Their OIDs are what golden results flag as `baseline` places, unless a test imports an object with the same OID
(its own system configuration): the flag does not depend on the line, only on the baseline OIDs and on what the test
imported (`GoldenResults.isBaseline`).

## Mapping placement

Where `Recorder48.identify` finds each kind of mapping on 4.8+ (`Record.elementPath`, located by `origin`), checked by
a probe that printed the branch per evaluation over the whole test suite, and pinned by `MappingPlacementTest` (every
mapping of the driven runs in an imported object is located, on 4.8+ by `origin`). "own": the bean is still part of
the owner as loaded; "path": the `ConfigurationItemOrigin.InObject` path, followed as far as it goes, then the mapping
below it by ID and name (a path that leads to a mapping in full is that mapping, even when midPoint changed its copy);
"IDs": a detached copy (approximate origin), placed by the topmost container ID of the copy
and the IDs below it.

| Kind | 4.8 | 4.9 | 4.10 |
|---|---|---|---|
| Resource object type: attribute, activation, credentials in/outbound | IDs (whole object type copied) | IDs | IDs (single attribute definitions, without their item) |
| Resource object type: legacy association in/outbound | IDs | own (approximate origin; the bean stays in the resource) | as 4.9 |
| Association type (`schemaHandling/associationType`, 4.9+) in/outbound | rejected | IDs (the copy's top has no ID: the mapping's own ID) | as 4.9 |
| Mapping nested in an association type's evaluator (`objectRef/mapping`) | - | not reachable (part of the evaluator bean, not of the object): `fingerprint` within its mapping | as 4.9 |
| Role construction attribute and association outbound | path | path | own |
| Focus mapping of an inducement | path (midPoint's path ends `focusMappings/<mapping ID>`) | as 4.8 | own (in an approval execution task: path) |
| Condition of an inducement | path (midPoint's path is the inducement's) | as 4.8 | own |
| Condition of a role, autoassign mapping | own / path | as 4.8 | own / path |
| Template mapping; template item mapping | own; path | as 4.8 | as 4.8 |
| Template multi-source selection, default authoritative source (copied, with the identities added as sources) | path (in full; the generated default selection of an item without one: no origin, unlocated) | as 4.8 | as 4.8 |
| Condition of an assignment in a delta (`InDelta`) | owner: the object the delta is for | as 4.8 | as 4.8 |

Before the path was followed beyond its end and the copies by their topmost ID, focus mappings and inducement
conditions (4.8, 4.9) and association type mappings (4.9, 4.10) were located by `name` or `fingerprint` only (the same
identities, but an unnamed focus mapping of 4.8/4.9 by its canonical form). 4.0 and 4.4 have no origin: their
`MappingPlacement` places what is still part of the origin object or the one equivalent value; the rest (autoassign,
credentials, item mappings, some conditions) by `name`, which `MappingPlacementTest` accepts there. The coverage report
lists the located mappings not placed by `origin` (section "Mappings not placed by midPoint").

## Harness

| | 4.0 | 4.4 | 4.8 / 4.9 / 4.10 |
|---|---|---|---|
| Repository | old (Hibernate) on PostgreSQL: `repositoryServiceFactoryClass` `TestSqlRepositoryFactory`, `hibernateHbm2ddl` validate | native, type `sqale` (4.4's `TestSqaleRepositoryBeanConfig` ignores `native`) | native, type `native` |
| Test configuration file | always `test-config.xml` | `tester-config.xml` through `test.config.file` | as 4.4 |
| Start | `initSystemConditional()` called explicitly | `@PostConstruct initSystem` + `MidpointTestContextWithTask` | as 4.4 |
| `ModelService.testResource` | 2 arguments | 2 arguments | 3 arguments |
| Account import | shadow search + `importFromResource(shadowOid)` | as 4.0 | `importAccountsRequest()` |
| `resetInternals` besides `CacheDispatcher.dispatchInvalidation` and `TaskManager.synchronizeTasks` (Quartz keeps jobs in memory) | `ExtItemDictionary.initialize()`, provisioning `ResourceCache.cache` cleared by reflection | `SqaleRepoContext.clearCaches()` | `LineSupport.clearRepositoryCaches` |
| Background work | a recurring task re-imported after a reset can be fired by Quartz but not run: not waited for 2 s after the trigger fired | | |
| Extension schema | `<global><extensionDir>` of the test configuration (`LineSupport.config`): `tester.extensionSchemaDir`, else an empty `<midpoint.home>/schema`; the shared tests' surefire sets `tester/core/src/test/resources/schema` | as 4.0 | as 4.0 |
| Extension items after a reset (written in one test method, written again in the next: `ExtensionTest`) | `ExtItemDictionary.initialize()` | `SqaleRepoContext.clearCaches()` | 4.8: `clearCaches()`; 4.9 / 4.10: `ExtItemCache.nameToExtItem` cleared first |
| `serializeRealValue` | the harness's `PrismContext` (no `PrismContext.get()`) | `PrismContext.get()` | as 4.4 |
| Hook script descriptions (`ScriptHook*.hookName`) | `Clockwork`: `hook '<name>'`, `scripting hook in system configuration` | `ClockworkHookHelper`, same text | as 4.4 |

## Behaviour axes pinned by the shared tests

Each row is a place in `tester/core/src/test` where the expectation depends on the line (`Lines.atLeast(mp, "X.Y")`,
`mp.line()`, or an assumption), with what each line does. A new line gets an entry per row, read from its sources and
confirmed by running the test; a row per gate (`grep -rn 'Lines.atLeast\|mp.line()\|assumeTrue'
tester/core/src/test/java`). The README's "Line notes" table is the integrator-facing copy of this one.

| Axis | 4.0 | 4.4 | 4.8 | 4.9 | 4.10 | Pinned in |
|---|---|---|---|---|---|---|
| Baseline archetypes: certification task archetype 520 | absent (no task archetypes) | present | present | present | present | `RuntimeTest.baselineAndWiring` |
| Baseline archetypes: bulk action task archetypes 508, 509 (the tasks of asynchronous policy scripts get one) | absent | present | present | present | present | `RuntimeTest.baselineAndWiring`, `AsyncPolicyScriptTest` |
| Mapping hook wiring | `mappingEvaluator` is `RecordingMappingEvaluator` | `mappingFactory` (also `ModelBeans.mappingFactory`) is `RecordingMappingFactory` | as 4.4 | as 4.4 | as 4.4 | `RuntimeTest.baselineAndWiring` |
| Template mapping for the old state of an added user | its result stays the final outcome (`removed=["null null"] \| old=["null null"]`); a failing script fails there first (`no number for null`) | intermediate only | as 4.4 | as 4.4 | as 4.4 | `RecorderTest.mappingWithError`, `GoldenTest.recordedPerPlace` (and the other `GoldenTest` methods) |
| Inbound `focus` of a new user | not asserted | not asserted | `PrismObject` (`MappedItem`) | `UserType` bean (`MappedSourceItem`) | as 4.9 | `RecorderTest.inboundMapping` |
| Inbound variables `association`, `object` | absent | absent | absent | present | present | `RecorderTest.inboundMapping` (also the `inputs` notes of the golden pairs into 4.9 and 4.10) |
| Correlation expression | `synchronization/objectSynchronization` format (`fixtures-4.4`), no correlation context | as 4.0 | `correlation/correlators` in the object type; `correlationContext`, `correlatorState` | as 4.8 | as 4.8 | `RecorderTest.correlationExpression` |
| `focus` of a correlation and a reaction condition without an owner | null | null | an empty pre-focus (`UserType` without name) | as 4.8 | as 4.8 | `SynchronizationTest.unmatchedStaffIsAdded` |
| A single correlation candidate | not confirmed, linked | confirmed; a rejected one gives a new user | as 4.4 | as 4.4 | as 4.4 | `SynchronizationTest.singleCandidateMustBeConfirmed`, `SynchronizationTest.correlationLinksTheConfirmedOwner` |
| Reaction conditions | none: the contractor is added | evaluated | as 4.4 | as 4.4 | as 4.4 | `SynchronizationTest.unmatchedContractorIsAddedOnlyWithoutReactionCondition`, `SynchronizationTest.unmatchedStaffIsAdded` |
| Import of an unclassified service account | fails: `No objectclass specified` | fails: `No object class found for the shadow` | `classificationCondition` false, imported without a user | as 4.8 | as 4.8 | `SynchronizationTest.serviceAccountsAreNotSynchronized` |
| Import of an account midPoint created (its shadow already classified) | not pinned | the synchronization condition is evaluated again | the classification condition is not evaluated | as 4.8 | as 4.8 | `SynchronizationTest.directoryMayDisableButNotEnable` |
| Full-processing import of the system configuration | runs the autoassign mappings and fails | passes | as 4.4 | as 4.4 | as 4.4 | `SynchronizationTest.fullProcessingImportOfTheSystemConfigurationRunsTheAutoassignMappings` |
| Outbounds of an account being deleted | the attribute outbounds are evaluated | only the existence outbound | as 4.4 | as 4.4 | as 4.4 | `SynchronizationTest.costCenterAssignsTheRoleAutomatically`, `HrToLdapScenarioTest.hrDrivesTheDirectoryThroughReconciliation`, `ActivationScenarioTest.deprovisioningDisablesAndDeletesThirtyDaysLater`, `ActivationTest.assignmentValidityEndsTheAccountsAsTheirResourcesDeprovision`, `DependencyTest.theDirectoryAccountGoesOnlyAfterTheAccountThatDependsOnIt` |
| Construction of a user being deleted | not evaluated | evaluated with a false condition | as 4.4 | as 4.4 | as 4.4 | `HrToLdapScenarioTest.hrDrivesTheDirectoryThroughReconciliation` |
| Template of a change the authorization refuses | evaluated first | not evaluated | as 4.4 | as 4.4 | as 4.4 | `AccessTest.isAuthorizedAndExecuteAsUser` |
| `assignmentTargetSearch` after the target changes: last filter evaluation | the old state's | the new state's | as 4.4 | as 4.4 | as 4.4 | `SearchTest.assignmentTargetSearchFollowsTheCostCenter` |
| `assignmentTargetSearch` minus set when the target changes | the old assignment once | as 4.0 | as 4.0 | twice: as computed (no ID) and as stored | as 4.9 | `SearchTest.assignmentTargetSearchFollowsTheCostCenter` |
| `associationTargetSearch` after the user moves: last filter evaluation | the new state's | the old state's | as 4.4 | as 4.4 | as 4.4 | `EntitlementTest.associationTargetSearchFindsTheGroupComputedFromTheUser` |
| Association inbound after a second membership: last evaluation | without the new membership | with it (filter evaluations per group in no stable order) | as 4.4 | as 4.4 | as 4.4 | `EntitlementTest.associationFromLinkTakesOnlyTheGroupOfEachRole` |
| Value of a legacy association outbound | `ShadowAssociationType` | as 4.0 | as 4.0 | `ShadowAssociationValueType` | as 4.9 | `EntitlementTest.associationFromLinkAddsTheMemberToTheGroupOfTheRole` |
| Simulation results after a preview | none (4.7+ feature) | none | none kept | none kept (`previewChanges` = `executeChanges` in `SIMULATED_PRODUCTION`) | as 4.9 | `RecorderTest.previewThenExecute` |
| Hook without `state`, per imported role | primary, secondary x3 (each with focus), final | primary, secondary, secondary without focus, final | as 4.4 | as 4.4 | as 4.4 | `ChangeTest.importObjectsIsOneRun` |
| Recipient expression returning a string | a string | a string | `RecipientExpressionResultType` (address) | as 4.8 | as 4.8 | `HookAndNotificationTest.notificationsPerRunAndPerTest` |
| Mail transport of `<transport>mail:hr</transport>` / `<transport>hr</transport>` | the one `notificationConfiguration/mail` (`fixtures-4.4/mail`; `TransportRegistry.getTransport` splits at `:`) | as 4.0 | the `messageTransportConfiguration/mail` named `hr`, its own `defaultFrom` (`TransportServiceImpl.getTransport`: exact name, then the part before `:`) | as 4.8 | as 4.8 | `MailNotificationTest.htmlMailWithAnAttachmentThroughAnotherTransport` |
| Two recipients of one notifier | one mail, both in `To` (`AbstractGeneralNotifier`) | as 4.0 | a mail per recipient (`notificationSendingStrategy`, default `separateNotificationToEachRecipient`) | as 4.8 | as 4.8 | `MailNotificationTest.oneMailPerRecipientFrom48` |
| Mail subject charset (`MimeMessage.setSubject`) | `"utf-8"`: `=?utf-8?Q?` | `StandardCharsets.UTF_8.name()`: `=?UTF-8?` | as 4.4 | as 4.4 | as 4.4 | `MailNotificationTest.plainTextMailWithCcAndBcc` |
| A mail transport with `redirectToFile` next to one without | not tested (one mail transport; assumption) | as 4.0 | file written, no mail | as 4.8 | as 4.8 | `MailNotificationTest.aTransportThatRedirectsToAFileSendsNoMail` |
| Approvers of stage 1, order | approver expressions order | org (candidates) first | expressions order | expressions order | expressions order | `ApprovalTest.preview` (`stage1Order`) |
| Approval display name: the object argument | `taro` | `UserDescriptiveName(Taro Tanaka, taro)` | as 4.4 | as 4.4 | as 4.4 | `ApprovalDetailsTest.displayNameWithTheObjectDisplayInformation` |
| `automaticallyCompleted` returning `skip` | a case, completed when the stage opens | no case | as 4.4 | as 4.4 | as 4.4 | `ApprovalDetailsTest.skipInItsShortForm` |
| Certification reviewer expressions | in the call (`task()` null) | in the call | in the call | in a certification task (`task()` set) | as 4.9 | `CertificationTest.campaign` |
| Failed `checkExpression` name in `ValueCheck.failed()` | its `failureMessage` | its display label | label | label | label | `ScriptTest.validateValue` |
| Resource outbound: `source`, `containingObject`, `thisObject`, `assignmentPath` | not bound | the resource, the resource, null, null | as 4.4 | as 4.4 | as 4.4 | `VariablesTest.resourceOutbound` |
| Construction in an inducement: `iteration` | absent | present | as 4.4 | as 4.4 | as 4.4 | `VariablesTest.constructionInAnInducement` |
| Import of a `targetRef` filter with an expression | skipped, left to the evaluation | as 4.0 | as 4.0 | fails: "filter matches no object" | as 4.9 | `VariablesTest.importOfATargetFilterWithAnExpression` |
| `SpringApplicationContextHolder` for scripts | `...wf.impl.processes.common` | as 4.0 | `...model.impl.expr` | as 4.8 | as 4.8 | `SampleConfigTest.bulkActionScriptWithASpringBean` |
| Function of a missing library | `ObjectNotFoundException` | as 4.0 | `ConfigurationException` "No function library with OID" | as 4.8 | as 4.8 | `SearchTest.functionOfAMissingLibrary` |
| Task query (activity format) | not ported: skipped (assumption) | runs | as 4.4 | as 4.4 | as 4.4 | `SearchTest.taskQuerySeesTheConfiguration` |
| Object collection reports and dashboard `widgetData` | none: report tests skipped (assumption); the widgets of collections and objects are evaluated as on 4.4+ (`DashboardServiceImpl.createWidgetData`; `fixtures-4.0/async/dashboard.xml` has no stored-data widget) | present | present | present | present | `ReportTest` (`hasCollectionReports`) |
| Report export file in `run.filesWritten()` | no collection reports: skipped (assumption) | present (`<midpoint.home>/export`) | as 4.4 | as 4.4 | as 4.4 | `FilesTest.reportExportWritesItsFile` |
| Scripting task writing a file | the scripting handler with `se:executeScript` in the extension (`fixtures-4.0/files`) | `nonIterativeScripting` activity | as 4.4 | as 4.4 | as 4.4 | `FilesTest.taskScriptWritesAFile` |
| Report rows | - | no subreport `resultHandling` (`fixtures-4.4`): one row per user, joined org column; no subscription footer | one row per org (`splitParentRow`), footer line | as 4.8 | as 4.8 | `ReportTest.export` |
| Report variables per line | - | subreports unbound | subreports chained, an empty one null | as 4.8 | as 4.8 | `ReportTest.exportVariablesPerLine` |
| Report `locale` column | - | no default locale | as 4.4 | as 4.4 | the default locale | `ReportTest.exportVariablesPerLine` |
| Report import | - (assumption: skipped) | `importReport`: columns by view order, `import/expression` with `input`, import script per row, a failing row a partial error | as 4.4 | as 4.4 | as 4.4 | `ReportImportTest` (`hasReportImport`) |
| Report column with the path `#` | - | the export fails | exported | as 4.8 | as 4.8 | `ReportTest.identifierPath` |
| Dashboard variations | applied in turn, a later false one resets the display | as 4.0 | as 4.0 | as 4.0 | a true variation's display is kept | `ReportTest.dashboardVariation` |
| `associationConstruction` evaluator (associationType model) | none | none | none | evaluable | evaluable | `AssociationTypeTest` |
| `schemaHandling/associationType` | rejected (`delineation`) | as 4.0 | rejected (`associationType`) | read and run | as 4.9 | `AssociationTypeTest` (`linesBefore49RejectTheDefinition`) |
| Policy constraint for the "no end date" rule | `objectState` with the expression (no `custom`; `fixtures-4.0/policy`) | as 4.0 (`fixtures-4.4/policy`) | `custom` (4.7+) | as 4.8 | as 4.8 | `PolicyTest.contractorsNeedAnEndDate` |
| `input` of a `scriptExecution` policy script | the user's `PrismObjectValue` (`PolicyRuleScriptExecutor`): `input.description` fails ("No such property"), and the operation fails with "... is still UNKNOWN during cleanup" (`OperationResult.cleanupResult`); the user is added, the script's change is not | the user (`input.description` resolves): the change is made | as 4.4 | as 4.4 | as 4.4 | `PolicyTest.fieldStaffAreMarkedByAScript` |
| Bulk action scripts (`scriptExecution`, scripting tasks) | `execute-script` actions (no `<s:execute>`): the script parameter is located at `.../action/parameter[script]` (`ConfigScanner` reads it as a `<script>`, which `ScriptHook40` records); tasks: the scripting handlers with `scext:executeScript` in the extension (`fixtures-4.0/scripting`, `fixtures-4.0/policy`) | `<s:execute>`, located at `.../executeScript/execute`, activities `iterativeScripting` / `nonIterativeScripting` | as 4.4 | as 4.4 | as 4.4 | `PolicyTest.fieldStaffAreMarkedByAScript`, `ScriptingTaskTest` |
| Value metadata of an object type's association outbound value | none | none | none | the mapping's provenance | none | `ObjectTypeAssociationTest.theAccountJoinsTheGroupOfItsCostCenter` |
| Dashboard report of a widget's own view (`widget/presentation/view/column/export/expression`; HTML only, CSV has the widget table only) | not tested (no object collection reports) | exported | as 4.4 | as 4.4 | as 4.4 | `DashboardReportTest.widgetViewColumnsAreExported` |
| Simulation metrics (`simulation/metric/computation` domain, value and selection expressions; `aggregation/domainRestriction`) of a preview task | none (4.7+) | none | computed per processed object (`ObjectMetricsComputation`), summed in the simulation result (`AggregatedMetricsComputation`) | as 4.8 | as 4.8 | `SimulationTest.metricsOfASimulatedRecomputation` |
| Custom audit property (`audit/eventRecording/property/expression`) of the request record | not evaluated: the request audit (`Clockwork.click`, state `INITIAL`) runs before `ContextLoader` sets the context's system configuration, whose properties `Clockwork.auditEvent` reads; the execution record has it | evaluated for the request and the execution record (`ClockworkAuditHelper`) | as 4.4 | as 4.4 | as 4.4 | `AuditPropertyTest.auditRecordsCarryTheCostCenterOfTheUser` |
| Audit recording expression (`audit/eventRecording/expression`) | none: 4.0's `SystemConfigurationAuditEventRecordingType` has no `expression` (assumption: skipped) | evaluated per record, request and execution (`ClockworkAuditHelper` -> `AuditHelper.evaluateRecordingExpression`); null drops the record | as 4.4 | as 4.4 | as 4.4 | `AuditRecordingTest` |
| Approval comments of an approved assignment (`approverCommentsFormatting`) | `assignment/metadata/createApprovalComment`, the user's `metadata/modifyApprovalComment` (`ApprovalMetadataHelper`) | as 4.0 | as 4.0 | in value metadata, `process/createApprovalComment` and `process/modifyApprovalComment` (`OperationalDataManager`) | as 4.9 | `ApprovalCommentsTest.theApprovedAssignmentKeepsTheFormattedComment` |
| Certification comments of a certified assignment (`reviewerCommentsFormatting`) | `assignment/metadata/certifierComment` (`AccessCertificationClosingTaskHandler`) | as 4.0 | as 4.0 | in value metadata, `process/certifierComment` (`OperationalDataManager.createAssignmentCertificationMetadataDeltas`) | as 4.9 | `CertificationCommentsTest.theCertifiedAssignmentKeepsTheFormattedComment` |
| Provenance feed populators of a resource object type (`provenance/metadataItemPopulator`, `acquisitionItemPopulator`) | none (no value metadata) | evaluated (`ProjectionValueMetadataCreator`); inbound values carry the metadata, template metadata mappings carry it on (`provenance` kept with `meta/item[provenance]`; 4.9 serializes the references of value metadata as elements: `<originRef><t:oid>`) | as 4.4 | as 4.4 | not evaluated: `MappedSourceItem` no longer calls `InboundsSource.setValueMetadata` (MID-10514), the values of resource objects have no metadata (the matrix lists the populators as not drivable on 4.10) | `ValueMetadataTest.valuesFromHrNoteTheirRecord` |
| Asynchronous policy scripts (`scriptExecution/asynchronousExecution`), object collection default views in reports, activity reports, an activity's control flow (`itemProcessingCondition`, `bucketProcessingCondition`, also tailored), the object sets of import, reconciliation and repository tasks, activity tracing and profiling, dashboard widgets on filtered collections (base collection, domain, the widget's own filter), report views (a collection's default view in an import, a dashboard report's own view) | none (not tested) | run | as 4.4 | as 4.4 | as 4.4 | `AsyncPolicyScriptTest`, `CollectionViewReportTest`, `ActivityReportsTest`, `ControlFlowTest`, `ResourceTaskQueryTest`, `RepositoryTaskQueryTest`, `ActivityTracingTest`, `DashboardWidgetTest`, `ReportViewTest` (assumptions) |
| Explicit work buckets (filter contents with expressions) | the iterative scripting handler with the buckets in its `workManagement` (`fixtures-4.0/task-distribution`); no buckets report | the activity's `distribution`; the buckets report filtered by its record filtering expression | as 4.4 | as 4.4 | as 4.4 | `ActivityDistributionTest` |
| Shadow cleanup (`shadowCleanup`) of a gone account's shadow | none (no activities) | the deletion of every shadow of the query is notified without reading the resource (`ShadowCleanupActivityHandler.deleteShadow`): situation deleted, the shadow kept, not dead, still linked | each shadow is refreshed from the resource (`processItem`: `getObject` with `forceRefresh`): situation deleted, dead | as 4.8 | as 4.8 | `RepositoryTaskQueryTest.theShadowsOfAShadowCleanupQuery` |
| Role membership management (`roleMembershipManagement`), shadow reclassification (`shadowReclassification`) | none (assumption: skipped) | none | membership management run (4.8+); no reclassification | both run (reclassification 4.9+) | as 4.9 | `RepositoryTaskQueryTest.theMembersOfARoleMembershipManagementQuery`, `ResourceTaskQueryTest.aReclassificationTakesTheAccountsOfItsQuery` |
| Report parameter with several values (`reportParam`) | - (assumption: skipped) | the export fails: "Attempt to get single value from item costCenter with multiple values" (`CollectionExportController.initializeParameters`) | a list (`getRealValuesAsTypedValue`): a script filter matches any value; a `path` expression fails ("Unexpected variable value [...] (class java.util.ArrayList)") | as 4.8 | as 4.8 | `ReportTest.severalParameterValues` |
| Correlation confirmation returning no value | the import's result only (no exception): "Failed to import: Error occurred during resource object shadow owner lookup, reason: Couldn't confirm user user" | fails: "... Couldn't confirm user user", cause "Expression returned no value (0) in confirmation expression for ..." (`SynchronizationExpressionsEvaluator`) | fails: "... Couldn't confirm candidate owner: Expression returned no value in confirmation expression for filter correlator 'by-employee-number' ..." (`FilterCorrelator`) | as 4.8 | as 4.8 | `MisconfigurationTest.confirmationMustReturnOneValue` |
| Focus archetype, `items` correlators, thresholds, correlation cases (`correlation/resource-staff.xml`) | none (assumption: skipped) | as 4.0 | archetype in the candidate query (`CorrelationItems`, `FilterCorrelator`); tier 2 weight 0.8: `uncertain`, owner options with confidence, a case of archetype 027; an items correlator without item values does not apply | as 4.8 | as 4.8 | `CorrelationTest` |
| Value policy limitations without `minUniqueChars` | unique characters not checked | as 4.0 | as 4.0 | minimum = `minLength` (`StringPolicy`) | as 4.9 | `PasswordReadingTest.uniqueCharactersDefaultToTheMinimalLength` |
| A `timeFrom` mapping whose condition is false (CRM's delayed delete of an account disabled with its user) | `MappingImpl.evaluate` stops at the unmet time constraint: the condition is not evaluated (`conditionNew()` null), the recompute trigger is set on the shadow anyway; when it fires the condition is false, the account stays | `AbstractMappingImpl.evaluate` evaluates the condition first (MID-6040): false, no trigger | as 4.4 | as 4.4 | as 4.4 | `ActivationScenarioTest.accountEndsWithTheUsersValidityAndIsKept` |
| Two structural archetypes on one user | `ConfigurationException` "Only single archetype supported" (`AssignmentProcessor`), after the template and the inducements were evaluated | `SchemaException` "Found [...] structural archetypes", before any evaluation (`ArchetypeManager`) | as 4.4 | as 4.4 | as 4.4 | `ArchetypeTest.anObjectHasOneStructuralArchetype` |
| `archetypeRef` of a user of a sub-archetype (`superArchetypeRef`) | none (assumption: skipped) | the assigned archetype | as 4.4 | the assigned archetype and its super-archetypes | as 4.9 | `ArchetypeTest.aSubArchetypeTakesThePolicyAndInducementsOfItsSuperArchetype` |
| Built-in meaning of a user's lifecycle state without a model (`ActivationComputer`) | `archived`: effective status ARCHIVED; `suspended`: DISABLED, assignments inactive | as 4.0 | `archived`: DISABLED (`getHardcodedForcedLifecycleActivationStatus`); `suspended`: DISABLED, assignments active (`defaultLifecycleHasActiveAssignments`) | as 4.8 | as 4.8 | `LifecycleStateModelTest.withoutAModelOnlyActiveAndDeprecatedUsersAreEnabled` |
| `activeAssignments` false of a state of an archetype's model, for an account that exists | the account is kept, enabled, also on recompute | as 4.0 | the account is deleted | as 4.8 | as 4.8 | `LifecycleStateModelTest.anArchivedStateWithoutAForcedStatusLeavesTheContractorEnabled` |
| Security policy of an archetype (`ArchetypeType/securityPolicyRef`) | none (`fixtures-4.4/security` has the archetype without it): the global policy and its value policy check the password | as 4.0 | the archetype's security policy: its value policy instead of the global one | as 4.8 | as 4.8 | `PasswordPolicyTest.privilegedUsersFollowTheSecurityPolicyOfTheirArchetype` |
| An expression in a certification scope's `searchFilter` | not evaluated: `AccCertOpenerHelper` hands the parsed filter to `repositoryService.searchObjectsIterative`; an `equal` without a value matches the users without a cost center | as 4.0 | as 4.0 | evaluated: the stage opens in a search-based activity (`SearchBasedActivityRun.resolveExpressionsInQuery`) | as 4.9 | `CertificationScopeTest.theSearchFilterSelectsTheCertifiedUsers` |
| A notification handler with `xsi:type="CustomNotifierType"` | run: `CustomNotifierType` extends `EventHandlerType`, `EventHandlerRegistry.forwardToHandler` dispatches by class | as 4.0 | `CustomNotifierType` extends `BaseEventHandlerType`: every notifying operation fails with a `ClassCastException` | as 4.8 | as 4.8 | `HandlerNotifierTest` |
| Transport recipient tests | one legacy mail transport (`fixtures-4.4/transport-recipients`): the unredirected filter and `recipientAddressExpression` skipped | as 4.0 | named transports (`messageTransportConfiguration`) | as 4.8 | as 4.8 | `TransportRecipientsTest` |
| Collection stats constraints (dashboard widget) | skipped: the fixture is unreadable (no `filter` in 4.0's `CollectionRefSpecificationType`) | run | as 4.4 | as 4.4 | as 4.4 | `CollectionStatsTest` |
| Lifecycle state outbound of an account (`lifecycle/lifecycleState/outbound`, `fixtures-4.4/lifecycle-state` on 4.0 and 4.4) | evaluated (`ActivationProcessor.getLifecycleStateMapping`): draft, then active; the draft account is provisioned | as 4.0 | `ConfigurationException` "Outbound mappings for 'lifecycleState' are no longer supported..." (`ActivationProcessor.assertNoLifecycleStateOutboundMappings`) before the mapping; the change fails | as 4.8 | as 4.8 | `AccountLifecycleStateTest.anAccountIsDraftUntilTheUserHasAnEmployeeNumber` |
| Import of an LDAP entry with an auxiliary object class no construction gives, `auxiliaryObjectClassMappings` not `tolerant` | the class is removed (`ReconciliationProcessor`); the last inbound evaluation still sees it: the user keeps the value | as 4.0 | as 4.0 | the class is removed; the inbound is evaluated again for the entry without it: the user has no value | as 4.9 | `AuxiliaryObjectClassTest.withoutToleranceTheImportRemovesTheClass` |
| Tag outbound of a multi-account object type (`multiplicity/tag/outbound`) | none (no multi-accounts; assumption: skipped) | one account per tag (`ResourceObjectConstruction.evaluateTagTriple`) | as 4.4 | as 4.4 | as 4.4 | `AccountTagTest` |
| Metadata mappings of a user template | none (no value metadata; assumption: skipped) | `item/meta/item/mapping` applied; `meta/mapping` and `item/meta/mapping` with a target never (`ItemValueMetadataProcessingSpec.isMetadataMappingApplicable` accepts a target only when the data mapping ignores it), without a target the change fails (NPE) | as 4.4 | as 4.4 | as 4.4 | `MetadataMappingTest` |
| Confidence expressions of correlators (`fixtures/correlation-confidence`) | none (4.6+; assumption: skipped) | as 4.0 | filter and items correlators' own per candidate (`BaseCorrelator.determineConfidence`, the variable `candidate`), an item's search per match metric (`CorrelationItem.computeConfidence`, `input`; also an attribute correlator's and a template item's search); the composite's, a composite child's and a no-op correlator's never | as 4.8 | as 4.8 | `CorrelationConfidenceTest` |
| Expression in a delineation filter (`fixtures/delineation-filter`) | none (4.6+; assumption: skipped) | as 4.0 | never evaluated (`DelineationProcessor`, `DelineationMatcher`), its branch matches nothing | as 4.8 | as 4.8 | `ResourceMisconfigurationTest.anExpressionInADelineationFilterIsNeverEvaluated` |
| The 4.4 format on 4.8+ (`Fixtures.legacy`: the `fixtures-4.4` variants of `base-context`, `sync`, `transport-recipients`) | the line's format (`fixtures-4.0` where it has one) | the line's format | still read: `RefinedResourceSchemaParser.parseDelineation` takes the object type's `baseContext` without a `delineation`; `SynchronizationPolicyFactory` makes the synchronization condition the classification condition (a service account is imported without a user, not refused) and the correlation and confirmation a filter correlator; `LegacyMailTransport` evaluates the recipient filter of `notificationConfiguration/mail` | as 4.8 | as 4.8 | `LegacyFormatTest` |
| Base contexts of a simulated reference (`fixtures/reference-base-context`, real LDAP) | none (4.9+; assumption: skipped, the definition rejected at `delineation`) | as 4.0 | rejected at `associationType` | the object's evaluated with no variables when the account's associations are read (`EntitlementObjectSearch`, `DelineationProcessor`); the subject's never (`EntitlementUtils.doesMatchSubjectDelineation` checks the auxiliary object class only); `configuredCapabilities/references` of an object type never read (`CapabilityUtil.getCapability` without an object type: the reference attribute is missing); an association on an association type's object side refused (`ResourceSchemaParser.checkParticipantObjectTypes`), its mappings never read (`parseModernAssociation` takes the subject's) | as 4.9 | `ReferenceBaseContextTest` |
| Synchronization situation of an account of an object type without reactions (`fixtures/sorter`) | unmatched (an `objectSynchronization` without reactions, 4.4 format) | as 4.0 | none: the object type has no `synchronization`, the account is not synchronized | as 4.8 | as 4.8 | `SynchronizationSorterTest.theSorterTellsPersonalAndAdminLoginsApart` |
| Static checks | see the line table in `docs/redesign.md` "Static checks (step 5)" | | | | | `StaticCheckTest` (`MidPointLine.isBefore`) |

Checked on every line with no difference to pin (4.4+ where activities or reports are needed): timed actions of approval
stages (`TimedActionsTest`: conditions never evaluated, `TimedActionTriggerHandler` / 4.0-4.4
`WfTimedActionTriggerHandler`), approval names from a constraint's short message and a process specification's key and
arguments (`ApprovalNamesTest`), the `addAssociationAspect` approver expression (`AssociationApproversTest`), a merge
configuration's default (`MergeDefaultTest`); an import's query of resource objects replaces the activity's own by
default (`queryApplication` `replace`: without the resource it fails "Resource not defined in a search query"; `append`
adds it, the default of reconciliation and shadow cleanup); a skipped bucket is not searched (its filter's expressions
are not evaluated); auto-scaling takes only the running tasks of its query; an internal operations report without a fast
filter reports nothing (`InternalOperationsReport.fastFiltersReject`); an expression in the record filter of an activity
report is never evaluated and the filter matches no record (`ActivityTracingTest`); reports and dashboards take a view's
columns, not its collection, whose filter is never evaluated, and a dashboard report cannot be imported
(`ReportViewTest`); an object state constraint decided by a bulk action (a `search` whose filter expressions get the
object; 4.0 locates them too, the action being a `search`) and by a message expression (`ObjectStateConstraintTest`);
the lockout status outbound of a simulated lockout attribute (`ActivationProcessor`, with the configured capability's
`lockoutStatus`), and no evaluation of the existence, lockout status, lifecycle state and purpose inbounds (4.0
`InboundProcessor`, 4.4 `InboundMappingsEvaluation`, 4.8 `ClockworkShadowInboundsPreparation`, 4.10
`FullInboundsProcessing` evaluate the password, status and validity ones only; `LockoutTest`); the base context filter
of an object type, evaluated with no variables (`ResourceObjectReferenceResolver.resolve`; `BaseContextTest`, real
LDAP); the buckets of a tailored segmentation of the explicit type (`ResourceTaskQueryTest`, 4.4+); the synchronization
sorter (`SynchronizationSorterTest`); an inbound in a construction refused, "Cannot process
inbound section" (4.0 `Construction`, 4.4+ `EvaluatedAssignedResourceObjectConstructionImpl`;
`ResourceMisconfigurationTest.anInboundInAConstructionIsRefused`); the auxiliary object class inbound, the outbound
never read (`AuxiliaryObjectClassTest`); the filters of an object state constraint and of a lifecycle state's forced
assignment are parsed and matched or searched as they are, an expression in them is never evaluated and matches nothing
(`MisconfigurationTest`; the forced assignment itself is applied by the projector on every line,
`AssignmentTripleEvaluator`); certification reviewer comments formatted when the campaign closes
(`CertificationCommentsTest`: the formatting is evaluated for every decision, also one without a comment; where they are
stored differs, see above); work item claim, release and forward, case cancellation (`ApprovalTest`), object change
approvals with a `processSpecification` (`ObjectApprovalTest`), certification reiteration
(`CertificationTest.reiterationReviewsOnlyTheCasesWithoutAResponse`; on 4.9+ in a task, like the stages), reviewer
expressions per stage and escalation timed actions (`CertificationStagesTest`); the password inbound of a readable
password and a projection's password policy (`PasswordReadingTest`), the LDAP connector's password read strategies
(`LdapPasswordTest`), extension items in mappings, changes and the merged `assignment` variable (`ExtensionTest`), a
collection filter with `$focus` (`MisconfigurationTest.collectionFilterHasNoFocus`), the owner's authorizations in a
task's bulk action search (`AccessTest.taskSearchesWithItsOwnersAuthorizations`; the bulk action authorizations an owner
needs differ: 4.0 and 4.4 `model-3#executeScript`, 4.8+ `bulk-3#<action>` per action, and a script action of a
non-superuser owner is refused by the `##legacyUnprivilegedBulkActions` profile); activation (`ActivationTest`,
`ActivationScenarioTest`): the input of a status outbound is the user's `effectiveStatus` without native validity dates
(`ActivationProcessor`, the same choice on every line), no status is provisioned without a status outbound, the validity
scanner (a fresh single-run scanner takes every date up to now) recomputes users and assignments whose dates passed, a
normal disable-instead-of-delete mapping misses the end of an assignment (zero set), the delayed delete's recompute
trigger is on the shadow and the trigger scanner deletes the account; the JVM's wall clock follows `mp.time()`
(`ClockTest`, `ActivationTest.scriptsSeeTheAdvancedTime`): the JDK sources are the same on JDK 11 (4.0, 4.4:
`Clock$SystemClock.instant()`) and JDK 21 (4.8+: `Clock.currentInstant()`), the agent is attached by the tester on 4.0
and 4.4 and given with `-javaagent` on 4.8+, and midPoint's `Clock` has the same `overrideOffset` field on every line,
which the tester copies into the JDK shift.

Not pinned by a test, from the design experiments (docs/redesign.md, E3): preview on 4.0 and 4.4 advances sequences
and really creates on demand; on 4.8+ preview writes nothing and sequences do not advance.

## Mail

Mail notifiers send through midPoint's own mail transport to the tester's in-memory SMTP server (`SmtpServer`,
GreenMail, started with the harness on `127.0.0.1` and a free port). `MailRedirect` (core) rewrites the mail servers
of a system configuration on import (README, "Mail notifications"). Per line:

| | 4.0 | 4.4 | 4.8 / 4.9 / 4.10 |
|---|---|---|---|
| Mail library of midPoint | `javax.mail:mail` 1.4.5 | `com.sun.mail:jakarta.mail` 1.6.7 (`javax.mail` packages) | `org.eclipse.angus:jakarta.mail` 2.0.5 (`jakarta.mail`, API included) |
| GreenMail | 1.6.15, its `com.sun.mail:jakarta.mail` excluded: it runs on 1.4.5 | 1.6.15 | 2.1.15, its `jakarta.mail-api` excluded |
| Configuration | `notificationConfiguration/mail` (one; `server`, `defaultFrom`, `redirectToFile`, `logToFile`, `debug`, lists) | as 4.0 | `messageTransportConfiguration/mail` (any number, by `name`; `server` with `basicAuthentication` / `oauth2Authentication` too); `notificationConfiguration/mail` is still read by the legacy `mail` transport |
| Transport class | `MailTransport` (reads the system configuration on each send) | as 4.0 | `MailMessageTransport`, configured by `TransportServiceImpl` on each system configuration change |
| Transport name `mail:x` | `mail` | as 4.0 | `mail:x` if configured, else `mail` (the legacy transport) |
| Recipients of one notifier | one message | as 4.0 | one message per recipient unless `notificationSendingStrategy` is `sameNotificationToAllRecipients` |
| `recipientFilterExpression` of a mail transport | evaluated per recipient; takes effect only with `redirectToFile` (refused recipients to the file, the others sent; `MailTransport.send`) | as 4.0 | as 4.0 (`MailMessageTransport.filterAndSetRecipients`) |
| `recipientAddressExpression` | none | none | evaluated for a recipient that is a focus (`AbstractGeneralNotifier.getRecipientAddressFromFocus`), mail and file transports |
| Attachment without an extension | `MimeTypeUtil.getDefaultExt` (`readme` -> `readme.txt`) | as 4.0 | `MimeTypeUtil.getExtension` (same result) |

All lines set the `mail.smtp.*` properties in `System.getProperties()` on each send (host, port, TLS flags): they are
global to the JVM.

## Connectors

Resources imported with `ResourceOptions.realConnector()` (README, "Real connectors") run on the connectors the line
ships: the versions of midPoint's own POM (`connector-csv` / `connector-ldap` in the dependency management of the
`midpoint` BOM; 4.0's are in `parent-4.0.3`, which its BOM does not import, so `midpoint-4.0/pom.xml` names them). The
line modules declare both with `runtime` scope and every transitive dependency excluded: the jars are ConnId bundles
with their libraries in `lib/`, loaded child-first by ConnId's `BundleClassLoader`; midPoint finds them by scanning the
classpath (`icf/scanClasspath`, set by every line's `LineSupport.config`). The LDAP server is UnboundID LDAP SDK
(`unboundid.version` in the root POM, 7.0.3: Java 8+, so it runs on the JDK 11 lines too).

| | 4.0 | 4.4 | 4.8 | 4.9 | 4.10 |
|---|---|---|---|---|---|
| ConnId framework | 1.5.0.10 | 1.5.1.10 | 1.6.0.0 | 1.6.0.0 | 1.6.0.0 |
| CSV connector | 2.3 | 2.4 | 2.8 | 2.8 | 2.9 |
| LDAP connector | 2.4.1 | 3.5.1 | 3.7.5 | 3.9.4 | 3.9.3 |
| Declared in | `midpoint-4.0/pom.xml` (versions) | `midpoint-4.4/pom.xml` (BOM) | `base-4.8/pom.xml` (BOM) | as 4.8 | as 4.8 |
| `HrToLdapRealConnectorsTest` (scenario on both real connectors) | passes | passes | passes | passes | passes |
| `ActivationRealConnectorsTest` (simulated status: a CSV column, an LDAP attribute) | passes | passes | passes | passes | passes |
| `LiveSyncRealConnectorsTest` (live synchronization: CSV copies, LDAP change log) | passes | passes | passes | passes | passes |

Passwords: the mirror returns them as the real connector does (README, "Target systems"): checked against the CSV
connector's `passwordAttribute` (`PasswordReadingRealConnectorTest`) and the LDAP connector's three
`passwordReadStrategy` values (`LdapPasswordTest`), the same on every line. ConnId's own `__PASSWORD__` (not readable,
not returned by default) stays for every other connector.

What the real connectors showed that the mirror does not (`HrToLdapRealConnectorsTest`): the CSV connector names the
unique column `ri:<column>` (ConnId's `__UID__` and `__NAME__` with the column as native name) on every line, not
`icfs:name`, so a `schemaHandling` written for `icfs:name` fails its schema test; its defaults are `;`-separated, CRLF
lines, `\` escapes and `#` comments. UnboundID's standard schema rejects an `inetOrgPerson` without `sn` and unknown
attributes, which the mirror stores; it has `member` optional in `groupOfNames` (OpenLDAP requires it). The scenario's
evaluations are the same on mirrors and real connectors on every line (the golden pairs compare both classes alike).

Activation (`ActivationRealConnectorsTest`): neither connector has a native status (the CSV connector has no
activation; the generic `LdapConnector` none for a plain server), so a resource simulates it with an attribute
(`capabilities/configured/activation/status`). The tester puts that attribute in the CSV header and in the mirror's
account class even when `schemaHandling` does not name it, and `TargetSystem.enabled` reads it with the enable and
disable values. The LDAP server's standard schema has no lock attribute (`nsAccountLock`, `pwdAccountLockedTime`):
the fixture uses `employeeType`. The results are the same on mirrors and real connectors on every line.

Live synchronization (`LiveSyncScenarioTest`, on the real connectors and on mirrors): every connector version of the
table synchronizes the same way on every line. The CSV connector (2.3 to 2.9) compares the file with its copy of the
token's run (`<tmpFolder>/<file>.sync.<millis>`); 2.3 (4.0) differs only in its first token, which reuses the newest
copy already in the folder (2.4+ always make a new one), so the tester restores the copies with the file. The LDAP
connector (2.4.1 to 3.9.x) picks the change log strategy from the root DSE's `changeLog` and reads `cn=changelog` by
`changeNumber` (`integerOrderingMatch` in UnboundID's standard schema); deletions without `targetUniqueID` are skipped
and `changeType: moddn` fails the run on every version. The token is in the task's extension on 4.0
(`LiveSyncTaskHandler`) and in the activity's perpetual work state on 4.4+ (kept when a closed single-run task runs
again). Differences pinned: the deleted-user step as in the reconciliation (4.0 evaluates the outbounds of the account
being deleted, 4.4+ the construction with a false condition); a directory deletion seen on the mirror leaves a dead
shadow that 4.0 keeps as the projection and 4.4+ as `related` (`LiveSyncMirrorTest.directoryDeletionIsAChange`).

## Golden results across lines

The cross-line golden chain (record on one line, verify on the next, accepted differences per pair in
`tester/core/src/test/golden/accepted-<a>-<b>.txt`) was removed in phase 4 of the next API with the PoC tests it
compared; the owner postponed the cross-line check (docs/next-design.md). Its last results are in git history.
