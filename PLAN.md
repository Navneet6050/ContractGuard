# Contract Guard — Architecture & Implementation Plan (Java / Spring Boot revision)

Status: design only (no application code written yet). Revision R2: technology stack switched
from Python/FastAPI to **Java 17 + Spring Boot** per team decision; product decisions,
compatibility rules, consumer impact model, publishing state machine, database concepts, API
design and test scenarios are unchanged from R1. Grounded in the reconnaissance of the actual
starter kit: 12 OpenAPI 3.0.3 specs (quote-api, policy-api, claims-api × 4 versions), 4
consumer files, stdlib Python webhook receiver, and the verified local toolchain (see §1).

Adopted publishing policy (risk-based, warning-first):

1. Non-breaking → publish normally.
2. Breaking, no registered-consumer impact → warn; publish after acknowledgment.
3. Breaking **with** registered-consumer impact → warn on first attempt, **block** by default;
   explicit override requires mandatory justification, recorded in audit history.
4. Invalid specs (incl. unresolved `$ref`) → reject unconditionally.
5. Semver is never trusted for compatibility; version-policy violations are flagged separately.

---

## 1. Technology stack & justification

### 1.1 Verified local toolchain (inspected before choosing versions)

| Fact | Detail |
|---|---|
| JVM on PATH | OpenJDK **25.0.5** LTS (Eclipse Temurin), `javac` 25.0.5 |
| Installed JDKs | `C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`, `jdk-21.0.13.9-hotspot`, `jdk-25.0.5.8-hotspot`; legacy Oracle `jdk-1.8` |
| Maven | **3.9.11** installed — but **running on JDK 1.8.0_431** because `JAVA_HOME=C:\Program Files\Java\jdk-1.8` (stale). Any modern build fails under this setting; every Maven invocation must run with `JAVA_HOME` pointed at a JDK 17+ install (mitigation in §1.3 and B-08) |
| Gradle | not installed |
| Docker | 29.6.2 (Podman absent) — still used to run the untouched webhook receiver |

### 1.2 Chosen stack and versions

| Layer | Choice (version) | Why |
|---|---|---|
| Language level | **Java 17** (`maven.compiler.release=17`) | Team direction "Java 17 or compatible"; runs on every installed JDK (17/21/25). |
| Build/JDK toolchain | **Maven 3.9.11 + Eclipse Temurin 21.0.13** as build/runtime JDK | LTS, installed locally, mature under Maven; JDK 25 also fine as runtime but adds no value here; JDK 8 must never be selected (see B-08). |
| Framework | **Spring Boot 4.1.2** (classic starters) | Verified Nov 2026 landscape: Boot 3.5 lost OSS support 2026-06-30 (3.5.16 final free patch) and 4.0 loses OSS support 2026-12-31 — both are poor targets; 4.1 (June 2026, patch 4.1.2 of 2026-10-22) is OSS-supported until 2027-07-31, keeps the Java 17 baseline, and 4.2 is not GA until 2026-11-30. Classic starters remain the boring, documented path. |
| REST | **Spring Web** (`spring-boot-starter-web`) + **Bean Validation** (`spring-boot-starter-validation`) | Monolith REST API; declarative validation for e.g. the mandatory override justification. |
| OpenAPI parsing/validation | **Swagger Parser** `io.swagger.parser.v3:swagger-parser:2.1.48` | De-facto standard; parses OpenAPI 3.0.3 (2.1.x supports 3.0 and 3.1), resolves local `$ref`s, exposes typed `io.swagger.v3.oas.models.*` POJOs, and reports parse messages we augment with our own collect-all structural walk (§3 step 3). Java 11+ prerequisite — we run 21. |
| YAML | **Jackson 3 YAML** `tools.jackson.dataformat:jackson-dataformat-yaml` (version managed by the Boot 4 BOM) for consumer files and canonical-JSON emission | Boot 4 standardized on **Jackson 3** (`tools.jackson.*` namespace). Consumer files are simple nested maps → `JsonNode` traversal. Swagger Parser brings its own Jackson 2 transitively; the two coexist by design (different root packages; `jackson-annotations` stays `com.fasterxml.jackson.annotation`, shared by both — details in B-10). |
| Persistence | **H2 in file mode** (`jdbc:h2:file:./data/contractguard`) via **Spring JDBC (`JdbcTemplate`)** — no ORM | Verified trade-off: H2 is pure-Java, version-managed by the Boot BOM, and gives **in-memory mode for tests** (`jdbc:h2:mem`) for free. SQLite would need a non-BOM driver pin and offers nothing extra here. `JdbcTemplate` + hand-written DDL keeps the thin-DAO design and avoids Hibernate dialect machinery entirely. |
| Testing | **JUnit 5 + Spring Boot Test** (`spring-boot-starter-test`): plain JUnit for pure modules, `MockMvc` for controllers, `TestRestTemplate` for full-stack integration | Standard; test DB is in-memory H2 so integration tests need no state cleanup. |
| Frontend | **Minimal vanilla HTML/CSS/JS** served from `src/main/resources/static` | No build chain; Spring serves it from the same jar. |
| Notifications | **Spring `RestClient`** (sync, 2 s timeouts) fire-and-forget | Talks HTTP to the untouched Python webhook receiver; no queue. Failure is logged, never blocks publishing. |
| Packaging/run | **Docker Compose** — new `docker-compose.app.yml` *override* alongside the untouched original `docker-compose.yml`; multi-stage `Dockerfile` (`maven:3.9-eclipse-temurin-21` build stage → `eclipse-temurin:21-jre` runtime) | Receiver stays a fixture ("do not change this file"); the override composes both services on the same default network. Fallback if image builds are slow on the day: a slim Dockerfile that copies a locally built jar. |

