# Contract Guard — Decision Log & Assumptions (Java / Spring Boot revision)

Each decision: context (grounded in the starter kit and the verified local toolchain), choice,
alternatives considered, consequences. Revision R2 changes only technology decisions
(B-series) and environment facts; all product decisions (A-series) are unchanged from R1.
Facts come from the reconnaissance of the actual files plus toolchain inspection and version
research on 2026-11-18; assumptions are explicitly marked and are the questions to confirm
with the product owner.

---

## A. Product & policy decisions (unchanged from R1)

**A-01 — Adopt the stated 5-rule publishing policy verbatim as the gate table.**
Context: risk-based, warning-first policy supplied by the team. Choice: the attempt state
machine implements exactly `auto_published | pending_ack | blocked | rejected` with the
override workflow for rule 3. Alternative rejected: scoring/severity-weighted gates
(over-engineered for 12 specs; undecidable thresholds). Consequence: gate outcomes are
explainable line-by-line from findings + impact tiers.

**A-02 — Semver is advisory only; version-policy findings are a separate channel.**
Context: policy kit plants a breaking change under a minor bump (policy-api 2.0.0→2.1.0:
`requestedBy` required + `VEHICLE` enum removed). Choice: compatibility engine computes
findings first; a separate module compares the semver bump and emits
`VERSION.POLICY.BREAKING_WITHOUT_MAJOR` etc. These decorate the attempt (red banner, audit)
but never gate. Consequence: rule 5 ("never trust semver alone") is honored literally; a
well-versioned breaking change still blocks (quote-api 2.0.0), a badly-versioned one is
flagged even when no consumer is affected.

**A-03 — Duplicate version: identical re-upload = idempotent no-op; differing content under
the same `info.version` = reject 409; rejected uploads don't reserve the version.**
Context: claims-api ships `1-initial`(1.0.0), `2-channel-and-notes`(1.1.0, broken ref) and
`3-channel-and-notes-fixed`(also 1.1.0, only the ref fixed). Choice: version identity is
claimed only by *stored* (published) versions. Upload of file 2 → rejected (invalid), so
file 3 publishes cleanly as 1.1.0; re-uploading file 3 → 200 no-op; a *different* spec with
an already-stored version → 409. Alternatives: revision chains (schema + UI cost, no
evaluation signal), silent overwrite (destroys audit integrity). Consequence: the
duplicate-version edge case is detected either way (409 or idempotent note) and never
poisons the registry. ⚠️ Assumption: evaluators prefer rejection over overwrite semantics.

**A-04 — Version regression rejected.** Incoming semver < current published → 409. Not in
the kit; cheap defensive rule preventing accidental downgrades. (Flagged as assumption A-11.)

**A-05 — `x-internal` is a badge, not an exemption.** Context: `DELETE
/internal/pricing-cache` (quote-api) carries `x-internal: true` in all versions and gains a
required `region` param in v2.0.0 — a real breaking change with zero registered consumers.
Choice: compatibility engine still reports `PARAM.REQUIRED_ADDED`; policy treats it under
rule 2 (warn + acknowledge) because impact is NONE; UI adds an "internal" badge.
Alternative rejected: auto-approving internal endpoints (hides the change from humans).
Consequence: uniform, explainable gating.

**A-06 — `x-sunset` is an enforceable deadline; deprecations trigger consumer warnings.**
Context: policy-api 1.1.0 adds `deprecated: true` + `x-sunset: '2026-11-01'` to
`Policy.legacyCode` (which payments-svc reads!) and `'2027-06-30'` to `Holder.phone`; 2.0.0
removes both. Choice: on `DEPRECATION.ADDED` → warn finding + webhook notice to the
registry (consumers reading the field are named from impact analysis); at any publish where
a field is still present with `x-sunset ≤ today` → `SUNSET_PASSED` warn (non-gating).
The 2026-11-01 date is already past (today 2026-11-18), which the kit deliberately allows
us to demonstrate. ⚠️ Assumption: sunset = "removed on/after this date", not "grace period
ends later".

