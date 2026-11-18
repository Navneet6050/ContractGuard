# Contract Guard

**API contract registry & breaking-change detection.** Provider teams publish OpenAPI
specs through a publishing gate; every registered consumer declaration is matched
against the incoming changes, so consumer-impacting breaking changes are **blocked**
before they ship — with an auditable override path, a per-attempt migration guide,
webhook notifications and a full audit trail.

Built on the untouched starter kit (`specs/`, `consumers/`, `webhook-receiver/`,
`docker-compose.yml` are evaluator fixtures and are never modified).

---

## Quick start

### Docker (one command)

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml up --build
```

- **Contract Guard UI:** http://localhost:8080 (host port override: `PORT_APP`)
- **Webhook receiver inbox:** http://localhost:9900 (override: `WEBHOOK_PORT`)

The registry database persists across container restarts in the named volume
`contractguard-data`. For a **fresh demo database**:

```bash
docker compose -f docker-compose.yml -f docker-compose.app.yml down -v
```

### Local (no Docker)

Two processes (Python 3.12+, JDK 21, Maven 3.9):

```bash
# 1. webhook receiver (never modified; stand-in for chat/email)
python webhook-receiver/app.py                    # listens on :9000

# 2. Contract Guard (fresh DB: delete the ./data folder first, if present)
mvn spring-boot:run                               # UI on http://localhost:8000
```

Defaults assume the receiver is reachable at `http://localhost:9900` — start it with
`PORT=9900 python webhook-receiver/app.py`, or point the app at :9000 with
`WEBHOOK_URL=http://localhost:9000/contractguard`.

On startup the app **imports the kit once** (`KitSeeder`, idempotent — a
`KIT_IMPORT` audit marker per service records it): the four consumer files become
registered consumers and the twelve spec files are pushed through the *real*
publishing pipeline in filename order, so every planned trap (invalid `$ref`,
breaking renames, semver violations, past sunset dates) lands exactly as designed.
Webhooks fire during import; outcomes are never propagated.

---

## The publishing gate

Every upload (`POST /api/services/{slug}/specs`) runs the full pipeline and lands in
exactly one of four outcomes:

| Situation | Outcome | HTTP |
| --- | --- | --- |
| No breaking change | `auto_published` — version stored, webhook sent | 200 |
| Breaking, but **0 AFFECTED** consumers | `pending_ack` — waits for the team's acknowledgement | 200 |
| Breaking with **≥1 AFFECTED** consumer | `blocked` — publish requires an override | 200 |
| Malformed / invalid spec (`errors[]` + `attemptId`) | rejected, nothing stored | **422** |

Other rules: identical re-upload is an idempotent no-op (200 `duplicate-identical`);
same version with different content → **409**; version regression → **409**;
unknown slug/service → **404**; missing/blank fields or a wrong-state decision
action → **400**; overriding with a blank justification → **422**. Acknowledge,
override and abandon are the only transitions out of `pending_ack`/`blocked`; every
transition is transactional with its audit event.

Breaking-change detection covers: response property/operation/media-type removals,
renames, required-field removals, enum/type widenings, request required-additions,
removals and narrowing, parameter required-additions/removals, path-shape changes,
plus lifecycle findings (`x-internal` deprecations, `x-sunset` schedule/passed).
Findings carry full location envelopes (`"GET /quotes/{0} :: response:200:application/json"`)
and old/new JSON evidence.

### Consumer declarations & the version question

Consumer files are point-in-time snapshots **without version pinning** (the kit
format has no version field — see `consumers/*.yaml`). Contract Guard keeps one
*active* definition per consumer (re-upload replaces it, history is kept) and
answers "which older versions are consumers still using?" with an explicitly
defined default — the **cumulative consumer-drift scan**: every declared
read/send/operation binding is verified against the *incoming* spec, so usage
removed by an **already-published** version (not just the current→candidate hop) is
still reported on every later upload as a non-gating `CONSUMER.DRIFT` /
`CONSUMER.STALE_BINDING` warning naming the consumers. Drift never blocks a
publication (it may be accepted history); it appears in findings, impacts
(POSSIBLE), migration guides and metrics. No version pinning was invented.

### Migration guides

