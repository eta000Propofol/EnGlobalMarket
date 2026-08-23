package com.englobalmarket.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageTest {

    @Test
    void emptyListShowsOnePage() {
        Page p = Page.of(1, 0, 36);
        assertEquals(1, p.page());
        assertEquals(1, p.totalPages());
        assertEquals(0, p.offset());
        assertFalse(p.hasPrevious());
        assertFalse(p.hasNext());
    }

    @Test
    void pageClampedToBounds() {
        Page p = Page.of(2, 36, 36);
        assertEquals(1, p.page());
        assertEquals(1, p.totalPages());

        Page q = Page.of(1, 100, 36);
        assertEquals(3, q.totalPages());
        assertEquals(1, q.page());
        assertTrue(q.hasNext());
        assertFalse(q.hasPrevious());
    }

    @Test
    void middlePageCalculatesOffset() {
        Page p = Page.of(2, 37, 36);
        assertEquals(2, p.page());
        assertEquals(2, p.totalPages());
        assertEquals(36, p.offset());
        assertTrue(p.hasPrevious());
        assertFalse(p.hasNext());
    }

    @Test
    void lastPageClamped() {
        Page p = Page.of(99, 37, 36);
        assertEquals(2, p.page());
        assertEquals(2, p.totalPages());
        assertFalse(p.hasNext());
    }
}

