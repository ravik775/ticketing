package com.ticketing.core;

import com.ticketing.core.internal.TenantRegistryStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Metering per tenant: counts API requests, throttled requests and created tickets in memory and
 * flushes them to {@code tenant_usage} once a minute (one upsert per tenant), so metering does not
 * add a database write to every request. Also exported as Prometheus counters tagged by tenant
 * (tenants are a bounded set; never tag by user or ticket).
 */
@Service
public class TenantUsageMeter {

    private static final Logger log = LoggerFactory.getLogger(TenantUsageMeter.class);
    private static final int REQUESTS = 0;
    private static final int THROTTLED = 1;
    private static final int CREATED = 2;

    private final TenantRegistryStore registry;
    private final MeterRegistry meters;
    private final Map<String, LongAdder[]> pending = new ConcurrentHashMap<>();

    public TenantUsageMeter(TenantRegistryStore registry, MeterRegistry meters) {
        this.registry = registry;
        this.meters = meters;
    }

    public void recordRequest(String tenantId) {
        add(tenantId, REQUESTS, "ticketing.tenant.requests");
    }

    public void recordThrottled(String tenantId) {
        add(tenantId, THROTTLED, "ticketing.tenant.throttled");
    }

    public void recordTicketCreated(String tenantId) {
        add(tenantId, CREATED, "ticketing.tenant.tickets.created");
    }

    private void add(String tenantId, int slot, String metric) {
        pending.computeIfAbsent(tenantId, t -> new LongAdder[] {new LongAdder(), new LongAdder(), new LongAdder()})[slot].increment();
        Counter.builder(metric).tag("tenant", tenantId).register(meters).increment();
    }

    @Scheduled(fixedDelayString = "${ticketing.metering.flush-interval:PT60S}", initialDelayString = "PT60S")
    public void flush() {
        Map<String, long[]> snapshot = new HashMap<>();
        pending.forEach((tenant, c) -> {
            long[] values = {c[REQUESTS].sumThenReset(), c[THROTTLED].sumThenReset(), c[CREATED].sumThenReset()};
            if (values[0] + values[1] + values[2] > 0) {
                snapshot.put(tenant, values);
            }
        });
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            registry.addUsage(LocalDate.now(ZoneOffset.UTC), snapshot);
        } catch (RuntimeException e) {
            // Put the counts back so the next flush retries them.
            snapshot.forEach((tenant, v) -> {
                LongAdder[] c = pending.computeIfAbsent(tenant, t -> new LongAdder[] {new LongAdder(), new LongAdder(), new LongAdder()});
                c[REQUESTS].add(v[0]);
                c[THROTTLED].add(v[1]);
                c[CREATED].add(v[2]);
            });
            log.warn("Usage metering flush failed, will retry: {}", e.toString());
        }
    }
}
