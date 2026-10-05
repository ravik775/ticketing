-- Tenant registry (structured reference data)
CREATE TABLE tenant (
    id      varchar(64)  PRIMARY KEY,
    name    varchar(200) NOT NULL,
    active  boolean      NOT NULL DEFAULT true
);

-- Ticket workflow state. Ticket details/comments are in MongoDB (same id).
CREATE TABLE ticket_workflow (
    id           uuid         PRIMARY KEY,
    tenant_id    varchar(64)  NOT NULL REFERENCES tenant (id),
    owner_email  varchar(254) NOT NULL,
    status       varchar(20)  NOT NULL,
    locked_by    varchar(254),
    locked_at    timestamptz,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_ticket_status CHECK (status IN ('OPEN', 'LOCKED', 'APPROVED', 'REJECTED', 'MORE_INFO')),
    CONSTRAINT ck_lock_consistency CHECK ((status = 'LOCKED') = (locked_by IS NOT NULL))
);

CREATE INDEX ix_ticket_tenant_owner  ON ticket_workflow (tenant_id, owner_email, created_at DESC);
CREATE INDEX ix_ticket_tenant_status ON ticket_workflow (tenant_id, status, created_at DESC);

-- Row-Level Security: the database itself refuses rows of other tenants.
-- The application sets app.tenant_id at the start of every transaction. If it is not set,
-- current_setting(..., true) is NULL, the predicate is never true, and NO rows are visible (fail closed).
-- FORCE applies the policy even to the table owner (the application's own DB user).
ALTER TABLE ticket_workflow ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_workflow FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON ticket_workflow
    USING      (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
