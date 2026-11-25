-- =============================================================================
-- V8 — Organizations (company workspaces)
-- =============================================================================
-- Every user belongs to one organization, and every recipient, group, template,
-- campaign, attachment and unsubscribe entry is owned by one. Existing rows are
-- moved into a "Default" organization, so an existing install keeps working.
--
-- Email addresses become unique per organization: two companies may each have
-- the same contact, and an opt-out applies to the company it was made for.
-- =============================================================================

CREATE TABLE organizations (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(255)    NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

INSERT INTO organizations (name) VALUES ('Default');

-- ── Users ───────────────────────────────────────────────────────────────────
ALTER TABLE users ADD COLUMN organization_id BIGINT REFERENCES organizations(id);
UPDATE users SET organization_id = (SELECT MIN(id) FROM organizations);
ALTER TABLE users ALTER COLUMN organization_id SET NOT NULL;
CREATE INDEX idx_users_org ON users(organization_id);

-- ── Company-owned data ──────────────────────────────────────────────────────
ALTER TABLE recipients        ADD COLUMN organization_id BIGINT REFERENCES organizations(id);
ALTER TABLE recipient_groups  ADD COLUMN organization_id BIGINT REFERENCES organizations(id);
ALTER TABLE email_templates   ADD COLUMN organization_id BIGINT REFERENCES organizations(id);
ALTER TABLE attachments       ADD COLUMN organization_id BIGINT REFERENCES organizations(id);
ALTER TABLE campaigns         ADD COLUMN organization_id BIGINT REFERENCES organizations(id);
ALTER TABLE unsubscribe_list  ADD COLUMN organization_id BIGINT REFERENCES organizations(id);

UPDATE recipients       SET organization_id = (SELECT MIN(id) FROM organizations);
UPDATE recipient_groups SET organization_id = (SELECT MIN(id) FROM organizations);
UPDATE email_templates  SET organization_id = (SELECT MIN(id) FROM organizations);
UPDATE attachments      SET organization_id = (SELECT MIN(id) FROM organizations);
UPDATE campaigns        SET organization_id = (SELECT MIN(id) FROM organizations);
UPDATE unsubscribe_list SET organization_id = (SELECT MIN(id) FROM organizations);

ALTER TABLE recipients       ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE recipient_groups ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE email_templates  ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE attachments      ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE campaigns        ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE unsubscribe_list ALTER COLUMN organization_id SET NOT NULL;

CREATE INDEX idx_recipients_org       ON recipients(organization_id);
CREATE INDEX idx_recipient_groups_org ON recipient_groups(organization_id);
CREATE INDEX idx_email_templates_org  ON email_templates(organization_id);
CREATE INDEX idx_attachments_org      ON attachments(organization_id);
CREATE INDEX idx_campaigns_org        ON campaigns(organization_id);

-- ── Uniqueness per organization ─────────────────────────────────────────────
-- Constraint names are the PostgreSQL defaults from V1 (<table>_<column>_key).
ALTER TABLE recipients DROP CONSTRAINT recipients_email_key;
ALTER TABLE recipients ADD CONSTRAINT uq_recipients_org_email UNIQUE (organization_id, email);

ALTER TABLE unsubscribe_list DROP CONSTRAINT unsubscribe_list_email_key;
ALTER TABLE unsubscribe_list ADD CONSTRAINT uq_unsubscribe_org_email UNIQUE (organization_id, email);
