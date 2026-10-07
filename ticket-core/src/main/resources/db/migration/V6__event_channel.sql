-- Records THROUGH WHICH PATH each history event was caused: 'REST' (web UI, scripts) or 'MCP' (AI agents
-- via the Model Context Protocol endpoint). Together with "actor" the audit trail answers both
-- "who approved it?" and "how?". Events written before this migration have no channel (NULL).
alter table ticket_outbox add column channel text
    constraint ticket_outbox_channel_chk check (channel in ('REST', 'MCP'));
