package com.ticketing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.core.TenantPolicy;
import com.ticketing.core.TenantPolicyService;
import com.ticketing.core.TenantUsageMeter;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-TENANT capacity isolation (noisy-neighbour protection). Kong already limits each USER; this
 * limits each TENANT, so a tenant with many users or a month-end peak cannot exhaust shared capacity:
 * <ul>
 *   <li>requests per minute (fixed window) from the tenant registry ({@code tenant.requests_per_minute});</li>
 *   <li>concurrent in-flight requests ({@code tenant.max_concurrent}), a bulkhead for the database pool.</li>
 * </ul>
 * Runs after {@link TenantContextFilter}, so the tenant is the one proven by the token. Counters are
 * in memory: exact with one replica; with several replicas use a shared store (Redis), see docs.
 * Every request and every rejection is metered per tenant.
 */
class TenantQuotaFilter extends OncePerRequestFilter {

    private static final class Window {
        final long minute;
        final AtomicLong count = new AtomicLong();

        Window(long minute) {
            this.minute = minute;
        }
    }

    private final TenantPolicyService policies;
    private final TenantUsageMeter usage;
    private final ObjectMapper mapper;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    TenantQuotaFilter(TenantPolicyService policies, TenantUsageMeter usage, ObjectMapper mapper) {
        this.policies = policies;
        this.usage = usage;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantContext ctx = TenantContextHolder.current().orElse(null);
        if (ctx == null) {                      // e.g. /api/me: no tenant selected yet
            chain.doFilter(request, response);
            return;
        }
        String tenant = ctx.tenantId();
        TenantPolicy policy = policies.policyFor(tenant);

        long nowMillis = System.currentTimeMillis();
        long minute = nowMillis / 60_000;
        Window window = windows.compute(tenant, (t, w) -> w == null || w.minute != minute ? new Window(minute) : w);
        long used = window.count.incrementAndGet();
        response.setHeader("X-Tenant-RateLimit-Limit", String.valueOf(policy.requestsPerMinute()));
        response.setHeader("X-Tenant-RateLimit-Remaining", String.valueOf(Math.max(0, policy.requestsPerMinute() - used)));
        if (used > policy.requestsPerMinute()) {
            reject(response, tenant, (60_000 - nowMillis % 60_000) / 1000 + 1,
                    "Tenant '" + tenant + "' exceeded its quota of " + policy.requestsPerMinute() + " requests per minute");
            return;
        }

        AtomicInteger current = inFlight.computeIfAbsent(tenant, t -> new AtomicInteger());
        if (current.incrementAndGet() > policy.maxConcurrent()) {
            current.decrementAndGet();
            reject(response, tenant, 1,
                    "Tenant '" + tenant + "' has too many requests in progress (limit " + policy.maxConcurrent() + ")");
            return;
        }
        try {
            usage.recordRequest(tenant);
            chain.doFilter(request, response);
        } finally {
            current.decrementAndGet();
        }
    }

    private void reject(HttpServletResponse response, String tenant, long retryAfterSeconds, String detail)
            throws IOException {
        usage.recordThrottled(tenant);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        Problems.write(mapper, response, HttpStatus.TOO_MANY_REQUESTS, detail);
    }
}
