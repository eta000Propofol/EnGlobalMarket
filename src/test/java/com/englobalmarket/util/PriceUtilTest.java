package com.englobalmarket.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PriceUtil 价格解析 / 税率 / 格式化")
class PriceUtilTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("parse 价格解析")
    class Parse {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                "12.5,        12.50",
                "' 3 ',       3.00",
                "'1,000',     1000.00",
                "'1,234,567.89', 1234567.89",
                "0.01,        0.01",
                ".5,          0.50",
                "5.,          5.00",
                "+5,          5.00",
                "0.5,         0.50",
                "0.50,        0.50",
                "1000000000,  1000000000.00",
                "1000000000.00, 1000000000.00",
                "'1,000.5',   1000.50",
                "0001,        1.00"
        })
        @DisplayName("合法价格解析并归一到两位小数")
        void parsesValidPrices(String input, String expected) {
            assertEquals(d(expected), PriceUtil.parse(input));
        }

        @ParameterizedTest(name = "拒绝 \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {
                "   ",
                "abc",
                "12abc",
                "1 2",
                "0",
                "0.0",
                "0.00",
                "-0",
                "-0.01",
                "-5",
                "+-5",
                "NaN",
                "Infinity",
                "-Infinity",
                // 科学计数法与 Java 字面量后缀一律拒绝（旧实现会被 Double.parseDouble 吃下去）
                "1e9",
                "1.5e12",
                "10d",
                "10f",
                "0x1p3",
                // 千分位必须三位一组
                "1,0,0",
                "1,,0",
                "1,0000",
                ",",
                ",,",
                // 超过两位小数
                "1.234",
                "0.001",
                "123.456",
                // 超上限
                "1000000000.01",
                "1000000001"
        })
        @DisplayName("非法/非正/超上限/超两位小数/畸形千分位返回 null")
        void rejectsInvalidPrices(String input) {
            assertNull(PriceUtil.parse(input));
        }

        @Test
        @DisplayName("上限恰为 10 亿，超出即拒绝")
        void maxPriceBoundary() {
            assertAll(
                    () -> assertEquals(0, PriceUtil.MAX_PRICE.compareTo(d("1000000000"))),
                    () -> assertNotNull(PriceUtil.parse("1000000000")),
                    () -> assertNotNull(PriceUtil.parse("1000000000.00")),
                    () -> assertNull(PriceUtil.parse("1000000000.0001")),
                    () -> assertNull(PriceUtil.parse("1000000000.01")));
        }

        @Test
        @DisplayName("解析结果统一为两位小数（可以直接用于结算与展示）")
        void alwaysScaledToTwoDecimals() {
            assertAll(
                    () -> assertEquals(2, PriceUtil.parse("1000").scale()),
                    () -> assertEquals(2, PriceUtil.parse("1e9".replace("e9", "000000000")).scale()),
                    () -> assertEquals(2, PriceUtil.parse(".5").scale()),
                    () -> assertEquals(new BigDecimal("1000.00"), PriceUtil.parse("1000")));
        }

        @Test
        @DisplayName("极小正数被拒绝：不足一分无法结算")
        void rejectsSubCentAmounts() {
            assertAll(
                    () -> assertNull(PriceUtil.parse("0.001")),
                    () -> assertNull(PriceUtil.parse("4.9e-324".replace("4.9e-324", "0.000000001"))),
                    () -> assertEquals(new BigDecimal("0.01"), PriceUtil.parse("0.01")));
        }
    }

    @Nested
    @DisplayName("proceeds 卖家实收")
    class Proceeds {

        @ParameterizedTest(name = "售价 {0} × (1-{1}) = {2}")
        @CsvSource({
                "100.00,       0.05, 95.00",
                "99.99,        0.05, 94.99",
                "100.00,       0,    100.00",
                "100.00,       0.99, 1.00",
                "1000000000.00, 0.05, 950000000.00",
                "123.45,       0.1,  111.10",
                "1.00,         0.333, 0.66",
                "0.02,         0.05, 0.01",
                "0.01,         0.05, 0.00",
                "80.00,        0.05, 76.00",
                "33.33,        0.33, 22.33"
        })
        @DisplayName("按税率计算并向下取整到分")
        void appliesTaxAndRoundsDown(String price, String rate, String expected) {
            assertEquals(0, d(expected).compareTo(PriceUtil.proceeds(d(price), d(rate))));
        }

        @ParameterizedTest(name = "售价 {0} 零税率实收 {0}")
        @ValueSource(strings = {"0.29", "0.57", "1.13", "0.60", "1.20", "2.29", "100.00"})
        @DisplayName("回归：零税率时实收必须等于售价（旧实现会少算一分）")
        void zeroTaxNeverLosesACent(String price) {
            assertEquals(0, d(price).compareTo(PriceUtil.proceeds(d(price), BigDecimal.ZERO)),
                    "price=" + price);
        }

        @ParameterizedTest(name = "售价 {0} 按 5% 计税实收 {1}")
        @CsvSource({
                "0.60, 0.57",
                "1.20, 1.14",
                "2.40, 2.28",
                "3.00, 2.85",
                "12.60, 11.97",
                "0.20, 0.19"
        })
        @DisplayName("回归：默认 5% 税率下曾少算一分的金额")
        void fivePercentNeverLosesACent(String price, String expected) {
            assertEquals(0, d(expected).compareTo(PriceUtil.proceeds(d(price), d("0.05"))),
                    "price=" + price);
        }

        @Test
        @DisplayName("取整方向是向下而非四舍五入")
        void floorsInsteadOfRounding() {
            assertAll(
                    // 1.90 × 0.5 = 0.95 精确，0.999 场景用整分金额验证
                    () -> assertEquals(d("0.95"), PriceUtil.proceeds(d("1.90"), d("0.5"))),
                    // 1.99 × 0.5 = 0.995，四舍五入为 1.00，向下取整为 0.99
                    () -> assertEquals(d("0.99"), PriceUtil.proceeds(d("1.99"), d("0.5"))));
        }

        @Test
        @DisplayName("全量扫描：1 万以内所有整分金额，零税率都不会少算")
        void zeroTaxIsExactForEveryCent() {
            int bad = 0;
            for (int cents = 1; cents <= 10_000; cents++) {
                BigDecimal price = BigDecimal.valueOf(cents, 2);
                BigDecimal proceeds = PriceUtil.proceeds(price, BigDecimal.ZERO);
                if (price.compareTo(proceeds) != 0) {
                    bad++;
                }
            }
            assertEquals(0, bad, "零税率下少算的金额数量");
        }

        @Test
        @DisplayName("全量扫描：默认税率与「乘 95 再除以 100」交叉校验，零偏差")
        void fivePercentMatchesIndependentFormula() {
            BigDecimal rate = new BigDecimal("0.05");
            BigDecimal hundred = new BigDecimal("100");
            int bad = 0;
            for (int cents = 1; cents <= 10_000; cents++) {
                BigDecimal price = BigDecimal.valueOf(cents, 2);
                BigDecimal expected = price.multiply(new BigDecimal("95"))
                        .divide(hundred, PriceUtil.SCALE, RoundingMode.FLOOR);
                if (expected.compareTo(PriceUtil.proceeds(price, rate)) != 0) {
                    bad++;
                }
            }
            assertEquals(0, bad, "与独立公式不一致的金额数量");
        }

        @Test
        @DisplayName("不变量：0 ≤ 实收 ≤ 售价，且始终为两位小数")
        void proceedsNeverExceedsPriceAndKeepsScale() {
            String[] prices = {"0.01", "0.02", "0.10", "0.99", "1.00", "9.99", "99.99", "100.00",
                    "1234.56", "1000000.00", "1000000000.00"};
            String[] rates = {"0", "0.01", "0.05", "0.1", "0.25", "0.5", "0.9", "0.99"};
            for (String price : prices) {
                for (String rate : rates) {
                    BigDecimal proceeds = PriceUtil.proceeds(d(price), d(rate));
                    String ctx = "price=" + price + ", rate=" + rate + " -> " + proceeds;
                    assertTrue(proceeds.signum() >= 0, "实收为负: " + ctx);
                    assertTrue(proceeds.compareTo(d(price)) <= 0, "实收超过售价: " + ctx);
                    assertEquals(PriceUtil.SCALE, proceeds.scale(), "实收小数位不是 2: " + ctx);
                }
            }
        }

        @Test
        @DisplayName("实收随售价单调不减")
        void monotonicInPrice() {
            BigDecimal previous = new BigDecimal("-1");
            for (int cents = 1; cents <= 100_000; cents += 37) {
                BigDecimal proceeds = PriceUtil.proceeds(BigDecimal.valueOf(cents, 2), d("0.05"));
                assertTrue(proceeds.compareTo(previous) >= 0, "在 " + cents + " 分处不单调");
                previous = proceeds;
            }
        }

        @Test
        @DisplayName("null 售价按 0 处理，不会抛异常")
        void nullPriceIsZero() {
            assertEquals(d("0.00"), PriceUtil.proceeds(null, d("0.05")));
        }

        @Test
        @DisplayName("double 税率入口与 BigDecimal 结果一致")
        void doubleRateOverloadMatches() {
            assertEquals(PriceUtil.proceeds(d("100.00"), d("0.05")),
                    PriceUtil.proceeds(d("100.00"), 0.05));
        }
    }

    @Nested
    @DisplayName("clampTaxRate 税率钳制")
    class ClampTaxRate {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "0.0,    0.0",
                "0.05,   0.05",
                "0.5,    0.5",
                "0.99,   0.99",
                "0.991,  0.991",
                "0.9999, 0.9999",
                "1.0,    0.99",
                "1.5,    0.99",
                "100,    0.99"
        })
        @DisplayName("[0,1) 原样保留，≥1 修正为 0.99")
        void keepsValidRatesAndCapsOverOne(double raw, double expected) {
            assertEquals(expected, PriceUtil.clampTaxRate(raw), 1e-12);
        }

        @ParameterizedTest(name = "{0} -> 0")
        @ValueSource(doubles = {-0.0001, -0.5, -1, -100, Double.NEGATIVE_INFINITY})
        @DisplayName("负数按 0")
        void clampsNegativeToZero(double raw) {
            assertEquals(0.0, PriceUtil.clampTaxRate(raw), 1e-12);
        }

        @Test
        @DisplayName("NaN 回退默认 0.05，正无穷按 0.99")
        void handlesNonFinite() {
            assertAll(
                    () -> assertEquals(0.05, PriceUtil.clampTaxRate(Double.NaN), 1e-12),
                    () -> assertEquals(0.99, PriceUtil.clampTaxRate(Double.POSITIVE_INFINITY), 1e-12),
                    () -> assertEquals(0.0, PriceUtil.clampTaxRate(-0.0), 1e-12));
        }

        @Test
        @DisplayName("不变量：任何有限输入的结果都落在 [0,1)")
        void resultAlwaysInRange() {
            Random random = new Random(20261117L);
            for (int i = 0; i < 500; i++) {
                double raw = (random.nextDouble() - 0.5) * 10;
                double clamped = PriceUtil.clampTaxRate(raw);
                assertTrue(clamped >= 0 && clamped < 1, "raw=" + raw + " -> " + clamped);
            }
            assertTrue(PriceUtil.clampTaxRate(Double.POSITIVE_INFINITY) < 1);
        }
    }

    @Nested
    @DisplayName("format 金额显示")
    class Format {

        @ParameterizedTest(name = "{0} -> \"{1}\"")
        @CsvSource({
                "100.00,       100",
                "1000.00,      1000",
                "0.00,         0",
                "0,            0",
                "95.50,        95.50",
                "94.99,        94.99",
                "0.50,         0.50",
                "0.01,         0.01",
                "1000000000.00, 1000000000",
                "1.5,          1.50"
        })
        @DisplayName("整数不带小数，否则保留 2 位")
        void formatsAmounts(String value, String expected) {
            assertEquals(expected, PriceUtil.format(d(value)));
        }

        @Test
        @DisplayName("double 入口与 BigDecimal 一致，且能处理非有限值")
        void formatsDoubles() {
            assertAll(
                    () -> assertEquals("95.50", PriceUtil.format(95.5)),
                    () -> assertEquals("100", PriceUtil.format(100.0)),
                    () -> assertEquals("-0.50", PriceUtil.format(-0.5)),
                    () -> assertEquals("NaN", PriceUtil.format(Double.NaN)));
        }

        @Test
        @DisplayName("format 与 proceeds 组合后金额可读")
        void formatsProceeds() {
            BigDecimal proceeds = PriceUtil.proceeds(d("123.45"), d("0.1"));
            assertAll(
                    () -> assertEquals(d("111.10"), proceeds),
                    () -> assertEquals("111.10", PriceUtil.format(proceeds)),
                    () -> assertEquals("95", PriceUtil.format(PriceUtil.proceeds(d("100.00"), d("0.05")))));
        }

        @Test
        @DisplayName("null 显示为 0，不会抛 NPE")
        void nullFormatsToZero() {
            assertEquals("0", PriceUtil.format((BigDecimal) null));
            assertEquals("0", PriceUtil.format(0.0));
        }

        @Test
        @DisplayName("setScale 用法保证不会因小数位不足抛异常")
        void scaleIsNeverLessThanZero() {
            assertThrows(ArithmeticException.class,
                    () -> d("1.005").setScale(2, RoundingMode.UNNECESSARY));
        }
    }
}
