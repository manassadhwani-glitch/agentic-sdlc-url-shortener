# Testing approach

Testing happens at three levels, and each one proves something different.

## 1. Orchestrator guarantees (`sdlc-orchestrator/src/test`, 37 tests)
The engine is tested with **scripted fake agents**, so each orchestration property is proven deterministically without an LLM or Maven.

| Property | Test |
|---|---|
| Parallel branches really overlap; the join waits for both | `EngineTest#parallelBranchesRunConcurrentlyAndJoinBeforeDownstream` |
| Retry with backoff + error fed back; MTTR recorded | `#transientFailureIsRetriedWithFeedbackThenSucceeds` |
| Retries are bounded | `#retriesAreBounded` |
| Model output keeps failing → bounded model attempts, then **fallback** to the deterministic agent; lineage records `fallback` | `#exhaustedModelAttemptsFallBackToTheDeterministicAgent` |
| No pointless fallback loop when no model is configured | `#noFallbackLoopWhenTheModelIsUnavailable` |
| Failed verification → rollback → route back → rework | `#downstreamFailureRoutesBackRollsBackAndReworks` |
| Pause for sign-off, persist, resume **in a new engine**, no redo | `#signoffPausesPersistsAndResumesInAFreshEngine` |
| Rejection comment reaches the reworked stage; fresh sign-off needed | `#rejectionSendsWorkBackWithTheReviewerComment` |
| Lineage invalidation + **hash short-circuit** | `#changedRequirementInvalidatesByLineageWithHashShortCircuit` |
| Planner adds a stage to the live graph; invalid proposals rejected | `#plannerCanAddAStageToTheLiveGraph`, `#invalidPlannerProposalIsRejectedAndGraphUnchanged` |
| Kill switch safe-stops; run resumes | `#killSwitchSafeStopsAndRunResumes` |
| Critical policy violation stops the run, never retried, nothing written | `#criticalPolicyViolationSafeStopsWithoutTouchingTheWorkspace` |
| Terminal failure → saga compensation restores the workspace | `#terminalFailureCompensatesAllAppliedChanges` |
| Policy-gated change set written only after approval, applied verbatim | `#policyGatedChangeIsAppliedOnlyAfterApproval` |
| Audit chain detects edits, deletions and a rewrite without the key | `FoundationTest#auditChain*` |
| Workspace boundaries (`..`, absolute paths, out of scope); atomic change sets | `FoundationTest#workspace*`, `#failedChangeSetLeavesNoPartialWrites` |
| Workflow validation (cycles, unknown deps, unbounded retries, missing scope/role) | `FoundationTest#validator*` |
| Policies (secrets, injection, migrations, destructive DDL, dependencies, size, PII) | `GovernanceTest` |
| Separation of duties, roles, rejection comments, immutable decisions | `GovernanceTest#*Approval*`, `#separationOfDuties*` |

## 2. Service correctness (`url-shortener-service/src/test`, 102 tests as run by Surefire, including the SSRF regression tests)
- **Unit tests:**
  - codec: uniqueness over 100k ids, non-adjacency, bounds
  - URL validator: 23 unsafe cases including SSRF, metadata IPs and userinfo tricks
  - alias policy
  - token bucket, including a 16-thread concurrency test
  - analytics helpers (UA family, referrer host only, salted IP hash)
  - click recorder (drops are counted, flush failure is contained)
  - service with Mockito (cache hit, unknown code not cached, tenant isolation)
- **Integration tests** (real Spring context, H2, Flyway, MockMvc):
  - create → redirect
  - 401 / 400 / 409 / 410 / 422 error contracts
  - idempotent replay, scoped per tenant
  - stats aggregation
  - rate limiting per key and per IP; health and metrics endpoints
- **Concurrency tests:** 8 threads released at the same moment race for the same alias, or retry with the same `Idempotency-Key`. Exactly one link must exist.
- **Coverage gate:** JaCoCo, 70% line coverage minimum (`mvn verify` fails below it).

## 3. End-to-end scenarios (`scenarios/*`)
Each scenario runs the full pipeline against a real copy of the service. The `verify` stage executes the real test suite (`mvn -q -B test`) in the run's workspace, and the **ac-traceability gate** requires every acceptance criterion's named tests to have passed. "Done" is proven, not asserted.

| Scenario | Proves |
|---|---|
| greenfield | full DAG; parallel paths; design rejection → rework; policy-gated large change sets; release sign-off |
| brownfield | impact analysis on real code; planner inserts `migration-check`; schema approval; **injected bug caught by tests → rollback → rework with feedback → re-approval → recovery (MTTR)** |
| ambiguous | clarifying questions; **compliance policy overrides a human's answer** (raw IPs) and the agent self-corrects; mid-run requirement amendment → lineage invalidation, new stage, re-approvals |
| bugfix | a **real** security bug in existing code (SSRF bypass via decimal/hex/octal IPs) fixed; regression tests written from the bug report; existing URL-safety criteria re-proven |

### What the scenarios caught while being built
Running the scenarios end to end found two real defects that unit tests had missed. That's the reason the scenarios exist:
1. **Compliance regex false positive:** the agent's compliant rewrite ("pseudonymised") was still blocked, because the exemption matched only the exact word "pseudonym". Fixed, and covered by `GovernanceTest#complianceBlocksRawPiiButAllowsPseudonymisedForms`.
2. **Race between parallel stages:** `test-design` decided whether tests were needed by checking for code that `implement` was writing at the same moment. The `ac-traceability` gate blocked the release, the bounded rework loop gave up, and saga compensation restored the workspace. So the failure path worked, but the bug was real. Fix: test-design now decides from the test tree only.

### A real bug found by probing the service
While closing the "bug fixes" part of the brief, I probed the service's SSRF filter with alternative IPv4 encodings. Three were accepted:
- `http://2130706433/` (decimal)
- `http://0x7f000001/` (hex)
- `http://0177.0.0.1/` (octal)

Browsers and HTTP clients resolve all three to 127.0.0.1. The original tests only used dotted-decimal addresses, so they missed it. `scenarios/04-bugfix` has the orchestrator fix the bug in the existing code and add `UrlValidatorEncodingTest`. The fix was then reviewed and merged into `url-shortener-service` ("agents propose, humans merge"). Re-running the scenario now reports "no code change required", so it's idempotent.

## Fault injection
`--fault implement:naive-first-attempt` (set in the brownfield scenario) makes the implementation agent's first attempt use a known-buggy variant (inverted expiry check). It is labelled `[FAULT INJECTION]` in the decision log. This lets the detection → rollback → rework path be shown on demand, the way chaos testing is used for infrastructure.

## How to run
```bash
mvn -B verify                                       # all unit + integration tests, coverage gate
mvn -pl sdlc-orchestrator test                      # orchestrator only (fast, no Spring)
orch demo scenarios/02-brownfield/scenario.json --llm off   # one end-to-end scenario
```
