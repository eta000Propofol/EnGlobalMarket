package com.englobalmarket.service;

import com.englobalmarket.model.PendingOp;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * 交易流水的持久化接口。
 *
 * <p>所有方法都必须真正落盘才返回成功；调用方据此决定是否可以继续动钱。
 */
public interface RecoveryStore {

    /** 写入一条流水；返回自增 id，失败返回 -1。 */
    int addPendingOp(PendingOp op);

    /** 推进流水阶段；返回是否真的更新了一行。 */
    boolean updatePendingOp(int id, PendingOp.Stage stage, String note);

    boolean deletePendingOp(int id);

    /** 按创建顺序读取流水（用于启动恢复与管理员排查）。 */
    List<PendingOp> getPendingOps(int limit);

    int countPendingOps();

    /**
     * 领取的两阶段提交：在同一个事务内删除收货箱记录并写入投递流水。
     *
     * <p>这样「记录消失」与「欠玩家物品」是同时发生的，不会出现
     * 「记录已删、物品还没进背包」的空档。成功返回流水 id，失败返回 -1（记录保持原样）。
     */
    int moveClaimToDelivery(int claimId, UUID owner, ItemStack item, String reason);
}
