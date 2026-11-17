package com.englobalmarket.model;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 一条交易流水（待完成 / 待恢复的操作）。
 *
 * <p>数据库、Vault 与 Bukkit 背包三者不在同一个事务里，任何一步都可能在崩溃时停在中间。
 * 因此每次动钱或动物品之前先把「打算做什么、做到哪一步」落盘，插件启动时按阶段补齐或回滚。
 *
 * @param amount    本条流水涉及的金额（购买价为买家支付额，退款为应退金额）；无金额时为 {@code null}
 * @param proceeds  卖家应得（税后）；仅购买流水有值
 * @param targetUuid 关联方：购买流水里是卖家，投递流水里为空
 * @param item      待交付/待归还的物品；纯资金流水为空
 * @param listingId 购买流水对应的上架记录 id（恢复上架时用原 id）
 */
public record PendingOp(
        int id,
        Kind kind,
        Stage stage,
        UUID playerUuid,
        String playerName,
        BigDecimal amount,
        BigDecimal proceeds,
        UUID targetUuid,
        String targetName,
        ItemStack item,
        int listingId,
        String note,
        long createdAt) {

    /** 流水类型。 */
    public enum Kind {
        /** 购买：可能涉及扣款、入账、发货。 */
        PURCHASE,
        /** 投递：欠玩家一件物品（收货箱领取时先落盘再交货）。 */
        DELIVERY
    }

    /**
     * 流水阶段。
     *
     * <p>购买流水按 {@code CLAIMED → CHARGING → CHARGED → PAYING → PAID} 单向推进，
     * 失败回滚走 {@code REFUND_PENDING → REFUNDED}。
     * {@code CHARGING} / {@code PAYING} 表示「正在调用经济插件时崩溃」，钱到底动没动无法判定，
     * 只能留给管理员对账，绝不自动猜测。
     */
    public enum Stage {
        /** 已从市场认领商品（记录已删除），尚未动钱。恢复：把商品放回市场。 */
        CLAIMED,
        /** 正在扣买家款，结果未知。恢复：不自动处理，标记待人工核对。 */
        CHARGING,
        /** 已扣买家款，卖家未入账。恢复：退款并放回商品。 */
        CHARGED,
        /** 正在给卖家入账，结果未知。恢复：不自动处理，标记待人工核对。 */
        PAYING,
        /** 卖家已入账，买家还没拿到物品。恢复：把物品投递给买家。 */
        PAID,
        /** 卖家入账失败且退款也失败：欠买家钱，商品不重新上架。恢复：重试退款。 */
        REFUND_PENDING,
        /** 已退款，商品尚未归还卖家。恢复：写入卖家收货箱。 */
        REFUNDED,
        /** 收货箱物品已从记录中取出，等待放进玩家背包。恢复：投递给玩家。 */
        DELIVERY_PENDING;

        /** 钱到底动没动无法判定，必须人工核对。 */
        public boolean needsManualReview() {
            return this == CHARGING || this == PAYING;
        }

        /** 是否欠玩家一件物品（插件启动或玩家上线时补发）。 */
        public boolean owesItem() {
            return this == PAID || this == DELIVERY_PENDING;
        }
    }

    public PendingOp withStage(Stage newStage, String newNote) {
        return new PendingOp(id, kind, newStage, playerUuid, playerName, amount, proceeds,
                targetUuid, targetName, item, listingId, newNote, createdAt);
    }
}
