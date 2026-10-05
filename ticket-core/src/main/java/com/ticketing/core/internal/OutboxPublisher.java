package com.ticketing.core.internal;

import com.ticketing.core.internal.OutboxStore.Backlog;
import com.ticketing.core.internal.OutboxStore.OutboxEvent;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Projects outbox events into MongoDB.
 * <ul>
 *   <li>{@link #publishNow} right after the workflow transaction commits (normal path: the user sees
 *       the history immediately);</li>
 *   <li>{@link #relayPending} on a schedule, for anything that failed (MongoDB down, document missing):
 *       retried until it succeeds, so history is eventually consistent but never lost.</li>
 * </ul>
 * Failures are counted ({@code ticketing.outbox.publish.failures}) and the backlog is exported as gauges,
 * so a projection problem raises an alert instead of slipping silently.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String RELAY_ACTOR = "system:outbox-relay";

    private final OutboxStore outbox;
    private final DocumentStore documents;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retention;
    private final Counter published;
    private final Counter failures;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    OutboxPublisher(OutboxStore outbox, DocumentStore documents, MeterRegistry meters,
                    @Value("${ticketing.outbox.batch-size:100}") int batchSize,
                    @Value("${ticketing.outbox.max-attempts:50}") int maxAttempts,
                    @Value("${ticketing.outbox.retention:P30D}") Duration retention) {
        this.retention = retention;
        this.outbox = outbox;
        this.documents = documents;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.published = Counter.builder("ticketing.outbox.published").description("Outbox events projected to MongoDB").register(meters);
        this.failures = Counter.builder("ticketing.outbox.publish.failures").description("Failed attempts to project an outbox event").register(meters);
        Gauge.builder("ticketing.outbox.pending", pending, AtomicLong::get).description("Events waiting to be projected").register(meters);
        Gauge.builder("ticketing.outbox.dead", dead, AtomicLong::get).description("Events that exhausted their retries").register(meters);
        Gauge.builder("ticketing.outbox.oldest.pending.age", oldestPendingAgeSeconds, AtomicLong::get)
                .baseUnit("seconds").description("Age of the oldest pending event").register(meters);
    }

    /** Called by TicketService after the workflow transaction committed. Never throws: failures are retried by the relay. */
    public boolean publishNow(OutboxEvent event) {
        return publish(event);
    }

    private boolean publish(OutboxEvent event) {
        try {
            documents.apply(event);
            outbox.markPublished(event.id());
            published.increment();
            return true;
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Outbox event {} ({} for ticket {}) not projected to MongoDB yet, will retry: {}",
                    event.id(), event.type(), event.ticketId(), e.toString());
            try {
                outbox.markFailed(event.id(), e.toString());
            } catch (RuntimeException markFailure) {
                log.warn("Could not record the failure of outbox event {}", event.id(), markFailure);
            }
            return false;
        }
    }

    /** Retries everything still pending, oldest first. One replica: no row locking needed (see docs). */
    /** Deletes published events past the retention window (default 30 days), hourly. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purge() {
        try {
            int deleted = outbox.purgePublished(Instant.now().minus(retention));
            if (deleted > 0) {
                log.info("Purged {} published outbox events older than {}", deleted, retention);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox purge failed: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${ticketing.outbox.relay-interval:PT5S}",
               initialDelayString = "${ticketing.outbox.relay-initial-delay:PT10S}")
    public void relayPending() {
        try {
            for (OutboxEvent event : outbox.pending(batchSize, maxAttempts)) {
                // The relay has no user request: bind the event's tenant so the same tenant-scoped
                // MongoDB access path (DocumentStore) is used, with no roles.
                Optional<TenantContext> previous = TenantContextHolder.current();
                TenantContextHolder.set(new TenantContext(RELAY_ACTOR, event.tenantId(), Set.of()));
                try {
                    publish(event);
                } finally {
                    previous.ifPresentOrElse(TenantContextHolder::set, TenantContextHolder::clear);
                }
            }
            Backlog backlog = outbox.backlog(maxAttempts);
            pending.set(backlog.pending());
            dead.set(backlog.dead());
            oldestPendingAgeSeconds.set(backlog.oldestPending() == null ? 0
                    : Duration.between(backlog.oldestPending(), Instant.now()).toSeconds());
            if (backlog.dead() > 0) {
                log.error("{} outbox events exhausted {} attempts and need manual action (ticket_outbox.last_error)",
                        backlog.dead(), maxAttempts);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed: {}", e.toString());
        }
    }
}
