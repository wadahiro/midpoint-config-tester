# Skill feedback: add-midpoint-line, adding midPoint 4.9 (4.9.9)

Running log, written while adding the 4.9 line. Correction afterwards: 4.10 is not an LTS release; the log's
"LTS -> LTS" for 4.8 -> 4.10 is wrong (the pair is kept as a comparison of the two ends of moving from the 4.8 LTS to 4.10; there is no direct
upgrade path, the configuration is migrated through 4.9). Started 2026-10-30 02:11 JST.

## Step 0: prerequisites (02:11-02:13, ~2 min)

- Helped: "clone the release tag, not the support branch" and the shallow clone command.
- Wrong/misleading: the Nexus directory listing URL returns the Nexus HTML UI page, not a browsable listing
  (`curl .../model-api/` gives the SPA shell). I had to probe concrete POM URLs
  (`.../model-api/4.9.10/model-api-4.9.10.pom` -> 404, `4.9.9` -> 200). `git ls-remote --tags` also lists
  `v4.9.10devel`, `v4.9.9devel`, `v4.9support` tags which are not releases.
  Suggested change: "Find the latest patch with `git ls-remote --tags https://github.com/Evolveum/midpoint.git 'v4.9.*'
  | grep -v 'devel\|\^{}'`, then confirm the artifact exists: `curl -so /dev/null -w '%{http_code}'
  https://nexus.evolveum.com/nexus/repository/public/com/evolveum/midpoint/model/model-api/<v>/model-api-<v>.pom`."
- Missing: nothing about a moving base branch. Mid-task the coordinator moved `redesign` (driver labels changed,
  `accepted-4.0-4.4.txt` key format changed). The skill says "Use a feature branch" but not "rebase onto the base
  before step 7 / before finishing, and re-run golden pairs whose run keys changed".
  Suggested change: a line in the intro: "If the base branch moves, rebase early (before writing accepted files:
  run keys/labels may change) and rerun steps 6-7."

## Step 1: research (02:13-02:18, ~5 min incl. 3 min dependency resolution)

- Helped a lot: the probe. Running it on 4.8, 4.9 and 4.10 and diffing both ways showed within a minute that 4.9 is
  API-identical to 4.10 at every place the tester touches (`getTargetItemName`, `openNextStage(campaign)`,
  `ObjectUpgradeValidator()` + record `ValidationItem`, `ExtItemCache.nameToExtItem`, `validateValue`/`generate`
  arity), and that prism 4.9 already has `mutator()`/`newPropertyDefinition` (after the jar was resolved).
  Decision: a `base-4.8` line whose `LineSupport` is a copy of 4.10's (only `LINE` changes).
- Wrong: the probe's "initial objects" section is empty for 4.9. In 4.9 `config/initial-objects/` only holds a
  README; the objects live in `repo/system-init/src/main/resources/initial-objects/{archetype,role}/`. Step 3 of
  SKILL.md also says "copies of the release's `config/initial-objects`" without this alternative.
  Suggested change: probe-api.sh should search `config/initial-objects` and
  `repo/system-init/src/main/resources/initial-objects` (fall back with `find -name '0[24]*-*.xml'`), and step 3 should
  mention both locations.
- Order problem: the prism section needs `prism-api-<v>.jar` in `~/.m2`, but step 0 says to resolve dependencies
  "once the POM exists", i.e. after step 2. So the first probe run is necessarily incomplete for prism.
  Suggested change: in step 1 say "create the module POM (step 2, first bullet) and run `dependency:resolve`
  before the probe", or let the probe fetch prism-api with `mvn dependency:get -Dartifact=com.evolveum.prism:prism-api:<v>`.
- Note: the 4.8 / 4.10 sources in `~/dev/...` are at support-branch heads (4.8.13-SNAPSHOT, 4.10.4-SNAPSHOT), not at
  the pinned release tags; the probe diff then mixes in post-release changes. The skill says "clone the release tag"
  for the new line but not that the comparison line should be at its pinned tag as well. Minor here.
- Missed by the skill: comments in the copied `LineSupport` claim "new in 4.10" (`nameToExtItem`); 4.9 already has
  it. When a line is inserted *between* two lines, existing "new in X" statements in code/docs must be revisited.
  Suggested change: a bullet "Inserting a line between two supported ones: grep the code and docs for 'new in
  <next line>' / '<next line>+' / 'up to <prev line>' and re-date each claim".