**A-07 — Override authorization = mandatory justification, no auth system.**
Context: rule 3 requires an "explicit authorized override with mandatory justification".
Hackathon scope: any API client may override; justification is required (422 otherwise);
actor recorded from optional `X-Acting-User` header (default `anonymous`) into the audit
row. Alternative rejected: login/user registry (no time, no evaluation signal). ⚠️
Assumption: evaluators check justification enforcement + audit trail, not identity.

**A-08 — Consumer files are point-in-time snapshots; re-upload replaces the active
definition.** Context: README says consumer files describe usage "when the file was
written; the specs may have moved on since". Choice: one active definition per consumer
(`active` flag + history in `consumer_definitions`); impact always computed against the
active definition at attempt time. ⚠️ Assumption: `reads` = hard dependency (block on
removal), `sends` = declared fields (absence ⇒ affected by `REQUIRED_ADDED`); enum values
sent are not recorded ⇒ `REQ.ENUM_NARROWED` conservatively affects all bound consumers
with an explicit caveat (partner-portal + `VEHICLE`).

**A-09 — Impact tiers: AFFECTED gates, POSSIBLE warns, NONE clears.**
Context: consumers can't declare "I match exhaustively on status". Choice:
`RESP.ENUM_WIDENED`/`TYPE_WIDENED`/`NULLABLE_ADDED` produce breaking findings with POSSIBLE
impact (bound consumers listed as advisory, no block ⇒ rule 2 path). `RESPONSE_ADDED`
(claims `202`) is info + advisory note, not breaking. Consequence: quote 2.0.0 `EXPIRED`
lands in the warn-and-ack path, matching recon ("none break per files; strict consumers
unknowable").

**A-10 — Registry-first workflow, plus one-click kit import.** Choice: specs enter via
`POST /services/{slug}/specs`; `POST /admin/import-kit` scans the untouched `specs/` +
`consumers/` folders in filename order for the demo. ⚠️ Assumption: README leaves ingestion
mode open; both paths are provided so either evaluation style works.

## B. Technical decisions

**B-01 — Java 17 target / JDK 21 toolchain / Spring Boot 4.1.2.**
Context: team direction is Java + Spring Boot; verified environment has Temurin JDKs 17/21/25
installed, Maven 3.9.11, Docker 29.6.2, no Gradle/Podman. Verified ecosystem (2026-11-18):
Spring Boot 3.5 lost OSS support 2026-06-30 (3.5.16 final free patch) and Boot 4.0 loses OSS
support 2026-12-31 — building a new product on either is unjustifiable; 4.1 (patch 4.1.2,
2026-10-22) is OSS-supported to 2027-07-31 and keeps a **Java 17 baseline** (runs on 17–26).
Choice: `maven.compiler.release=17`; build and run on installed Temurin **21.0.13** (LTS,
mature under Maven); Docker runtime `eclipse-temurin:21-jre`. Alternatives rejected: Boot
3.5.x (unsupported), Boot 4.0.x (support cliff in six weeks), Boot 4.2 (GA only 2026-11-30 —
race), running on JDK 25 (works, adds nothing, and JDK 8 in `JAVA_HOME` is a trap — B-08).
Dependencies (version-managed where the Boot BOM provides it): `spring-boot-starter-web`,
`-validation`, `-jdbc`, `-test`; `io.swagger.parser.v3:swagger-parser:2.1.48` (latest 2.1.x,
OpenAPI 3.0+3.1); `tools.jackson.dataformat:jackson-dataformat-yaml`; `com.h2database:h2`.
Alternatives rejected: Gradle (not installed), Kotlin (team said Java), springdoc v2
(Boot-3-only; v3 optional, cut-first).

**B-02 — Effective-schema comparison via Swagger Parser resolution + own canonicalizer.**
Context: quote-api 1.0.0→1.1.0 renames components (`Premium`→`PremiumDetails`,
`BreakdownItem`→`PriceLine`), inlines `Vehicle`/`Rider`, renames path param `{id}`→
`{quoteId}` — all with zero wire change. Choice: parse with `OpenAPIParser`; keep our own
collect-all `$ref` walk (the parser's message list alone is not trusted to surface every
error); canonicalize by inline-expanding refs, erasing names/order, and comparing shapes at
wire locations `(method, pathShape, scope)`. Canonical JSON is emitted from our own DTOs with
Jackson 3 (sorted keys) rather than re-serializing swagger-core models — safe either way
since `jackson-annotations` is shared by Jackson 2 and 3, but own DTOs keep the canonical
form independent of the parser's object graph. Component renames with identical shape → info
note at most. Consequence: the trap yields zero breaking findings plus a "cosmetic reorg,
0 breaking" note that proves the engine worked. Cycle/depth guard (50) documented as
limitation (kit has no cycles).

**B-03 — Consumer matching = method + normalized path shape; placeholder names ignored.**
Context: consumer files say `GET /quotes/{id}` with `sends: [id]` while specs ≥1.1.0 use
`{quoteId}`. Choice: normalize templates to segment+ordinal (`{0}`); match method+shape;
field paths (`premium.breakdown[].amount`) mapped into the same coordinate system as
findings; prefix rule (reads `premium` ⇒ affected by `premium.total` removal); required-add
rule (affected iff consumer doesn't declare sending it). Consequence: notifications-svc and
mobile-app bindings keep matching after the rename; no false "no matching operation".

**B-04 — Breaking classification follows the guarantee asymmetry principle.**
Requests: accepted space may only grow (`REQUIRED_ADDED`, narrowing type/enum/constraints,
property removal ⇒ breaking). Responses: guaranteed space may only grow (`REQUIRED_REMOVED`,
property removal, possible-space growth ⇒ breaking); guarantee *strengthening*
(`REQUIRED_ADDED`, narrowing) ⇒ info. Rename detection (removed+added, same parent, same
type) reported once as `RESP.PROPERTY_RENAMED` with old+new names, matched against readers
of the old name (mobile-app + `amount`→`value`). Consequence: all nine planted edge cases
from recon fall out of ~15 rules without special-casing.

**B-05 — Modular monolith: pure-Java core packages, Spring only at the edges.**
`specs`, `compatibility`, `consumers`, `impact`, `policy`, `domain` contain **no Spring
annotations and no I/O** — plain JUnit-testable classes. `api`, `persistence`, `notify`,
`audit` own all Spring/I-O concerns. Rejected: microservices (no infra value), Spring Data
JPA/Hibernate (dialect machinery, lazy-loading traps, annotation overhead for 9 tables in
5 h), ORM-free raw `java.sql` (loses transaction/pool plumbing). Chosen middle ground:
**`JdbcTemplate` + hand-written `schema.sql`** — full SQL control, zero magic, the thin-DAO
design from R1 survives verbatim.

**B-06 — H2 (file mode) over SQLite; in-memory H2 for tests.**
Verified trade-off: H2 is pure-Java and version-managed by the Boot BOM → zero driver/dialect
risk, one dependency; SQLite would require pinning `org.xerial:sqlite-jdbc` outside the BOM
plus Windows file-locking quirks, with no countervailing benefit. Runtime:
`jdbc:h2:file:./data/contractguard`; tests: `jdbc:h2:mem` per context. DDL in `schema.sql`
executed by Spring SQL init (`CREATE TABLE IF NOT EXISTS`), H2-dialect-reviewed
(`IDENTITY` PKs, `CLOB` payloads, native `CHECK`). Known constraint: single-process lock on
the file DB (documented; only the app opens it). Schema concepts identical to R1.

**B-07 — Mixed-Jackson containment (Boot 4 ↔ Swagger Parser).**
Context: Spring Boot 4 standardized on **Jackson 3** (`tools.jackson.*`); Swagger Parser
2.1.48 remains Jackson-2-based internally. Verified compatibility fact: Jackson 3 moved
group/package names to `tools.jackson` **except `jackson-annotations`**, which stays
`com.fasterxml.jackson.core:jackson-annotations` (`com.fasterxml.jackson.annotation`
package) and is used by both — so Jackson 2 and 3 coexist on one classpath and Jackson 3 can
read swagger POJOs' annotations. Choice: our own JSON/YAML (canonical forms, consumer files,
webhook payloads) uses **Jackson 3** (`tools.jackson.dataformat-yaml`, Boot-managed);
Swagger Parser resolves its own Jackson 2 transitively and we never configure or share
mappers across the two worlds. Fallbacks: if the `tools.jackson.dataformat:jackson-dataformat-yaml`
artifact isn't resolved by the BOM, read consumer files with SnakeYAML (already transitive
via swagger-parser); if any Boot-4/Jackson-2 friction appears, `spring-boot-jackson2` is the
documented escape hatch. Consequence: no version surgery during the hackathon.

