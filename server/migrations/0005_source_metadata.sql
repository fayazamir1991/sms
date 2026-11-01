ALTER TABLE catalog_origins ADD COLUMN original_text TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_origins ADD COLUMN original_language TEXT NOT NULL DEFAULT 'fa';
ALTER TABLE catalog_origins ADD COLUMN translation_provider TEXT NOT NULL DEFAULT '';
ALTER TABLE catalog_origins ADD COLUMN license_url TEXT NOT NULL DEFAULT '';
