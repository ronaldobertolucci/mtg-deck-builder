ALTER TABLE deck_analysis_messages ADD COLUMN code VARCHAR(100) NOT NULL DEFAULT 'LEGACY_MESSAGE';
ALTER TABLE deck_analysis_messages ADD COLUMN severity VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE deck_analysis_messages ADD COLUMN parameters JSON NOT NULL DEFAULT JSON '{}';
-- Preserve historical text without inferring individual codes or severity from its wording.
ALTER TABLE deck_analysis_messages ADD CONSTRAINT ck_analysis_reason_severity
    CHECK (severity IN ('VIOLATION', 'UNCERTAINTY', 'LEGACY'));
