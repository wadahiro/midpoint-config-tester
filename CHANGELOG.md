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
