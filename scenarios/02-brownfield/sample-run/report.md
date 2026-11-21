> Recorded with real `mvn -q -B test` in the `verify` stage, offline (deterministic) agents. Full run folder: `runs/run-20261121-030332-brownfield-8444/` (state, artifacts, audit log, journals, workspace, changes.patch).

# Run report: `run-20261121-030332-brownfield-8444`

| | |
|---|---|
| Scenario | brownfield |
| Status | **SUCCEEDED** |
| Initiator | manas |
| Plan version | 4 |
| Audit chain | ✅ chain intact (130 entries) |

## Stage graph

```
── ✔ requirements    SUCCEEDED              attempts=1  6ms
── ✔ design          SUCCEEDED              attempts=1  18ms
── ✔ plan            SUCCEEDED              attempts=1  0ms
┬ parallel
├─ ✔ implement       SUCCEEDED              attempts=4 reworks=1  17ms
├─ ✔ test-design     SUCCEEDED              attempts=1  11ms
── ✔ migration-check SUCCEEDED              attempts=2 invalidated=1  5ms  [added by planner]
── ✔ verify          SUCCEEDED              attempts=2  13701ms
┬ parallel
├─ ✔ security        SUCCEEDED              attempts=1  3ms
├─ ✔ docs            SUCCEEDED              attempts=1  2ms
── ✔ release         SUCCEEDED              attempts=1  1ms
```

## Human checkpoints

| Id | Stage | Kind | Role | Risk | Status | By | Comment | Why |
|---|---|---|---|---|---|---|---|---|
| A-1 | design | SIGNOFF | tech-lead | MEDIUM | APPROVED | alice | Additive nullable column, expand/contract. Approved. | sign-off required for stage design |
| A-2 | implement | CHANGE_SET | tech-lead | MEDIUM | APPROVED | alice | Migration V2 reviewed. | CHG-001 [REQUIRE_APPROVAL] database schema change (src/main/resources/db/migration/V2__link_expiry.sql) |
| A-3 | implement | CHANGE_SET | tech-lead | MEDIUM | APPROVED | alice | Re-reviewed the corrected change set. | CHG-001 [REQUIRE_APPROVAL] database schema change (src/main/resources/db/migration/V2__link_expiry.sql) |
| A-4 | release | SIGNOFF | release-manager | HIGH | APPROVED | rahul | Release approved. | sign-off required for stage release |

## Re-planning and recovery events