`GET /api/attempts/{id}/migration-guide` generates the migration steps for one
attempt **strictly from its stored findings and impacts**: location, field, code
→ concrete action (what readers/senders must change), and which consumers are
named in the stored impact analysis. Unknown finding codes degrade to a review
step quoting the finding's own detail — nothing is fabricated. The changelog
(`GET /api/services/{slug}/changelog`) embeds the same guide per version entry.

### Metrics

`GET /api/metrics` aggregates the whole workflow from real database rows:
services, published/superseded versions, publication attempts by state,
findings by severity, breaking-findings total, AFFECTED consumer impacts (by
consumer), blocked/overridden/rejected publications, webhook delivery statuses,
audit-event count. The UI has a **Metrics** panel.

---

## Demo journey (≈10 minutes)

1. Open http://localhost:8000 (local) or http://localhost:8080 (Docker). Health and
   registry already show the imported kit.
2. **Dry-run check:** paste `specs/policy-api/4-endorsement-rules.yaml` into
   *Upload & publish*, press **Dry-run check** — decision + findings render, nothing stored.
3. **Invalid spec:** paste `specs/claims-api/2-channel-and-notes.yaml` → 422 with the
   broken `$ref` listed; nothing stored.
4. **Breaking with consumers:** paste `specs/quote-api/3-price-lines.yaml`
   (`amount`→`value` rename) and press **Upload & run publishing gate** → `blocked`,
   mobile-app AFFECTED (attempt id noted in the result). Press **Migration guide for
   this attempt** — grounded steps for the rename.
5. **Override:** load that attempt id, enter a justification (blank → 422), press
   **Override** → `overridden_published`, webhook delivered to the receiver inbox.
6. **Ack flow:** paste a minor bump of the *current* claims spec that only removes an
   optional field nobody reads (e.g. take the published `1.1.0` claims spec, drop
   `assessorNotes`, set `version: 1.2.0`) → `pending_ack` (breaking, 0 affected) →
   **Acknowledge** with a comment → `published`.
7. **Drift:** upload `specs/quote-api/4-ncb-and-taxes.yaml` → `auto_published`, but a
   `CONSUMER.DRIFT` warning now flags mobile-app's stale
   `premium.breakdown[].amount` declaration (removed in an earlier hop).
8. Inspect **Compare** (any two registered versions), **History/Changelog/Audit**,
   **Webhook notifications**, **Metrics**, and the receiver inbox at :9900.
9. CI-style gate without the UI:
    `scripts/ci-check.sh --service quote-api --spec specs/quote-api/3-price-lines.yaml`
    → exit 1, BLOCKED with the affected consumer printed.

Other kit uploads behave exactly as gated: `claims 2.0.0` → blocked (mobile-app reads
`amountApproved`), `policy 2.0.0` → blocked (payments-svc reads `legacyCode`),
`policy 2.1.0` → blocked (partner-portal does not send the now-required `requestedBy`).

## HTTP API

| Method & path | Purpose |
| --- | --- |
| `GET /api/health` | liveness + import status |
| `GET /api/services` · `POST /api/services` | registry list / register a service |
| `GET /api/services/{slug}` | service detail (current version, counts) |
| `POST /api/services/{slug}/specs` | upload candidate → gate (auto-publish / pending_ack / blocked / 422) |
| `POST /api/services/{slug}/check` | **dry-run** gate — same decision, stores nothing |
| `GET /api/services/{slug}/compare?from&to` | dry-run diff of two registered versions |
| `GET /api/services/{slug}/versions` · `/history` · `/changelog` | version list / history / changelog (with migration steps) |
| `GET /api/services/{slug}/attempts` · `GET /api/attempts/{id}` | attempt list / detail |
| `POST /api/attempts/{id}/acknowledge` \| `/override` \| `/abandon` | decision transitions (state-checked) |
| `GET /api/attempts/{id}/migration-guide` | migration steps from stored findings |
| `POST /api/impact` | standalone consumer-impact analysis of any old/new spec pair |
| `GET /api/audit` (`?service&attemptId`) · `GET /api/notifications` | audit trail / webhook log |
| `GET /api/metrics` | workflow metrics |

All mutating endpoints attribute the actor from the optional `X-Acting-User`
header (default `anonymous`) — **attribution only, not RBAC** (documented scope
decision).