## Steps 2-5: line module, SQL/baseline, recorder, drivers (02:18-02:19, ~2 min)

- Helped: "copy the closest module" plus the decision table. Copying `tester/midpoint-4.10` (POM, `LineSupport`,
  `sql/`, `baseline/`) and changing `LINE` compiled and started on the first try. Steps 4 and 5 needed no work:
  4.9 shares `base-4.8`'s recorder, hook, drivers; the probe already proved the signatures equal 4.10's.
- Missing: the skill never says *which* existing module is "closest" when the new line falls between two. The
  probe diff answered it (4.9 = 4.10 API), but a sentence "for a line between two supported ones, diff against
  both neighbours and copy the one with fewer differences" would save thinking.
- Missing: a ready recipe for refreshing files: I used
  `for f in .../sql/*.sql; do cp $SRC/config/sql/native/$(basename $f) $f; done` and the same with
  `find $SRC/repo/system-init/src/main/resources/initial-objects -name $(basename $f)` for the baseline.
  Suggested change: put these two loops in step 3 (or a `scripts/refresh-release-files.sh <src> <module>`).
- Missing: the generated-columns check for PostgreSQL 18 is only described for <4.8; a sentence "4.8+ native
  scripts are already fine" would close the question. (4.9's postgres.sql loaded without change.)

## Step 6: shared tests (02:19-02:22, ~4 min incl. three test runs)

- First run: 161 tests, 5 failures + 1 error, all at `Lines.atLeast(mp, "4.10")` gates: 4.9 already behaves
  like 4.10 at every such place except two (ReportTest locale `true`/`none`, dashboard variation colour), where
  it sides with 4.8. Each was verified in the 4.9 sources (ModelController.openNextStage -> createNextStageTask;
  MappedSourceItem with VAR_OBJECT/VAR_ASSOCIATION and the focus bean; ModelImplUtils.resolveObjectsFromRef;
  `associationType` in common-provisioning-3.xsd; AssociationConstructionExpressionEvaluatorFactory), then the
  gates were moved to "4.9". AssociationTypeTest's skipped tests (`assumeTrue(... "4.10 (4.9 not checked)")`)
  run and pass on 4.9.
- Helped: "Decide per place, never per line" and the grep for the gates; the anatomy's axes table gave the
  4.8 vs 4.10 column for each place, so the decision was "which side is 4.9 on".
- Missing / misleading: the skill frames line differences as new behaviour of the *new* line. When inserting a
  line between two existing ones the main work is the opposite: existing gates at the *next* line
  (`atLeast("4.10")`) may really be "since 4.9". The failures make it visible, but a gate where 4.9 sides with the
  newer line *and* the test passes anyway (e.g. an assertion that holds on both) stays silently mis-dated.
  Suggested change: in step 6 "When the new line is between two supported ones, every `atLeast(<next>)` gate needs a
  decision: run the tests, and for each passing gate check the sources whether the behaviour starts at the new line".
