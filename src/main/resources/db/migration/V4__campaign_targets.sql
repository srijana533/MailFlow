-- =============================================================================
-- V4 — Campaign target groups and recipients join tables
-- =============================================================================

CREATE TABLE campaign_target_groups (
    campaign_id BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    group_id    BIGINT NOT NULL REFERENCES recipient_groups(id) ON DELETE CASCADE,
    PRIMARY KEY (campaign_id, group_id)
);

CREATE TABLE campaign_target_recipients (
    campaign_id  BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    recipient_id BIGINT NOT NULL REFERENCES recipients(id) ON DELETE CASCADE,
    PRIMARY KEY (campaign_id, recipient_id)
);

CREATE TABLE campaign_attachments (
    campaign_id   BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    attachment_id BIGINT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
    PRIMARY KEY (campaign_id, attachment_id)
);
