package com.englobalmarket.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExpiryUtil 过期时间计算")
class ExpiryUtilTest {

    private static final long DAY = 86_400_000L;

    @Nested
    @DisplayName("expiresAt 过期时刻")
    class ExpiresAt {

        @ParameterizedTest(name = "上架 {0} + {1} 天 = {2}")
        @CsvSource({
                "0,     0,   0",
                "1000,  1,   86401000",
                "1000,  7,   604801000",
                "0,     365, 31536000000",
                "12345, 30,  2592012345"
        })
        @DisplayName("上架时间 + 天数 × 一天的毫秒数")
        void addsDaysExactly(long listedAt, int days, long expected) {
            assertEquals(expected, ExpiryUtil.expiresAt(listedAt, days));
        }

        @Test
        @DisplayName("0 天表示立即到期，负数天数为防御性行为（早于上架时间）")
        void zeroAndNegativeDays() {
            assertAll(
                    () -> assertEquals(1_000L, ExpiryUtil.expiresAt(1_000L, 0)),
                    () -> assertEquals(1_000L - DAY, ExpiryUtil.expiresAt(1_000L, -1)));
        }

        @Test
        @DisplayName("超大天数不溢出（long 运算）")
        void largeDayCountDoesNotOverflow() {
            long expectedMax = (long) Integer.MAX_VALUE * DAY;
            assertAll(
                    () -> assertEquals(expectedMax, ExpiryUtil.expiresAt(0L, Integer.MAX_VALUE)),
                    () -> assertEquals(100_000L * DAY, ExpiryUtil.expiresAt(0L, 100_000)),
                    () -> assertTrue(expectedMax > 0));
        }

        @Test
        @DisplayName("天数越大过期时刻越晚（严格单调）")
        void monotonicInDays() {
            long previous = Long.MIN_VALUE;
            for (int days = 0; days <= 400; days++) {
                long at = ExpiryUtil.expiresAt(1_700_000_000_000L, days);
                assertTrue(at > previous, "days=" + days + " 未递增");
                previous = at;
            }
        }
    }

    @Nested
    @DisplayName("isExpired 过期判定")
    class IsExpired {

        @Test
        @DisplayName("到期前一毫秒未过期，到期时刻起已过期")
        void boundaryIsInclusive() {
            long listed = 1_000L;
            long deadline = listed + 7 * DAY;
            assertAll(
                    () -> assertFalse(ExpiryUtil.isExpired(listed, 7, deadline - 1)),
                    () -> assertTrue(ExpiryUtil.isExpired(listed, 7, deadline)),
                    () -> assertTrue(ExpiryUtil.isExpired(listed, 7, deadline + 1)));
        }

        @Test
        @DisplayName("0 天：上架即过期，上架前不算")
        void zeroDaysExpiresImmediately() {
            long listed = 1_000L;
            assertAll(
                    () -> assertFalse(ExpiryUtil.isExpired(listed, 0, listed - 1)),
                    () -> assertTrue(ExpiryUtil.isExpired(listed, 0, listed)),
                    () -> assertTrue(ExpiryUtil.isExpired(listed, 0, listed + 1)));
        }

        @Test
        @DisplayName("与 expiresAt 判定一致（随机校验）")
        void agreesWithExpiresAt() {
            Random random = new Random(20261117L);
            for (int i = 0; i < 500; i++) {
                long listed = random.nextLong(0, 4_102_444_800_000L);
                int days = random.nextInt(366);
                long now = listed + random.nextLong(-DAY, 400L * DAY);
                long deadline = ExpiryUtil.expiresAt(listed, days);
                assertEquals(now >= deadline, ExpiryUtil.isExpired(listed, days, now),
                        "listed=" + listed + ", days=" + days + ", now=" + now);
            }
        }
    }

    @Nested
    @DisplayName("remainingMillis 剩余时间")
    class RemainingMillis {

        @Test
        @DisplayName("上架瞬间剩余整期，到期为 0，之后为负")
        void countsDown() {
            long listed = 1_000L;
            assertAll(
                    () -> assertEquals(7 * DAY, ExpiryUtil.remainingMillis(listed, 7, listed)),
                    () -> assertEquals(1L, ExpiryUtil.remainingMillis(listed, 7, listed + 7 * DAY - 1)),
                    () -> assertEquals(0L, ExpiryUtil.remainingMillis(listed, 7, listed + 7 * DAY)),
                    () -> assertEquals(-1L, ExpiryUtil.remainingMillis(listed, 7, listed + 7 * DAY + 1)));
        }

        @Test
        @DisplayName("恒等于 过期时刻 − 当前时刻")
        void equalsDeadlineMinusNow() {
            Random random = new Random(42L);
            for (int i = 0; i < 500; i++) {
                long listed = random.nextLong(0, 4_102_444_800_000L);
                int days = random.nextInt(366);
                long now = listed + random.nextLong(-DAY, 400L * DAY);
                assertEquals(ExpiryUtil.expiresAt(listed, days) - now,
                        ExpiryUtil.remainingMillis(listed, days, now));
            }
        }

        @Test
        @DisplayName("与 isExpired 符号一致：正=未过期，≤0=已过期")
        void signMatchesIsExpired() {
            long listed = 5_000_000L;
            for (int days = 0; days <= 30; days++) {
                for (long offset = -2; offset <= 31 * DAY; offset += 3_600_000L) {
                    long now = listed + offset;
                    long remain = ExpiryUtil.remainingMillis(listed, days, now);
                    assertEquals(remain <= 0, ExpiryUtil.isExpired(listed, days, now),
                            "days=" + days + ", offset=" + offset);
                }
            }
        }
    }
}
