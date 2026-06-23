INSERT INTO system_setting (setting_key, setting_value, description)
VALUES
    ('default_speech_model', '', 'Default speech-to-text model key used by browser voice input.')
ON CONFLICT (setting_key) DO NOTHING;