Deliberately excluded: microservices, Redis/queues, **Spring Data JPA/Hibernate**, **springdoc
openapi** (the Boot-4-compatible v3 line is an optional cut-first nice-to-have), Lombok,
MapStruct, auth systems, Testcontainers, GraalVM native, React/build tooling.

### 1.3 Environment remediation (must do at hour 0)

```powershell
# Every shell that runs Maven (or add to scripts/dev.ps1 helper):
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot"
mvn -v   # must report Java 21, not 1.8.0_431
```

Then kick off a dependency warm-up in the background immediately
(`mvn -q dependency:go-offline`) while coding pure modules — first build downloads
everything; the demo must never wait on that.

## 2. High-level architecture

Modular monolith — one Spring Boot process, strict internal layering. The core packages are
plain Java (no Spring annotations, no I/O) and independently testable; Spring lives only at
the edges.

```
                 ┌────────────────────────────────────────────────────┐
  upload / UI ──▶│ api/  (@RestController, DTOs, @ControllerAdvice,   │
                 │        static/ frontend)                           │
                 └───┬────────────────────────────────────────────────┘
                     ▼
                 ┌────────────────┐
                 │ pipeline/      │  orchestrates the steps below (I/O boundaries)
                 └─┬────┬────┬────┘
       ┌───────────┘    │    └───────────────┐
       ▼                ▼                    ▼
 specs/          compatibility/           consumers/
 SwaggerParser   effective-schema diff    Jackson-YAML reader
 wrapper +       + rule classification    + binding normalize
 collect-all        (plain Java)          + matching by method+path-shape
 structural walk                           │
 + $ref resolve                            ▼
 + canonicalize                     impact/
 + effective schemas                finding × binding → affected consumers
 + deprecation/x-sunset scan                │
       │                                    ▼
       ▼                             policy/
 policy/           ◀── findings + impacts + semver bump        publish gate
 (plain Java)      ◀── deprecation/x-sunset scan                (5 policy rules)
       │
       ▼
 domain/ (attempt state machine) ──▶ audit/ (append-only transitions)
       │
       ▼
 notify/ (RestClient) ──HTTP POST──▶ webhook-receiver (Python kit, UNTOUCHED, :9900)
       │
       ▼
 persistence/ (JdbcTemplate + H2 file: services, spec_versions, attempts, findings,
               impacts, consumers, bindings, transitions, notifications)
```

Package responsibilities (the mandated separation):

| Package | Responsibility | Explicitly does NOT |
|---|---|---|
| `specs` | Swagger Parser wrapper (parse + typed model); **collect-all structural validation incl. every local `$ref`**; canonicalization; **effective-schema extraction** per wire location; deprecation/`x-sunset` scan | classify changes, know consumers, touch the DB |
| `compatibility` | Independent schema-level diff between two effective schema maps; classify each difference with a stable rule code + severity; cosmetic-equivalence normalization | know consumers, policy, or versions |
| `consumers` | Read consumer YAML (Jackson 3 YAML); normalize bindings (method + **path shape**); match bindings to spec operations | classify severity |
| `impact` | For each breaking finding, decide which registered consumers are affected (AFFECTED / POSSIBLE / NONE) with evidence | decide publish gating |
| `policy` | Map (breaking count, affected consumers, version bump) → required action per the 5 policy rules; **separate semver-policy findings** | perform diffs |
| `domain` | Attempt state machine; guarded transitions; audit events emitted as plain events | any I/O except through interfaces |
| `audit` | Append-only history; query API | mutate findings |
| `notify` | Structured webhook payloads to the kit receiver (`RestClient`) | retry forever / block pipeline |
| `api` | HTTP surface, DTO validation, `@ControllerAdvice` error mapping, static frontend | business logic |
| `persistence` | `JdbcTemplate` DAOs + `schema.sql` (H2 init) | business logic |

