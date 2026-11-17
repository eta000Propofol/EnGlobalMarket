package com.englobalmarket.service;

import org.bukkit.inventory.ItemStack;

/**
 * 物品与数据库字节之间的编解码。
 *
 * <p>抽成接口是为了让数据层能脱离 Bukkit 服务端测试：真实实现走
 * {@link ItemStack#serializeAsBytes()}，测试实现只关心「字节有没有正确落盘」。
 */
public interface ItemCodec {

    /** 序列化；允许 {@code item} 为 {@code null}（测试用空载荷）。 */
    byte[] encode(ItemStack item);

    /** 反序列化；数据不可识别时返回 {@code null}。 */
    ItemStack decode(byte[] data);
}