- `2026-11-21T03:03:32.743602Z` **replan.node-added** migration-check: {"reason":"design introduces a schema migration [V2 (additive, for expiry)]","stage":{"id":"migration-check","agent":"migration-check","dependsOn":["implement"],"consumes":["code-changes","impact"],"produces":["migration…
- `2026-11-21T03:03:32.743990Z` **replan.edge-added** verify: {"dependsOn":["implement","test-design","migration-check"]}
- `2026-11-21T03:03:39.669956Z` **rollback** implement: {"reason":"rework after verify failed","journal":"implement-a2","files":10}
- `2026-11-21T03:03:39.670234Z` **replan.rework** verify: {"rework":1,"routeTo":"implement","reason":"Downstream stage 'verify' failed: gate tests-pass failed: 2 of 104 tests failing → LinkExpiryTest#expiredLinkIsGone: Expecting code to raise a throwable.; LinkExpiryTest#notYet…
- `2026-11-21T03:03:39.687488Z` **replan.invalidated** migration-check: {"artifact":"code-changes","latestVersion":2,"consumedVersion":1}

## Policy findings

- implement: `CHG-001` REQUIRE_APPROVAL: database schema change (src/main/resources/db/migration/V2__link_expiry.sql)
- implement: `CHG-001` REQUIRE_APPROVAL: database schema change (src/main/resources/db/migration/V2__link_expiry.sql)

## Decision lineage

- **requirements**: Normalized requirement into 3 acceptance criteria across features [redirect, expiry] _(because each criterion is tied to named tests, so 'done' is checked by the ac-traceability gate, not asserted)_ inputs={"requirement":1}
- **design**: Extend existing modules in place _(because impact analysis shows the change fits existing module boundaries; no new service needed)_ inputs={"spec":1}
- **design**: Additive, forward-only schema migration [V2 (additive, for expiry)] _(because nullable new column keeps old rows valid and allows zero-downtime rollout (expand/contract))_ inputs={"spec":1}
- **plan**: Insert migration-check stage into the live graph _(because schema changes are the hardest changes to roll back; an automated additive-only check runs before tests)_ inputs={"spec":1,"design":1,"impact":1}
- **plan**: Decomposed into 9 tasks _(because every acceptance criterion maps to at least one implementation and one test task)_ inputs={"spec":1,"design":1,"impact":1}
- **implement**: [FAULT INJECTION] first attempt for expiry uses the naive variant _(because demonstrates detection by tests, journal rollback and rework with feedback)_ inputs={"spec":1,"plan":1,"impact":1,"design":1}
- **implement**: Change set for [expiry] (10 files) _(because verified playbook implementation)_ inputs={"spec":1,"plan":1,"impact":1,"design":1}
- **test-design**: Designed 3 AC-to-test mappings, 1 test files _(because tests come from acceptance criteria, written in parallel with (and independent of) the implementation)_ inputs={"plan":1,"spec":1}
- **migration-check**: Migration review: safe _(because automated expand/contract checks)_ inputs={"code-changes":1,"impact":1}
- **verify**: Ran test suite: 104 tests, 2 failing _(because real execution in the workspace; results are evidence for the gates)_ inputs={"code-changes":1,"spec":1,"test-cases":1,"test-changes":1}
- **implement**: Revised expiry after feedback _(because previous attempt failed: Downstream stage 'verify' failed: gate tests-pass failed: 2 of 104 tests failing → LinkExpiryTest#expiredLinkIsGone: Expecting code to raise a throwable.; LinkExpiryTest#notYetExpiredLinkStillRe…)_ inputs={"spec":1,"plan":1,"impact":1,"design":1}
- **implement**: Change set for [expiry] (10 files) _(because verified playbook implementation)_ inputs={"spec":1,"plan":1,"impact":1,"design":1}
- **migration-check**: Migration review: safe _(because automated expand/contract checks)_ inputs={"code-changes":2,"impact":1}
- **verify**: Ran test suite: 104 tests, 0 failing _(because real execution in the workspace; results are evidence for the gates)_ inputs={"code-changes":2,"spec":1,"test-cases":1,"test-changes":1}
- **docs**: Docs: docs/changes/REQ-8444.md + CHANGELOG entry _(because docs generated from verified evidence, not from intent)_ inputs={"code-changes":2,"spec":1,"test-report":1,"design":1}
- **security**: Security review of 11 files: 0 findings _(because policy rules re-applied to the final change set plus static smell checks)_ inputs={"test-changes":1,"code-changes":2}
- **release**: Recommend release (pending human sign-off) _(because computed from test, security, migration and approval evidence)_ inputs={"docs":1,"migration-check":1,"test-report":1,"security-findings":1,"spec":1}

## Metrics

| Metric | Value |
|---|---|
| runStatus | SUCCEEDED |
| stages | 10 |
| stagesSucceeded | 10 |
| attempts | 15 |
| attemptSuccessRate | 0.933 |
| firstPassYield | 0.9 |
| retries | 0 |
| retryRate | 0.0 |
| fallbacks | 0 |
| rollbacks | 1 |
| rollbackRate | 0.067 |
| reworks | 1 |
| invalidations | 1 |
| planVersion | 4 |
| llmCalls | 0 |
| incidents | 1 |
| incidentsRecovered | 1 |
| mttrMs | 6845 |
| approvalsRequested | 4 |
| approvalsDecided | 4 |
| approvalsRejected | 0 |
| questionsAsked | 0 |
| endToEndLatencyMs | 13847 |
| agentExecutionMs | 13764 |
| humanWaitMs | 10 |
| stageLatencyMs | {"requirements":6,"design":18,"plan":0,"implement":17,"test-design":11,"verify":13701,"security":3,"docs":2,"release":1,"migration-check":5} |

## Artifacts (versioned, content-addressed)

| Artifact | SHA-256 | Produced by | Mode | Inputs |
|---|---|---|---|---|
| requirement@v1 | `11968598e660` | human (attempt 0) | human | {} |
| spec@v1 | `7d0661cf42e0` | requirements (attempt 1) | deterministic | {"requirement":1} |
| impact@v1 | `5093683b7f7e` | design (attempt 1) | deterministic | {"spec":1} |
| design@v1 | `e524a0b2dc34` | design (attempt 1) | deterministic | {"spec":1} |
| plan@v1 | `6a8bd524439f` | plan (attempt 1) | deterministic | {"spec":1,"design":1,"impact":1} |
| implement-proposed@v1 | `d35f16bb40d4` | implement (attempt 1) | deterministic | {"spec":1,"plan":1,"impact":1,"design":1} |
| implement-proposed@v2 | `3a5c1236234f` | implement (attempt 3) | deterministic | {"spec":1,"plan":1,"impact":1,"design":1} |
| test-cases@v1 | `1a7132ba5825` | test-design (attempt 1) | deterministic | {"plan":1,"spec":1} |
| test-changes@v1 | `98062b9a53c6` | test-design (attempt 1) | deterministic | {"plan":1,"spec":1} |
| code-changes@v1 | `02dd4facf7fd` | implement (attempt 2) | human-approved | {"spec":1,"plan":1,"impact":1,"design":1} |
| code-changes@v2 | `bb433e1e8bbe` | implement (attempt 4) | human-approved | {"spec":1,"plan":1,"impact":1,"design":1} |
| migration-check@v1 | `2b98094a4c34` | migration-check (attempt 1) | deterministic | {"code-changes":1,"impact":1} |
| test-report@v1 | `6ec8a2e30712` | verify (attempt 2) | deterministic | {"code-changes":2,"spec":1,"test-cases":1,"test-changes":1} |
| docs@v1 | `a0b8117bfd40` | docs (attempt 1) | deterministic | {"code-changes":2,"spec":1,"test-report":1,"design":1} |
| doc-changes@v1 | `c48491c61dda` | docs (attempt 1) | deterministic | {"code-changes":2,"spec":1,"test-report":1,"design":1} |
| security-findings@v1 | `2f7e26497a00` | security (attempt 1) | deterministic | {"test-changes":1,"code-changes":2} |
| release-checklist@v1 | `491586f45f47` | release (attempt 1) | deterministic | {"docs":1,"migration-check":1,"test-report":1,"security-findings":1,"spec":1} |
| release-notes@v1 | `2bb39b994d07` | release (attempt 1) | deterministic | {"docs":1,"migration-check":1,"test-report":1,"security-findings":1,"spec":1} |