- Missing: hard-coded line numbers inside expected strings (CoverageTest's "not evaluable by midPoint 4.10")
  and test method names (`linesBefore410RejectTheDefinition`). Suggested change: "grep tests for the literal next-line
  version (`4\.10\|410`) too, not only for `Lines.atLeast`".
- Time: 3 test runs x ~25 s each for one line; running all lines 4.8/4.9/4.10 sequentially ~1.5 min.

## Step 8: static checks (02:22-02:23, ~2 min, done before step 7)

- Helped: "copy the nearest line's class and change only what the probe shows". StaticLine of 4.10 works unchanged.
- First run: 4 of 9 StaticCheckTest methods failed, again at `isBefore(LINE, "4.10")` gates: prism 4.9 already names
  items by local name, `shadowOwnerReferenceSearch`/`associationConstruction`/`associationSynchronization` and
  `associationType` exist; only the `<filter>` evaluator is 4.10+. The evaluator set needed splitting into a 4.9 and
  a 4.10 increment.
- Missing: the same "inserting between lines" note as in step 6; plus a hint that the evaluator set can be read
  straight from the xsd: `grep -o 'element name="[a-zA-Z]*"' ` over `substitutionGroup="tns:expressionEvaluator"`
  lines of `infra/schema/.../common/*.xsd` (one evaluator, `shadowOwnerReferenceSearch`, is declared elsewhere, so
  the probe could print the set via the prism context instead).
- The static module needs its own `dependency:resolve` online run (another ~1 min); the skill mentions resolving
  only for the tester module.
- Order: the step 8 test also depends on the root POM property added in step 2; commit order matters if each commit
  should build (I had to reorder my commits).

## Step 7: golden chain (02:23-02:31, ~8 min, mostly test runs)

- Helped: the command block and "fresh golden directory per pair"; the accepted file format with examples.
- Problem: copying the commands into zsh with an options variable (`O="-o -t ..."; mvn $O`) fails (zsh does not
  word-split). Minor, but a `scripts/golden-pair.sh <prev> <new> [accepted]` in the skill would avoid it and also
  copy the `*.diff.md` away (the next verify on the same module overwrites `target/tester/golden`).
- Decision the skill does not cover: where to put an inserted line in the chain. I chose 4.8 -> 4.9 -> 4.10 and kept
  4.8 -> 4.10 as the LTS upgrade check (4.8 and 4.10 are LTS, 4.9 a feature release; real upgrades go 4.8 -> 4.10).
  Suggested change: "When a line is inserted, add both hops and decide whether the old direct pair stays (keep it when
  it is a supported upgrade path, e.g. LTS -> LTS)". Also the CI report/artefact step overwrites the 4.10 diff when
  4.10 verifies twice; say so.
- Found: 4.8 -> 4.9 differs only in the association outbound value type (ShadowAssociationValueType), the same
  entry as 4.8 -> 4.10; 4.9 -> 4.10 only in the report locale column and the unstable order of the inbound filter.
  The unstable-order entry showed only in one of three 4.8 -> 4.9 runs: the skill should say "run each verify at
  least twice before declaring the accepted file complete" and the anatomy should list known unstable places.
