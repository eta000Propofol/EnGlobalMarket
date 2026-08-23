package com.englobalmarket.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PriceUtilTest {

    @Test
    void parsesValidPrices() {
        assertEquals(12.5, PriceUtil.parse("12.5"));
        assertEquals(3.0, PriceUtil.parse(" 3 "));
        assertEquals(1000.0, PriceUtil.parse("1,000"));
        assertEquals(1_000_000_000.0, PriceUtil.parse("1000000000"));
    }

    @Test
    void rejectsInvalidPrices() {
        assertNull(PriceUtil.parse(null));
        assertNull(PriceUtil.parse(""));
        assertNull(PriceUtil.parse("  "));
        assertNull(PriceUtil.parse("abc"));
        assertNull(PriceUtil.parse("12abc"));
        assertNull(PriceUtil.parse("0"));
        assertNull(PriceUtil.parse("-5"));
        assertNull(PriceUtil.parse("1.5e12"));
        assertNull(PriceUtil.parse("NaN"));
        assertNull(PriceUtil.parse("Infinity"));
    }

    @Test
    void sellerProceedsAppliesTaxAndRoundsDown() {
        assertEquals(95.0, PriceUtil.sellerProceeds(100, 0.05));
        assertEquals(94.99, PriceUtil.sellerProceeds(99.99, 0.05));
        assertEquals(100.0, PriceUtil.sellerProceeds(100, 0));
        assertEquals(0.0, PriceUtil.sellerProceeds(100, 1));
        assertEquals(76.0, PriceUtil.sellerProceeds(80, 0.05));
    }

    @Test
    void formatRemovesTrailingZeros() {
        assertEquals("100", PriceUtil.format(100.0));
        assertEquals("94.99", PriceUtil.format(94.99));
        assertEquals("0", PriceUtil.format(0.0));
    }
}
