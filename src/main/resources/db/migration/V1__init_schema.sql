-- =============================================================================
-- V1 — MailFlow initial schema
-- =============================================================================
-- Conventions:
--   • All PKs are BIGSERIAL (auto-increment).
--   • Timestamps stored as TIMESTAMPTZ (UTC).
--   • Soft-delete patterns use deleted_at rather than a boolean flag.
--   • JSON columns used for extensible custom fields only.
-- =============================================================================

-- ─── Extensions ──────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =============================================================================
-- USERS & ROLES
-- =============================================================================

CREATE TABLE roles (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(50)     NOT NULL UNIQUE,
    description VARCHAR(255)
);

CREATE TABLE users (
    id              BIGSERIAL       PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    first_name      VARCHAR(100)    NOT NULL,
    last_name       VARCHAR(100)    NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0   -- optimistic locking
);

CREATE TABLE user_roles (
    user_id     BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id     BIGINT  NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- =============================================================================
-- RECIPIENTS
-- =============================================================================

CREATE TABLE recipient_groups (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(255)    NOT NULL,
    description TEXT,
    created_by  BIGINT          REFERENCES users(id),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE recipients (
    id              BIGSERIAL       PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    name            VARCHAR(255),
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),
    company         VARCHAR(255),
    course          VARCHAR(255),
    custom_fields   JSONB           NOT NULL DEFAULT '{}',
    status          VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | UNSUBSCRIBED | BOUNCED | INVALID
    import_source   VARCHAR(100),                               -- 'CSV_UPLOAD' | 'API' | 'MANUAL'
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0
);

CREATE TABLE recipient_group_members (
    recipient_id    BIGINT  NOT NULL REFERENCES recipients(id) ON DELETE CASCADE,
    group_id        BIGINT  NOT NULL REFERENCES recipient_groups(id) ON DELETE CASCADE,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (recipient_id, group_id)
);

-- =============================================================================
-- UNSUBSCRIBE
-- =============================================================================

CREATE TABLE unsubscribe_list (
    id              BIGSERIAL       PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    token           VARCHAR(64)     NOT NULL UNIQUE,
    reason          TEXT,
    unsubscribed_at TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    campaign_id     BIGINT          -- FK added after campaigns table
);

-- =============================================================================
-- EMAIL TEMPLATES
-- =============================================================================

CREATE TABLE email_templates (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    subject         TEXT            NOT NULL,
    html_body       TEXT,
    plain_body      TEXT,
    variables       JSONB           NOT NULL DEFAULT '[]',  -- ["name","email","course"]
    created_by      BIGINT          REFERENCES users(id),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0
);

-- =============================================================================
-- ATTACHMENTS
-- =============================================================================

CREATE TABLE attachments (
    id              BIGSERIAL       PRIMARY KEY,
    original_name   VARCHAR(500)    NOT NULL,
    stored_name     VARCHAR(500)    NOT NULL UNIQUE,
    content_type    VARCHAR(255)    NOT NULL,
    size_bytes      BIGINT          NOT NULL,
    storage_path    TEXT            NOT NULL,
    uploaded_by     BIGINT          REFERENCES users(id),
    uploaded_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- CAMPAIGNS
-- =============================================================================

CREATE TABLE campaigns (
    id                  BIGSERIAL       PRIMARY KEY,
    name                VARCHAR(255)    NOT NULL,
    description         TEXT,
    subject             TEXT            NOT NULL,
    template_id         BIGINT          REFERENCES email_templates(id),
    sender_name         VARCHAR(255),
    sender_email        VARCHAR(255),
    reply_to            VARCHAR(255),
    status              VARCHAR(50)     NOT NULL DEFAULT 'DRAFT',
    -- DRAFT | SCHEDULED | QUEUED | RUNNING | PAUSED | COMPLETED | FAILED | CANCELLED
    scheduled_at        TIMESTAMPTZ,
    created_by          BIGINT          REFERENCES users(id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    total_recipients    BIGINT          NOT NULL DEFAULT 0,
    sent_count          BIGINT          NOT NULL DEFAULT 0,
    failed_count        BIGINT          NOT NULL DEFAULT 0,
    skipped_count       BIGINT          NOT NULL DEFAULT 0,
    bounced_count       BIGINT          NOT NULL DEFAULT 0,
    retry_count         BIGINT          NOT NULL DEFAULT 0,
    batch_job_id        VARCHAR(255),   -- Spring Batch job execution ID reference
    version             BIGINT          NOT NULL DEFAULT 0
);

-- =============================================================================
-- CAMPAIGN RECIPIENTS (join table with per-recipient delivery state)
-- =============================================================================

CREATE TABLE campaign_recipients (
    id                  BIGSERIAL       PRIMARY KEY,
    campaign_id         BIGINT          NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    recipient_id        BIGINT          NOT NULL REFERENCES recipients(id),
    delivery_status     VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    -- PENDING | QUEUED | SENT | FAILED | SKIPPED | BOUNCED
    idempotency_key     VARCHAR(100)    NOT NULL UNIQUE,
    -- Prevents duplicate sends: format = campaign_{id}_recipient_{id}
    queued_at           TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ,
    retry_count         INT             NOT NULL DEFAULT 0,
    last_error          TEXT,
    personalized_subject TEXT,
    UNIQUE (campaign_id, recipient_id)
);

-- =============================================================================
-- EMAIL LOGS (detailed per-message audit trail)
-- =============================================================================

CREATE TABLE email_logs (
    id                  BIGSERIAL       PRIMARY KEY,
    campaign_id         BIGINT          NOT NULL REFERENCES campaigns(id),
    recipient_id        BIGINT          REFERENCES recipients(id),
    campaign_recipient_id BIGINT        REFERENCES campaign_recipients(id),
    to_address          VARCHAR(255)    NOT NULL,
    subject             TEXT,
    status              VARCHAR(50)     NOT NULL,
    -- SENT | FAILED | RETRYING | SKIPPED
    provider_message_id VARCHAR(500),   -- ID returned by SMTP/provider
    error_message       TEXT,
    error_type          VARCHAR(100),   -- TEMPORARY | PERMANENT
    attempt_number      INT             NOT NULL DEFAULT 1,
    processed_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- EMAIL EVENTS (open/click tracking)
-- =============================================================================

CREATE TABLE email_events (
    id              BIGSERIAL       PRIMARY KEY,
    email_log_id    BIGINT          REFERENCES email_logs(id),
    campaign_id     BIGINT          REFERENCES campaigns(id),
    recipient_id    BIGINT          REFERENCES recipients(id),
    event_type      VARCHAR(50)     NOT NULL,  -- OPENED | CLICKED | BOUNCED | COMPLAINED
    event_data      JSONB,
    user_agent      TEXT,
    ip_address      VARCHAR(45),
    occurred_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- SCHEDULED CAMPAIGNS
-- =============================================================================

CREATE TABLE scheduled_campaigns (
    id              BIGSERIAL       PRIMARY KEY,
    campaign_id     BIGINT          NOT NULL UNIQUE REFERENCES campaigns(id),
    scheduled_at    TIMESTAMPTZ     NOT NULL,
    timezone        VARCHAR(100)    NOT NULL DEFAULT 'UTC',
    quartz_job_key  VARCHAR(255),
    status          VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    -- PENDING | TRIGGERED | CANCELLED
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    triggered_at    TIMESTAMPTZ
);

-- =============================================================================
-- DELAYED: add FK from unsubscribe_list to campaigns
-- =============================================================================
ALTER TABLE unsubscribe_list
    ADD CONSTRAINT fk_unsubscribe_campaign
    FOREIGN KEY (campaign_id) REFERENCES campaigns(id);

-- =============================================================================
-- INDEXES
-- =============================================================================

-- Users
CREATE INDEX idx_users_email         ON users(email);
CREATE INDEX idx_users_enabled       ON users(enabled) WHERE enabled = TRUE;

-- Recipients
CREATE INDEX idx_recipients_email    ON recipients(email);
CREATE INDEX idx_recipients_status   ON recipients(status);
CREATE INDEX idx_recipients_deleted  ON recipients(deleted_at) WHERE deleted_at IS NULL;

-- Campaigns
CREATE INDEX idx_campaigns_status       ON campaigns(status);
CREATE INDEX idx_campaigns_created_by   ON campaigns(created_by);
CREATE INDEX idx_campaigns_scheduled_at ON campaigns(scheduled_at) WHERE scheduled_at IS NOT NULL;

-- Campaign recipients
CREATE INDEX idx_cr_campaign    ON campaign_recipients(campaign_id);
CREATE INDEX idx_cr_recipient   ON campaign_recipients(recipient_id);
CREATE INDEX idx_cr_status      ON campaign_recipients(delivery_status);
CREATE INDEX idx_cr_idem_key    ON campaign_recipients(idempotency_key);

-- Email logs
CREATE INDEX idx_email_logs_campaign    ON email_logs(campaign_id);
CREATE INDEX idx_email_logs_recipient   ON email_logs(recipient_id);
CREATE INDEX idx_email_logs_status      ON email_logs(status);
CREATE INDEX idx_email_logs_processed   ON email_logs(processed_at);

-- Email events
CREATE INDEX idx_email_events_campaign  ON email_events(campaign_id);
CREATE INDEX idx_email_events_type      ON email_events(event_type);

-- Unsubscribe
CREATE INDEX idx_unsub_email    ON unsubscribe_list(email);
CREATE INDEX idx_unsub_token    ON unsubscribe_list(token);

-- Templates
CREATE INDEX idx_templates_deleted ON email_templates(deleted_at) WHERE deleted_at IS NULL;
