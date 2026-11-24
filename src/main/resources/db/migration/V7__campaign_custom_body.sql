-- =============================================================================
-- V7 — Campaign-wide email body
-- =============================================================================
-- A campaign can carry its own plain-text email body, written in the New
-- Campaign form, instead of a template's body. The subject stays in
-- campaigns.subject.
--
-- Additive only: one nullable column. NULL means "use the template's body",
-- which is what every existing campaign does today, so no existing row changes.
--
--   • custom_body: the campaign's own body (plain text, placeholders such as
--     {{FirstName}} allowed). When set, it is sent instead of the template's
--     bodies. The API limits it to 100,000 characters.
-- =============================================================================

ALTER TABLE campaigns ADD COLUMN custom_body TEXT;
