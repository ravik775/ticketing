-- Transactional outbox: every workflow change writes its history event HERE, in the same PostgreSQL
-- transaction as the state change, so an event can never be lost. MongoDB (ticket details + history)
-- becomes a projection that is (re)built from these rows: immediately after commit, and by the relay
-- for anything that failed. PostgreSQL is the source of truth.
CREATE TABLE ticket_outbox (
    id              uuid         PRIMARY KEY,
    tenant_id       varchar(64)  NOT NULL REFERENCES tenant (id),
    ticket_id       uuid         NOT NULL,
    ticket_version  bigint       NOT NULL,
    event_type      varchar(32)  NOT NULL,
    actor           varchar(254) NOT NULL,
    comment         text,
    -- ticket details, only for CREATED. Kept for the retention window (ticketing.outbox.retention, default
    -- 30 days) so a lost MongoDB document can be rebuilt; then the row is purged (data minimisation).
    title           varchar(120),
    mobile          varchar(16),
    description     text,
    occurred_at     timestamptz  NOT NULL,
    published_at    timestamptz,
    attempts        integer      NOT NULL DEFAULT 0,
    last_error      text
);

-- The relay scans only what is still pending.
CREATE INDEX ix_outbox_pending ON ticket_outbox (occurred_at) WHERE published_at IS NULL;

-- Same tenant isolation as the workflow table. The relay works across tenants, so it opts in per
-- transaction with app.outbox_relay = 'on' (transaction-local, like app.tenant_id).
ALTER TABLE ticket_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_outbox FORCE ROW LEVEL SECURITY;

CREATE POLICY outbox_tenant_isolation ON ticket_outbox
    USING      (tenant_id = current_setting('app.tenant_id', true) OR current_setting('app.outbox_relay', true) = 'on')
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true) OR current_setting('app.outbox_relay', true) = 'on');
