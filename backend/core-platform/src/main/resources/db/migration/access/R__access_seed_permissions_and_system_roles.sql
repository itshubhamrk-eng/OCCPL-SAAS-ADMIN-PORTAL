-- Repeatable: re-applied whenever this file changes. Keeps the permission catalogue and the
-- permission sets of SYSTEM roles in sync with the code. Custom company roles are untouched.

INSERT INTO permission (code, scope, description) VALUES
    ('platform.companies.read',      'PLATFORM', 'View all companies'),
    ('platform.companies.manage',    'PLATFORM', 'Create, update, activate, suspend and deactivate companies'),
    ('platform.users.read',          'PLATFORM', 'View platform users and their memberships'),
    ('platform.users.manage',        'PLATFORM', 'Enable or disable platform users'),
    ('platform.roles.manage',        'PLATFORM', 'Assign platform roles to users'),
    ('platform.products.read',       'PLATFORM', 'View the product catalogue'),
    ('platform.products.manage',     'PLATFORM', 'Register and configure products'),
    ('platform.leads.read',         'PLATFORM', 'View leads'),
    ('platform.leads.manage',       'PLATFORM', 'Register, update and change the status of leads'),
    ('platform.plans.read',          'PLATFORM', 'View subscription plans'),
    ('platform.plans.manage',        'PLATFORM', 'Create and publish subscription plans'),
    ('platform.subscriptions.read',  'PLATFORM', 'View company subscriptions'),
    ('platform.subscriptions.manage','PLATFORM', 'Assign, change and cancel company subscriptions'),
    ('platform.provisioning.read',   'PLATFORM', 'View provisioning jobs'),
    ('platform.provisioning.manage', 'PLATFORM', 'Retry or cancel provisioning jobs'),
    ('platform.audit.read',          'PLATFORM', 'View the platform audit log'),
    ('platform.reports.read',        'PLATFORM', 'View platform reports'),
    ('platform.settings.manage',     'PLATFORM', 'Change platform settings'),
    ('company.profile.read',         'COMPANY',  'View the company profile and settings'),
    ('company.profile.manage',       'COMPANY',  'Update the company profile and settings'),
    ('company.users.read',           'COMPANY',  'View company members and invitations'),
    ('company.users.manage',         'COMPANY',  'Invite, suspend and remove company members'),
    ('company.roles.assign',         'COMPANY',  'Assign roles to company members'),
    ('company.roles.manage',         'COMPANY',  'Create and edit custom company roles'),
    ('company.subscription.read',    'COMPANY',  'View company subscriptions'),
    ('company.billing.read',         'COMPANY',  'View invoices and payments'),
    ('company.billing.manage',       'COMPANY',  'Pay invoices'),
    ('company.usage.read',           'COMPANY',  'View entitlements and usage'),
    ('company.audit.read',           'COMPANY',  'View the company audit log'),
    ('company.products.launch',      'COMPANY',  'Open entitled products')
AS new
ON DUPLICATE KEY UPDATE scope = new.scope, description = new.description;

INSERT INTO role (id, code, scope, company_id, system_role, name, description, created_at, updated_at) VALUES
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000001'), 'SUPER_ADMIN',      'PLATFORM', NULL, TRUE, 'Super Admin',      'Full platform administration', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000002'), 'PLATFORM_SUPPORT', 'PLATFORM', NULL, TRUE, 'Platform Support', 'Read-only access across companies', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000011'), 'COMPANY_ADMIN',    'COMPANY',  NULL, TRUE, 'Company Admin',    'Full administration of one company', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000012'), 'COMPANY_MANAGER',  'COMPANY',  NULL, TRUE, 'Company Manager',  'Manages company members', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000013'), 'COMPANY_USER',     'COMPANY',  NULL, TRUE, 'Company User',     'Uses entitled products', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
AS new
ON DUPLICATE KEY UPDATE name = new.name, description = new.description, updated_at = UTC_TIMESTAMP(6);

DELETE rp FROM role_permission rp JOIN role r ON r.id = rp.role_id WHERE r.system_role = TRUE;

-- SUPER_ADMIN: every platform permission.
INSERT INTO role_permission (role_id, permission_code)
SELECT UUID_TO_BIN('01920000-0000-7000-8000-000000000001'), code FROM permission WHERE scope = 'PLATFORM';

-- PLATFORM_SUPPORT: read-only platform permissions.
INSERT INTO role_permission (role_id, permission_code)
SELECT UUID_TO_BIN('01920000-0000-7000-8000-000000000002'), code FROM permission
WHERE scope = 'PLATFORM' AND code LIKE '%.read';

-- COMPANY_ADMIN: every company permission.
INSERT INTO role_permission (role_id, permission_code)
SELECT UUID_TO_BIN('01920000-0000-7000-8000-000000000011'), code FROM permission WHERE scope = 'COMPANY';

INSERT INTO role_permission (role_id, permission_code) VALUES
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000012'), 'company.profile.read'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000012'), 'company.users.read'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000012'), 'company.users.manage'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000012'), 'company.roles.assign'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000012'), 'company.subscription.read'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000012'), 'company.usage.read'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000012'), 'company.products.launch'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000013'), 'company.profile.read'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000013'), 'company.products.launch');
