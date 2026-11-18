-- Contract Guard registry schema (H2). CREATE TABLE IF NOT EXISTS so restarts are safe.
-- Design: PLAN.md §4. spec_versions rows exist only for versions that became current;
-- rejected/blocked attempts live in publication_attempts + audit.

CREATE TABLE IF NOT EXISTS services (
    id          IDENTITY PRIMARY KEY,
    slug        VARCHAR(255) NOT NULL UNIQUE,
    title       VARCHAR(255) NOT NULL,
    owner_team  VARCHAR(255),
    description VARCHAR(1024),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS spec_versions (
    id               IDENTITY PRIMARY KEY,
    service_id       BIGINT NOT NULL REFERENCES services(id),
    version          VARCHAR(64) NOT NULL,
    source_sha       VARCHAR(64) NOT NULL,
    source_filename  VARCHAR(255),
    raw_yaml         CLOB NOT NULL,
    canonical_json   CLOB NOT NULL,
    effective_json   CLOB NOT NULL,
    state            VARCHAR(32) NOT NULL CHECK (state IN ('published', 'superseded')),
    published_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by     VARCHAR(255) NOT NULL DEFAULT 'anonymous',
    CONSTRAINT uq_spec_version UNIQUE (service_id, version)
);

CREATE TABLE IF NOT EXISTS publication_attempts (
    id                IDENTITY PRIMARY KEY,
    service_id        BIGINT NOT NULL REFERENCES services(id),
    incoming_version  VARCHAR(64) NOT NULL,
    source_sha        VARCHAR(64) NOT NULL,
    raw_yaml          CLOB NOT NULL,
    canonical_json    CLOB,
    state             VARCHAR(32) NOT NULL CHECK (state IN (
                        'received', 'rejected', 'auto_published', 'pending_ack',
                        'blocked', 'published', 'overridden_published',
                        'abandoned', 'failed')),
    breaking_count    INT NOT NULL DEFAULT 0,
    required_action   VARCHAR(255),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at        TIMESTAMP,
    decided_by        VARCHAR(255),
    justification     CLOB,
    note              CLOB,
    decision_json     CLOB
);

CREATE TABLE IF NOT EXISTS findings (
    id          IDENTITY PRIMARY KEY,
    attempt_id  BIGINT NOT NULL REFERENCES publication_attempts(id),
    code        VARCHAR(64) NOT NULL,
    severity    VARCHAR(16) NOT NULL CHECK (severity IN ('breaking', 'warn', 'info')),
    scope       VARCHAR(64),
    method      VARCHAR(16),
    path        VARCHAR(512),
    location    VARCHAR(255),
    field_path  VARCHAR(512),
    detail      CLOB,
    old_json    CLOB,
    new_json    CLOB
);

CREATE TABLE IF NOT EXISTS finding_impacts (
    id             IDENTITY PRIMARY KEY,
    finding_id     BIGINT NOT NULL REFERENCES findings(id),
    consumer       VARCHAR(255) NOT NULL,
    level          VARCHAR(16) NOT NULL CHECK (level IN ('affected', 'possible', 'none')),
    evidence_json  CLOB
);

CREATE TABLE IF NOT EXISTS consumer_definitions (
    id          IDENTITY PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    team        VARCHAR(255),
    raw_yaml    CLOB NOT NULL,
    source_sha  VARCHAR(64) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replaced_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS consumer_bindings (
    id           IDENTITY PRIMARY KEY,
    consumer_id  BIGINT NOT NULL REFERENCES consumer_definitions(id),
    provider_slug VARCHAR(255) NOT NULL,
    method       VARCHAR(16) NOT NULL,
    path_raw     VARCHAR(512) NOT NULL,
    path_shape   VARCHAR(512) NOT NULL,
    sends_json   CLOB NOT NULL,
    reads_json   CLOB NOT NULL
);

-- Append-only audit trail; rows are never updated or deleted.
CREATE TABLE IF NOT EXISTS attempt_transitions (
    id          IDENTITY PRIMARY KEY,
    attempt_id  BIGINT NOT NULL REFERENCES publication_attempts(id),
    from_state  VARCHAR(32),
    to_state    VARCHAR(32) NOT NULL,
    actor       VARCHAR(255) NOT NULL DEFAULT 'anonymous',
    comment     CLOB,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS notifications (
    id            IDENTITY PRIMARY KEY,
    kind          VARCHAR(64) NOT NULL,
    service       VARCHAR(255),
    version       VARCHAR(64),
    payload_json  CLOB NOT NULL,
    target_url    VARCHAR(512) NOT NULL,
    status        VARCHAR(32) NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at  TIMESTAMP
);

-- Append-only audit trail for SERVICE-level and attempt-level actions (registration,
-- uploads, dry-run checks, rejections, decisions). attempt_transitions stays the
-- attempt state log; audit_events is the human-readable who/what/why record.
CREATE TABLE IF NOT EXISTS audit_events (
    id            IDENTITY PRIMARY KEY,
    service       VARCHAR(255),
    version       VARCHAR(64),
    attempt_id    BIGINT,
    action        VARCHAR(64) NOT NULL,
    decision      VARCHAR(32),
    actor         VARCHAR(255) NOT NULL DEFAULT 'anonymous',
    justification CLOB,
    details       CLOB,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Columns added after the kit shipped; IF NOT EXISTS keeps older local databases
-- (data/contractguard.mv.db) upgradeable in place.
ALTER TABLE services ADD COLUMN IF NOT EXISTS description VARCHAR(1024);
ALTER TABLE spec_versions ADD COLUMN IF NOT EXISTS published_by VARCHAR(255) NOT NULL DEFAULT 'anonymous';
ALTER TABLE publication_attempts ADD COLUMN IF NOT EXISTS decision_json CLOB;
ALTER TABLE publication_attempts ALTER COLUMN required_action VARCHAR(255);
