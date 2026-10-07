-- =============================================================================
-- V3 — Seed data for local development / demo
-- =============================================================================
-- IMPORTANT: These are DEMO credentials. Change them before any real deployment.
--
-- Admin:            admin@mailflow.local       / Admin@123
-- Campaign Manager: manager@mailflow.local     / Manager@123
-- Viewer:           viewer@mailflow.local      / Viewer@123
--
-- Passwords are BCrypt-hashed (cost 12).
-- =============================================================================

-- Roles
INSERT INTO roles (name, description) VALUES
    ('ADMIN',            'Full system access'),
    ('CAMPAIGN_MANAGER', 'Create and manage campaigns, recipients, templates'),
    ('VIEWER',           'Read-only analytics and reporting')
ON CONFLICT (name) DO NOTHING;

-- Users (BCrypt hash of Admin@123, Manager@123, Viewer@123)
INSERT INTO users (email, password_hash, first_name, last_name, enabled) VALUES
    ('admin@mailflow.local',
     '$2a$12$QAs4bs8wloGrgZh0d0Tc0ud9E6OeQij.KmbcIi8fmHtUpUGs9fd0a',
     'Admin', 'User', TRUE),
    ('manager@mailflow.local',
     '$2a$12$08kG7KkUDvuh1jLJqY7DOueqKxa3Ic86v7SIymxRWcV3XyLmNVlUC',
     'Campaign', 'Manager', TRUE),
    ('viewer@mailflow.local',
     '$2a$12$5wz6U1uNpE65GXP/NWL2z.QtJHdMukvTUJNHUHCt9TPVOVckiSU9W',
     'Analytics', 'Viewer', TRUE)
ON CONFLICT (email) DO NOTHING;

-- User-Role assignments
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r
WHERE u.email = 'admin@mailflow.local'
  AND r.name IN ('ADMIN', 'CAMPAIGN_MANAGER', 'VIEWER')
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r
WHERE u.email = 'manager@mailflow.local'
  AND r.name IN ('CAMPAIGN_MANAGER', 'VIEWER')
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u CROSS JOIN roles r
WHERE u.email = 'viewer@mailflow.local'
  AND r.name = 'VIEWER'
ON CONFLICT DO NOTHING;

-- Recipient Groups ("descr" alias — "desc" is a reserved keyword in PostgreSQL)
INSERT INTO recipient_groups (name, description, created_by)
SELECT
    grp.name,
    grp.descr,
    (SELECT id FROM users WHERE email = 'admin@mailflow.local')
FROM (VALUES
    ('CS Final Year 2026',   'Computer Science graduates 2026'),
    ('IT Department',        'Information Technology batch'),
    ('MBA Cohort 2026',      'MBA students graduation batch')
) AS grp(name, descr);

-- Email Templates
INSERT INTO email_templates (name, description, subject, html_body, plain_body, variables, created_by)
SELECT
    'Placement Workshop Invitation',
    'Invite students to campus placement workshop',
    'Invitation: Placement Preparation Workshop — {{name}}',
    '<html><body><p>Dear <strong>{{name}}</strong>,</p><p>We are pleased to invite you to the <strong>Placement Preparation Workshop</strong> on October 15, 2026.</p><p>Course: {{course}} | Code: <code>{{code}}</code></p><p>Register at: https://placement.example.com</p><p>Best regards,<br/>Placement Cell</p></body></html>',
    'Dear {{name}}, You are invited to the Placement Preparation Workshop on October 15, 2026. Course: {{course}} | Code: {{code}}. Register at: https://placement.example.com',
    '["name","email","course","company","code"]'::jsonb,
    (SELECT id FROM users WHERE email = 'admin@mailflow.local')
WHERE NOT EXISTS (SELECT 1 FROM email_templates WHERE name = 'Placement Workshop Invitation');

INSERT INTO email_templates (name, description, subject, html_body, plain_body, variables, created_by)
SELECT
    'Cloud Lab Access',
    'Send cloud lab registration codes to students',
    'Your Cloud Lab Access Code — {{name}}',
    '<html><body><p>Hi <strong>{{name}}</strong>,</p><p>Your access code for the <strong>{{course}}</strong> cloud lab is: <code>{{code}}</code></p><p>Visit https://labs.example.com to redeem.</p><p>— MailFlow Team</p></body></html>',
    'Hi {{name}}, Your code for {{course}}: {{code}}. Visit https://labs.example.com. — MailFlow Team',
    '["name","email","course","code"]'::jsonb,
    (SELECT id FROM users WHERE email = 'admin@mailflow.local')
WHERE NOT EXISTS (SELECT 1 FROM email_templates WHERE name = 'Cloud Lab Access');

-- Demo Recipients (100 records)
INSERT INTO recipients (email, name, first_name, last_name, course, company, custom_fields, import_source, status)
SELECT
    'student' || n || '@demo.mailflow.local',
    'Student ' || n,
    'Student',
    n::TEXT,
    CASE WHEN n % 3 = 0 THEN 'Computer Science'
         WHEN n % 3 = 1 THEN 'Information Technology'
         ELSE 'MBA' END,
    CASE WHEN n % 4 = 0 THEN 'TechCorp'
         WHEN n % 4 = 1 THEN 'InnoSoft'
         WHEN n % 4 = 2 THEN 'DataSystems'
         ELSE 'CloudBase' END,
    ('{"code": "CODE-' || LPAD(n::TEXT, 4, '0') || '"}')::jsonb,
    'SEED_DATA',
    'ACTIVE'
FROM generate_series(1, 100) AS n
ON CONFLICT (email) DO NOTHING;

-- Sample DRAFT Campaign
INSERT INTO campaigns (name, description, subject, template_id, status, created_by,
                       sender_name, sender_email, total_recipients)
SELECT
    'Placement Workshop 2026',
    'Campus placement preparation workshop invitation campaign',
    'Invitation: Placement Preparation Workshop',
    (SELECT id FROM email_templates WHERE name = 'Placement Workshop Invitation' LIMIT 1),
    'DRAFT',
    (SELECT id FROM users WHERE email = 'admin@mailflow.local'),
    'Placement Cell',
    'placement@mailflow.local',
    100
WHERE NOT EXISTS (SELECT 1 FROM campaigns WHERE name = 'Placement Workshop 2026');
