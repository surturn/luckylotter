package com.lucklotter.repo;

import java.time.LocalDateTime;

/**
 * One {@code (week, count)} row from a weekly aggregate query.
 *
 * <p>{@code weekStart} is a wall-clock timestamp, not an instant: the query
 * truncates {@code AT TIME ZONE 'UTC'} so week boundaries don't move with the
 * database session's timezone, which would otherwise shuffle counts between
 * buckets depending on where the server runs.
 */
public interface WeeklyCount {

    LocalDateTime getWeekStart();

    long getTotal();
}
