package dev.servershop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.*;

final class Text {
    private static final LegacyComponentSerializer S =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    static Component c(String s) {
        return S.deserialize(s).decoration(TextDecoration.ITALIC, false).colorIfAbsent(NamedTextColor.WHITE);
    }

    static String price(double d) {
        return d == Math.floor(d) ? String.format("$%,d", (long) d) : String.format("$%,.2f", d);
    }
}

final class ShopItem {
    final Material material;
    final String potion; // null unless tipped arrow
    final double price;

    ShopItem(Material m, String potion, double price) {
        this.material = m; this.potion = potion; this.price = price;
    }

    ItemStack create(int amount) {
        ItemStack s = new ItemStack(material, amount);
        if (potion != null && s.getItemMeta() instanceof PotionMeta pm) {
            try {
                pm.setBasePotionType(PotionType.valueOf(potion));
                s.setItemMeta(pm);
            } catch (IllegalArgumentException ignored) { }
        }
        return s;
    }
}

final class Category {
    final String key, name, view;
    final Material icon;
    final int slot, perPage;
    final List<ShopItem> items = new ArrayList<>();

    Category(String key, String name, String view, Material icon, int slot, int perPage) {
        this.key = key; this.name = name; this.view = view; this.icon = icon;
        this.slot = slot; this.perPage = perPage;
    }
}

final class EnchEntry {
    final Enchantment ench;
    final int level;
    final double price;

    EnchEntry(Enchantment ench, int level, double price) {
        this.ench = ench; this.level = level; this.price = price;
    }
}

final class EnchCat {
    final String key, name, suffix;
    final Material icon;
    final int slot;
    final List<EnchEntry> entries = new ArrayList<>();

    EnchCat(String key, String name, Material icon, int slot, String suffix) {
        this.key = key; this.name = name; this.icon = icon; this.slot = slot; this.suffix = suffix;
    }
}

final class ShopHolder implements InventoryHolder {
    enum Type { MAIN, CATEGORY, ENCHANTS, ENCH_CAT }

    final Type type;
    final Object data; // Category or EnchCat
    final int page;
    final Map<Integer, Object> targets = new HashMap<>();
    Inventory inv;

    ShopHolder(Type type, Object data, int page) {
        this.type = type; this.data = data; this.page = page;
    }

    @Override public Inventory getInventory() { return inv; }
}

/** Vault access through reflection so the plugin loads without Vault. */
@SuppressWarnings({"unchecked", "rawtypes"})
final class Money {
    private Object eco;
    private Method bal, withdraw, deposit, ok;

    boolean ready() {
        if (eco != null) return true;
        try {
            Class c = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(c);
            if (rsp == null) return false;
            eco = rsp.getProvider();
            bal = c.getMethod("getBalance", OfflinePlayer.class);
            withdraw = c.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = c.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            ok = Class.forName("net.milkbowl.vault.economy.EconomyResponse").getMethod("transactionSuccess");
            return true;
        } catch (ReflectiveOperationException e) {
            eco = null;
            return false;
        }
    }

    double balance(OfflinePlayer p) {
        try { return (double) bal.invoke(eco, p); } catch (ReflectiveOperationException e) { return 0; }
    }

    boolean withdraw(OfflinePlayer p, double amt) {
        try { return (boolean) ok.invoke(withdraw.invoke(eco, p, amt)); } catch (ReflectiveOperationException e) { return false; }
    }

    void deposit(OfflinePlayer p, double amt) {
        try { deposit.invoke(eco, p, amt); } catch (ReflectiveOperationException ignored) { }
    }
}