## 3. End-to-end data flow (publication pipeline)

Entry points: `POST /services/{slug}/specs` (single upload) and `POST /admin/import-kit`
(reads the untouched `specs/` + `consumers/` folders in filename order and replays step 1–10
per file). Steps:

1. **Receive**: multipart file (or raw YAML body). Capture bytes → SHA-256.
2. **Parse** (`specs`): Swagger Parser `OpenAPIV3Parser` reads content; result must expose
   `openapi: 3.0.3*`, `info.title`, `info.version`, `paths`. Non-YAML/malformed → `400`.
3. **Validate + resolve** (`specs`): combine parser messages with our own document walk —
   every `$ref` string must resolve to an existing node; **collect all** errors, not
   fail-fast (e.g. claims v1.1.0 reports `#/components/schemas/ClaimDocumnt` unresolved).
   Any error → attempt state `rejected`, HTTP `422`. **Unconditional per policy rule 4.**
4. **Duplicate/regression check**: same service + same `info.version` already stored →
   identical canonical content → idempotent no-op `200`; different content → reject `409`
   (claims file 2 vs file 3 scenario). Semver lower than current published → reject `409`.
5. **Canonicalize + extract effective schemas**: inline-expand every local `$ref` at each
   usage site (swagger-core model after parser resolution, plus our own canonical form);
   erase component names, key order, formatting; produce `effective[location]` where
   `location = (method, pathShape, scope)` and
   `scope ∈ {requestBody, param:<in>:<ordinal>, response:<status>}`. Canonical JSON is
   emitted by our own DTOs with Jackson 3 (sorted keys), not by re-serializing raw
   swagger models (containment rule B-10).
6. **Diff** (`compatibility`): compare effective maps old(current published)→new; classify
   each difference (§6 rule catalog) → `findings[]` with `breaking|warn|info`.
   First version of a service → no diff → auto-publish path.
7. **Version policy** (`policy`): semver bump type old→new computed **independently**;
   emits policy findings (`VERSION.POLICY.BREAKING_WITHOUT_MAJOR`, `MAJOR_WITHOUT_BREAKING`,
   `VERSION.UNPARSEABLE`). Never merged into compatibility findings.
8. **Deprecation scan** (`specs`→findings): newly `deprecated` fields → `DEPRECATION.ADDED`
   (+ notify readers); `x-sunset` date ≤ today while field still present → `SUNSET_PASSED`
   warn (e.g. `legacyCode` sunset `2026-11-01` already past).
9. **Impact** (`impact`): for every breaking finding → match consumer bindings (method +
   path-shape; `{id}`≡`{quoteId}`) and field paths (`premium.breakdown[].amount`) →
   AFFECTED / POSSIBLE / NONE + evidence lines.
10. **Gate** (`policy` + `domain`): persist attempt + findings + impacts in one
    `JdbcTemplate` transaction; audit transition:
    - no breaking → `auto_published` (spec stored as current version) + webhook `publish`
    - breaking, zero AFFECTED → `pending_ack` (warn + wait)
    - ≥1 AFFECTED → `blocked` (warn + wait)
    Response returns state + allowed actions + full findings.

11. **Decision** (later, human): `POST /attempts/{id}/acknowledge` (comment optional) →
    `published`; `POST /attempts/{id}/override` (**justification mandatory, enforced by
    Bean Validation**, non-blank) → `overridden_published`; `POST /attempts/{id}/abandon`.
    Every transition audited; publish/override/deprecation events POSTed to the receiver
    (webhook payload includes service, version, finding summary, affected consumers,
    justification when present).

Dry-run: `GET /diff/{slug}?from=&to=` executes steps 5–8 read-only, persists nothing.

## 4. Domain model & database schema (H2 file mode, Spring SQL init)

Same entities as R1 (database concepts unchanged): `Service`, `SpecVersion`,
`PublicationAttempt`, `Finding`, `FindingImpact`, `Consumer`, `ConsumerBinding`,
`AttemptTransition` (audit), `Notification`. DDL lives in `schema.sql`, executed by Spring
Boot SQL init (`spring.sql.init.mode=always`, `CREATE TABLE IF NOT EXISTS`), reviewed for H2
dialect: primary keys as `IDENTITY`, large JSON/YAML payloads as `CLOB`, `CHECK` constraints
supported natively.

