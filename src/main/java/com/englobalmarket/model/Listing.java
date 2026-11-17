package com.englobalmarket.model;

import com.englobalmarket.util.ExpiryUtil;
import com.englobalmarket.util.PriceUtil;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * 一条上架商品记录。
 *
 * <p>价格使用 {@link BigDecimal}（两位小数），避免浮点误差导致结算少算一分钱。
 */
public record Listing(int id, UUID sellerUuid, String sellerName, ItemStack item,
                      BigDecimal price, long listedAt) {

    /** 单价（总价 / 数量），仅用于显示，保留两位小数。 */
    public BigDecimal unitPrice() {
        int amount = Math.max(1, item.getAmount());
        return price.divide(BigDecimal.valueOf(amount), PriceUtil.SCALE, RoundingMode.HALF_UP);
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
