CREATE TABLE IF NOT EXISTS catalog_offers(installation_id TEXT NOT NULL REFERENCES installations(id),catalog_id TEXT NOT NULL REFERENCES catalog_messages(id) ON DELETE CASCADE,served_at INTEGER NOT NULL,PRIMARY KEY(installation_id,catalog_id));
CREATE INDEX IF NOT EXISTS catalog_offers_age ON catalog_offers(installation_id,served_at);
