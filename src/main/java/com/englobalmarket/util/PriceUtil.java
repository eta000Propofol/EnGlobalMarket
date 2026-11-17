package com.englobalmarket.util;

/**
 * 价格工具：解析、税率计算与格式化。
 */
public final class PriceUtil {

    /** 价格上限：10 亿。 */
    public static final double MAX_PRICE = 1_000_000_000;

    private PriceUtil() {
    }

    /** 解析价格字符串；非法/非正数/超过上限返回 null。 */
    public static Double parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim().replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!Double.isFinite(value)) {
            return null;
        }
        if (value <= 0 || value > MAX_PRICE) {
            return null;
        }
        return value;
    }

    /** 卖家实收：售价 × (1 − 税率)，向下取整到 2 位小数。税率按原值计算（调用方保证在 [0,1)）。 */
    public static double sellerProceeds(double price, double taxRate) {
        double proceeds = price * (1.0 - taxRate);
        return Math.floor(proceeds * 100.0) / 100.0;
    }

    /**
     * 把配置里的税率钳制到 [0, 0.99]：NaN 回退 0.05，负数按 0，≥ 1 按 0.99，其余原样返回。
     */
    public static double clampTaxRate(double raw) {
        if (Double.isNaN(raw)) {
            return 0.05;
        }
        if (raw < 0) {
            return 0;
        }
        if (raw >= 1) {
            return 0.99;
        }
        return raw;
    }

    /** 金额显示：整数不带小数，否则保留 2 位。 */
    public static String format(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.2f", value);
    }
}
