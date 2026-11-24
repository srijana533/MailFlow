-- =============================================================================
-- V5 — Campaign-only addresses
-- =============================================================================
-- Lets a campaign email typed or uploaded addresses WITHOUT creating saved
-- contacts. Additive only: no existing row, column or constraint is removed,
-- and existing data keeps its meaning.
--
--   • campaign_target_addresses holds a campaign's campaign-only addresses
--     (what a draft will send to). Rows are deleted with the campaign.
--   • campaign_recipients.recipient_id becomes nullable. A row without a
--     recipient delivers to a campaign-only address and carries a snapshot of
--     it (email, name, first_name, last_name, company, custom_fields).
--     Existing rows keep their recipient_id; their snapshot columns stay NULL.
-- =============================================================================

CREATE TABLE campaign_target_addresses (
    id              BIGSERIAL       PRIMARY KEY,
    campaign_id     BIGINT          NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    email           VARCHAR(255)    NOT NULL,   -- stored lower-case
    name            VARCHAR(255),
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),
    company         VARCHAR(255),
    custom_fields   JSONB           NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_campaign_target_addresses_email UNIQUE (campaign_id, email),
    CONSTRAINT ck_campaign_target_addresses_lower CHECK (email = lower(email))
);

-- A campaign-only delivery row has no saved contact ...
ALTER TABLE campaign_recipients ALTER COLUMN recipient_id DROP NOT NULL;

-- ... and carries a snapshot of the address instead (NULL on saved-contact rows).
ALTER TABLE campaign_recipients
    ADD COLUMN email            VARCHAR(255),
    ADD COLUMN name             VARCHAR(255),
    ADD COLUMN first_name       VARCHAR(100),
    ADD COLUMN last_name        VARCHAR(100),
    ADD COLUMN company          VARCHAR(255),
    ADD COLUMN custom_fields    JSONB;

-- Every delivery row must say where it sends to.
ALTER TABLE campaign_recipients
    ADD CONSTRAINT ck_campaign_recipients_target
    CHECK (recipient_id IS NOT NULL OR email IS NOT NULL);

-- At most one campaign-only row per address in a campaign.
CREATE UNIQUE INDEX uq_campaign_recipients_address
    ON campaign_recipients (campaign_id, lower(email))
    WHERE recipient_id IS NULL;
