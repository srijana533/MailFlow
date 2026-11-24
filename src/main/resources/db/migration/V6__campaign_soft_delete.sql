-- =============================================================================
-- V6 — Campaign soft delete
-- =============================================================================
-- Deleting a campaign hides it instead of removing its row. email_logs (the
-- per-message audit trail), email_events, unsubscribe_list and
-- scheduled_campaigns reference campaigns without ON DELETE CASCADE, and the
-- delivery history and unsubscribe entries must be kept, so the row stays.
--
-- Additive only: two nullable columns and an index. Existing campaigns keep
-- deleted_at NULL, which means "not deleted"; no existing row is changed.
--
--   • deleted_at: when the campaign was deleted (NULL for a live campaign).
--   • deleted_by: the user who deleted it (NULL when not known).
-- =============================================================================

ALTER TABLE campaigns
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by BIGINT REFERENCES users(id);

-- Same convention as recipients and email_templates: lists read live rows only.
CREATE INDEX idx_campaigns_deleted ON campaigns(deleted_at) WHERE deleted_at IS NULL;
