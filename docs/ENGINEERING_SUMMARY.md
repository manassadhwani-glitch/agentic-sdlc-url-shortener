# Engineering summary

## 1. What was asked, and how I read it
The brief asks for two things that are easy to blur together:
1. a **URL shortener** with core APIs, analytics and reliability features, built to production standards;
2. an **agentic orchestration layer** that takes a requirement through the SDLC under governance. The brief calls this the critical differentiator.

I treated (2) as the main deliverable and (1) as the realistic codebase it works on: it is the reference implementation for greenfield runs and the target of brownfield changes.

Ambiguities in the brief and how I resolved them:

| Ambiguity | Decision |
|---|---|
| Do agents really write code? | Yes, but only as **proposals**. Change sets go through policy → (approval) → journaled apply → tests. Approved change sets are applied exactly as reviewed. |
| Which LLM, and what if it's unavailable? | `LlmClient` interface; Claude via the Messages API when `ANTHROPIC_API_KEY` is set. Otherwise deterministic agents, so runs are reproducible and CI-friendly. |
| How "real" must approvals be? | Real rules (roles, separation of duties, rejection comments, immutability) via CLI. Identity is asserted with `--as` (see limitations). |
| "Dynamic re-planning" | Implemented in three ways: (a) lineage-based invalidation when any upstream artifact changes; (b) the planner inserting stages into the live graph; (c) failure and rejection routing back upstream. |

## 2. Plan and decomposition
1. **Design.** Architecture, decomposition and assumptions, reviewed before any code (`ARCHITECTURE.md`).
2. **Service.**
   - API contract (OpenAPI), schema (Flyway), domain, cache, analytics, rate limiting, idempotency.
   - Unit, integration and concurrency tests.
3. **Orchestrator core.**
   - Model, durable state, HMAC audit, content-addressed artifacts.
   - Validator, guarded workspace, policies, approvals.
   - Engine: coordinator and workers, retries, rework, invalidation, compensation, safe-stop.
4. **Agents and tools.**
   - Ten agents, catalog, playbooks, repo analyzer, test runner, LLM client, CLI.
5. **Scenarios and evidence.**
   - Three end-to-end scenarios, run reports, metrics, docs.

Steps 2 and 3 were independent and could proceed in parallel.

## 3. Key design decisions
| Decision | Rationale | Trade-off |
|---|---|---|
| Deterministic control flow; LLM only inside stages | A model can't skip a gate or approve itself; runs are auditable and reproducible | Less "autonomous-looking" than a manager agent picking the next step. I think that's the right call for a regulated environment |
| Single coordinator thread + worker pool | State changes are race-free without locks; every transition is persisted first | Coordinator throughput is bounded (irrelevant at SDLC timescales) |
| Content-addressed artifacts + lineage | Precise invalidation; unchanged output stops the ripple | Requires every stage to declare `consumes`/`produces` |
| Per-stage journals instead of `git reset` | Safe with parallel stages sharing a workspace | Journals hold before-images (fine for source trees) |
| JDK-only orchestrator | The component with the most authority gets the smallest supply chain; easy to reason about | Hand-rolled JSON/CLI instead of libraries |
| Database decides races in the service | Unique constraints are the only correct arbiter under concurrency | Pre-checks exist only for friendlier errors |
| Analytics off the redirect path | Redirect availability beats analytics completeness | Clicks still queued can be lost on a crash (documented; Kafka is the production path) |

