package com.ticketing.core.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs of this module: the outbox relay and the usage-metering flush. */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
