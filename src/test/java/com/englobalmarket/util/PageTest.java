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

@DisplayName("Page 分页计算")
class PageTest {

    @Nested
    @DisplayName("页数与偏移")
    class Paging {

        @ParameterizedTest(name = "count={1}, size={2} 请求第 {0} 页")
        @CsvSource({
                // page, totalCount, pageSize, expectedPage, expectedTotalPages, expectedOffset
                "1, 0,    36, 1, 1,  0",
                "1, 1,    36, 1, 1,  0",
                "1, 36,   36, 1, 1,  0",
                "1, 37,   36, 1, 2,  0",
                "2, 37,   36, 2, 2,  36",
                "1, 72,   36, 1, 2,  0",
                "1, 73,   36, 1, 3,  0",
                "3, 73,   36, 3, 3,  72",
                "99, 73,  36, 3, 3,  72",
                "0, 73,   36, 1, 3,  0",
                "-5, 73,  36, 1, 3,  0",
                "1, 10,   0,  1, 10, 0",
                "3, 10,   0,  3, 10, 2",
                "1, 10,   -3, 1, 10, 0",
                "1, 10,   1,  1, 10, 0",
                "10, 10,  1,  10, 10, 9"
        })
        @DisplayName("边界页、越界页与非法页大小")
        void computesPageAndOffset(int page, int totalCount, int pageSize,
                                   int expectedPage, int expectedTotalPages, int expectedOffset) {
            Page p = Page.of(page, totalCount, pageSize);
            assertAll(
                    () -> assertEquals(expectedPage, p.page()),
                    () -> assertEquals(expectedTotalPages, p.totalPages()),
                    () -> assertEquals(expectedOffset, p.offset()),
                    () -> assertEquals(Math.max(1, pageSize), p.pageSize()));
        }

        @Test
        @DisplayName("空列表固定为第 1/1 页")
        void emptyListShowsOnePage() {
            Page p = Page.of(5, 0, 36);
            assertAll(
                    () -> assertEquals(1, p.page()),
                    () -> assertEquals(1, p.totalPages()),
                    () -> assertEquals(0, p.offset()),
                    () -> assertFalse(p.hasPrevious()),
                    () -> assertFalse(p.hasNext()));
        }
    }

    @Nested
    @DisplayName("翻页标记")
    class Flags {

        @Test
        @DisplayName("单页时前后都没有")
        void singlePageHasNoNeighbours() {
            Page p = Page.of(1, 36, 36);
            assertAll(
                    () -> assertFalse(p.hasPrevious()),
                    () -> assertFalse(p.hasNext()));
        }

        @Test
        @DisplayName("首页只有下一页，末页只有上一页")
        void firstAndLastPageFlags() {
            Page first = Page.of(1, 100, 36);
            Page last = Page.of(3, 100, 36);
            assertAll(
                    () -> assertFalse(first.hasPrevious()),
                    () -> assertTrue(first.hasNext()),
                    () -> assertTrue(last.hasPrevious()),
                    () -> assertFalse(last.hasNext()));
        }

        @Test
        @DisplayName("中间页前后都有")
        void middlePageHasBoth() {
            Page p = Page.of(2, 100, 36);
            assertAll(
                    () -> assertTrue(p.hasPrevious()),
                    () -> assertTrue(p.hasNext()),
                    () -> assertEquals(36, p.offset()));
        }
    }

    @Nested
    @DisplayName("不变量（随机组合）")
    class Invariants {

        @Test
        @DisplayName("页数、页码、偏移始终自洽")
        void pageInvariantsHold() {
            Random random = new Random(20261117L);
            for (int i = 0; i < 500; i++) {
                int count = random.nextInt(2001);
                int size = random.nextInt(68) - 3;
                int requested = random.nextInt(Math.max(1, count / Math.max(1, size)) + 4) - 5;

                Page p = Page.of(requested, count, size);
                int effectiveSize = Math.max(1, size);
                String ctx = "requested=" + requested + ", count=" + count + ", size=" + size
                        + " -> " + p;

                assertTrue(p.page() >= 1, "页码小于 1: " + ctx);
                assertTrue(p.page() <= p.totalPages(), "页码超过总页数: " + ctx);
                assertTrue(p.totalPages() >= 1, "总页数小于 1: " + ctx);
                assertTrue(p.offset() >= 0, "偏移为负: " + ctx);
                assertEquals((p.page() - 1) * effectiveSize, p.offset(), "偏移与页码不一致: " + ctx);
                assertEquals((count + effectiveSize - 1) / effectiveSize, p.totalPages(),
                        "总页数不等于向上取整: " + ctx);
                if (count > 0) {
                    assertTrue(p.offset() < count, "偏移越过数据尾: " + ctx);
                }
                if (p.page() == p.totalPages() && count > 0) {
                    assertTrue(p.offset() + p.pageSize() >= count, "末页未覆盖最后一条: " + ctx);
                }
                assertEquals(p.page() > 1, p.hasPrevious(), "hasPrevious 与页码不一致: " + ctx);
                assertEquals(p.page() < p.totalPages(), p.hasNext(), "hasNext 与页码不一致: " + ctx);
            }
        }

        @Test
        @DisplayName("每页 36 件时首末页如实反映数量")
        void pageSizeThirtySixMatchesGui() {
            assertEquals(1, Page.of(1, 36, 36).totalPages());
            assertEquals(2, Page.of(1, 37, 36).totalPages());
            assertEquals(36, Page.of(2, 72, 36).offset());
            assertEquals(71, Page.of(2, 72, 36).offset() + 35);
        }
    }
}
