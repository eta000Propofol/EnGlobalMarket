package com.englobalmarket.model;

import com.englobalmarket.util.ExpiryUtil;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 一条上架商品记录。
 */
public record Listing(int id, UUID sellerUuid, String sellerName, ItemStack item, double price, long listedAt) {

    /** 单价（总价 / 数量）。 */
    public double unitPrice() {
        return price / Math.max(1, item.getAmount());
    }

    public long expiresAt(int expireDays) {
        return ExpiryUtil.expiresAt(listedAt, expireDays);
    }

    public long remainingMillis(int expireDays) {
        return ExpiryUtil.remainingMillis(listedAt, expireDays, System.currentTimeMillis());
    }

    public boolean isExpired(int expireDays) {
        return ExpiryUtil.isExpired(listedAt, expireDays, System.currentTimeMillis());
    }
}
