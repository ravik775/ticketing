package com.ticketing.core;

import com.ticketing.core.internal.TenantRegistryStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads tenant quotas from the registry, cached briefly so the quota check adds no database round
 * trip per request. Unknown/inactive tenants get the default (they are rejected later anyway).
 */
@Service
public class TenantPolicyService {

    private record Cached(TenantPolicy policy, Instant expires) {
    }

    private final TenantRegistryStore registry;
    private final Duration ttl;
    private final int defaultRpm;
    private final int defaultConcurrent;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TenantPolicyService(TenantRegistryStore registry,
                               @Value("${ticketing.tenant-quota.cache-ttl:PT60S}") Duration ttl,
                               @Value("${ticketing.tenant-quota.default-requests-per-minute:1200}") int defaultRpm,
                               @Value("${ticketing.tenant-quota.default-max-concurrent:20}") int defaultConcurrent) {
        this.registry = registry;
        this.ttl = ttl;
        this.defaultRpm = defaultRpm;
        this.defaultConcurrent = defaultConcurrent;
    }

    public TenantPolicy policyFor(String tenantId) {
        Instant now = Instant.now();
        Cached cached = cache.get(tenantId);
        if (cached != null && cached.expires().isAfter(now)) {
            return cached.policy();
        }
        TenantPolicy policy = registry.quota(tenantId)
                .map(q -> new TenantPolicy(tenantId, q.tier(), q.requestsPerMinute(), q.maxConcurrent()))
                .orElse(new TenantPolicy(tenantId, "unknown", defaultRpm, defaultConcurrent));
        cache.put(tenantId, new Cached(policy, now.plus(ttl)));
        return policy;
    }
}
