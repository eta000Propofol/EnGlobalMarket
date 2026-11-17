package com.englobalmarket.service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 内存版 {@link EconomyGateway}，可指定某玩家的扣款/入账失败，用于验证补偿回滚。
 */
final class FakeEconomy implements EconomyGateway {

    final Map<UUID, BigDecimal> balances = new HashMap<>();
    List<String> events = new ArrayList<>();
    /** 预设 has() 的返回值：用于模拟「检查时还有钱、扣款时已经没钱」的竞态。 */
    final Deque<Boolean> hasScript = new ArrayDeque<>();
    private final Set<UUID> withdrawDenied = new HashSet<>();
    private final Set<UUID> depositDenied = new HashSet<>();
    boolean available = true;

    void setBalance(UUID player, String amount) {
        balances.put(player, new BigDecimal(amount));
    }

    BigDecimal balanceOf(UUID player) {
        return balances.getOrDefault(player, BigDecimal.ZERO);
    }

    void denyWithdraw(UUID player) {
        withdrawDenied.add(player);
    }

    void denyDeposit(UUID player) {
        depositDenied.add(player);
    }

    boolean denyAllDeposits;

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        events.add("has:" + player);
        if (!hasScript.isEmpty()) {
            return hasScript.poll();
        }
        return available && balanceOf(player).compareTo(amount) >= 0;
    }

    @Override
    public boolean withdraw(UUID player, BigDecimal amount) {
        events.add("withdraw:" + player);
        if (!available || withdrawDenied.contains(player)) {
            return false;
        }
        BigDecimal balance = balanceOf(player);
        if (balance.compareTo(amount) < 0) {
            return false;
        }
        balances.put(player, balance.subtract(amount));
        return true;
    }

    @Override
    public boolean deposit(UUID player, BigDecimal amount) {
        events.add("deposit:" + player);
        if (!available) {
            return false;
        }
        if (depositDenied.contains(player) || denyAllDeposits) {
            return false;
        }
        balances.put(player, balanceOf(player).add(amount));
        return true;
    }

    @Override
    public BigDecimal balance(UUID player) {
        return balanceOf(player);
    }
}
