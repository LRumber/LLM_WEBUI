-- Add non-destructive message revision, branch lineage, and reusable prompt templates.
ALTER TABLE conversation
    ADD COLUMN parent_conversation_id UUID REFERENCES conversation (id),
    ADD COLUMN branch_from_message_id UUID REFERENCES conversation_message (id);

ALTER TABLE conversation_message
    ADD COLUMN edited_at TIMESTAMPTZ,
    ADD COLUMN superseded_at TIMESTAMPTZ;

CREATE TABLE prompt_template (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    description         VARCHAR(512),
    system_prompt       TEXT NOT NULL,
    owner_user_id       BIGINT REFERENCES app_user (id),
    scope               VARCHAR(16) NOT NULL DEFAULT 'system'
                        CHECK (scope IN ('system', 'personal', 'team')),
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_conversation_parent ON conversation (parent_conversation_id);
CREATE INDEX idx_prompt_template_scope ON prompt_template (scope, enabled);

INSERT INTO prompt_template (id, name, description, system_prompt, scope) VALUES
    ('10000000-0000-0000-0000-000000000001', '通用助手', '清晰、准确地回答一般问题',
     '你是企业离线智能工作台助手。回答应准确、简洁，并明确说明不确定信息。', 'system'),
    ('10000000-0000-0000-0000-000000000002', '代码助手', '侧重代码质量、安全和可维护性',
     '你是资深软件工程助手。优先给出可运行、可测试且安全的实现，并解释关键取舍。', 'system'),
    ('10000000-0000-0000-0000-000000000003', '文档分析', '基于用户提供材料进行结构化分析',
     '你是企业文档分析助手。严格依据提供的材料作答，区分事实、推断与缺失信息。', 'system');
