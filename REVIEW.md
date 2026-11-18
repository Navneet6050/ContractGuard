# Contract Guard — Engineering Review

**Scope:** the shipped implementation at commit `93ceeff` (docs transcript `2ab05c6` excluded — no code).
**Method:** every claim below was checked against the source, the test suite (144/144 green,
19 test classes) and, where marked **live**, against the running Docker Compose stack during
release verification (see [PROGRESS.md](PROGRESS.md), *Release verification*). Nothing here is
claimed as verified merely because the code exists.

Verification legend used throughout:

- **[T]** automated-test verified · **[L]** live-deployment verified · **[D]** documented limitation
- unmarked statements describe implementation structure (read from code, not behaviorally exercised)

---

## 1. Product and architecture overview

Contract Guard is an **API contract registry and publishing gate**. It ingests OpenAPI 3.0
documents, compares the *effective wire contract* against the currently published version,
cross-checks every breaking change against declarative consumer-usage files, and decides the
publication outcome: `auto_published`, `pending_ack` (human acknowledges), `blocked` (override
with mandatory justification required), or `rejected` (invalid spec, nothing stored).

The architecture is a **modular monolith**: one Spring Boot process with a strictly layered
core. The packages `specs`, `compatibility`, `consumers`, `impact`, `policy` and `domain` are
plain Java — no Spring annotations, no I/O — and are unit-testable without a container.
Spring exists only at the edges: `api` (controllers + typed error mapping), `persistence`
(`JdbcTemplate` + `schema.sql`), `notify` (webhook delivery), `pipeline` (orchestration,
`KitSeeder`, `MigrationGuide`). This layering is what makes the 144-test suite fast and
deterministic: the entire decision logic runs against in-memory H2 and pure classes.

The design principle that drives correctness is the **guarantee asymmetry**: the set of
requests a server accepts may only grow, and the guarantees it makes about responses may only
grow. Every rule in the engine is a consequence of that principle — there is no severity
scoring, no thresholds, no heuristics beyond rename detection, and gate outcomes are
explainable line-by-line from findings + impact tiers.

## 2. Component responsibilities

