-- The audit log is append-only (SECURITY.md §6, DATABASE.md): no account, including the application,
-- can change or remove entries. MySQL cannot exclude one table from a schema-wide grant, so this is
-- enforced with triggers. A future retention purge must be its own reviewed migration.
CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';

CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
