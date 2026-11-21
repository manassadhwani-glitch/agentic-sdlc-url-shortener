# Setup

## Prerequisites
- **JDK 17+** (`java -version`)
- **Maven 3.9+** (`mvn -version`)
- Optional: `git` (used only to export `changes.patch`), Docker (PostgreSQL profile), `ANTHROPIC_API_KEY` (LLM mode)

## 1. Build and test everything
```bash
mvn -B verify
```
This runs both modules:
- **url-shortener-service:** unit and integration tests (H2 + Flyway), plus a JaCoCo coverage gate of 70% line coverage.
- **sdlc-orchestrator:** 37 tests covering the engine, governance and the audit chain, and packages `sdlc-orchestrator/target/sdlc-orchestrator.jar`.

The first build downloads dependencies. Later runs are offline-friendly.

## 2. Run the four scenarios
From the repository root:
```bash
alias orch='java -jar sdlc-orchestrator/target/sdlc-orchestrator.jar'

orch demo scenarios/01-greenfield/scenario.json --llm off
orch demo scenarios/02-brownfield/scenario.json --llm off
orch demo scenarios/03-ambiguous/scenario.json  --llm off
orch demo scenarios/04-bugfix/scenario.json     --llm off
```
`demo` plays the reviewers from each scenario's `script` (alice = tech-lead, rahul = release-manager). Each run writes `runs/<runId>/` containing:

| File | Contents |
|---|---|
| `report.md` | human-readable run report |
| `metrics.json` | reliability metrics |
| `audit.jsonl` | tamper-evident audit trail |
| `state.json` | durable run state |
| `artifacts/` | versioned spec, design, plan, test report, … |
| `workspace/` | the code the agents changed |
| `baseline/` | the code before the run |
| `changes.patch` | unified diff for human review/merge |
| `journals/` | rollback journals |

Each `verify` stage runs the real `mvn -q -B test` inside that run's workspace, so a scenario takes a few minutes.

## 3. Drive a run by hand (what you'd show live)
```bash
orch run --scenario scenarios/02-brownfield/scenario.json --llm off   # pauses at the design sign-off
orch status  <runId>
orch approve <runId> A-1 --as manas                                    # ✖ separation of duties (manas started the run)
orch approve <runId> A-1 --as alice --comment "approved"
orch resume  <runId>                                                   # pauses at the schema-change approval
orch reject  <runId> A-2 --as alice --comment "add a rollback note"    # → rework with that feedback
orch resume  <runId>
orch amend   <runId> --as alice "…new requirement text…"              # → lineage-based re-planning on resume
orch stop    <runId> --reason "change freeze"                          # kill switch (safe-stop, resumable)
orch verify-audit <runId>
orch report  <runId>
orch metrics                                                           # aggregate across runs
```
Ad-hoc requirement against the service:
```bash
orch run --requirement "Add link expiration; expired links return 410" \
         --source url-shortener-service --reference url-shortener-service
```

Identities and roles live in `sdlc-orchestrator/src/main/resources/approvers.json`. Override them with `--approvers my-approvers.json`.

## 4. Optional: LLM mode
```bash
export ANTHROPIC_API_KEY=...            # never commit it; the policy engine blocks keys in code
export ORCH_LLM_MODEL=claude-sonnet-5-5 # optional
orch demo scenarios/02-brownfield/scenario.json        # --llm auto is the default
```
The requirements, design, implementation and security agents call the model. If it is unavailable, slow, or returns output that fails validation, the agent falls back to its deterministic path, and the report shows the mode used per artifact (`llm` / `fallback`). Model output gets no special treatment: it passes the same policies, approvals and tests.

## 5. Production hardening knobs
| Env var | Purpose |
|---|---|
| `ORCH_AUDIT_KEY` | HMAC key for the audit chain. The dev default is reported in the run as `DEV KEY` |
| `ANTHROPIC_API_KEY`, `ORCH_LLM_MODEL` | LLM mode |
| `SHORTENER_SECURITY_API_KEYS`, `SHORTENER_CODE_SECRET`, `SHORTENER_ANALYTICS_IP_HASH_SALT` | service secrets (Spring relaxed binding) |

## 6. Run the service itself
```bash
mvn -pl url-shortener-service spring-boot:run
curl -s -X POST localhost:8080/api/v1/urls -H 'X-API-Key: dev-key-change-me' \
     -H 'Content-Type: application/json' -d '{"longUrl":"https://example.com"}'
```
See `url-shortener-service/README.md` for PostgreSQL and more examples.
