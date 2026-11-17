package com.englobalmarket.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * 价格工具：解析、税率计算与格式化。
 *
 * <p>金额一律用 {@link BigDecimal} 表示，精度固定为 2 位小数（分）。
 * 旧版本用 double 计算「售价 × (1 − 税率)」再 {@code Math.floor}，二进制误差会让部分金额
 * 少算一分钱（例如 0.29 × 100 = 28.999999999999996，floor 后变成 0.28），
 * 因此这里全部改为十进制运算，并只接受最多两位小数的输入。
 */
public final class PriceUtil {

    /** 价格上限：10 亿。 */
    public static final BigDecimal MAX_PRICE = new BigDecimal("1000000000");

    /** 金额小数位数（分）。 */
    public static final int SCALE = 2;

    /**
     * 合法写法：{@code 1} / {@code 1.5} / {@code .5} / {@code 5.} / {@code 1,000} / {@code 1,234,567.89}。
     * 千分位必须三个一组，因此 {@code 1,0,0}、{@code 1,,0} 会被拒绝；
     * 只匹配十进制数字，因此 {@code 10d}、{@code 0x1p3}、{@code NaN}、{@code 1e9} 也会被拒绝。
     */
    private static final Pattern PLAIN_NUMBER = Pattern.compile(
            "\\+?(?:\\d{1,3}(?:,\\d{3})+|\\d*)(?:\\.\\d*)?");

    private PriceUtil() {
    }

    /**
     * 解析价格字符串；非法、非正数、超过上限或超过两位小数时返回 null。
     *
     * @return 已归一到两位小数的金额（如 {@code 1000} → {@code 1000.00}）
     */
    public static BigDecimal parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty() || !PLAIN_NUMBER.matcher(s).matches()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(s.replace("+", "").replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        if (value.signum() <= 0 || value.compareTo(MAX_PRICE) > 0) {
            return null;
        }
        // 超过两位小数无法用「分」精确表示，直接拒绝，避免显示值与实际扣款不一致
        if (value.stripTrailingZeros().scale() > SCALE) {
            return null;
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 卖家实收：售价 × (1 − 税率)，向下取整到分。税率保证在 [0,1) 内（见 {@link #clampTaxRate}）。 */
    public static BigDecimal proceeds(BigDecimal price, BigDecimal taxRate) {
        if (price == null) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        BigDecimal rate = taxRate == null ? BigDecimal.ZERO : taxRate;
        BigDecimal net = price.multiply(BigDecimal.ONE.subtract(rate));
        BigDecimal floored = net.setScale(SCALE, RoundingMode.FLOOR);
        return floored.signum() < 0 ? BigDecimal.ZERO.setScale(SCALE) : floored;
    }

    /** 卖家实收（税率直接取配置里的 double）。 */
    public static BigDecimal proceeds(BigDecimal price, double taxRate) {
        return proceeds(price, BigDecimal.valueOf(taxRate));
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
    public static String format(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal trimmed = value.stripTrailingZeros();
        if (trimmed.scale() <= 0) {
            return trimmed.toBigIntegerExact().toString();
        }
        return trimmed.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 金额显示（double 入口，保留给日志等场景）。 */
    public static String format(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return format(BigDecimal.valueOf(value));
    }
}
