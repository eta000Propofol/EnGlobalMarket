package com.englobalmarket.service;

import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 上架记录与收货箱的数据访问接口。
 *
 * <p>所有写操作必须以返回值表示是否真正落库（{@code false} / 负数 = 未写入），
 * 调用方据此决定是否继续或回滚。抽出接口是为了在测试里注入故障，验证补偿逻辑。
 */
public interface ListingStore {

    // ---------------- 上架记录 ----------------

    Listing getListing(int id);

    List<Listing> getListings(int offset, int limit);

    List<Listing> getListingsBySeller(UUID seller, int offset, int limit);

    int countListings();

    int countBySeller(UUID seller);

    /** 新增上架记录；返回自增 id，失败返回 -1。 */
    int createListing(UUID sellerUuid, String sellerName, ItemStack item, BigDecimal price);

    /** 删除指定记录；返回是否真的删掉了一行。 */
    boolean deleteListing(int id);

    /**
     * 回滚用：按原 id 重新插入一条之前被删除的记录。
     * 用于「已删除 → 扣款失败」的场景，保证商品不会凭空消失。
     */
    boolean restoreListing(Listing listing);

    boolean updatePrice(int id, BigDecimal price);

    List<Listing> getExpiredListings(long cutoffMillis);

    /** 在同一个事务内把上架记录转为收货箱记录（删除 + 插入），任一步失败则全部回滚。 */
    boolean moveListingToClaim(Listing listing, String reason);

    // ---------------- 收货箱 ----------------

    Claim getClaim(int id);

    List<Claim> getClaims(UUID owner, int offset, int limit);

    int countClaims(UUID owner);

    /** 写入收货箱；返回自增 id，失败返回 -1。 */
    int addClaim(UUID owner, ItemStack item, String reason);

    boolean deleteClaim(int id);
}
