INSERT INTO system_setting (setting_key, setting_value, description)
VALUES
    ('deployment_domain', 'ai.sitp.ac.cn', 'Public domain served by the reverse proxy.'),
    ('deployment_http_port', '80', 'Desired host HTTP port applied by deploy/apply-ports.sh.'),
    ('deployment_https_port', '443', 'Desired host HTTPS port applied by deploy/apply-ports.sh.')
ON CONFLICT (setting_key) DO NOTHING;
