package com.englobalmarket.util;

/**
 * 过期时间工具（纯函数，便于单元测试）。
 */
public final class ExpiryUtil {

    private static final long DAY_MILLIS = 86_400_000L;

    private ExpiryUtil() {
    }

    /** 上架时间 + 有效期天数 = 过期时刻。 */
    public static long expiresAt(long listedAt, int expireDays) {
        return listedAt + (long) expireDays * DAY_MILLIS;
    }

    /** 是否已过期。 */
    public static boolean isExpired(long listedAt, int expireDays, long now) {
        return now >= expiresAt(listedAt, expireDays);
    }

    /** 剩余毫秒数（可为负）。 */
    public static long remainingMillis(long listedAt, int expireDays, long now) {
        return expiresAt(listedAt, expireDays) - now;
    }
}
