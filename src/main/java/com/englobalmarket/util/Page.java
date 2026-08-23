package com.englobalmarket.util;

/**
 * 分页信息：由当前页与总数计算页数、偏移量。
 */
public record Page(int page, int totalPages, int offset, int pageSize) {

    public static Page of(int page, int totalCount, int pageSize) {
        int size = Math.max(1, pageSize);
        int totalPages = Math.max(1, (totalCount + size - 1) / size);
        int current = Math.max(1, Math.min(page, totalPages));
        return new Page(current, totalPages, (current - 1) * size, size);
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }
}
