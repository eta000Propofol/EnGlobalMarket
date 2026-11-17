package com.englobalmarket.util;

/**
 * 时间显示工具：把剩余毫秒格式化成「x 天 x 小时 / x 小时 x 分 / x 分」。
 * 纯函数，便于单元测试。
 */
public final class TimeUtil {

    private static final long MINUTE_MILLIS = 60_000L;

    private TimeUtil() {
    }

    /**
     * 剩余时间显示：按天/小时/分逐级降级，负数与零统一显示「0 分」。
     * 不足一分钟按 0 分计（向下取整）。
     */
    public static String formatRemain(long millis) {
        long totalMinutes = Math.max(0, millis / MINUTE_MILLIS);
        long days = totalMinutes / 1440;
        long hours = (totalMinutes % 1440) / 60;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return days + " 天 " + hours + " 小时";
        }
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分";
        }
        return minutes + " 分";
    }
}