**B-08 — Environment remediation: `JAVA_HOME` fix + background dependency warm-up.**
Verified fact: `JAVA_HOME=C:\Program Files\Java\jdk-1.8` makes Maven 3.9.11 run on JDK 8 —
every modern build fails under it. Choice: every Maven shell sets
`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot` first (verified against
`mvn -v` reporting Java 21); optional `scripts/dev.ps1` helper does this + run. Build
encoding pinned to UTF-8 in the POM (platform default is Cp1252). At hour 0, run
`mvn -q dependency:go-offline` in the background so the first-build download never blocks
milestones. Consequence: the single most likely hour-0 failure mode is pre-empted.

**B-09 — Reuse the webhook receiver unmodified via a compose override file.**
Context: `app.py` and `docker-compose.yml` are evaluator fixtures ("Do not change this
file"). Choice: add `docker-compose.app.yml` (override) adding the `contractguard` service;
start with both files. Inside Compose the app reaches the receiver at
`http://webhook-receiver:9000/contractguard` (shared default network); locally at
`http://localhost:9900/contractguard` — selected by `WEBHOOK_URL` env with those defaults.
Receiver keeps in-memory state (documented limitation: restart clears notifications; our
`notifications` table is the durable log). Fire-and-forget `RestClient` delivery with one
retry; failures logged, never block publishing. Dockerfile is multi-stage
(`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`); fallback for slow first image
builds: slim Dockerfile copying a locally built jar.

**B-10 — Frontend = static vanilla JS page served from the Boot jar.** Pages: Publish
(upload + findings + ack/override dialog with enforced non-blank justification), Registry,
Consumers (+ impact matrix), Audit, Diff tool. No build chain; resources under
`src/main/resources/static`. Cut-first item if time runs short.

## C. Fact ledger (reconnaissance + environment — treated as ground truth)

Kit facts (unchanged from R1):
- All 12 specs are OpenAPI **3.0.3**, YAML, local `$ref`s only; versions: quote
  1.0.0/1.1.0/2.0.0/2.1.0, policy 1.0.0/1.1.0/2.0.0/2.1.0, claims 1.0.0/**1.1.0/1.1.0**/2.0.0.
- Planted breaking changes: quote 2.0.0 (`amount`→`value` response rename; required `region`
  on internal op; `EXPIRED` enum), policy 2.0.0 (`legacyCode`, `phone` removed), policy 2.1.0
  (`requestedBy` required, `VEHICLE` removed — under a **minor** bump), claims 2.0.0
  (`amountApproved` leaves response `required`; `202` added).
- Planted invalidity: claims v1.1.0 file 2 refs `#/components/schemas/ClaimDocumnt`
  (nonexistent; fixed in file 3, same version number).
- Planted cosmetic trap: quote 1.0.0→1.1.0 reorg with zero wire change.
- Planted lifecycle: `legacyCode` x-sunset **2026-11-01** (past), `phone` **2027-06-30**.
- Consumers: mobile-app reads `premium.breakdown[].amount` (rename victim) and
  `amountApproved` (required-removal victim); payments-svc reads `legacyCode`
  (removal victim); partner-portal sends `type`,`details` (required-add + enum-removal
  victim); notifications-svc reads only safe fields; nobody binds the internal op;
  `Holder.phone` has no readers.
- Consumer↔spec drift: `/quotes/{id}` vs `{quoteId}`.

Environment & ecosystem facts (added R2, verified 2026-11-18):
- Temurin JDKs **17.0.18 / 21.0.13 / 25.0.5** installed; `java`/`javac` on PATH = 25.0.5;
  Maven **3.9.11** installed but runs on **JDK 1.8** (`JAVA_HOME` stale) — builds fail until
  remediated; Docker **29.6.2**; no Gradle, no Podman.
- Spring Boot **3.5 OSS support ended 2026-06-30** (last free patch 3.5.16); **4.0 OSS ends
  2026-12-31**; **4.1** (Jun 2026, latest 4.1.2 of 2026-10-22) OSS-supported to **2027-07-31**,
  Java 17–26, Jackson 3 default; 4.2 GA planned 2026-11-30.
- Jackson 3 moved group/packages to `tools.jackson` **except `jackson-annotations`** (stays
  `com.fasterxml.jackson.core` / `com.fasterxml.jackson.annotation`, shared with Jackson 2)
  — Jackson 2 and 3 coexist; Spring Boot 4 ships Jackson 3 default with a deprecated
  `spring-boot-jackson2` escape hatch.
- Swagger Parser latest 2.1.x = **2.1.48** (OpenAPI 3.0 + 3.1, Java 11+).

## D. Assumption ledger (to confirm with product owner)

| # | Assumption | Risk if wrong | Mitigation |
|---|---|---|---|
| A-01 | Kit file order (numeric prefix) = publication order | chain diffs wrong | import honors prefix order; manual upload unaffected |
| A-05 | `x-internal` doesn't exempt from gating | evaluator expects auto-pass | one-line policy tweak |
| A-06 | `x-sunset` semantics = removal deadline; "today" = server date | wrong warn timing | date logic isolated in one function |
| A-07 | Override authority = justification + actor header, no real auth | evaluator expects login | header is a stub for a real identity later |
| A-08 | `reads`=hard dep, `sends`=declared fields, enum values unknown ⇒ conservative | over/under-blocking | tiers + evidence make each case explicit |
| A-10 | Duplicate same-version different content should be **rejected** (not superseded) | evaluator expects revision chain | rejection is the loud, auditable behavior; policy isolated in one gate |
| A-11 | Version regression (lower semver) should be rejected | not in kit; unexpected 409 | easily disabled via config flag |
| A-12 | Evaluators may either upload via API or expect folder import | workflow mismatch | both entry points provided |
| A-13 | `RESPONSE_ADDED` (claims `202`) is info, not breaking | evaluator treats as breaking | single rule-code reclassification |
| A-14 | Notification target default `:9900` local / `webhook-receiver:9000` in Compose | receiver path preference | configurable via `WEBHOOK_URL` |
| A-15 | Overriding `JAVA_HOME` per-session (to Temurin 21) is acceptable for build/run | eval env rebuilds with its own env | Docker build path is env-independent; `release=17` keeps artifacts portable |
| A-16 | Boot 4 BOM manages `tools.jackson.dataformat:jackson-dataformat-yaml` | artifact not resolved | fallback: SnakeYAML (transitive via swagger-parser) for consumer files |
| A-17 | Network access for Maven Central + Docker Hub base images on the day | first build stalls | background `dependency:go-offline` warm-up; slim Dockerfile fallback copying a prebuilt jar |

## E. Out of scope (explicit)

Multi-tenant auth/RBAC; spec *authoring* or editor UI; runtime traffic verification
(consumer files are declarative snapshots, not observed traffic); cross-file/remote `$ref`
support (kit is local-only; depth guard documented); retry queues for webhooks; consumer
definition versioning beyond active+history; OpenAPI 2.0 parsing; Spring Data JPA/Hibernate;
springdoc/Swagger UI for our own API (optional cut-first); Testcontainers; GraalVM native
images; Kotlin.
