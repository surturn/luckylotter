package com.lucklotter.repo;

/**
 * One {@code (bucket, count)} row from a grouped aggregate query.
 *
 * <p>The bucket name is produced by the query rather than derived in Java so the
 * grouping happens in the database, instead of shipping every active flag to the
 * JVM to be sorted into three piles.
 */
public interface BucketCount {

    String getBucket();

    long getTotal();
}
