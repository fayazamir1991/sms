PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS catalog_messages (
 id TEXT PRIMARY KEY,
 body TEXT NOT NULL,
 fingerprint TEXT NOT NULL UNIQUE,
 category TEXT NOT NULL,
 active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
 created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS catalog_category ON catalog_messages(category,active);
CREATE TABLE IF NOT EXISTS installations (
 id TEXT PRIMARY KEY,
 device_hash TEXT,
 token_hash TEXT NOT NULL,
 app_version TEXT NOT NULL,
 enabled INTEGER NOT NULL DEFAULT 1,
 created_at INTEGER NOT NULL,
 last_seen_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS installations_device ON installations(device_hash);
CREATE TABLE IF NOT EXISTS queued_messages (
 installation_id TEXT NOT NULL REFERENCES installations(id),
 id TEXT NOT NULL,
 body_cipher TEXT NOT NULL,
 recipient_cipher TEXT NOT NULL,
 content_hash TEXT NOT NULL,
 status TEXT NOT NULL,
 sim_id INTEGER NOT NULL,
 revision INTEGER NOT NULL,
 approved_at INTEGER NOT NULL,
 planned_at INTEGER,
 submitted_at INTEGER,
 updated_at INTEGER NOT NULL,
 PRIMARY KEY(installation_id,id)
);
CREATE INDEX IF NOT EXISTS queue_status ON queued_messages(status,updated_at);
CREATE TABLE IF NOT EXISTS message_events (
 installation_id TEXT NOT NULL,
 id TEXT NOT NULL,
 message_id TEXT NOT NULL,
 status TEXT NOT NULL,
 revision INTEGER NOT NULL,
 occurred_at INTEGER NOT NULL,
 PRIMARY KEY(installation_id,id),
 FOREIGN KEY(installation_id,message_id) REFERENCES queued_messages(installation_id,id)
);
CREATE INDEX IF NOT EXISTS events_message ON message_events(installation_id,message_id,occurred_at);
CREATE TABLE IF NOT EXISTS sent_logs (
 installation_id TEXT NOT NULL,
 message_id TEXT NOT NULL,
 sent_at INTEGER NOT NULL,
 received_at INTEGER NOT NULL,
 PRIMARY KEY(installation_id,message_id),
 FOREIGN KEY(installation_id,message_id) REFERENCES queued_messages(installation_id,id)
);
CREATE TABLE IF NOT EXISTS seed_runs (
 version TEXT PRIMARY KEY,
 count INTEGER NOT NULL,
 seeded_at INTEGER NOT NULL
);
