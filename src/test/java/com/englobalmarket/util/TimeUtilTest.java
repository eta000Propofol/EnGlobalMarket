package com.englobalmarket.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TimeUtil 剩余时间显示")
class TimeUtilTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    @ParameterizedTest(name = "{0} ms -> \"{1}\"")
    @CsvSource({
            // 不足 1 分钟向下取整
            "0,        0 分",
            "1,        0 分",
            "59999,    0 分",
            "60000,    1 分",
            "119999,   1 分",
            "120000,   2 分",
            // 分钟级
            "3540000,  59 分",
            // 小时级
            "3600000,  1 小时 0 分",
            "3660000,  1 小时 1 分",
            "86340000, 23 小时 59 分",
            // 天级
            "86400000, 1 天 0 小时",
            "90000000, 1 天 1 小时",
            "176400000, 2 天 1 小时",
            "604800000, 7 天 0 小时",
            "31536000000, 365 天 0 小时"
    })
    @DisplayName("按天 / 小时 / 分逐级降级")
    void formatsRemaining(long millis, String expected) {
        assertEquals(expected, TimeUtil.formatRemain(millis));
    }

    @Nested
    @DisplayName("边界与异常输入")
    class Edges {

        @Test
        @DisplayName("0 与负数统一显示 0 分")
        void nonPositiveShowsZero() {
            assertAll(
                    () -> assertEquals("0 分", TimeUtil.formatRemain(0)),
                    () -> assertEquals("0 分", TimeUtil.formatRemain(-1)),
                    () -> assertEquals("0 分", TimeUtil.formatRemain(-DAY)),
                    () -> assertEquals("0 分", TimeUtil.formatRemain(Long.MIN_VALUE)));
        }

        @Test
        @DisplayName("极大值不抛异常且仍是天级文案")
        void largeValueDoesNotThrow() {
            String text = TimeUtil.formatRemain(Long.MAX_VALUE);
            assertAll(
                    () -> assertTrue(text.endsWith(" 小时"), text),
                    () -> assertTrue(text.contains(" 天 "), text));
        }

        @Test
        @DisplayName("整点边界不产生 60 分 / 24 小时")
        void neverShowsSixtyMinutesOrTwentyFourHours() {
            assertAll(
                    () -> assertEquals("1 小时 0 分", TimeUtil.formatRemain(HOUR)),
                    () -> assertEquals("1 天 0 小时", TimeUtil.formatRemain(DAY)),
                    () -> assertEquals("2 天 0 小时", TimeUtil.formatRemain(2 * DAY)));
        }

        @Test
        @DisplayName("同一时刻的更新不改变天数/小时数（只影响更低一级）")
        void daysAndHoursStableWithinHour() {
            String base = TimeUtil.formatRemain(3 * DAY + 5 * HOUR);
            assertEquals("3 天 5 小时", base);
            assertEquals(base, TimeUtil.formatRemain(3 * DAY + 5 * HOUR + 59 * MINUTE));
        }
    }
}
