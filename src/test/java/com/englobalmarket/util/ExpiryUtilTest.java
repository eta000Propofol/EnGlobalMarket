package com.englobalmarket.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiryUtilTest {

    private static final long DAY = 86_400_000L;

    @Test
    void expiresAtAddsDays() {
        assertEquals(1000L + 7 * DAY, ExpiryUtil.expiresAt(1000L, 7));
    }

    @Test
    void notExpiredBeforeDeadline() {
        long listed = 1_000L;
        assertFalse(ExpiryUtil.isExpired(listed, 7, listed + 7 * DAY - 1));
    }

    @Test
    void expiredAtDeadline() {
        long listed = 1_000L;
        assertTrue(ExpiryUtil.isExpired(listed, 7, listed + 7 * DAY));
        assertTrue(ExpiryUtil.isExpired(listed, 7, listed + 7 * DAY + 1));
    }

    @Test
    void zeroDaysExpiresImmediately() {
        long listed = 1_000L;
        assertTrue(ExpiryUtil.isExpired(listed, 0, listed));
        assertTrue(ExpiryUtil.isExpired(listed, 0, listed + 1));
    }

    @Test
    void remainingMillisCountsDown() {
        long listed = 1_000L;
        assertEquals(7 * DAY, ExpiryUtil.remainingMillis(listed, 7, listed));
        assertEquals(-1, ExpiryUtil.remainingMillis(listed, 7, listed + 7 * DAY + 1));
    }
}