```sql
services            (id IDENTITY PK, slug VARCHAR UNIQUE, title, owner_team, created_at TIMESTAMP)
spec_versions       (id IDENTITY PK, service_id FK->services, version VARCHAR, source_sha,
                     source_filename, raw_yaml CLOB, canonical_json CLOB, effective_json CLOB,
                     state VARCHAR CHECK(state IN ('published','superseded')),
                     published_at TIMESTAMP, UNIQUE(service_id, version))
publication_attempts(id IDENTITY PK, service_id FK, incoming_version, source_sha, raw_yaml CLOB,
                     canonical_json CLOB, state VARCHAR CHECK(state IN
                       ('received','rejected','auto_published','pending_ack',
                        'blocked','published','overridden_published','abandoned','failed')),
                     breaking_count INT, required_action, created_at TIMESTAMP, decided_at TIMESTAMP,
                     decided_by, justification CLOB, note CLOB)
findings            (id IDENTITY PK, attempt_id FK, code, severity VARCHAR CHECK(severity IN
                       ('breaking','warn','info')),
                     scope, method, path, location, field_path, detail, old_json CLOB, new_json CLOB)
finding_impacts     (id IDENTITY PK, finding_id FK, consumer, level VARCHAR CHECK(level IN
                       ('affected','possible','none')), evidence_json CLOB)
consumer_definitions(id IDENTITY PK, name VARCHAR UNIQUE, team, raw_yaml CLOB, source_sha, active,
                     created_at TIMESTAMP, replaced_at TIMESTAMP)   -- re-upload replaces active row
consumer_bindings   (id IDENTITY PK, consumer_id FK, provider_slug, method, path_raw,
                     path_shape, sends_json CLOB, reads_json CLOB)
attempt_transitions (id IDENTITY PK, attempt_id FK, from_state, to_state, actor, comment,
                     created_at TIMESTAMP)                          -- append-only, never updated
notifications       (id IDENTITY PK, kind, service, version, payload_json CLOB, target_url,
                     status, created_at TIMESTAMP, delivered_at TIMESTAMP)
```

Notes (unchanged in substance):
- `spec_versions` rows are created **only** when a version actually becomes current
  (auto-publish or post-ack/override). Rejected/blocked attempts live only in
  `publication_attempts` (+audit), so rejected claims v1.1.0 never blocks the fixed v1.1.0.
