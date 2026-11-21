# Agentic SDLC System: URL Shortener

An agentic engineering system that turns a requirement into a **reviewed, tested, documented** change. It does this through a governed dependency graph of SDLC stages, demonstrated by building and evolving a production-style URL shortener.

> **Agents propose. The orchestrator verifies. Humans decide.**

```
requirements → design ⏸ → plan → ┬ implement   ┐→ [migration-check]* → verify → ┬ security ┐→ release ⏸
                                 └ test-design ┘                                └ docs     ┘
⏸ human sign-off      * inserted at runtime by the planner when a schema change is detected
```

## What's here
| Path | What |
|---|---|
| `sdlc-orchestrator/` | The orchestration engine, agents, policies, approvals, audit, metrics, CLI (Java 17, JDK-only runtime) |
| `url-shortener-service/` | The URL shortener (Spring Boot 3.3): create / redirect / stats / disable, idempotency, rate limiting, SSRF protection, privacy-safe analytics |
| `scenarios/` | Greenfield, brownfield, ambiguous and bug-fix scenarios, each runnable end to end, with recorded real runs |
| `docs/ARCHITECTURE.md` | Components, orchestration model, control flow, key decisions |
| `docs/SETUP.md` | Build, run the scenarios, drive a run by hand, LLM mode |
| `docs/TESTING.md` | Testing approach and what each test proves |
| `docs/ENGINEERING_SUMMARY.md` | Plan, rationale, risks, validation, assumptions, limitations |

## Quick start
```bash
mvn -B verify                                                     # build + all tests
java -jar sdlc-orchestrator/target/sdlc-orchestrator.jar demo scenarios/02-brownfield/scenario.json --llm off
open runs/*/report.md                                             # stage graph, approvals, lineage, metrics
```

## Requirement → implementation map
| Brief requirement | Where |
|---|---|
| Requirement understanding, ambiguity | `RequirementsAgent`, clarifying questions, `scenarios/03-ambiguous` |
| Task decomposition with dependencies | `PlannerAgent` → `plan` artifact; `plan-valid` gate |
| Brownfield codebase reasoning | `RepoAnalyzer` + `DesignAgent` → `impact` artifact (endpoints, entities, migrations, impacted files) |
| Explicit dependency graph, entry/exit gates | `workflows/sdlc-default.json`, `WorkflowValidator`, `Gates` |
| Sequential + parallel paths with synchronization | coordinator/worker `Engine`; joins; `implement ‖ test-design`, `security ‖ docs` |
| Cross-stage context and decision lineage | content-addressed `ArtifactStore`, `ArtifactMeta.inputs`, `decision` audit events |
| Human approval for high-impact actions | policy-triggered approvals + sign-offs; roles; separation of duties |
| Bounded retries, fallback, rollback, safe-stop | `maxAttempts`/backoff, deterministic fallback, journals + saga compensation, kill switch/budgets/fail-closed audit |
| Policy guardrails: security, compliance, change control | `PolicyEngine` (SEC/CMP/CHG rules), `GuardedWorkspace` |
| Audit-grade observability and traceability | HMAC-chained `audit.jsonl`, `verify-audit`, `report.md` |
| Reliability metrics (success, retry/rollback, MTTR, latency) | `Metrics` → `metrics.json`; `metrics` across runs |
| Dynamic re-planning | lineage invalidation with hash short-circuit, planner stage insertion, rework routing, `amend` |
| Production-quality code, API/schema, tests, docs | `url-shortener-service` (OpenAPI, Flyway, 102 tests, coverage gate), 37 orchestrator tests |
| Brownfield bug fixes and test improvements | `scenarios/04-bugfix`: fixes a real SSRF bypass in existing code and adds regression tests |
