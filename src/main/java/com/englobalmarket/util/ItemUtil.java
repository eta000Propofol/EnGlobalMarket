package com.englobalmarket.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;

/**
 * 物品工具：生成仅用于界面展示的物品副本。
 */
public final class ItemUtil {

    private ItemUtil() {
    }

    /**
     * 生成仅用于界面展示的物品副本：清空 PersistentDataContainer，避免其他插件
     * （如 EzStrengthen）注册的全局“打开容器即按 PDC 重建 lore”监听覆盖我们追加的
     * 市场信息行（卖家/价格/剩余时间）。存储与成交仍使用带完整 PDC 的原物品。
     */
    public static ItemStack displayCopy(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            new ArrayList<>(meta.getPersistentDataContainer().getKeys())
                    .forEach(key -> meta.getPersistentDataContainer().remove(key));
            copy.setItemMeta(meta);
        }
        return copy;
    }
}
