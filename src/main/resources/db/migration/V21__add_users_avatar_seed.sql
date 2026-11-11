-- A random, public seed for the user's default avatar. It is not derived from the
-- user id, so showing it reveals nothing, and it never changes, so a user keeps
-- the same avatar after renaming.
ALTER TABLE users ADD COLUMN avatar_seed UUID NOT NULL DEFAULT gen_random_uuid();