---

## CI gate

`scripts/ci-check.sh` runs the real gate as a dry-run from any pipeline:

```bash
scripts/ci-check.sh --base-url http://localhost:8080 --service quote-api \
  --spec specs/quote-api/3-price-lines.yaml [--strict] [--actor ci-bot]
```

Exit-code contract:

| Code | Meaning |
| --- | --- |
| `0` | `PUBLISH` (no consumer-impacting breaking change) — or `PENDING_ACK` without `--strict` (breaking, zero AFFECTED consumers; the real publish still requires a human acknowledgement) |
| `1` | `BLOCKED` — breaking change with ≥1 AFFECTED consumer (with `--strict`, also `PENDING_ACK`) |
| `2` | HTTP 422 — the candidate spec itself is invalid |
| `3` | operational failure (server unreachable, non-JSON, unexpected status) |
| `4` | usage error (missing args, unreadable file) |

## Deployment

- `Dockerfile` — multi-stage build (`maven:3.9-eclipse-temurin-21` →
  `eclipse-temurin:21-jre`), jar-only runtime image, kit fixtures baked in.
- `docker-compose.app.yml` — override that adds the app next to the untouched
  starter-kit receiver and wires `WEBHOOK_URL=http://webhook-receiver:9000/contractguard`.
- Persistence: H2 file DB under `/app/data` (named volume). All configuration is
  environment-variable driven (below); no hardcoded paths, ports or secrets.

### Configuration

| Env var | Default | Meaning |
| --- | --- | --- |
| `PORT_APP` | `8000` local / `8080` in image | HTTP port of Contract Guard |
| `WEBHOOK_URL` | `http://localhost:9900/contractguard` | publication webhook target |
| `DB_URL` | `jdbc:h2:file:./data/contractguard` | H2 file DB (mount a volume in Docker) |
| `SPECS_DIR` | `specs` | read-only kit specs for startup import |
| `CONSUMERS_DIR` | `consumers` | read-only kit consumer declarations |
| `KIT_IMPORT` | `true` | run the idempotent kit import at startup |

---

## Kit fixtures (read-only reference)

The untouched starter kit provides everything the demo uses:

- **`specs/`** — three provider services (`quote-api`, `policy-api`, `claims-api`),
  four ordered versions each (`info.version` is authoritative; `$ref` is local-only;
  `x-internal` / `x-sunset` extensions carry lifecycle rules; several versions
  contain planted problems Contract Guard is built to catch).
- **`consumers/`** — four consumer declarations (`mobile-app`, `partner-portal`,
  `payments-svc`, `notifications-svc`) in the format shown above: which endpoints
  each service calls, what it `sends`, what it `reads` (dots nest, `[]` = list items).
- **`webhook-receiver/`** — stdlib Python stand-in for chat/email; POSTed webhooks
  appear on its page, `GET /api/received` lists them as JSON.
- **Rules:** never modify `specs/`, `consumers/`, `webhook-receiver/`, or the
  original `docker-compose.yml` — evaluators test against the originals.

## Development

```bash
mvn test                 # full suite (in-memory H2; kit import off; ./data untouched)
mvn spring-boot:run      # run locally against ./data + ./specs + ./consumers
```

Module layout (`src/main/java/com/contractguard/`): `specs/` (parsing, effective
schemas, canonicalization), `compatibility/` (diff engine, findings, location keys),
`consumers/` (declaration parsing), `impact/` (consumer matching, drift scanner),
`pipeline/` (publishing gate, state machine, kit seeder, migration guide),
`persistence/` (H2 store), `webhook/` (delivery), `api/` (HTTP surface),
`domain/` (decision policy). The UI is a single static page
(`src/main/resources/static/index.html`) — no build chain.

### Documented limitations

- Consumer declarations carry no version pinning (kit format); drift is reported
  cumulatively as advisory warnings instead (see above).
- Consumer re-upload replaces the active definition (planned A-08 upload endpoint
  was cut for scope; kit consumers are imported at startup).
- The receiver keeps notifications in memory (its own fixture behavior); the
  `notifications` table is the durable log.
- H2 file DB is single-process — don't run two app instances against one `./data`.
