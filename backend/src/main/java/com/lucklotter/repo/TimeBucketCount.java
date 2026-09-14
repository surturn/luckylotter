package com.lucklotter.repo;

import java.time.LocalDateTime;

/**
 * One {@code (bucket, count)} row from a time-bucketed aggregate query.
 *
 * <p>{@code bucketStart} is a wall-clock timestamp, not an instant: the queries
 * truncate {@code AT TIME ZONE 'UTC'} so bucket boundaries don't move with the
 * database session's timezone, which would otherwise shuffle counts between
 * buckets depending on where the server runs.
 *
 * <p>The queries bucket by day and the service rolls those up when it needs
 * weeks. Bucketing in the database at the finest granularity any reporting
 * window asks for keeps one query per metric instead of one per metric per
 * granularity, and a day at UTC midnight rolls into exactly the Monday that
 * {@code date_trunc('week', …)} would have produced.
 */
public interface TimeBucketCount {

    LocalDateTime getBucketStart();

    long getTotal();
}