- Found: the existing accepted entry for the association values named no pinning test ("EntitlementTest, LdapTest,
  MembershipTest"); no test asserted the value type. I added the pin to
  `EntitlementTest.associationFromLinkAddsTheMemberToTheGroupOfTheRole`. The skill's rule ("pin the change in a test,
  then accept it") was not followed by the earlier line; a check "every accepted reason names a Class.method that
  exists" would catch it.
- The verify run fails whole tests: the "N failing tests" summary per class plus `grep '^- \*\*'` in the diff files
  was the fastest way to list the real differences. A one-liner in the skill would help:
  `grep -h '^- \*\*\(changed\|missing\|new\)\*\*' target/tester/golden/*.diff.md | sort | uniq -c`.

## Step 9: documentation (02:31-02:33, ~3 min)

- Helped: the README checklist (supported lines, line notes, build commands, `grep -n '4\.[0-9]' README.md`).
- Conflict: step 9 says to add the line to `references/line-anatomy.md`, i.e. to edit the skill itself. In this task
  that was forbidden (the coordinator applies feedback), so the anatomy is NOT updated; its content for 4.9 is below.
  Suggested change: move the anatomy out of the skill into `docs/line-anatomy.md` (the skill links to it), so that
  adding a line never requires editing the skill, or say explicitly that the anatomy update is part of the line work.
- Missing: the README line-notes table did not have rows for several existing 4.10 pins (SearchTest minus set,
  VariablesTest import of filters, association value type); I added them with the 4.9 column. The skill could say
  "every `Lines.atLeast` place should have a row; check the table against the grep of step 6".
- `docs/redesign.md` "a section per line step": worked; I added "midPoint 4.9 (step 6e)" and the 4.9 column of the
  static table.

Anatomy content for 4.9 (to be applied by the coordinator): every "What a line module provides" row: "as 4.10"
(LineSupport delegates to Line48, adapters `mutator()`, `getTargetItemName()`, clears `ExtItemCache.nameToExtItem`);
baseline from `repo/system-init/src/main/resources/initial-objects`. Axes table, 4.9 column: template old state as
4.4; inbound focus `UserType` bean and `association`/`object` present (as 4.10); correlation as 4.8; preview as 4.10;
hook as 4.4; recipient as 4.8; approvers expressions order; certification reviewers in a task (as 4.10); checkExpression
label; reports as 4.8 (no locale default); dashboard variation as 4.8; `associationConstruction` evaluable (as 4.10);
association outbound value `ShadowAssociationValueType` (as 4.10, new row, `EntitlementTest`); static checks: prism
local names, evaluators without `<filter>`. Golden pairs: 4.8 -> 4.9 (association values + unstable inbound order),
4.9 -> 4.10 (report locale + unstable order), 4.8 -> 4.10 kept (LTS).

## Final verification (02:33-02:36)

`mvn -o -t toolchains.xml -P pgmem-darwin-arm64 test` from the root: all 5 tester lines 161 tests, 0 failures
(skips: 4.0 11, 4.4 2, 4.8 2, 4.9 0, 4.10 0), all 5 static modules 9 tests, 0 failures (1 skip each:
`-Dstatic.dir` not set). Total ~2 min 45 s.

## Overall assessment

The skill is good for this case: the probe plus "copy the closest module" made the module itself a 2-minute job; the
real work was re-dating existing gates, which the skill does not anticipate because it was written while adding lines
at the ends of the range. Total time ~25 min wall clock, about half of it Maven runs.

## Top 10 suggested changes

1. Add a section "Inserting a line between two supported ones": diff the probe against both neighbours, copy the
   closer module, and give every `atLeast(<next line>)` / `isBefore(LINE, <next line>)` gate a decision from the
   sources, including gates whose test passes anyway; also grep literal versions (`4\.10`, `410`) in expected strings
   and test method names, and re-date "new in <next>" comments in code, accepted files and docs.
2. probe-api.sh: also look for initial objects under `repo/system-init/src/main/resources/initial-objects` (4.9);
   step 3 should name both locations and give the copy loops for SQL and baseline files.
3. Fix the order of step 0/1: create the module POM and run `dependency:resolve` before the probe (or let the probe
   fetch `prism-api` itself); the static module needs its own resolve too.
4. Step 0: give a working way to find the latest release (`git ls-remote --tags ... | grep -v 'devel\|support\|\^{}'`
   and a HEAD on the concrete Nexus POM URL); the Nexus directory URL in the skill returns the UI shell, not a listing.
5. Step 7: say where an inserted line goes in the chain and whether the old direct pair stays (keep 4.8 -> 4.10: LTS
   upgrade path); mention that a module verified twice overwrites its `target/tester/golden` report (copy it away;
   CI artefacts too).
6. Step 7: run each verify at least twice before declaring the accepted file complete; list the known unstable place
   (association inbound filter order in `EntitlementTest.associationFromLinkTakesOnlyTheGroupOfEachRole`).
7. Step 7: require every accepted entry to name an existing pinning `Class.method`; the 4.8 -> 4.10 association entry
   had none. A tiny check (grep the reasons for `[A-Z][a-zA-Z]*Test\.[a-z]`) would enforce it.
8. Ship `scripts/golden-pair.sh <prev> <new> [accepted]` (record, verify, copy the diff, print
   `grep -h '^- \*\*\(changed\|missing\|new\)\*\*' ... | sort | uniq -c`); it avoids shell quirks (zsh word-splitting)
   and makes the triage one command.
9. Move `references/line-anatomy.md` to the repository docs (or say that updating it is part of adding a line), so
   that the per-line facts can be maintained without editing the skill; keep the README line-notes table complete
   against the gates grep.
10. Mention a moving base branch: rebase before writing accepted files (run keys and labels change) and rerun steps
    6-7 after the rebase; and suggest commit order (root POM property with the tester module, then the static module)
    so that every commit builds.

## Addendum (02:38)

- Review before handback found three more comments that still dated behaviour to 4.10 or listed lines without 4.9
  (RecorderTest preview comment, ReportTest locale javadoc, ReportTest dashboard comment), in places whose gates did
  not need to move. This confirms suggestion 1: grep comments for the neighbour line, not only the gates.
- CI YAML validated with `ruby -ryaml` (no PyYAML here). The skill could name a way to validate `ci.yml` locally.
- Golden hops verified locally: 4.8 -> 4.9, 4.9 -> 4.10, 4.8 -> 4.10. 4.0 -> 4.4 and 4.4 -> 4.8 were not re-run (the
  shared-test changes only move gates and add an assertion; recorded evaluations are unaffected).
