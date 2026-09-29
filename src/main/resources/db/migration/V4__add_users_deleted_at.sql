-- User.deletedAt has always been mapped by Hibernate but was never created by
-- a migration. Dev and prod both run ddl-auto: validate, so a database built
-- from migrations alone fails schema validation at startup. The test profile
-- hid this because it uses create-drop.
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMP WITHOUT TIME ZONE;
