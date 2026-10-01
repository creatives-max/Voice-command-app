-- Voice macros: a VOICE trigger runs its flow when the user says its phrase on the phone.
ALTER TABLE flow_triggers DROP CONSTRAINT IF EXISTS flow_triggers_type_check;
ALTER TABLE flow_triggers ADD CONSTRAINT flow_triggers_type_check CHECK (type IN ('APP_OPEN', 'SCHEDULE', 'VOICE'));
ALTER TABLE flow_triggers ADD COLUMN phrase TEXT;
-- Normalized phrase (lower case, no punctuation); one flow per phrase and account.
ALTER TABLE flow_triggers ADD COLUMN phrase_key TEXT;
ALTER TABLE flow_triggers ADD CONSTRAINT flow_triggers_voice_phrase CHECK (type <> 'VOICE' OR (phrase IS NOT NULL AND phrase_key IS NOT NULL));
CREATE UNIQUE INDEX flow_triggers_user_phrase ON flow_triggers (user_id, phrase_key) WHERE type = 'VOICE';

ALTER TABLE run_requests DROP CONSTRAINT IF EXISTS run_requests_source_check;
ALTER TABLE run_requests ADD CONSTRAINT run_requests_source_check CHECK (source IN ('MANUAL', 'SCHEDULE', 'APP_OPEN', 'VOICE'));