## 4. Validation and risk control
**Validation is layered, and it's evidence, not assertion:**
- workflow validation before a run starts and after every re-plan;
- entry/exit gates per stage;
- policy review of every change set *before* it touches disk;
- real test execution;
- the **ac-traceability** gate (every acceptance criterion's named tests must pass);
- an independent security review;
- an evidence-computed release checklist;
- human sign-off.

| Risk / failure scenario | Control |
|---|---|
| Model output is wrong, invalid or hallucinated | JSON contracts; constrained to catalog features; policy; gates; tests; fallback to deterministic agents |
| Agent writes unsafe code or leaks secrets | SEC rules (critical → immediate safe-stop, no retry); write scopes enforced in code; independent security stage |
| Prompt injection through repo content | `<untrusted>` fencing; output validated; LLM findings advisory only (≤ MEDIUM) |
| Risky change lands unreviewed | Policy-triggered approvals (schema, deps, security config, size, deletions); separation of duties; approved change set applied verbatim |
| Compliance breach (PII) | CMP-001 blocks requirements that store raw PII. In the ambiguous scenario it **overrode a human's answer**, and the agent rewrote it to the pseudonymised form, recording its decision |
| Runaway loops or cost | `maxAttempts` (1–5), `maxReworks`, run budgets (attempts, LLM calls, wall clock) → safe-stop |
| Partial failure leaves a broken workspace | Atomic change sets; rollback on gate failure; saga compensation on terminal failure |
| Crash or restart mid-run | State persisted after every transition; resume in a new process |
| Tampering with history | HMAC-chained audit; `verify-audit`; fail-closed writes |
| Requirement changes mid-flight | Lineage invalidation re-runs exactly the affected stages and supersedes stale approvals |

## 5. Results (from the three scenario runs)
All four scenarios were run with real `mvn test` in the verify stage. Recorded reports are in `scenarios/*/sample-run/`.

| | Greenfield | Brownfield | Ambiguous | Bug fix |
|---|---|---|---|---|
| Outcome | SUCCEEDED (100 tests) | SUCCEEDED (104 tests) | SUCCEEDED | SUCCEEDED |
| Human checkpoints | 5 (1 rejection → rework) | 4 (incl. re-approval after fix) | 2 questions + 5 approvals (1 superseded by the amendment) | 2 |
| Recovery | — | injected bug caught by real JUnit (2 of 104 failing) → rollback → rework → recovered | compliance retry; amendment → 9 lineage invalidations (8 from the new spec, 1 later for security), 1 stage added | — |
| Dynamic graph change | — | `migration-check` added by planner | `migration-check` added after the amendment | — |
| Scope from the brief | greenfield | brownfield enhancement | ambiguous requirement | brownfield bug fix + test improvement |

The bug-fix scenario fixes a **real** vulnerability I found by probing the service: the SSRF filter accepted decimal, hex and octal encodings of 127.0.0.1.

The numbers in each `runs/<id>/metrics.json` are computed from the audit trail. MTTR and latency include human wait time; `agentExecutionMs` and `humanWaitMs` are reported separately.

## 6. Assumptions
- Single-node prototype; one run per workspace at a time.
- Approver identity comes from the CLI (`--as`) and is checked against a registry.
- Offline mode replays verified playbooks for known features. Unknown requests need LLM mode, or the agent asks for clarification rather than guessing.
- The service runs on H2 by default and PostgreSQL via a profile; Flyway migrations are portable across both.

## 7. Limitations (and the production path)
| Limitation | Production path |
|---|---|
| No authentication on approvals (`--as`) | SSO/OIDC identity; approvals as signed PR reviews on a protected branch |
| File-based state, single coordinator, CLI only | Database-backed run store with leader election; REST/UI for approvals; queue-backed workers |
| Offline agents know only catalog features | LLM mode (implemented) with the same guardrails; extend the catalog/playbooks for regulated "golden paths" |
| The recorded scenario runs used the deterministic agents; LLM mode (Anthropic client, validation, fallback) is implemented and its fallback path is unit-tested, but it was not exercised against the live API for this submission | Run the scenarios with `ANTHROPIC_API_KEY` set; the report shows per artifact whether the model or the fallback produced it |
| Pattern-based policy and secret scanning | Add SAST (Semgrep/CodeQL), gitleaks, SCA/licence scanning in the security stage |
| Regex-based impact analysis | JavaParser/LSP index, call graph |
| HMAC audit key in an environment variable | KMS/HSM-held key; ship audit events to WORM storage / SIEM |
| Service: per-instance rate limit and cache, crash can lose queued clicks | Redis (limits + cache invalidation pub/sub), Kafka for click events |
| Merge is manual (`changes.patch`) | The release stage opens a PR; branch protection enforces the approvals already recorded |

## 8. How AI was used
The brief allowed AI assistance, and I used Claude as a pair programmer throughout. It wrote the first drafts of most of the code, tests and documentation. My part was to set the scope, make the design calls in §3, review what it produced, and check every result against real evidence rather than taking it on trust.

That checking is where the important findings came from:
- My first local build failed one test: Spring Boot disables metrics export in tests, so the Prometheus endpoint returned 404. Fixed with `@AutoConfigureObservability`.
- Running the scenarios end to end exposed two orchestrator defects that unit tests had missed: a compliance-rule false positive and a race between parallel stages (see `TESTING.md`).
- Probing the service's own SSRF filter turned up a real bypass through decimal, hex and octal IP encodings. That became the bug-fix scenario, and the fix was merged into the service.

All four scenarios were then run on my machine with real `mvn test`, and the recorded reports are in `scenarios/*/sample-run/`.

That's also the working model the orchestrator enforces: agents do the work inside defined boundaries, and people own the decisions, the approvals and the final quality.
