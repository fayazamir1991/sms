-- Additive: preserves catalog IDs, approved snapshots, users, schedules and logs.
ALTER TABLE catalog_messages ADD COLUMN body_formal TEXT;
ALTER TABLE catalog_messages ADD COLUMN body_colloquial TEXT;
ALTER TABLE catalog_origins ADD COLUMN inspiration_refs TEXT NOT NULL DEFAULT '[]';
