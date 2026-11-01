ALTER TABLE catalog_origins ADD COLUMN selection_policy TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_origins ADD COLUMN selection_score INTEGER NOT NULL DEFAULT 0;
ALTER TABLE catalog_origins ADD COLUMN selection_signals TEXT NOT NULL DEFAULT '[]';
CREATE INDEX IF NOT EXISTS catalog_created ON catalog_messages(created_at);
CREATE INDEX IF NOT EXISTS installations_created ON installations(created_at);
CREATE INDEX IF NOT EXISTS queued_approved ON queued_messages(approved_at);
CREATE INDEX IF NOT EXISTS sent_at_index ON sent_logs(sent_at);
CREATE INDEX IF NOT EXISTS events_time_index ON message_events(occurred_at);
CREATE INDEX IF NOT EXISTS jobs_started_index ON catalog_jobs(started_at);
