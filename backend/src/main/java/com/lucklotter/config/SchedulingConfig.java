package com.lucklotter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the daily cadence-break scan (FR-3).
 *
 * <p>Single-instance assumption: with more than one replica running, every
 * replica fires the job. That is survivable — the partial unique index means
 * concurrent runs still produce one flag per customer (FR-8) — but it would
 * duplicate the work, so horizontal scaling needs a lock or a scheduler.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
