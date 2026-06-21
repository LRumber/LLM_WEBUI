-- Persist the selected template identity and name so restored conversations show their original setup.
ALTER TABLE conversation
    ADD COLUMN prompt_template_id UUID REFERENCES prompt_template (id) ON DELETE SET NULL,
    ADD COLUMN prompt_template_name VARCHAR(128);

CREATE INDEX idx_conversation_prompt_template ON conversation (prompt_template_id);
