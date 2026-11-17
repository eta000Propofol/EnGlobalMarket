package com.englobalmarket.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 经济接口（Vault 的薄封装）。
 *
 * <p>金额以 {@link BigDecimal} 传入（两位小数），由实现负责在调用 Vault 时降级为 double。
 * 所有操作以返回值表示是否成功，失败时调用方必须回滚已完成的步骤。
 */
public interface EconomyGateway {

    boolean isAvailable();

    boolean has(UUID player, BigDecimal amount);

    /** 扣款；成功返回 true。 */
    boolean withdraw(UUID player, BigDecimal amount);

    /** 入账；成功返回 true。 */
    boolean deposit(UUID player, BigDecimal amount);

    BigDecimal balance(UUID player);
}
