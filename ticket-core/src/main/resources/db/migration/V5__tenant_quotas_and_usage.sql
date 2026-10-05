-- Per-tenant performance isolation and metering.
-- tier / requests_per_minute / max_concurrent are enforced by the API (TenantQuotaFilter) so one tenant
-- (e.g. a month-end peak) cannot exhaust capacity for everybody.
ALTER TABLE tenant
    ADD COLUMN tier                varchar(20) NOT NULL DEFAULT 'standard',
    ADD COLUMN requests_per_minute integer     NOT NULL DEFAULT 1200 CHECK (requests_per_minute > 0),
    ADD COLUMN max_concurrent      integer     NOT NULL DEFAULT 20   CHECK (max_concurrent > 0);

-- Daily usage per tenant (metering for capacity planning, cost allocation and pricing).
-- Contains counters only, no ticket data.
CREATE TABLE tenant_usage (
    tenant_id        varchar(64) NOT NULL REFERENCES tenant (id),
    day              date        NOT NULL,
    api_requests     bigint      NOT NULL DEFAULT 0,
    throttled        bigint      NOT NULL DEFAULT 0,
    tickets_created  bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, day)
);