| Component | Responsibility | Notably does NOT |
|---|---|---|
| `specs/SpecParser` + `StructuralValidator` | Swagger Parser 2.1.48 wrapper + collect-all structural walk; every local `$ref` must resolve; all errors reported at once | fail-fast; trust the parser's message list alone |
| `specs/EffectiveSchemaExtractor` (+ `CanonicalJson`, `PathShapes`, `RefResolver`, `JsonMappers`) | build the effective contract per wire location `VERB /pathShape :: scope`; `$ref`-free, component-name-free, key-order-free; cycles via `x-circular` marker; `x-content` for media-type params; `x-serialization` for style/explode/allowReserved deviations | guess severities; know about consumers |
| `specs/DeprecationScanner` | `x-deprecated` / `x-sunset` lifecycle walk (depth-capped 500) | gate on lifecycle findings |
| `compatibility/CompatibilityEngine` | diff two effective contracts; stable rule codes; `BREAKING`/`WARN`/`INFO`; rename detection; deterministic (location, fieldPath, code) ordering | interpret semver; touch persistence |
| `consumers/` | strict YAML parsing; binding normalization to `method + path-shape` (placeholder **positions**, not names); registry loads files read-only at startup | modify consumer files; hot-reload |
| `impact/ConsumerImpactAnalyzer` | findings × bindings → per-consumer **AFFECTED / POSSIBLE / NONE** with evidence reasons | decide gating (that's `policy`+`domain`) |
| `impact/ConsumerDriftScanner` | cumulative drift warnings (`CONSUMER.DRIFT`, `CONSUMER.STALE_BINDING`) computed from stored bindings vs stored versions | **gate** — drift is advisory by design |
| `policy/` | the 3-outcome gate mapping (`PublishingDecision` is in `domain/`); separate semver advisory channel (`VERSION.POLICY.*`, `VERSION.UNPARSEABLE`) | let semver findings gate |
| `pipeline/PublicationService` | orchestration: duplicate/regression checks → diff → deprecation scan → policy → impact → gate → persist → webhook-after-commit; also history/changelog/metrics assembly | do schema math itself |
| `pipeline/KitSeeder` | idempotent startup import of the starter kit through the real pipeline (`KIT_IMPORT` audit marker) | special-case the kit with synthetic data |
| `pipeline/MigrationGuide` | steps generated **only** from stored findings + impact rows | invent steps |
| `domain/PublishingDecision` | the gate table: no breaking → PUBLISH; breaking+0 AFFECTED → PENDING_ACK; ≥1 AFFECTED → BLOCK | see invalid specs (rejected earlier, unconditionally) |
| `persistence/RegistryStore` | thin `JdbcTemplate` DAOs over 10 tables; state-guarded decision transitions; append-only transitions/audit | hold business rules |
| `notify/WebhookNotifier` | single fire-and-forget POST (2 s timeouts); every outcome recorded in `notifications` | retry; ever fail a committed publication |
| `api/` | 22 REST endpoints; typed error contract (400/404/409/422); health probe | contain business logic |

## 3. Effective contract extraction

The single most important engineering choice in the product: **compare contracts, not
documents**. Concretely:

- At every wire location the schema is inline-expanded, component names erased and key order
  canonicalized (`CanonicalJson` — sorted keys, TreeMap-backed envelopes). The planted
  cosmetic trap (quote 1.0.0→1.1.0: `Premium`→`PremiumDetails`, `BreakdownItem`→`PriceLine`,
  `Vehicle`/`Rider` inlined, `{id}`→`{quoteId}`) diffs to **zero** findings [T: `CosmeticEquivalenceTest`,
  `KitPairCompatibilityTest`; L: kit import auto-publishes 1.1.0].
- Local `$ref` resolution is complete, including `$ref`-wrapped **parameters, request bodies
  and responses** — a referenced location compares byte-identically to its inline twin
  [T: `ExtractionCornerCasesTest.refWrappedSerialization`; the M6 false `REQUEST_BODY.REMOVED`
  regression test].
- Cycles terminate at an opaque `{"x-circular": "<wire path>"}` marker (depth cap 50,
  component-name free), so cyclic schemas diff without exploding [T].
- Media-type parameters (`content:`) are captured as `x-content` inside the envelope [T].
- Parameter serialization deviations are captured as `x-serialization` (§5).
- Collect-all validation means one broken upload returns the *complete* diagnosis: the kit's
  `#/components/schemas/ClaimDocumnt` miss is reported with every other error in one 422
  response, and nothing is stored [T: `BrokenSpecRejectionTest`; L: 422 with attempt id].

## 4. OpenAPI compatibility analysis

The engine diffs envelope maps keyed by wire locations and classifies by the asymmetry rule.
Verified properties:

- **Request side**: required additions, type/enum/constraint narrowing, property and
  parameter removals → BREAKING; relaxations → INFO [T, rule-by-rule in `SyntheticCompatibilityTest`].
- **Response side**: property removals, guarantee removals, enum/type widening → BREAKING;
  guarantee strengthening → INFO [T].
- **Rename detection**: a removed+added pair with the same parent and same type is reported
  once as `*_PROPERTY_RENAMED` with both names — so the `amount`→`value` rename names
  `premium.breakdown[].amount` and the consumer reading it, instead of an opaque
  removed+added pair [T; L: mobile-app AFFECTED on the blocked quote 2.0.0 upload].
- **Nesting and arrays**: field paths keep wire notation (`premium.breakdown[].amount`); array
  items and nested objects are walked [T].
- **Parameters**: matched by `(in, ordinal)` position — deliberately name-free, so a path
  parameter rename stays cosmetic while schema/required changes are still caught [T].
- **Compositions**: `allOf` branches merge when non-conflicting (making "move fields into
  allOf" wire-neutral); `anyOf`/`oneOf` alternatives compare as a **set** — reordering is
  cosmetic, add/remove follows the asymmetry, same-size leftovers pair positionally [T:
  `oneOfSemantics`]. **`oneOf` exclusivity is not interpreted** — documented in the engine
  javadoc and listed as a limitation (§15).
- **Determinism**: findings sort by (location, fieldPath, code); stored reports, changelogs
  and guides are stable across runs [T].

Honest classification notes: `RESP.ENUM_WIDENED` / widening findings are BREAKING at the
finding level but yield **POSSIBLE** (advisory) consumer impact, because consumer files cannot
declare exhaustive value matching — the gate consequently takes the warn-and-acknowledge path
rather than blocking, which matches the documented impact-tier semantics (A-09). This is a
deliberate two-level classification, not an inconsistency.

## 5. Parameter serialization handling

Shipped in `93ceeff` after a release-phase engine review found the gap:

- **Extraction**: only **deviations from the OpenAPI defaults** are recorded under the
  envelope key `x-serialization` (defaults per OAS 3.0: query/cookie → `form` + explode-true;
  path/header → `simple` + explode-false; `allowReserved` query-only, default false). A spec
  that spells its implicit defaults out produces byte-identical envelopes → no findings —
  wire-neutrality is provable, not assumed [T: `serializationEnvelope` pins five envelope
  shapes including the explicit-defaults case and the query-only rule for `allowReserved`].
- **Diffing** (`PARAM.SERIALIZATION_CHANGED`):
  - style change → **BREAKING** (always alters the wire format) [T];
  - explode change → **BREAKING for array/object parameters**, **INFO** for single-valued
    ones (no wire-format change — never guessed breaking) [T: one array + one scalar param
    changed together yield one BREAKING and one INFO];
  - `allowReserved` disabled → **BREAKING** (permission removed), enabled → INFO (additive) [T];
  - explode is compared only when both sides share the same style — a style flip already
    covers the wire change and its explode default shifts with it [T].
- **Impact**: a consumer declaring `sends` of the parameter → AFFECTED on breaking changes;
  otherwise NONE (non-breaking changes never rise above NONE) [T].
- **Scope honesty**: `deepObject`/`spaceDelimited`/`pipeDelimited` styles flow through the
  same style-change rule but have no style-specific wire rendering modeled beyond the OAS
  defaults; `collectionFormat` (Swagger 2.0) has no OAS 3.0 counterpart and is not compared.

## 6. Consumer impact analysis

- **Onboarding format** is the starter-kit YAML: `consumer`, `team`, and `uses:` bindings with
  `provider`, `method`, `path`, `sends`, `reads`. Parsing is strict (shape errors are
  reported, not silently coerced) and bindings normalize to method + path *shape*, so
  `/quotes/{id}` matches the spec's `/quotes/{quoteId}` [T: `ConsumerParserTest`].
- **Field-path semantics** are shared with the engine: dots for nesting, `[]` after array
  properties; prefix matching (reads `premium` ⇒ affected by `premium.total` removal) [T].
- **Tier rules** [T: `ConsumerImpactAnalyzerTest`, `KitConsumerImpactTest`; L: quote 1.1.0→2.0.0
  analysis returned mobile-app AFFECTED, notifications-svc POSSIBLE, partner-portal and
  payments-svc NONE]:
  - removals/renames on fields a consumer reads → **AFFECTED**;
  - `REQ.REQUIRED_ADDED` → AFFECTED for candidates *not* declaring `sends` of it;
  - `REQ.ENUM_NARROWED` → AFFECTED with an explicit caveat (sent values are not recorded in
    the files);
  - widening/nullable/response-add findings → **POSSIBLE** (advisory);
  - no matching binding → **NONE** with the reason stated.
- **Standalone analysis** exists as `POST /api/impact` (any spec pair, no publication) [L;
  note: requires `service`, `oldSpec`, `newSpec` — a blank-field call correctly 400s].
- **Cumulative drift** (`ConsumerDriftScanner`): on every upload/check, declared bindings are
  re-checked against the incoming contract *cumulatively* over stored versions, so usage
  removed by an already-published version still surfaces as a non-gating
  `CONSUMER.DRIFT`/`CONSUMER.STALE_BINDING` warning [T; L: `CONSUMER.DRIFT` on the stale
  mobile-app binding during quote 2.1.0's auto-publish]. Drift deliberately never gates:
  consumer files carry no version pins, so "still using an old version" cannot be proven —
  the system warns with evidence instead of inventing pins (A-08, B-11 of the plan).

## 7. Publication governance

- **Gate mapping** is three lines of code (`PublishingDecision.evaluate`) with explanatory
  reasons attached to every outcome [T: `PublishingDecisionTest` covers the table].
- **States**: attempts are inserted directly with their decided state
  (`auto_published`/`pending_ack`/`blocked`/`rejected`) and transition to
  `published`/`overridden_published`/`abandoned`. The schema also permits `received`/`failed`
  but the shipped pipeline never writes them (defensive values only).
- **Human actions**: acknowledge (comment optional, from `pending_ack` only), override
  (justification **mandatory**, blank → 422, from `blocked` only), abandon (from
  `pending_ack`/`blocked`). Wrong-state actions → 400 naming the current state [all L].
- **Idempotence & conflicts**: identical re-upload → audited 200 no-op; same version different
  content → 409; version regression → 409 [T; L for the no-op and 409 paths].
- **Concurrency**: a decision transition is guarded by the expected previous state inside the
  same transaction that writes its transition row, impacts and audit event; the losing
  concurrent decision rolls back completely and surfaces as 409 ("attempt was decided
  concurrently") [T: `PersistenceGuaranteesIntegrationTest`].
- **Kit import** runs *through* this same governance in filename order at startup, idempotent
  via the `KIT_IMPORT` audit marker; kit specs with none of the new attributes shift no
  outcomes [L: fresh import = 12 attempts, 6 auto / 5 blocked / 1 rejected; restart re-import
  correctly skipped].

## 8. Persistence and transaction guarantees

- **H2 in file mode** (`jdbc:h2:file:...`) accessed through `JdbcTemplate` with hand-written
  `schema.sql`; no ORM. Tables: `services`, `spec_versions`, `publication_attempts`,
  `findings`, `finding_impacts`, `consumer_definitions`, `consumer_bindings`,
  `attempt_transitions`, `notifications`, `audit_events`.
- **Boot-time safety**: `CREATE TABLE IF NOT EXISTS` plus idempotent
  `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` migrations — an older local database upgrades in
  place at startup.
- **`spec_versions` rows are written only when a version actually becomes current** — a
  rejected attempt never reserves or poisons its version number [T: claims file-2 → file-3
  sequence publishes the fixed 1.1.0].
- **Attempt payloads** (raw YAML, canonical JSON, effective JSON, decision JSON, findings,
  impacts, justification) are stored with the attempt, so any decision is fully reconstructible.
- **Append-only history**: `attempt_transitions` and `audit_events` are never updated.
- **Live durability** [L]: container restart → state snapshot byte-identical, kit re-import
  skipped; `compose down` (volume retained) → `up -d` → state snapshot byte-identical again;
  only a deliberate `down -v` deletes data. The durable `notifications` table survived both;
  the receiver's in-memory inbox reset (its own documented design).
- **Single-process constraint**: H2 takes a file lock; only one app instance may open the data
  file (documented operational note, not an accident).

## 9. Webhook behavior

- Delivery is a **single fire-and-forget POST** after the publication transaction commits
  (2 s connect/read timeouts). **No retry** — a failed delivery is recorded in `notifications`
  with a compact status classifier (column width-bounded so even failure logging cannot
  throw) and logged; it can never fail an already-committed publication.
- Kinds delivered: `published`, `overridden_published` (carrying the justification),
  `blocked` (carrying `affected_consumers`). Payload includes service, version, decision,
  actor, findings summary with locations and field paths, timestamp [L: four events observed
  at the receiver with correct kinds, actors, versions and affected lists].
- The receiver is the untouched starter-kit Python service (in-memory inbox at
  `:9900/api/received`); the app's `notifications` table is the durable delivery log [L].

## 10. Migration guide and changelog

- **Migration guides** are generated per attempt exclusively from *stored* findings and impact
  rows: each step carries code, severity, location, field path, a concrete mapped action, and
  the affected consumer names with their tiers. Parameter names (deliberately absent from
  finding envelopes) are restored from the spec's parameter-name index. Codes without a
  template degrade to an honest review step quoting the finding's own detail — a step is
  never invented beyond what the finding states [T: `MigrationGuideTest`; L: attempt 14
  produced 5 steps naming three consumers across three tiers].
- **Changelog** (`GET /api/services/{slug}/changelog`) returns per-version entries with the
  embedded migration steps; **history** returns versions with states plus the attempt
  timeline [L: quote 1.0.0→2.1.0 with all four versions and states].

## 11. Metrics and CI

- **Metrics** (`GET /api/metrics`) are aggregated from real rows only — services, versions,
  attempts by state, findings by severity, breaking total, AFFECTED impacts by consumer,
  blocked/overridden/rejected counts, webhook delivery statuses, audit-event count [L:
  consistent with the live attempts; no fabricated counters].
- **CI gate** (`scripts/ci-check.sh`) calls the real gate as a dry-run and enforces a strict
  exit-code contract: `0` PUBLISH or PENDING_ACK (without `--strict`), `1` BLOCKED (or
  PENDING_ACK with `--strict`), `2` invalid spec (422), `3` operational failure, `4` usage
  error. The codes were reviewed against the script's implementation branch-by-branch; the
  header comment documents the contract. Interpreter discovery (`python3` → `python`) keeps
  it Windows-portable. Automated CI execution was not part of the verification harness — the
  script's behavior is code-reviewed, not pipeline-executed (stated honestly; not marked [L]).

## 12. Deployment

- **Packaging**: multi-stage `Dockerfile` (Maven+JDK21 build → JRE 21 runtime; fixtures baked
  in) plus `docker-compose.app.yml` as an **override** — the original `docker-compose.yml`
  (receiver only) is never modified. One command starts both containers on one network.
- **Verified live** [L]: images built, both containers up; app on host **8090** via the
  documented `PORT_APP` override (an unrelated Apache `httpd` process occupied host 8080 —
  not touched), receiver on **9900**→9000; health reported `db: UP` and the in-network
  webhook URL; kit imported idempotently. **The verified deployment is local Docker Compose;
  no public cloud deployment exists or is claimed.**
- **Persistence wiring**: named volume `contractguard-data` (effective name
  `contractguard_contractguard-data` under the compose project) mounted at `/app/data`;
  repo specs/consumers mounted read-only over the baked copies.

## 13. Security and operational considerations

- **No authentication or authorization** — by documented scope decision. `X-Acting-User` is
  **attribution only** (audit actor, default `anonymous`); anyone who can reach the API can
  register services, upload specs and override a blocked publication with any justification
  string. The control that *does* exist is the justification requirement (non-blank, 422
  otherwise) and its audit trail. This is adequate for the hackathon/demo scope and must be
  stated plainly for any other use.
- **Input handling**: uploads are parsed YAML/JSON bodies (not multipart); slug validation
  rejects malformed identifiers; consumer files are read-only fixtures. No shell-outs, no
  template execution; the frontend is static.
- **Webhook target** is configuration (`WEBHOOK_URL`); deliveries carry spec findings —
  treat the receiver network accordingly in any real deployment.
- **H2** is single-process; the receiver is a stdlib Python HTTP server (no TLS, in-memory).
- **Operational surface**: health endpoint exposes only status/db/webhook target; metrics
  contain no secrets; audit rows record actor identifiers verbatim.

## 14. Automated and live verification evidence

**Automated: 144/144 tests, 0 failures, 0 skipped** (BUILD SUCCESS, exit 0; re-run twice at
the final commit). 19 test classes across eight areas:

| Area | Classes | What they pin |
|---|---|---|
| specs | `SpecParserQuoteV1Test`, `BrokenSpecRejectionTest`, `CosmeticEquivalenceTest`, `DeprecationScannerTest`, `PathShapesTest`, `ExtractionCornerCasesTest` | parsing, collect-all rejection, cosmetic zero-diff, envelopes (incl. `x-serialization`, `x-content`, `$ref` twins) |
| compatibility | `SyntheticCompatibilityTest` (26 tests), `KitPairCompatibilityTest` | rule catalog incl. serialization + oneOf semantics; every kit version pair |
| consumers / impact | `ConsumerParserTest`, `ConsumerRegistryTest`, `ConsumerImpactAnalyzerTest`, `KitConsumerImpactTest`, `ConsumerDriftScannerTest` | binding normalization, tiers with evidence, drift warnings |
| policy / domain | `VersionPolicyAnalyzerTest`, `PublishingDecisionTest` | semver advisory, the 3-outcome gate table |
| pipeline | `MigrationGuideTest`, `PublishingWorkflowIntegrationTest`, `PersistenceGuaranteesIntegrationTest` | grounded guides; the whole gate over MockMvc + in-memory H2; restart safety, guarded transitions |
| api | `ImpactApiIntegrationTest` | the standalone impact endpoint |

**Live (Docker Compose, after `93ceeff`)** — every item actually executed: registration, 422
rejection with attempt id, dry-run storing nothing, blocked publication with 5 breaking
findings, 422 blank override, justified override, auto-publish with drift warning,
pending_ack → acknowledge → published, wrong-state 400, standalone impact (AFFECTED /
POSSIBLE / NONE), migration guide with named consumers, changelog/history/audit/metrics,
four webhook deliveries with correct payloads, durable `notifications` (4 × `delivered`),
byte-identical state across restart **and** recreation, volume retention, receiver-inbox
reset behavior, and fixture integrity (`git status` clean; the 19 starter-kit fixtures
byte-identical throughout). One operator-side error (missing `service` field on
`POST /api/impact`) produced the documented 400 — the app behaved correctly; details in
[PROGRESS.md](PROGRESS.md).

**Known gap in evidence**: `scripts/ci-check.sh` is code-reviewed against its documented
exit-code contract but was not executed inside a real CI pipeline; the acceptance matrix ran
as the live verification session instead. Also, automated tests cover the app with the
in-memory H2 profile — the file-mode database path is exercised by the live deployment
checks, not by the suite.

## 15. Known limitations

1. **`oneOf` exclusivity is not interpreted.** Alternatives are compared as a set;
   mutual-exclusion changes and removals that widen overlapping accepted sets are not
   detected as such. Documented in the engine javadoc.
2. **Regex subsetting for `pattern` changes** is decided only for the trivial all-accepting
   case; other pattern changes produce an explicit "direction cannot be determined" finding
   rather than a guess.
3. **Serialization styles** are compared by attribute value; style-specific renderings beyond
   the OAS defaults (e.g. `deepObject`) are not individually modeled, and Swagger-2.0
   `collectionFormat` has no counterpart to compare.
4. **Cycle-cut expansion**: changes *deep inside* a cyclic schema beyond the `x-circular`
   marker are not compared.
5. **Consumer onboarding is YAML files loaded at startup** — no self-service registration API,
   no dashboard upload, no hot reload (restart required).
6. **`X-Acting-User` is attribution, not authentication**; override authority = any API
   client with a non-blank justification.
7. **H2 is a single-process file database** — one app instance per data file; scale-out would
   require a network database.
8. **The receiver inbox is in-memory** (starter-kit design); the durable log is the
   `notifications` table.
9. **Webhooks are single-attempt** — no retry/backoff/queue; a down receiver at publish time
   means a recorded `failed:*` status, not a redelivered event.
10. **Deep-history limits**: consumer "which version do you still use?" cannot be answered
    (no pins in the format) — drift is advisory by construction.
11. Schema values `received`/`failed` are legal but unwritten by the pipeline (defensive
    only); error bodies are the flat `{"error": "message"}` shape, not the richer nested
    shape once sketched in the plan.

## 16. Recommended future improvements

Prioritized by risk-reduction per unit of work:

1. **Webhook hardening**: retry with exponential backoff + HMAC payload signatures + a
   delivery sweep for stuck `failed:*` rows. (Highest-value ops improvement; the durable log
   already exists.)
2. **AuthN/AuthZ minimal viable**: real identity for `X-Acting-User` (OIDC or signed
   tokens), role-gated override with two-person rule for regulated teams.
3. **Consumer self-service**: `POST /api/consumers` (parser already exists — this is mostly
   the A-08-cut transport), plus a "consumer verifies own declaration" flow and dashboard
   upload; hot-reload or file-watch for the registry.
4. **Pattern subsetting via automata**: implement regex intersection-emptiness for the
   common PCRE subset instead of the trivial case.
5. **oneOf exclusivity analysis**: satisfiability check over sibling discriminator/required
   constraints for the common cases (discriminated unions), falling back to today's
   conservative set semantics.
6. **Database portability path**: extract the DAO layer behind a port interface and add a
   Postgres profile for multi-instance deployments (H2 remains the zero-ops default).
7. **Observability**: actuator/micrometer metrics + request logging with attempt ids; export
   gate decisions as OpenTelemetry spans for pipeline integration.
8. **Diff robustness**: property-based fuzzing of the engine (random schema mutations →
   assert asymmetry invariants hold) and golden-file snapshots of kit pair diffs to make
   rule changes reviewable.
9. **OpenAPI 3.1 first-class**: exercise the parser's 3.1 path against 3.1-only constructs
   (`jsonSchemaDialect`, `examples` in schemas) and pin behavior in tests.
10. **CI runner image**: publish a small container bundling `ci-check.sh` so pipelines get a
    one-line gate step, and add an integration test that executes the script against a
    stubbed server to convert the code-review evidence into test evidence.
