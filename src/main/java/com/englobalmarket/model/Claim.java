package com.englobalmarket.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 收货箱中的一件待领取物品。
 */
public record Claim(int id, UUID ownerUuid, ItemStack item, String reason, long createdAt) {

    public static final String REASON_EXPIRED = "expired";
    public static final String REASON_DELISTED = "delisted";
    public static final String REASON_OVERFLOW = "overflow";
}
