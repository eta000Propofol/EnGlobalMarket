package com.englobalmarket.service;

import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import com.englobalmarket.model.PendingOp;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内存版 {@link ListingStore} + {@link RecoveryStore}，可注入各类写入失败，
 * 用于验证补偿回滚与崩溃恢复。
 *
 * <p>商品载荷用 null 代替 ItemStack（Bukkit 的物品类需要服务端注册表才能实例化，
 * 而 {@link TradingCore} 只做透传，不解析物品内容）。
 */
final class FakeStore implements ListingStore, RecoveryStore {

    final Map<Integer, Listing> listings = new LinkedHashMap<>();
    final Map<Integer, Claim> claims = new LinkedHashMap<>();
    final Map<Integer, PendingOp> pendingOps = new LinkedHashMap<>();
    List<String> events = new ArrayList<>();
    private int nextListingId = 1;
    private int nextClaimId = 1;
    private int nextOpId = 1;

    boolean failDeleteListing;
    boolean failRestoreListing;
    boolean failUpdatePrice;
    boolean failDeleteClaim;
    boolean failAddClaim;
    boolean failMoveToClaim;

    boolean failAddPendingOp;
    boolean failUpdatePendingOp;
    boolean failDeletePendingOp;
    boolean failMoveClaimToDelivery;

    Listing putListing(UUID seller, String price) {
        int id = nextListingId++;
        Listing listing = new Listing(id, seller, "卖家" + id, null, new BigDecimal(price), 1_000L);
        listings.put(id, listing);
        return listing;
    }

    Claim putClaim(UUID owner, String reason) {
        int id = nextClaimId++;
        Claim claim = new Claim(id, owner, null, reason, 1_000L);
        claims.put(id, claim);
        return claim;
    }

    PendingOp putPendingOp(PendingOp op) {
        int id = nextOpId++;
        PendingOp stored = new PendingOp(id, op.kind(), op.stage(), op.playerUuid(), op.playerName(),
                op.amount(), op.proceeds(), op.targetUuid(), op.targetName(), op.item(),
                op.listingId(), op.note(), op.createdAt());
        pendingOps.put(id, stored);
        return stored;
    }

    @Override
    public Listing getListing(int id) {
        return listings.get(id);
    }

    @Override
    public List<Listing> getListings(int offset, int limit) {
        return new ArrayList<>(listings.values());
    }

    @Override
    public List<Listing> getListingsBySeller(UUID seller, int offset, int limit) {
        return listings.values().stream().filter(l -> l.sellerUuid().equals(seller)).toList();
    }

    @Override
    public int countListings() {
        return listings.size();
    }

    @Override
    public int countBySeller(UUID seller) {
        return (int) listings.values().stream().filter(l -> l.sellerUuid().equals(seller)).count();
    }

    @Override
    public int createListing(UUID sellerUuid, String sellerName, ItemStack item, BigDecimal price) {
        int id = nextListingId++;
        listings.put(id, new Listing(id, sellerUuid, sellerName, item, price, 1_000L));
        return id;
    }

    @Override
    public boolean deleteListing(int id) {
        events.add("deleteListing:" + id);
        if (failDeleteListing) {
            return false;
        }
        return listings.remove(id) != null;
    }

    @Override
    public boolean restoreListing(Listing listing) {
        events.add("restoreListing:" + listing.id());
        if (failRestoreListing) {
            return false;
        }
        listings.put(listing.id(), listing);
        return true;
    }

    @Override
    public boolean updatePrice(int id, BigDecimal price) {
        events.add("updatePrice:" + id);
        if (failUpdatePrice) {
            return false;
        }
        Listing old = listings.get(id);
        if (old == null) {
            return false;
        }
        listings.put(id, new Listing(old.id(), old.sellerUuid(), old.sellerName(), old.item(), price,
                old.listedAt()));
        return true;
    }

    @Override
    public List<Listing> getExpiredListings(long cutoffMillis) {
        return listings.values().stream().filter(l -> l.listedAt() <= cutoffMillis).toList();
    }

    @Override
    public boolean moveListingToClaim(Listing listing, String reason) {
        events.add("moveListingToClaim:" + listing.id());
        if (failMoveToClaim) {
            return false;
        }
        if (listings.remove(listing.id()) == null) {
            return false;
        }
        putClaim(listing.sellerUuid(), reason);
        return true;
    }

    @Override
    public Claim getClaim(int id) {
        return claims.get(id);
    }

    @Override
    public List<Claim> getClaims(UUID owner, int offset, int limit) {
        return claims.values().stream().filter(c -> c.ownerUuid().equals(owner)).toList();
    }

    @Override
    public int countClaims(UUID owner) {
        return (int) claims.values().stream().filter(c -> c.ownerUuid().equals(owner)).count();
    }

    @Override
    public int addClaim(UUID owner, ItemStack item, String reason) {
        events.add("addClaim:" + reason);
        if (failAddClaim) {
            return -1;
        }
        return putClaim(owner, reason).id();
    }

    @Override
    public boolean deleteClaim(int id) {
        events.add("deleteClaim:" + id);
        if (failDeleteClaim) {
            return false;
        }
        return claims.remove(id) != null;
    }

    // ---------------- 交易流水 ----------------

    @Override
    public int addPendingOp(PendingOp op) {
        events.add("addPendingOp:" + op.stage());
        if (failAddPendingOp) {
            return -1;
        }
        return putPendingOp(op).id();
    }

    @Override
    public boolean updatePendingOp(int id, PendingOp.Stage stage, String note) {
        events.add("updatePendingOp:" + stage);
        if (failUpdatePendingOp) {
            return false;
        }
        PendingOp old = pendingOps.get(id);
        if (old == null) {
            return false;
        }
        pendingOps.put(id, old.withStage(stage, note));
        return true;
    }

    @Override
    public boolean deletePendingOp(int id) {
        events.add("deletePendingOp:" + id);
        if (failDeletePendingOp) {
            return false;
        }
        return pendingOps.remove(id) != null;
    }

    @Override
    public List<PendingOp> getPendingOps(int limit) {
        return new ArrayList<>(pendingOps.values());
    }

    @Override
    public int countPendingOps() {
        return pendingOps.size();
    }

    @Override
    public int moveClaimToDelivery(int claimId, UUID owner, ItemStack item, String reason) {
        events.add("moveClaimToDelivery:" + claimId);
        if (failMoveClaimToDelivery) {
            return -1;
        }
        Claim claim = claims.get(claimId);
        if (claim == null) {
            return -1;
        }
        claims.remove(claimId);
        ItemStack payload = item == null ? claim.item() : item;
        return putPendingOp(new PendingOp(0, PendingOp.Kind.DELIVERY, PendingOp.Stage.DELIVERY_PENDING,
                owner, null, null, null, null, null, payload, 0, reason, 2_000L)).id();
    }
}
