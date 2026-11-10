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
