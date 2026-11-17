package com.englobalmarket.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 收货箱中的一件待领取物品。
 */
public record Claim(int id, UUID ownerUuid, ItemStack item, String reason, long createdAt) {

    /** 超过有效期自动下架。 */
    public static final String REASON_EXPIRED = "expired";
    /** 主动下架时背包放不下。 */
    public static final String REASON_OVERFLOW = "overflow";
    /** 交易回滚时商品记录无法恢复，兜底转入收货箱（避免物品丢失）。 */
    public static final String REASON_ROLLBACK = "rollback";
}
