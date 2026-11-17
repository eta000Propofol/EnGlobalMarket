package com.englobalmarket.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PriceUtil 价格解析 / 税率 / 格式化")
class PriceUtilTest {

    @Nested
    @DisplayName("parse 价格解析")
    class Parse {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                "12.5,        12.5",
                "' 3 ',       3.0",
                "'1,000',     1000.0",
                "'1,234,567.89', 1234567.89",
                "0.01,        0.01",
                ".5,          0.5",
                "5.,          5.0",
                "+5,          5.0",
                "1e9,         1000000000.0",
                "0.1,         0.1",
                "1000000000,  1000000000.0",
                "1000000000.0, 1000000000.0",
                "1.7976931348623157E8, 179769313.48623157"
        })
        @DisplayName("合法价格原样解析")
        void parsesValidPrices(String input, double expected) {
            assertEquals(expected, PriceUtil.parse(input));
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
                "-0",
                "-0.01",
                "-5",
                "+-5",
                "NaN",
                "Infinity",
                "-Infinity",
                "1e400",
                "1.5e12",
                "1000000000.01",
                "1000000001"
        })
        @DisplayName("非法/非正/超上限价格返回 null")
        void rejectsInvalidPrices(String input) {
            assertNull(PriceUtil.parse(input));
        }

        @Test
        @DisplayName("上限恰为 10 亿，超出即拒绝")
        void maxPriceBoundary() {
            assertAll(
                    () -> assertEquals(1_000_000_000d, PriceUtil.MAX_PRICE),
                    () -> assertNotNull(PriceUtil.parse("1000000000")),
                    () -> assertNull(PriceUtil.parse("1000000000.0001")),
                    () -> assertNull(PriceUtil.parse("1000000000.01")));
        }

        @Test
        @DisplayName("逗号被无条件删除（已知宽松行为）")
        void commasStrippedAnywhere() {
            assertAll(
                    () -> assertEquals(1_000d, PriceUtil.parse("1,000")),
                    () -> assertEquals(10d, PriceUtil.parse("1,,0")),
                    () -> assertEquals(100d, PriceUtil.parse("1,0,0")),
                    () -> assertNull(PriceUtil.parse(",,")));
        }

        @Test
        @DisplayName("极小正数可解析，全 0 视为非法")
        void tinyPositiveValues() {
            assertAll(
                    () -> assertEquals(Double.MIN_VALUE, PriceUtil.parse("4.9e-324")),
                    () -> assertEquals(1e-9, PriceUtil.parse("0.000000001")),
                    () -> assertNull(PriceUtil.parse("0.0000")));
        }
    }

    @Nested
    @DisplayName("sellerProceeds 卖家实收")
    class SellerProceeds {

        @ParameterizedTest(name = "售价 {0} × (1-{1}) = {2}")
        @CsvSource({
                "100,    0.05, 95.0",
                "99.99,  0.05, 94.99",
                "100,    0,    100.0",
                "100,    0.99, 1.0",
                "1000000000, 0.05, 950000000.0",
                "123.456, 0.1, 111.11",
                "1,      0.333, 0.66",
                "0.02,   0.05, 0.01",
                "0.01,   0.05, 0.0",
                "80,     0.05, 76.0",
                "33.33,  0.33, 22.33"
        })
        @DisplayName("按税率计算并向下取整到分")
        void appliesTaxAndRoundsDown(String price, String rate, double expected) {
            assertEquals(expected,
                    PriceUtil.sellerProceeds(Double.parseDouble(price), Double.parseDouble(rate)),
                    1e-9);
        }

        @Test
        @DisplayName("取整方向是向下而非四舍五入")
        void floorsInsteadOfRounding() {
            assertAll(
                    // 0.019 若四舍五入应得 0.02，向下取整得 0.01
                    () -> assertEquals(0.01, PriceUtil.sellerProceeds(0.019, 0), 1e-9),
                    // 0.999 × 0.5 = 0.4995，四舍五入为 0.50，向下取整为 0.49
                    () -> assertEquals(0.49, PriceUtil.sellerProceeds(0.999, 0.5), 1e-9));
        }

        @Test
        @DisplayName("零税率时实收等于售价（保留 2 位）")
        void zeroTaxKeepsPrice() {
            for (double price : new double[]{0.01, 0.1, 1, 9.99, 100, 1234.56, 1_000_000_000}) {
                assertEquals(Math.floor(price * 100.0) / 100.0,
                        PriceUtil.sellerProceeds(price, 0), 1e-9, "price=" + price);
            }
        }

        @Test
        @DisplayName("不变量：0 ≤ 实收 ≤ 售价，且始终为整分")
        void proceedsNeverExceedsPriceAndIsWholeCents() {
            double[] prices = {0.01, 0.02, 0.1, 0.99, 1, 9.99, 99.99, 100, 1234.56, 1e6, 1e9};
            double[] rates = {0, 0.01, 0.05, 0.1, 0.25, 0.5, 0.9, 0.99};
            for (double price : prices) {
                for (double rate : rates) {
                    double proceeds = PriceUtil.sellerProceeds(price, rate);
                    String ctx = "price=" + price + ", rate=" + rate + " -> " + proceeds;
                    assertTrue(proceeds >= 0, "实收为负: " + ctx);
                    assertTrue(proceeds <= price, "实收超过售价: " + ctx);
                    double cents = proceeds * 100.0;
                    double tolerance = Math.max(1e-6, Math.abs(cents) * 1e-14);
                    assertEquals(Math.rint(cents), cents, tolerance, "实收不是整分: " + ctx);
                }
            }
        }

        @Test
        @DisplayName("实收随售价单调不减")
        void monotonicInPrice() {
            double previous = -1;
            for (double price = 0.01; price <= 1000; price += 0.37) {
                double proceeds = PriceUtil.sellerProceeds(price, 0.05);
                assertTrue(proceeds >= previous, "在 price=" + price + " 处不单调");
                previous = proceeds;
            }
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
                "100.0,        100",
                "1000.0,       1000",
                "0.0,          0",
                "95.5,         95.50",
                "94.99,        94.99",
                "0.5,          0.50",
                "0.01,         0.01",
                "1000000000.0, 1000000000",
                "-0.5,         -0.50"
        })
        @DisplayName("整数不带小数，否则保留 2 位")
        void formatsAmounts(double value, String expected) {
            assertEquals(expected, PriceUtil.format(value));
        }

        @Test
        @DisplayName("format 与 sellerProceeds 组合后金额可读")
        void formatsProceeds() {
            double proceeds = PriceUtil.sellerProceeds(123.456, 0.1);
            assertAll(
                    () -> assertEquals(111.11, proceeds, 1e-9),
                    () -> assertEquals("111.11", PriceUtil.format(proceeds)),
                    () -> assertEquals("95", PriceUtil.format(PriceUtil.sellerProceeds(100, 0.05))));
        }
    }
}
