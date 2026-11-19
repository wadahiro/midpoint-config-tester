# [0.12.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.11.0...v0.12.0) (2026-11-19)


### Bug Fixes

* **gui:** a user's GUI profile has the GUI's part from the first login on, whether or not a form loaded the GUI ([27b3a63](https://github.com/wadahiro/midpoint-config-tester/commit/27b3a639d2a3912e003831f4a9d051f28221298f))
* **membership:** read memberships by the association's value attribute ([00c3553](https://github.com/wadahiro/midpoint-config-tester/commit/00c35537ad35723837224ebdec5329e8760b4b10))


### Features

* **failure:** t.expectFailure(...).userMessage("ja") is the refusal as the GUI shows it, translated with the deployment's localization (config.localization) ([f6de172](https://github.com/wadahiro/midpoint-config-tester/commit/f6de172112d2fbe7c462b681df05879d5c264b6f))
* **forms:** t.newObjectForm(type) and t.editForm(type, name) run the GUI's own details page in the JVM, as the user: a value typed into a field, Save, and whether the user may open the page ([8f72124](https://github.com/wadahiro/midpoint-config-tester/commit/8f72124edea0697a6b587e179e14985a5d9bbd15))
* **membership:** read 4.9+ simulated reference types as associations ([bfb3bdf](https://github.com/wadahiro/midpoint-config-tester/commit/bfb3bdf97d7f261ac2be77b713fdec033a4e0808))
* **mirror:** an identifier per object class that the target system issues (identifier: { User: userId, Group: groupId }), and memberships a group holds by the accounts' identifiers read through them ([81dbfb9](https://github.com/wadahiro/midpoint-config-tester/commit/81dbfb90700b144b8abc72fd2586c281dfb4137a))

# [0.11.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.10.0...v0.11.0) (2026-11-18)


### Features

* **manifest:** repository.ddl runs the deployment's own DDL on midPoint's repository before the post-initial import and before a cache restore; the files are part of the cache key ([0a901f2](https://github.com/wadahiro/midpoint-config-tester/commit/0a901f29230519edbe98d68e221275dc36452d4b))
* **rest:** RestResponse.json() parses the body as Jackson's tree ([3a163ac](https://github.com/wadahiro/midpoint-config-tester/commit/3a163ac7055931c354a4f779c5979aa5edf464fa))
* **rest:** t.rest() sends a request to midPoint's REST API (4.8 and later) through its security filter chain and controllers, in a web context over the tester's context, as a driver call ([1872ace](https://github.com/wadahiro/midpoint-config-tester/commit/1872ace8121ee1553ce4c4f61038730950ab9182))
* **timing:** the JVM before the tester, the deployment's load, each test's own time, a JSON report compared with the previous run or a baseline ([a28b1ff](https://github.com/wadahiro/midpoint-config-tester/commit/a28b1ff6740223e5fe593d5b44cd741107ac76d2))


### Performance Improvements

* **effects:** a task's or node's modification is recorded without reading the object before and after, and a wait does not search the tasks while task threads run ([0d37442](https://github.com/wadahiro/midpoint-config-tester/commit/0d37442483c15a321b0b241c46d364bdef18fc29))
* **reset:** a reset back to the state Quartz still matches (no task row written, no job or trigger changed or fired since) leaves out the synchronization of the tasks and the re-arming of their triggers ([16c0055](https://github.com/wadahiro/midpoint-config-tester/commit/16c005545297ddd0f1d15b624afa7120b031504e))
* **reset:** keep the provisioning caches on 4.4 and 4.8+ too, invalidating every other cache of midPoint's cache registry (all of them when the registry cannot be read so) ([e80082d](https://github.com/wadahiro/midpoint-config-tester/commit/e80082d77eddeb97623c1ecac08939566efd9693))
* **reset:** on 4.0 a reset back to the state the provisioning caches still match (no resource or connector row written since) keeps the resource cache and the connector manager's instances and types ([1b4213c](https://github.com/wadahiro/midpoint-config-tester/commit/1b4213ce7cfafd4e1a0ac92542609cb50b4cc6d0))
* **reset:** reuse the administrator's login of the last reset to the same state when neither the user nor a role, org, archetype, service, security policy or system configuration row was written since and the user's version is unchanged ([2099411](https://github.com/wadahiro/midpoint-config-tester/commit/2099411eec708df42a3971fef8d5ed72f1a31291))
* **wait:** the waits for background work wake on its changes (task threads and runs, Quartz jobs) instead of a 20 ms tick, and time each task ([2211db2](https://github.com/wadahiro/midpoint-config-tester/commit/2211db27884dfaf27043620d23af99c6b6c7cafa))

# [0.10.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.9.0...v0.10.0) (2026-11-18)


### Bug Fixes

* **raw:** a lookup table's rows written past the model are read back ([29a1ed3](https://github.com/wadahiro/midpoint-config-tester/commit/29a1ed36da9b397df0d56650e1a8746b12e359cf))
* **time:** midPoint 4.0's identifiers allow a short step back of the host clock ([86331d3](https://github.com/wadahiro/midpoint-config-tester/commit/86331d3e7e08c1c83dbd467370de443d45995192))


### Features

* **actor:** an actor changes and deletes as a logged-in user, in the GUI's channel ([61d97fb](https://github.com/wadahiro/midpoint-config-tester/commit/61d97fb613b2648e9817981d29a01f29ebe04f0e))
* **load:** later stages of the configuration, each imported after the ones before it ([1222f10](https://github.com/wadahiro/midpoint-config-tester/commit/1222f10fba3917b2faf83e3957d7ec3e5190dd64))
* **mirror:** an issued identifier already there can be handed back instead of failing ([005ea83](https://github.com/wadahiro/midpoint-config-tester/commit/005ea83e90be985400da360656722379137824de))

# [0.9.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.8.0...v0.9.0) (2026-11-17)


### Bug Fixes

* **effects:** the tasks the tester creates are not effects of the block ([a4be14b](https://github.com/wadahiro/midpoint-config-tester/commit/a4be14b2e0d445a126cfefaf050d3a3ddadc72c9))


### Features

* **assign:** an assignment with a validity: assign(role, a -> a.validTo(end)) ([3576544](https://github.com/wadahiro/midpoint-config-tester/commit/357654470b0bf7c5f191e538085e1368bc4cf3c4))
* **effects)!: drop ignored(:** every effect a verification lets through is checked ([d5e6233](https://github.com/wadahiro/midpoint-config-tester/commit/d5e6233cbb94b2f915e5aba370d3e88a73a23461))
* **effects:** verifyAll(c -> ...) verifies a verb's effects in one call from Java ([ea9f81c](https://github.com/wadahiro/midpoint-config-tester/commit/ea9f81c60c492f31999a8b8a21419d90ed22add2))
* **real:** any connector against a system the test provides, restored through a RealSystem ([0118b2f](https://github.com/wadahiro/midpoint-config-tester/commit/0118b2f44bcc571dcf9431f325da08500addf0e9))
* **scripts:** a resource's provisioning scripts and its additional connectors on the mirror ([5758e52](https://github.com/wadahiro/midpoint-config-tester/commit/5758e522c7d49d7dff985d9d715b95e6732726cd))


### BREAKING CHANGES

* **effects)!: drop ignored(:** Changes, ConnectorCalls, Mails, Logs and Audit lose ignored(); claim
what it let through with exactly(n), single() or none().

# [0.8.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.7.1...v0.8.0) (2026-11-16)


* feat!: a verb that makes midPoint work returns what midPoint did ([c44fcb0](https://github.com/wadahiro/midpoint-config-tester/commit/c44fcb00adc0b15cef5f23933a69fb09db948f01))
* feat!: one way to say each thing in the API ([273f3ce](https://github.com/wadahiro/midpoint-config-tester/commit/273f3ce9b819d4ccbd9972796f2000de862debd9))
* feat!: run midPoint in the server's time zone, a required tester.timeZone ([b8f5d13](https://github.com/wadahiro/midpoint-config-tester/commit/b8f5d132d2661a499cc4b96e960446302618a7e9))
* feat!: users, roles, orgs and services as focuses, their projections as they are on the target system ([e926194](https://github.com/wadahiro/midpoint-config-tester/commit/e926194a1f0fd201bd513e1ab3dac5b9d114a74f))
* feat(kotest)!: check what a container's body made in a precondition, its first test ([f65d9d8](https://github.com/wadahiro/midpoint-config-tester/commit/f65d9d8e7556b80196c9b1206bd274186c83d7ed))


### Bug Fixes

* guard a fork's connections, Socket loaded before the agent ([7694b33](https://github.com/wadahiro/midpoint-config-tester/commit/7694b33744cbf93888a41704f66889cdf79ec34a))
* **mirror:** do not return attributes the schema does not let midPoint read by default ([71cb063](https://github.com/wadahiro/midpoint-config-tester/commit/71cb0633cb83f743799060a3562788d578cf5c3d))
* **mirror:** refuse an attribute the object class does not have ([7f3bde8](https://github.com/wadahiro/midpoint-config-tester/commit/7f3bde89116d81a87effeb9d484b5ec64eb008fb))


### Features

* add, modify and delete a user, role, org or service through the model ([8c9802b](https://github.com/wadahiro/midpoint-config-tester/commit/8c9802be9e79f60e42ebc48492b3636bb7179fc4))
* claim the configuration at rest with Changes.focuses() ([a360cb0](https://github.com/wadahiro/midpoint-config-tester/commit/a360cb08d88797a100b214c2a8b741bbdcf9483d))
* **static:** take a script without a language in the deployment's default language ([a2fd7c5](https://github.com/wadahiro/midpoint-config-tester/commit/a2fd7c59341bd5345d7bfb3887282dc5abc0b8e5))
* **task:** suspend a task as the GUI's Suspend ([0bf7057](https://github.com/wadahiro/midpoint-config-tester/commit/0bf7057b2cd30596d7ec7730a45896cd4e03163e))
* **tester:** add the midpoint 4.11 line ([c6fe6bb](https://github.com/wadahiro/midpoint-config-tester/commit/c6fe6bb06385af7b822b9db72ac7e194efbd3b99))
* write items and linked accounts past the model through t.raw() ([fd09670](https://github.com/wadahiro/midpoint-config-tester/commit/fd09670ee5737c3809ec07350efca3331078ca03))


### BREAKING CHANGES

* a config repository sets <tester.timeZone> in its POM.
* hasRole/hasNoRole are hasAssignment/hasNoAssignment and
count the default relation only unless a relation is given; the Failure
checks return the Failure; isInStage is atStage; the selections take a
class instead of a type name; Status, Account.status() and
User.setPassword are gone; Cases.named is an exact name.
* accounts(resource) is a selection (names() gives the
list); account(...) returns Projection<Account>; hasAccountOn fails when
the target system does not have the linked account, and hasAccountOn and
hasNoAccountOn count live shadows of kind account only;
RawObject.add/delete are addValues/removeValues; Resource throws
IllegalArgumentException for a resource the deployment does not have.
* the verbs return Effects; a chain after a verb
(campaign.closeStage().remediate(), user.assign(r).hasAssignment(r)) is
two statements now.
* a container whose body changes midPoint needs a
precondition as its first test.

## [0.7.1](https://github.com/wadahiro/midpoint-config-tester/compare/v0.7.0...v0.7.1) (2026-11-12)


### Bug Fixes

* **names:** keep a generated class that another JDK wrote in the other Unicode form ([7ad6ec7](https://github.com/wadahiro/midpoint-config-tester/commit/7ad6ec7388bfa3479b8d056cd88ef30b2dff356d))

# [0.7.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.6.0...v0.7.0) (2026-11-12)


* feat!: each line on the JDK its midPoint supports, without a toolchain ([b4f6c55](https://github.com/wadahiro/midpoint-config-tester/commit/b4f6c5514d6d7eec3fbbb8afbb14e55e9a1b3aa7))


### BREAKING CHANGES

* the profile jdk11-toolchain is gone: run Maven on JDK 11
for a config repository on 4.0, and on a JDK the line supports for the
others (a build on another one fails before the tests).

# [0.6.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.5.0...v0.6.0) (2026-11-11)


* fix(task)!: run resumed tasks as midPoint does and drop tasks.recurring ([ca01b8e](https://github.com/wadahiro/midpoint-config-tester/commit/ca01b8ee920b825f9aceaec6e152ac8a009133b9))


### Bug Fixes

* **task:** say why a suspended task cannot run ([11f7210](https://github.com/wadahiro/midpoint-config-tester/commit/11f7210d57eff36797fca0c28e8f9233251daeab))
* **task:** wait for a single-run task that 4.0 binds tightly ([2f7cf5e](https://github.com/wadahiro/midpoint-config-tester/commit/2f7cf5e44b02999ccdbfd0168e6e3de019e1922a))


### BREAKING CHANGES

* a manifest with a tasks key fails to load; delete the line
(tasks: { recurring: held } was the default).

# [0.5.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.4.0...v0.5.0) (2026-11-11)


* feat(task)!: a run that failed on some objects fails alike everywhere, unless allowPartialErrors() ([70628b6](https://github.com/wadahiro/midpoint-config-tester/commit/70628b67621cce38d3b8d99190ec064e2be33383))


### BREAKING CHANGES

* t.clock().runDue() fails when a due task failed on some of its
objects (PARTIAL_ERROR); t.clock().allowPartialErrors().runDue() passes it.

# [0.4.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.3.0...v0.4.0) (2026-11-10)


### Bug Fixes

* **build:** compile Kotlin against the build's JDK, not a reused daemon's ([370db6b](https://github.com/wadahiro/midpoint-config-tester/commit/370db6bd13e3d2671caa2e54d2177be72528f842))
* **build:** pin the compiler and resources plugins for Maven before 3.9 ([e34875f](https://github.com/wadahiro/midpoint-config-tester/commit/e34875fabec838134c531fc4f0a65702b550e106))
* **effects:** name the accounts and leave out the cached shadow attributes on 4.9 on ([5d705ff](https://github.com/wadahiro/midpoint-config-tester/commit/5d705ff1bb81669e4c898b1768ea1b371bf6db2e))


### Features

* **effects:** match connector call accounts ignoring case ([72ff690](https://github.com/wadahiro/midpoint-config-tester/commit/72ff690c9a8f423aefe559647dde28c984d9988d))
* effectsOf, what a block did, verified exhaustively ([6d8f797](https://github.com/wadahiro/midpoint-config-tester/commit/6d8f797195b6daa65529471b3e99376ad42cb339))

# [0.3.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.2.1...v0.3.0) (2026-11-10)


* feat!: Kotlin-friendly names, and no JDK 11 toolchain unless Maven needs one ([3e6f695](https://github.com/wadahiro/midpoint-config-tester/commit/3e6f695392fc001f78446b99d9141be8400ea862))


### BREAKING CHANGES

* as(user) is now actor(user); User.object() and
Shadow.object() are now asObject(); MidPoint.object(type, nameOrOid)
and TargetSystem.object(objectClass, name) are now find(...).

## [0.2.1](https://github.com/wadahiro/midpoint-config-tester/compare/v0.2.0...v0.2.1) (2026-11-10)


### Bug Fixes

* **parent:** let the Kotlin test compilation read the Java test sources ([18b3cba](https://github.com/wadahiro/midpoint-config-tester/commit/18b3cba9996a30469999d5d2b2f90b530f701d3d))

# [0.2.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.1.0...v0.2.0) (2026-11-10)


### Features

* checkpoints, Kotest specs and nested classes that share a prefix ([7a32232](https://github.com/wadahiro/midpoint-config-tester/commit/7a32232261d90cc42728dc673e7f701acaf9c5b0))


### BREAKING CHANGES

* @MidPointTest, ResetMode, MidPoint#time() and the public
Time are removed; golden.mode and the other golden.* properties are gone;
@MidPointScenarios, Scenario, ScenarioTask and ScenarioTime are renamed
(MidPointConfigTest, MidPointConfigTester...), Scenario#time() is
MidPointConfigTester#clock().

# [0.1.0](https://github.com/wadahiro/midpoint-config-tester/compare/v0.0.0...v0.1.0) (2026-11-09)


### Bug Fixes

* **tester:** index the gui pages of 4.9 and 4.10 ([fe9ee41](https://github.com/wadahiro/midpoint-config-tester/commit/fe9ee41cfb36ce5b8d8a5b783e83db8f2d68d5dd))


### Features

* midPoint config tester ([b68274a](https://github.com/wadahiro/midpoint-config-tester/commit/b68274a0ff696e42fab0cd6bd49f7add18b7afce))
* **tester:** stop the clock at an instant ([a151131](https://github.com/wadahiro/midpoint-config-tester/commit/a1511317d6d19e91bba7dc359c90f40e4a97d363))
