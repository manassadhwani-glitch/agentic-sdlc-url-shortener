# Scenarios

Each folder has a `scenario.json` (requirement, starting workspace, scripted reviewers). Run one with:

```bash
java -jar sdlc-orchestrator/target/sdlc-orchestrator.jar demo scenarios/<folder>/scenario.json --llm off
```

Reviewers in the scripts: **alice** (tech-lead, product-owner), **rahul** (release-manager). Runs are started by **manas**, so separation of duties stops manas approving them himself.

## 01-greenfield: build the service from an empty workspace
**Requirement:** shorten URLs with optional aliases, redirect, click analytics; idempotent, rate-limited API; SSRF protection; privacy-safe analytics.

**Decomposition:** 8 features → 10 acceptance criteria → tasks for implement, test-design, verify, security, docs and release.

**Orchestration you'll see:**
1. Design sign-off **rejected** by alice ("document cache invalidation and 302 vs 301"). The design is reworked with the comment as feedback, and a fresh sign-off is approved.
2. `implement ‖ test-design` run in parallel. Both change sets exceed 400 lines, and V1 is a schema creation, so policy pauses each for tech-lead approval.
3. After approval, the approved change sets are applied as-is.
4. `verify` runs the real test suite, and the **ac-traceability** gate maps every AC to passing tests.
5. `security ‖ docs` run in parallel, then release sign-off by rahul.

**Validation:** spec-complete, compliance-text, design-covers-acs, plan-valid, tests-cover-acs, tests-pass, ac-traceability, no-high-findings, release-checklist.

## 02-brownfield: add link expiry to the existing service
**Requirement:** links can carry an optional expiry; expired links answer 410.

**Codebase reasoning:** `impact` lists the existing endpoints, entities and migrations, and the impacted files in domain, service, api, idempotency and db/migration. It plans migration V2 as additive and nullable.

**Orchestration you'll see:**
1. The planner **adds `migration-check` to the live graph** and makes `verify` depend on it.
2. Implement's change set contains a new Flyway migration, so it waits for tech-lead approval (CHG-001).
3. **Fault injection:** the first implementation has an inverted expiry check.
   - `verify` fails (`LinkExpiryTest` catches it).
   - The engine **rolls back** implement's journal and routes back to implement with the failing tests as feedback.
   - The corrected change set **needs re-approval** because the reviewed code changed.
   - `migration-check` is invalidated (its input changed) and re-runs.
   - Verify passes, and the report records MTTR.
4. Release sign-off, then `changes.patch` for human merge.

## 03-ambiguous: "Make links safer and track more stuff about visitors."
**Orchestration you'll see:**
1. The requirements agent **asks two clarifying questions** instead of guessing.
2. alice answers: "block other URL shorteners" and "store each visitor's raw IP".
3. The **compliance policy (CMP-001) blocks the raw-IP answer**. The agent's retry rewrites it to the pseudonymised form already in place and records the decision and assumption.
4. Design sign-off, parallel implement/test-design (denylist feature), verify, security and docs.
5. At release, alice **amends the requirement** (adds link expiry). On resume:
   - Lineage invalidation re-runs requirements, design, plan, implement, test-design, verify, docs and release. Their applied changes are rolled back first, and stale approvals are superseded.
   - `security` is *not* invalidated by the spec change (it doesn't read the spec). It re-runs only when the test files it reviews change.
   - The planner adds `migration-check`, the migration needs approval, and a fresh release sign-off is required.

## 04-bugfix: fix a real SSRF bypass in existing code
**Bug report:** the URL validator accepts `http://2130706433/` (decimal), `http://0x7f000001/` (hex) and `http://0177.0.0.1/` (octal). Browsers and HTTP clients send all of them to 127.0.0.1. This is a real bug that was found by probing the service; it isn't staged.

**Orchestration you'll see:**
1. Requirements maps the report to two criteria:
   - the new fix (reject non-canonical numeric hosts);
   - the **existing** URL-safety criterion, so the fix must not regress current protections.
2. Design sign-off, then the fix and the regression tests (`UrlValidatorEncodingTest`) are written in parallel. The tests come from the bug report, not from the fix.
3. Verify runs the full suite, and ac-traceability proves both criteria.
4. Security review, docs (change note and CHANGELOG), release sign-off.

The fix was then merged into `url-shortener-service`. Re-running this scenario now reports "no code change required" for the fix, which is the expected idempotent behaviour.

## Driving a scenario by hand
Use `run` instead of `demo`, then `status`, `approve` / `reject` / `answer` / `amend`, and `resume`. See `docs/SETUP.md`.