- `finding_impacts` is the denormalized join that answers "who breaks if this publishes?"
- DAO = thin `JdbcTemplate` repositories in `persistence`; one transaction per pipeline
  stage (`@Transactional` at the pipeline boundary). H2 file DB is single-process: the app
  holds the lock (don't open a second process against the same file).

## 5. REST API design

Base: `http://localhost:8000` (env `PORT_APP` / `server.port`). JSON errors:
`{"error": {"code", "message", "details": [...]}}` via `@ControllerAdvice`.

| Method & path | Purpose | Success | Errors |
|---|---|---|---|
| `POST /services/{slug}/specs` | Upload spec (multipart `file`, optional `override_title`). Runs pipeline. | `200` attempt (state per gate) | `400` not YAML · `409` duplicate-differs / regression · `422` invalid spec (all $ref errors listed) |
| `GET /services` | Registry overview incl. current versions | `200` | — |
| `GET /services/{slug}/specs` | Version list + states | `200` | `404` |
| `GET /services/{slug}/specs/{version}` | Stored spec + findings + audit link | `200` | `404` |
| `GET /diff/{slug}?from=V&to=V` | Dry-run diff (no state change) | `200` findings + policy + impacts | `404` |
| `GET /attempts/{id}` | State, findings, impacts, allowed actions | `200` | `404` |
| `POST /attempts/{id}/acknowledge` | Body `{comment?}`; only from `pending_ack` | `200` attempt=`published` | `400` wrong state |
| `POST /attempts/{id}/override` | Body `{justification}` **required non-blank**; only from `blocked` | `200` attempt=`overridden_published` | `400` wrong state · `422` missing justification |
| `POST /attempts/{id}/abandon` | From `pending_ack`/`blocked` | `200` `abandoned` | `400` wrong state |
| `POST /consumers` | Upload consumer YAML (replaces active) | `200` parsed bindings | `400`/`422` |
| `GET /consumers` / `GET /consumers/{name}` | List / bindings + current impact matrix | `200` | `404` |
| `GET /audit?service=&attempt=` | Audit history | `200` | — |
| `GET /notifications` | Webhook delivery log | `200` | — |
| `POST /admin/import-kit` | Import `specs/` + `consumers/` (read-only scan, filename order) | `200` per-file results | — |
| `GET /api/health` | Liveness + db + webhook config | `200` | — |

Identity: actor recorded from optional `X-Acting-User` header, default `"anonymous"`
(hackathon-level; see DECISIONS.md A-07).

## 6. Compatibility classification rules

*(Unchanged from R1 — product logic, not technology.)*

**Master principle (asymmetry):** the set of requests a server accepts may only *grow*, and
the set of responses it guarantees may only *shrink in promise* — any narrowing of the
accepted-request space or of the guaranteed-response space is breaking.

Canonicalization that makes cosmetic reorganization invisible (quote 1.0.0→1.1.0 must yield
**zero** breaking findings): inline-expand all local `$ref`s → compare shapes at wire
locations → drop component names (`Premium`→`PremiumDetails`, `BreakdownItem`→`PriceLine`,
`Vehicle`/`Rider` inlined) → drop key order → treat path-template placeholder **names** as
positions (`{id}`≡`{quoteId}`). Component renames/inline moves with identical shape → at most
an `info` note, never a finding. Cycle guard: depth cap (50) + recursive-ref marker.

### Request side (consumers send)

| Code | Condition | Severity | Impact match |
|---|---|---|---|
| `REQ.REQUIRED_ADDED` | property enters `required` (policy 2.1.0 `requestedBy`) | **breaking** | bound consumers **not** declaring `sends` of that path → AFFECTED |
| `REQ.PROPERTY_REMOVED` | property deleted from request schema | **breaking** (conservative: strict servers 400 senders) | bound consumers declaring `sends` of it |
| `REQ.ENUM_NARROWED` | enum value removed (`VEHICLE` dropped) | **breaking** | all bound consumers (files don't record sent values) → AFFECTED w/ caveat |
| `REQ.TYPE_NARROWED` | accepted type set shrinks (number→integer, string→enum, format added) | **breaking** | `sends` match |
| `REQ.CONSTRAINT_TIGHTENED` | min/max/minLength/pattern tightened (e.g. new `maximum` on existing field) | **breaking** | `sends` match |
| `REQ.DEFAULT_CHANGED` | default changed | info | — |
| `REQ.PROPERTY_ADDED` | new optional property (`ncbPercent`) | info | — |
| `REQ.REQUIRED_REMOVED` | property leaves `required` | info (constraint relaxed) | — |
| `REQ.TYPE_WIDENED` / `REQ.CONSTRAINT_LOOSENED` | accepted set grows | info | — |

### Parameters

| Code | Condition | Severity | Notes |
|---|---|---|---|
| `PARAM.REQUIRED_ADDED` | new required param or optional→required (quote 2.0.0 `region`) | **breaking** | path-param additions = path-shape change below; `x-internal` badge only, no auto-pass |
| `PARAM.REMOVED` | param deleted | **breaking** | — |
| `PARAM.REQUIRED_REMOVED` / `PARAM.ADDED_OPTIONAL` | — | info | — |
| `PATH_SHAPE.CHANGED` | template placeholder count/positions differ | **breaking** | affected = all bound consumers |
| `PARAM.RENAMED` | placeholder renamed, position+schema equal (`id`→`quoteId`) | info | cosmetic per canonicalization |

### Response side (consumers read)

| Code | Condition | Severity | Impact match |
|---|---|---|---|
| `RESP.PROPERTY_REMOVED` | property deleted (`legacyCode`, `phone`) | **breaking** | consumers whose `reads` hit that path (exact, prefix, or `[]`) |
| `RESP.REQUIRED_REMOVED` | property leaves response `required` (claims 2.0.0 `amountApproved`) | **breaking** | `reads` match — field no longer guaranteed |
| `RESP.PROPERTY_RENAMED` | removed+added same parent, same type (heuristic; `amount`→`value`) | **breaking** | readers of old name (mobile-app) |
| `RESP.ENUM_WIDENED` | new enum value (`EXPIRED`) | **breaking** (strict readers) | bound readers of field → POSSIBLE tier (no file declares exhaustive matching) |
| `RESP.TYPE_WIDENED` / `RESP.CONSTRAINT_LOOSENED` | possible value space grows | **breaking** (strict readers) | POSSIBLE tier |
| `RESP.NULLABLE_ADDED` | `nullable` added | **breaking** (strict readers) | POSSIBLE tier |
| `RESP.RESPONSE_REMOVED` | documented status+schema deleted | **breaking** | all bound |
| `RESP.MEDIA_TYPE_REMOVED` | response no longer documents `application/json` | **breaking** | all bound |
| `RESP.RESPONSE_ADDED` | new documented status (claims 2.0.0 `202`) | info + advisory note | — |
| `RESP.PROPERTY_ADDED` | new optional (`taxes`, `assessorNotes`) | info | — |
| `RESP.REQUIRED_ADDED` | guarantee strengthened | info | — |
| `RESP.TYPE_NARROWED` / `RESP.CONSTRAINT_TIGHTENED` / `RESP.ENUM_NARROWED` | guarantee strengthened | info | — |

### Structure / lifecycle

| Code | Condition | Severity |
|---|---|---|
| `OPERATION.REMOVED` / `PATH.REMOVED` | endpoint deleted | **breaking** (bound consumers AFFECTED) |
| `OPERATION.ADDED` | new endpoint | info |
| `REQUEST_BODY.MADE_REQUIRED` | body required added to existing op | **breaking** |
| `COMPONENT.MOVED_OR_RENAMED_SHAPE_EQUIVALENT` | reorg, zero wire diffs | info (trap detector) |
| `DEPRECATION.ADDED` | `deprecated: true` appears (policy 1.1.0) | warn (non-gating) + notify readers |
| `SUNSET_PASSED` | `x-sunset` ≤ today while field still present (`legacyCode` 2026-11-01) | warn (non-gating) |
| `SUNSET_SCHEDULED` | future `x-sunset` (phone 2027-06-30) | info |

### Version policy findings (separate channel — policy rule 5)

`VERSION.POLICY.BREAKING_WITHOUT_MAJOR` (policy 2.0.0→2.1.0: breaking under minor — the
planted violation) · `VERSION.POLICY.MAJOR_WITHOUT_BREAKING` (info) ·
`VERSION.UNPARSEABLE` (skip with note). Policy findings are displayed and audited but
**never gate** publication by themselves; gating is driven exclusively by compatibility
findings + impact results.

## 7. Consumer impact calculation

*(Unchanged from R1.)*

Binding normalization (`consumers`):
- Path shape: `/quotes/{id}` → segments `["quotes","{0}"]`; placeholder names ignored,
  positions kept. Spec `/quotes/{quoteId}` → identical shape. Method exact (uppercase).
- Field-path syntax unified: dots for nesting, `[]` after array property
  (`premium.breakdown[].amount`).

Matching algorithm per breaking finding `F` at location `(method, pathShape, scope, fieldPath)`:
1. Bindings where `provider_slug == F.service` **and** `method` equal **and** `path_shape`
   equal → candidate set. (No candidate → impact NONE; e.g. `region` param, `x-internal`.)
2. Scope/field mapping:
   - request findings ↔ consumer `sends`
   - response findings ↔ consumer `reads`
   - `REQ.REQUIRED_ADDED`: AFFECTED for candidates **not** declaring `sends[fieldPath]`
     (parent declaration counts as covering children).
   - removals/renames (`RESP.*`, `REQ.PROPERTY_REMOVED`): AFFECTED if consumer path equals
     fieldPath, or consumer path is a **prefix** of it (reads `premium` ⇒ affected by
     `premium.total` removal), or matches through `[]`.
   - `RESP.ENUM_WIDENED`/`TYPE_WIDENED`/`NULLABLE_ADDED`/`RESPONSE_ADDED`: POSSIBLE (advisory)
     — consumer files cannot express exhaustive matching.
   - `REQ.ENUM_NARROWED`: AFFECTED for all candidates with caveat "sent values not recorded
     in consumer file" (partner-portal sends `type`, values unknown).
3. Output per finding: `level ∈ {affected, possible, none}` + evidence list
   (consumer, binding path, matched read/send paths).

Gate mapping: any finding with ≥1 **affected** → attempt `blocked` (policy rule 3);
breaking findings with only possible/none → `pending_ack` (rule 2); none breaking →
`auto_published` (rule 1). Expected kit outcomes (from recon): quote 2.0.0 rename →
mobile-app affected → **blocked**; policy 2.0.0 `legacyCode` → payments-svc **blocked**,
`phone` → nobody; policy 2.1.0 → partner-portal **blocked** + version-policy violation;
claims 2.0.0 `amountApproved` → mobile-app **blocked**; quote 1.0.0→1.1.0 → auto-publish.

## 8. Publishing state machine & override workflow

*(Unchanged from R1.)*

```
 received ──invalid spec────────────▶ rejected (terminal, audited)      [rule 4]
 received ──duplicate differs/regres▶ rejected (terminal, audited)
 received ──no breaking findings───▶ auto_published ─▶ spec_versions row [rule 1]
 received ──breaking, none affected▶ pending_ack                        [rule 2]
 received ──breaking, ≥1 affected──▶ blocked                             [rule 3]

 pending_ack ──acknowledge{comment?}──────▶ published
 pending_ack ──abandon────────────────────▶ abandoned
 blocked     ──override{justification!}───▶ overridden_published
 blocked     ──abandon────────────────────▶ abandoned
```

- Guards: `acknowledge` valid only from `pending_ack`; `override` only from `blocked` and
  only with non-blank `justification` (Bean Validation `@NotBlank` + HTTP 422 otherwise);
  every transition writes `attempt_transitions` (actor, timestamp, comment/justification)
  before any state change.
- `overridden_published` stores: full findings snapshot, affected-consumer list, justification,
  actor → audit page shows "who broke what and why it was allowed".
- Version-policy violations never change the state machine; they decorate the attempt.
- Idempotent re-upload (same sha, same version) → `200` with existing attempt, no new audit
  noise.
- Webhooks on: `auto_published`, `published`, `overridden_published` (with justification +
  affected consumers), `blocked`, `DEPRECATION.ADDED`, `SUNSET_PASSED`. Payload:
  `{event, service, version, findings: [{code, severity, location, field_path}],
  affected_consumers, justification?, received_by}`. Target default
  `http://localhost:9900/contractguard` when running locally;
  `http://webhook-receiver:9000/contractguard` inside Compose (service name on the shared
  default network — set via `WEBHOOK_URL` env). Delivery is fire-and-forget (`RestClient`
  with 2 s timeouts, one retry); failure logged in `notifications`, never blocks publishing.

## 9. Error handling & validation strategy

- **Collect-all validation**: spec parse+validate gathers every error (all unresolved refs,
  missing `info.version`, …) before rejecting — one upload of claims v1.1.0 yields the full
  `ClaimDocumnt` diagnosis. Parser messages are merged with our own structural walk; the
  parser alone is not trusted to surface everything.
- Status-code map: `400` malformed upload / wrong-state action; `404` unknown resource;
  `409` duplicate-version-differs, version regression; `422` invalid spec (rule 4) and
  missing override justification; `500` unexpected, attempt → `failed`, transaction rolled
  back (no half-published state). All mapping centralized in one `@ControllerAdvice`.
- Bean Validation on request DTOs (`@NotBlank` justification etc.) fails at the boundary
  with 422, not deep in the state machine.
- Never persist a `spec_versions` row unless the attempt reaches a publish state — a
  rejected version must not poison later uploads of the corrected same version.
- Webhook failures are recorded, retried once, never fatal (`RestClient` timeouts caught and
  logged into `notifications`).
- Concurrency: H2 file DB + `@Transactional` per pipeline stage; duplicate simultaneous
  uploads of the same sha resolve to the same attempt (unique `(service_id, version)`).
- Character encoding: Maven build with `project.build.sourceEncoding=UTF-8` (local platform
  default is Cp1252); kit files are ASCII, but UTF-8 is enforced anyway.

## 10. Test plan (unit → integration → acceptance)

Unit (plain JUnit 5, no Spring context, pure packages):
- `specs`: Swagger Parser wrapper resolves chains; unresolved ref collected with
  JSON-pointer; cycle/depth guard; canonicalization makes `Premium≡PremiumDetails`,
  `Vehicle/Rider inline ≡ $ref`, `{id}≡{quoteId}`; `x-sunset` date parse (past/future/missing).
- `compatibility`: synthetic minimal pairs per rule code (required add/remove, property
  remove, enum add/remove, type widen/narrow, constraint tighten, param required add,
  response-required removal, operation add/remove, rename detection); kit-pair smoke: quote
  1→2 yields zero breaking + info note; quote 2→3 yields `RESP.PROPERTY_RENAMED` + `PARAM.REQUIRED_ADDED`.
- `consumers`: shape matching `{id}≡{quoteId}`; `/policies/{policyId}` ≢
  `/policies/{policyId}/endorsements`; field-path prefix & `[]` matching; Jackson-YAML
  reading of all four kit consumer files.
- `impact`: each tier with evidence; prefix rules; required-add exclusion rule.
- `policy`: decision table for all 5 rules; semver bump classifier; unparseable version.

Integration (`@SpringBootTest` + `MockMvc`/`TestRestTemplate`, **in-memory H2**):
- quote chain 1.0.0→1.1.0: auto-publish, zero findings beyond info.
- quote 1.1.0→2.0.0: `blocked`; mobile-app listed affected; override w/o justification → 422;
  override with justification → published; audit rows exist; webhook delivered (receiver
  stubbed only in fast tests; real receiver hit in acceptance).
- policy 1.0.0→1.1.0: deprecation warn; payments-svc notified (notification row).
- policy 1.1.0→2.0.0: `legacyCode` → payments-svc blocked; `phone` → no consumers.
- policy 2.0.0→2.1.0: blocked (partner-portal) **and** `VERSION.POLICY.BREAKING_WITHOUT_MAJOR`.
- claims 1.0.0→1.1.0 (file 2): 422 rejected, `ClaimDocumnt` named, no spec row.
- claims file 3 after file 2 rejected: publishes as 1.1.0; re-upload file 3 → idempotent 200.
- claims 1.1.0→2.0.0: `RESP.REQUIRED_REMOVED` → mobile-app blocked.
- consumer re-upload changing reads flips subsequent impact results.
- error mapping: unknown attempt → 404; acknowledge from wrong state → 400.

Acceptance (scripted against the running stack — PowerShell `Invoke-RestMethod` or a JUnit
acceptance test hitting `TestRestTemplate` with real ports; expected matrix from
reconnaissance): `POST /admin/import-kit` then assert per file: 12 specs → quote {auto, auto,
blocked, published-after-override…}, policy {auto, warn-only, blocked(payments),
blocked(partner)+version violation}, claims {rejected($ref), published, dup-idempotent,
blocked(mobile)}. Final state: current versions = quote 2.1.0, policy 2.1.0, claims 2.0.0;
audit timeline complete; the untouched webhook receiver's page visibly shows
publish/override/deprecation notifications; UI smoke (upload → findings → override dialog
enforces justification).

## 11. Five-hour implementation plan (E2E demo inside first 2 hours)

| Time | Milestone | Exit criterion |
|---|---|---|
| 0:00–0:25 | **Environment fix + scaffold**: set `JAVA_HOME`→Temurin 21; `pom.xml` (Boot 4.1.2, starters web/jdbc/validation/test, swagger-parser 2.1.48, H2, Jackson-YAML); start `mvn -q dependency:go-offline` **in background**; skeleton `Application`, `schema.sql`, `/api/health`, static placeholder; `docker-compose.app.yml` written | `mvn spring-boot:run` boots; health OK; dependency download running while coding |
| 0:25–1:05 | `specs` package: Swagger Parser wrapper, collect-all validation incl. `$ref` walk, canonicalization, effective-schema map, deprecation scan; JUnit tests with kit pairs | claims v1.1.0 rejected with `ClaimDocumnt`; quote 1≡2 canonicalized equal |
| 1:05–1:45 | `compatibility` package: diff engine + core rules (§6) + rename heuristic + `policy` version module; JUnit tests | quote 2→3 diff correct; policy 2.0.0→2.1.0 flags violation |
| 1:45–2:00 | **E2E splice**: upload endpoint + auto/ack/block gating (consumers file mocked absent → no-impact path), H2 persistence, minimal UI upload+findings | **DEMO CHECKPOINT**: import quote v1→v1.1 auto-publish; v2.0.0 shows breaking findings + warn; claims v1.1.0 rejected |
| 2:00–2:40 | `consumers` + `impact` + policy gate wiring; ack/override/abandon endpoints; audit transitions; `notify` (RestClient) | mobile-app breaks quote v2.0.0 → blocked → override with justification works end-to-end |
| 2:40–3:10 | deprecation/x-sunset scan; duplicate-version + regression rules; dry-run `/diff`; `import-kit` admin endpoint | acceptance matrix for policy + claims passes |
| 3:10–3:50 | UI: findings table with affected consumers + evidence, override dialog (mandatory justification), audit timeline, consumers/impact page, registry page | full manual walkthrough clean |
| 3:50–4:30 | full JUnit suite green (unit + `@SpringBootTest` integration), acceptance script vs kit matrix, bug bash | matrix green |
| 4:30–5:00 | multi-stage `Dockerfile` + compose override verified (`docker compose -f docker-compose.yml -f docker-compose.app.yml up --build`), run docs, final rehearsal, buffer | demo rehearsed (fallback: local `mvn spring-boot:run` + original compose for receiver) |

**Cut list if behind** (in order): UI polish → rename heuristic (report removal+addition) →
springdoc → dry-run endpoint → webhook non-blocking notifications → component-rename info
notes. Never cut: validation incl. `$ref`, the three gate states, consumer impact, audit.

**Demo script (checkpoint + final):** start stack (local: receiver via original compose +
`mvn spring-boot:run`; or everything via both compose files) → import kit → Registry shows
quote 1.0.0 published → upload `2-reorganised.yaml` → auto-publish with "cosmetic reorg,
0 breaking" → upload `3-price-lines.yaml` → BLOCKED: `premium.breakdown[].amount renamed to
value`, mobile-app affected (+ required `region` on internal op, `EXPIRED` enum advisory) →
override with justification → published, audit + webhook receiver show the record → upload
claims file 2 → 422 unresolved `ClaimDocumnt` → upload file 3 → published → upload policy
file 4 → BLOCKED (partner-portal, `requestedBy`/`VEHICLE`) + red semver-policy banner
"breaking change shipped as minor".

## 12. Known assumptions

Recorded in full in `DECISIONS.md`; headline items: service slug from `info.title`
("Quote API"→`quote-api`, matches kit folders); duplicate version = reject (no revision
chain) but identical re-upload idempotent; consumers are point-in-time snapshots replaced on
re-upload; `x-internal` is a badge, not an exemption; override authority = any API client
plus mandatory justification (no auth system); webhook target configurable, default kit
receiver (localhost:9900 local / `webhook-receiver:9000` in Compose); "today" for `x-sunset`
comparisons is server date (legacyCode sunset 2026-11-01 already past at time of writing);
JAVA_HOME may be overridden per-session to Temurin 21 for Maven (A-15).
