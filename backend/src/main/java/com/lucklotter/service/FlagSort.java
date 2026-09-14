package com.lucklotter.service;

/**
 * The orders the flagged-customer list can be read in (§5).
 *
 * <p>Priority is the default because the screen's question is "who should I act
 * on today", and that is not the same as "who went quiet most recently". The
 * others are there for the admin who wants to check the reasoning: the same
 * customers, ranked on one component instead of the combination.
 *
 * <p>The enum exists so an unknown value is a 400 rather than a silently
 * ignored parameter — a screen that quietly falls back to a different order
 * than the one asked for is worse than one that refuses.
 *
 * <p>Wire values are the constant names, matching the {@code status} filter
 * beside them: {@code ?sort=PRIORITY}.
 */
public enum FlagSort {

    /** Risk, value and intervention history combined. */
    PRIORITY,

    /** How far past their own threshold. */
    OVERDUE,

    /** Lifetime spend. */
    VALUE,

    /** Most recently flagged. */
    RECENT
}
