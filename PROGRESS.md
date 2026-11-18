# Contract Guard — Engineering Progress Report

**Status:** complete · **Final code commit:** `93ceeff` (`2ab05c6` adds the development transcript) ·
**Automated tests:** 144/144 green (19 test classes, in-memory H2) ·
**Deployment:** local Docker Compose, verified live (not a public cloud deployment)

This document records the actual build journey, milestone by milestone, grounded in the Git
history and the verification evidence recorded while the work happened. Everything in the
"Verified" rows below was actually executed — nothing is aspirational.

---

## How to read this document

Every capability in this project sits in exactly one verification tier:

| Tier | Meaning |
|---|---|
| **Implemented** | The code exists and is wired into the pipeline. |
| **Automated-test verified** | Covered by one or more of the 144 automated tests. |
| **Live-deployment verified** | Actually executed against the running Docker Compose stack during release verification (see [Release verification](#release-verification--live-deployment)). |
| **Documented limitation** | Deliberately not supported; the boundary is written down in code and in [REVIEW.md](REVIEW.md). |
| **Deferred** | Was planned (see [PLAN.md](PLAN.md)), consciously cut, and documented (see [DECISIONS.md](DECISIONS.md)). |

Timeline at a glance — all work happened on **2026-11-18**:

| Commit | When | Milestone | Scope |
|---|---|---|---|
| `5944b84` | 17:22 | 1 | Scaffold, OpenAPI validation & extraction, starter kit committed |
| `b9ba8a4` | 17:30 | 2 | Compatibility engine & semver advisory |
| `550d691` | 17:57 | 3 | Consumer parsing, registry & impact analysis |
| `f6f4e7f` | 18:18 | 4 | Registry persistence, publishing workflow, webhooks |
| `c634b69` | 19:08 | 5 | Deployment, drift scanner, migration guides, CI gate |
| `5abfc3b` | 19:44 | 6 | OpenAPI diff correctness & persistence hardening |
| `93ceeff` | 19:52 | 7 | Parameter wire-serialization compatibility |
| `2ab05c6` | 20:12 | 8 | Development transcript (docs only) |

---

## Phase 0 — Repository reconnaissance, plan and decision log (pre-code)

**Objective.** Understand the given starter kit precisely before writing a line of code, and
produce a grounded plan instead of an invented one.

**Done.**
- Read every starter-kit artifact: 12 OpenAPI 3.0.3 specs (quote-api / policy-api / claims-api,
  4 versions each — including the deliberately broken `$ref` in
  `specs/claims-api/2-channel-and-notes.yaml`), 4 consumer YAML declarations, the stdlib
  Python webhook receiver, and the untouched `docker-compose.yml` (receiver only, :9900→:9000).
- Verified the local toolchain (JDK 21, Maven, Docker daemon) and researched library versions
  (Swagger Parser, Spring Boot, H2) — recorded in DECISIONS.md as facts dated 2026-11-18.
- Produced [PLAN.md](PLAN.md) (architecture, compatibility rule set, publishing state machine,
  API design, DB concepts, test scenarios) and [DECISIONS.md](DECISIONS.md) (A-series product
  decisions, B-series technology decisions, each with alternatives and consequences).
- Key planning decisions locked in here: effective-contract comparison (not text diffing), the
  request/response compatibility asymmetry, the 5-rule publishing policy with semver as an
  advisory-only channel, declarative consumer files loaded read-only at startup, and the
  never-modify-the-starter-kit constraint.

**Result.** Plan and decisions committed alongside the scaffold in `5944b84`
(PLAN.md 488 lines, DECISIONS.md 256 lines).

---

## Milestone 1 — Application scaffold, OpenAPI validation and effective extraction (`5944b84`)

**Objective.** Stand up the Spring Boot application, wrap Swagger Parser with collect-all
validation, and build the canonicalization + effective-schema layer everything else diffs on.

**Implemented** (61 files, +4,469 — includes the starter-kit fixtures and the plan documents).
- `Application` + Spring Boot wiring, `application.yml` (env-driven: `PORT_APP`, `DB_URL`,
  `WEBHOOK_URL`, `SPECS_DIR`, `CONSUMERS_DIR`, `KIT_IMPORT`), initial `schema.sql` (113 lines).
- `specs/` package: `SpecParser` (Swagger Parser 2.1.48 wrapper), `StructuralValidator`
  (**collect-all** validation — every structural error and every unresolvable local `$ref` is
  reported at once, not fail-fast), `RefResolver`, `CanonicalJson` (key-order-free
  canonical form), `PathShapes` (placeholder-positions path normalization), `HttpMethods`,
  `JsonMappers`, `EffectiveSchemaExtractor` + `EffectiveLocation` — the effective contract:
  per wire location `VERB /pathShape :: scope`, `$ref`-free, component-name-free, order-free.
- `DeprecationScanner` + `DeprecationRecord`: `x-deprecated` / `x-sunset` lifecycle walk
  (depth-capped at 500).
- Minimal dashboard (`static/index.html`, 31 lines placeholder) and health endpoint.
- The untouched starter kit itself: 12 specs, 4 consumer files, `webhook-receiver/app.py`
  (79 lines) + receiver Dockerfile, original `docker-compose.yml`, `.gitattributes`/`.gitignore`.

**Tests added.** `SpecParserQuoteV1Test` (parse + shape), `BrokenSpecRejectionTest` (collect-all
error reporting, broken `$ref` rejection, nothing stored), `CosmeticEquivalenceTest`
(reorganizations diff to zero), `DeprecationScannerTest`, `PathShapesTest`, shared `Fixtures`.

**Verified.** Build green; the broken-`$ref` spec was confirmed to reject with the complete
error list rather than the first error.

**Result.** Commit `5944b84` — scaffold + extraction layer with the first 6 test classes.

---

## Milestone 2 — Compatibility engine and semver advisory (`b9ba8a4`)

**Objective.** Implement the core intellectual property: diff two effective contracts and
classify every difference with stable codes, severities, and the request/response asymmetry.

**Implemented** (15 files, +1,784).
- `compatibility/`: `CompatibilityEngine` (768 lines), `CompatibilityFinding` (code, severity,
  scope, method, path, location, fieldPath, detail, old/new JSON evidence), `LocationKeys`
  (wire-location keys), `SchemaSide`, `Severity` (`BREAKING` / `WARN` / `INFO`).
- Rule set: required additions/removals, type narrowing/widening, enum add/remove, constraint
  tightening/relaxation, property add/remove with **rename detection** (removed+added, same
  parent, same type → one `*_PROPERTY_RENAMED` finding), nested field paths in wire notation
  (`premium.breakdown[].amount`), array item handling, request-body schema add/remove
  asymmetry, parameter required/schema changes matched by `(in, ordinal)` position,
  response-status/media handling, compositions (`allOf` merge when non-conflicting,
  `anyOf`/`oneOf` compared as an alternative set).
- `policy/`: `Semver` (parse + compare), `VersionPolicyAnalyzer` — `VERSION.POLICY.
  BREAKING_WITHOUT_MAJOR` (WARN) / `MAJOR_WITHOUT_BREAKING` (INFO) as a **separate findings
  channel** that can never gate the decision.

**Engineering details.**
- Findings sort deterministically by (location, fieldPath, code) so stored reports and
  changelogs are stable.
- Envelopes are compared as parsed canonical JSON, never as raw text.
- The engine treats REQUEST and RESPONSE asymmetrically on purpose: accepted-request space may
  only grow, guaranteed-response promises may only grow.

**Tests added.** `SyntheticCompatibilityTest` (460 lines of rule-by-rule cases),
`KitPairCompatibilityTest` (every consecutive kit version pair — proving the planted kit
changes produce exactly the designed findings), `VersionPolicyAnalyzerTest`.

**Result.** Commit `b9ba8a4` — the diff core that everything downstream consumes.

---

## Milestone 3 — Consumer declarations, registry and impact analysis (`550d691`)

**Objective.** Model *who uses the API* and turn findings into per-consumer blast radius.

**Implemented** (26 files, +2,152).
- `consumers/`: `ConsumerParser` (Jackson YAML, strict shape validation,
  `method + path-shape` binding normalization — placeholder **positions** match, names don't),
  `DeclaredOperation` (provider, method, path shape, `sends`, `reads`), `ConsumerDeclaration`,
  `ConsumerRegistry` (loads every file in `CONSUMERS_DIR` read-only at startup),
  `ConsumerParseException`.
- `impact/`: `ConsumerImpactAnalyzer` — matches findings against declarations and produces
  per-consumer impact tiers **AFFECTED / POSSIBLE / NONE**, each row carrying the finding code,
  location, field path and a plain-English evidence reason; matching logic mirrors the engine's
  asymmetry (a consumer that *sends* a removed/required-added parameter is AFFECTED; a consumer
  that *reads* a renamed/removed response field is AFFECTED; undeclared usage degrades
  gracefully to NONE/POSSIBLE with the reason stated).
- `api/ImpactController` (96 lines): `GET /api/consumers`, `POST /api/impact`
  (`{"service","oldSpec","newSpec"}` — standalone analysis without publishing anything).

**Tests added.** `ConsumerParserTest`, `ConsumerRegistryTest`,
`ConsumerImpactAnalyzerTest`, `KitConsumerImpactTest` (kit consumers × kit findings).

**Result.** Commit `550d691` — consumers became first-class gate inputs.

---

## Milestone 4 — Registry persistence, publishing workflow, state machine and webhooks (`f6f4e7f`)

**Objective.** Turn analysis into governed publication: durable registry, attempts, decisions,
audit trail, notifications, and the full HTTP surface.

**Implemented** (20 files, +3,163).
- `persistence/RegistryStore` (509 lines, `JdbcTemplate`, schema expanded by 30 lines:
  `spec_versions`, `publication_attempts`, `findings`, `finding_impacts`,
  `consumer_definitions`/`consumer_bindings`, `attempt_transitions`, `notifications`,
  `audit_events`).
- `pipeline/PublicationService` (776 lines): upload orchestration — duplicate/regression
  checks (identical re-upload = audited no-op; same-version-different-content → `409`;
  version regression → `409`) → diff + deprecation scan → version-policy advisory →
  consumer impact → `PublishingDecision` → state-persisting attempt → webhook after commit.
- `domain/`: `Decision` (`PUBLISH` / `PENDING_ACK` / `BLOCK`), `DecisionResult`,
  `PublishingDecision` (the 5-rule gate mapping: no breaking → publish; breaking + 0
  AFFECTED → pending_ack; ≥1 AFFECTED → block; invalid never reaches this class).
- `api/`: `RegistryController` (293 lines — registration, upload, check (dry-run), compare,
  attempts, acknowledge/override/abandon, history, changelog, audit, notifications, metrics)
  plus the typed error contract (`ApiException` → `InvalidRequestException` 400 /
  `NotFoundException` 404 / `ConflictException` 409 / `SpecInvalidException` 422 with the
  collected error list + audit attempt id).
- `notify/WebhookNotifier`: fire-and-forget POST (2 s connect/read timeouts), single attempt,
  every outcome (including failures) recorded in the `notifications` table — can never fail an
  already-committed publication.
- `pipeline/KitSeeder` (121 lines): idempotent startup import of the starter kit **through the
  real pipeline** in filename order, with a `KIT_IMPORT` audit marker making re-imports no-ops.
- Dashboard grew to the full single-page UI (index.html +523 lines).
- `src/test/resources/application.yml`: isolated in-memory H2 for integration tests.

**Tests added.** `PublishingDecisionTest` (unit), `PublishingWorkflowIntegrationTest`
(517 lines — the full gate over MockMvc + in-memory H2).

**Result.** Commit `f6f4e7f` — the product exists end-to-end.

---

## Milestone 5 — Deployment, consumer drift, migration guides and CI gate (`c634b69`)

**Objective.** Make it deployable, explorable and evaluatable: Docker packaging, cumulative
drift detection, grounded migration guides, a CI script, and real documentation.

**Implemented** (17 files, +1,570).
- `Dockerfile` (multi-stage: Maven build → JRE runtime, fixtures baked in) and
  `docker-compose.app.yml` (the **override file** — the original `docker-compose.yml` stays
  untouched; adds the app, the named volume `contractguard-data` mounted at `/app/data`, and
  the in-network webhook URL).
- `impact/ConsumerDriftScanner` (386 lines): cumulative drift warnings computed from stored
  bindings + stored versions — `CONSUMER.DRIFT` (a declared binding no longer matches the
  current contract shape) and `CONSUMER.STALE_BINDING` (a binding matches an older version but
  not the current one). **Deliberately non-gating** and **without invented consumer version
  pins** — consumers don't record which version they coded against, so the scanner warns, it
  doesn't gate.
- `pipeline/MigrationGuide` (170 lines): steps generated **only from stored findings + impact
  rows** (no fabrication), with parameter names restored from the spec's parameter index and
  affected consumer names attached; unknown codes degrade to a review step quoting the finding
  detail.
- `scripts/ci-check.sh` (162 lines): runs the real gate as a dry-run (`POST /check`) with the
  strict exit-code contract (0 publish / pending_ack-without-strict · 1 blocked or strict
  pending_ack · 2 invalid spec · 3 operational · 4 usage).
- README rewritten (274 lines added) and PLAN/DECISIONS annotated.

**Tests added.** `ConsumerDriftScannerTest` (142 lines), `MigrationGuideTest`,
`PublishingWorkflowIntegrationTest` extended.

**Result.** Commit `c634b69` — deployable and demonstrable.

---

## Milestone 6 — OpenAPI diff correctness and persistence hardening (`5abfc3b`)

**Objective.** Adversarial review of the engine and the persistence layer; fix every defect
found without weakening a single test.

**Changed** (11 files, +1,782/−149).
- `EffectiveSchemaExtractor` (+488): hardened $ref resolution across every location kind
  (including `$ref`-wrapped parameters / request bodies / responses — a referenced location now
  compares **identically** to its inline twin), cycle-safe expansion via the opaque
  `{"x-circular": "<wire path>"}` marker (depth cap 50, component-name free), media-type
  (`content:`) parameters captured inside the envelope as `x-content`.
- `CompatibilityEngine` (+361): fixed false positives — a `$ref`-wrapped request body no longer
  reports `REQUEST_BODY.REMOVED`; schema-less parameter/response comparisons no longer emit
  spurious removals; composition and response-side comparisons tightened.
- `persistence/RegistryStore` (+122): **state-guarded transactions** — every decision
  transition is guarded by the expected previous state inside the transaction, so two
  concurrent decisions cannot both apply (the loser rolls back entirely and surfaces as
  `409`); per-statement `READ_COMMITTED` isolation on the metrics/audit reads.
- `schema.sql`: idempotent `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` migrations so an older
  local database upgrades in place at boot.

**Tests added.** `ExtractionCornerCasesTest` (246 lines — envelope pinning, corner cases),
`PersistenceGuaranteesIntegrationTest` (180 lines — restart safety, guarded transitions,
duplicate handling), `SyntheticCompatibilityTest` extended (+465).

**Verified.** Full suite **139/139 green** after the fixes (recorded during the work session);
no test was weakened or skipped.

**Result.** Commit `5abfc3b` — `fix: strengthen OpenAPI diff correctness and persistence
guarantees`.

---

## Milestone 7 — Parameter wire-serialization compatibility (`93ceeff`)

**Objective.** Close a real detection gap found during engine review: `style`, `explode` and
`allowReserved` change how a parameter's value physically appears on the wire
(`;id=3` vs `/3`, `?ids=1&ids=2` vs `?ids=1,2`) but were invisible to the diff.

**Implemented** (6 files, +428).
- `EffectiveSchemaExtractor`: records only **deviations from the OpenAPI defaults** under the
  envelope key `x-serialization` (defaults per OAS 3.0: `form`+explode-true for query/cookie,
  `simple`+explode-false for path/header, `allowReserved` query-only default false). Spelling
  an implicit default out explicitly is therefore provably wire-neutral → zero findings.
- `CompatibilityEngine` → `PARAM.SERIALIZATION_CHANGED`: style change always BREAKING; explode
  change BREAKING for array/object parameters, INFO for single-valued ones;
  `allowReserved` disabled BREAKING, enabled INFO. Explode is compared only when both sides
  share the same style (a style flip already covers the wire change).
- `ConsumerImpactAnalyzer`: declared senders → AFFECTED on breaking serialization changes,
  NONE otherwise (non-breaking changes never rise above NONE).
- `MigrationGuide`: grounded "regenerate the client" step for the new code.
- oneOf semantics **documented in code** as a limitation (exclusivity not interpreted) and
  pinned by tests; no exclusivity behavior was invented.

**Tests added (5).** Engine: `paramSerializationSemantics`, `paramStyleAndAllowReserved`,
`oneOfSemantics` (reorder invariance, asymmetric branch add/remove, evolved branch diffs at
its position). Extraction: `serializationEnvelope`, `refWrappedSerialization`.

**Verified.** Full suite **144/144 green, 0 failures** (BUILD SUCCESS, exit 0) — re-run twice
during the session.

**Result.** Commit `93ceeff` — `feat: surface parameter wire-serialization changes in the
compatibility diff`.

---

## Milestone 8 — Docs transcript (`2ab05c6`)

`transcripts/prompts.json` (74,948 lines) + `transcripts/session.json` — the full AI-assisted
development transcript, committed for transparency. Documentation-only; no code touched.

---

## Release verification — live deployment

**All of the following was actually executed against the running stack** during the release
verification session — chronologically between commits `93ceeff` and `2ab05c6`. This is
distinct from the automated
test suite: it exercises the real containers, real files, real webhooks, real persistence.

### Deployment

| Item | Verified result |
|---|---|
| Command | `docker compose -f docker-compose.yml -f docker-compose.app.yml up --build -d` |
| Images | `contractguard-contract-guard`, `contractguard-webhook-receiver` — both built |
| Containers | both `Up`; app published on host **8090** (`PORT_APP` override — an unrelated Apache `httpd` process occupied host 8080), receiver on **9900**→9000 |
| Health | `{"status":"UP","service":"contractguard","webhook_url":"http://webhook-receiver:9000/contractguard","db":"UP"}` |
| Frontend | dashboard served (HTTP 200, "Contract Guard" title) |
| Kit import | 3 services (`claims-api`, `policy-api`, `quote-api`), each at current `1.1.0` / 2 versions; the broken `$ref` claims spec rejected; consumers registered |
| Deployment scope | **local Docker Compose only — no public cloud deployment was performed or claimed** |

### Live workflow (every step returned the documented outcome)

| Step | Result |
|---|---|
| Service registration | `POST /api/services` → 200, `release-api` registered |
| Invalid spec upload | broken claims spec → **422**, error list returned, audit attempt id returned, nothing stored |
| Dry-run | `POST /api/services/policy-api/check` (4-endorsement-rules) → 200 `BLOCK`, "nothing was published or stored" confirmed (versionCount unchanged) |
| Breaking change, affected consumer | quote 3-price-lines (`amount`→`value`) → **blocked**, 5 breaking findings, attempt id 14 |
| Blank override | **422** — "a non-empty justification is required" |
| Justified override | **overridden_published**, actor + justification audited |
| Safe change | quote 4-ncb-and-taxes → **auto_published**; findings `REQ/RESP.PROPERTY_ADDED` + `CONSUMER.DRIFT` (stale mobile-app binding warning) |
| Pending ack | claims 1.2.0 (published 1.1.0 minus `assessorNotes`) → **pending_ack**, 2 breaking, 0 affected → acknowledge (with comment) → **published**; a subsequent wrong-state abandon → **400** naming the current state |
| Standalone impact | `POST /api/impact` quote 1.1.0→2.0.0 → mobile-app **AFFECTED** (reads the renamed field), notifications-svc **POSSIBLE**, partner-portal & payments-svc **NONE** |
| Migration guide | attempt 14 → 5 steps (`PARAM.REQUIRED_ADDED`, `RESP.PROPERTY_RENAMED`×2, `RESP.ENUM_WIDENED`, …) with consumer names + impact tiers |
| Changelog / history / audit | changelog 4 entries (1.0.0→2.1.0); history shows current + superseded + all attempts; audit 27 events incl. `SPEC_REJECTED`, `OVERRIDE`, `ACKNOWLEDGED` |
| Webhook receiver | 4 events at `/contractguard`: `blocked` (affected_consumers=["mobile-app"]), `overridden_published` (with justification), `published`×2 — correct service/version/actor/affected lists |
| Durable notifications | `/api/notifications` — all 4 `delivered` |

*(One operator-script error occurred and was corrected during verification: the first
`/api/impact` call omitted the required `service` field and got the documented `400`; the app
behaved correctly.)*

### Persistence & data lifecycle (all verified)

| Check | Result |
|---|---|
| Restart (`docker compose restart contract-guard`) | full state snapshot (services, versions+states, audit count, metrics) **byte-identical** after restart; kit re-import correctly skipped (idempotency marker) |
| Recreation (`compose down` **without** `-v` → `up -d`) | state snapshot again **byte-identical**; named volume `contractguard_contractguard-data` present before and after (declared as `contractguard-data`; never deleted) |
| Receiver inbox | reset to 0 after recreation — its documented in-memory design; the durable `notifications` table retained all 4 `delivered` rows |

### Final state

- Working tree **clean**; `git status --porcelain specs consumers webhook-receiver docker-compose.yml` **empty**
  (all 19 starter-kit fixtures byte-identical, checked repeatedly).
- Final code commit `93ceeff` on top of `5abfc3b`; docs-only `2ab05c6` after.

---

## Verification tiers — summary of the finished product

| Area | Tier |
|---|---|
| OpenAPI validation (collect-all, broken `$ref` rejection) | automated-test + live verified |
| Effective extraction, cosmetic-equivalence (reorgs diff to zero) | automated-test verified |
| Compatibility rule set (required, types, enums, renames, nesting, params, statuses) | automated-test verified |
| Parameter serialization (`style`/`explode`/`allowReserved`) | automated-test verified |
| Publishing gate (4 outcomes), acknowledge / override / abandon, 409/422/400 contract | automated-test + live verified |
| Concurrency: state-guarded decision transitions | automated-test verified (`PersistenceGuaranteesIntegrationTest`) |
| Consumer impact (AFFECTED / POSSIBLE / NONE) + standalone API | automated-test + live verified |
| Cumulative consumer drift (non-gating warnings) | automated-test + live verified |
| Migration guides grounded in stored findings | automated-test + live verified |
| Audit trail, changelog, history, metrics | automated-test + live verified |
| Webhook delivery (fire-and-forget, durable log) | automated-test + live verified (4 events) |
| Kit import (idempotent, through the real pipeline) | live verified |
| Docker Compose deployment, named volume persistence | live verified |
| CI gate script (`scripts/ci-check.sh`) exit codes 0/1/2/3/4 | implemented; header-contract reviewed against code |
| `oneOf` exclusivity validation | documented limitation |
| General regex subsetting for `pattern` changes | documented limitation |
| Self-service consumer registration API / dashboard upload | deferred (documented scope cut, DECISIONS.md A-08) |
| Authentication / RBAC (`X-Acting-User` is attribution only) | deferred (documented scope decision) |
| Public cloud deployment | not performed — local Docker Compose verification only |
