package com.englobalmarket.service;

import org.bukkit.inventory.ItemStack;

/**
 * 生产环境实现：使用 Paper 自带的物品序列化（含全部 NBT/组件数据）。
 */
public final class BukkitItemCodec implements ItemCodec {

    @Override
    public byte[] encode(ItemStack item) {
        return item == null ? null : item.serializeAsBytes();
    }

    @Override
    public ItemStack decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        return ItemStack.deserializeBytes(data);
    }
}
