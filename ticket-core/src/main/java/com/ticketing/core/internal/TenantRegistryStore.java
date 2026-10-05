package com.ticketing.core.internal;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tenant registry (quotas, tier) and usage metering tables. Neither holds ticket data, so no RLS. */
@Component
public class TenantRegistryStore {

    /** Raw quota row. */
    public record QuotaRow(String tier, int requestsPerMinute, int maxConcurrent) {
    }

    private final JdbcTemplate jdbc;

    TenantRegistryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<QuotaRow> quota(String tenantId) {
        return jdbc.query("select tier, requests_per_minute, max_concurrent from tenant where id = ? and active",
                (rs, n) -> new QuotaRow(rs.getString(1), rs.getInt(2), rs.getInt(3)), tenantId).stream().findFirst();
    }

    /** Adds the counters to today's row (one upsert per tenant per flush). */
    @Transactional
    public void addUsage(LocalDate day, Map<String, long[]> perTenant) {
        perTenant.forEach((tenant, c) -> jdbc.update("""
                insert into tenant_usage (tenant_id, day, api_requests, throttled, tickets_created)
                select ?, ?, ?, ?, ? where exists (select 1 from tenant where id = ?)
                on conflict (tenant_id, day) do update set
                    api_requests    = tenant_usage.api_requests    + excluded.api_requests,
                    throttled       = tenant_usage.throttled       + excluded.throttled,
                    tickets_created = tenant_usage.tickets_created + excluded.tickets_created""",
                tenant, day, c[0], c[1], c[2], tenant));
    }
}
