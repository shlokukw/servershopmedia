package dev.servershop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShopPlugin extends JavaPlugin implements CommandExecutor, Listener {
    private static final String TITLE = "MEDIASMP";
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, EnchCat> enchCats = new LinkedHashMap<>();
    private final Money money = new Money();

    private YamlConfiguration enc;
    private int sellSlot = 21;
    private String sellCmd = "sell";
    private Material sellMat = Material.EMERALD;

    @Override
    public void onEnable() {
        for (String f : new String[]{"shop.yml", "enchants.yml"})
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        load();
        getCommand("shop").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void load() {
        categories.clear();
        enchCats.clear();

        YamlConfiguration shop = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shop.yml"));
        sellSlot = shop.getInt("sell.slot", 21);
        sellCmd = shop.getString("sell.command", "sell");
        Material sm = Material.matchMaterial(shop.getString("sell.material", "EMERALD"));
        sellMat = sm == null ? Material.EMERALD : sm;

        ConfigurationSection cs = shop.getConfigurationSection("categories");
        if (cs == null) {
            getLogger().warning("shop.yml has no 'categories' section.");
        } else {
            int idx = 0;
            for (String key : cs.getKeys(false)) {
                ConfigurationSection s = cs.getConfigurationSection(key);
                if (s == null) continue;
                Material icon = Material.matchMaterial(s.getString("icon", "STONE"));
                if (icon == null) icon = Material.STONE;
                Category c = new Category(key, s.getString("name", key), s.getString("view", key), icon,
                        s.getInt("slot", 10 + idx), Math.min(28, Math.max(1, s.getInt("per-page", 28))));
                ConfigurationSection items = s.getConfigurationSection("items");
                if (items != null) for (String k : items.getKeys(false)) {
                    double price = items.getDouble(k, -1);
                    String potion = null;
                    String matName = k;
                    if (k.startsWith("TIPPED_ARROW_")) { potion = k.substring(13); matName = "TIPPED_ARROW"; }
                    Material m = Material.matchMaterial(matName);
                    if (m == null || price < 0) { getLogger().warning("Skipping " + key + "." + k); continue; }
                    c.items.add(new ShopItem(m, potion, price));
                }
                categories.put(key, c);
                idx++;
            }
        }

        enc = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "enchants.yml"));
        double defPrice = enc.getDouble("price", 70000);
        ConfigurationSection ec = enc.getConfigurationSection("categories");
        if (ec != null) for (String key : ec.getKeys(false)) {
            ConfigurationSection s = ec.getConfigurationSection(key);
            if (s == null) continue;
            Material icon = Material.matchMaterial(s.getString("icon", "BOOK"));
            if (icon == null) icon = Material.BOOK;
            EnchCat c = new EnchCat(key, s.getString("name", key.toUpperCase()), icon, s.getInt("slot", 10),
                    s.getString("item-suffix", ""));
            for (String line : s.getStringList("enchants")) {
                String[] p = line.trim().split(":");
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(p[0].trim().toLowerCase()));
                if (e == null) { getLogger().warning("Unknown enchant '" + line + "' in " + key); continue; }
                try {
                    int level = p.length > 1 ? Integer.parseInt(p[1].trim()) : e.getMaxLevel();
                    double price = p.length > 2 ? Double.parseDouble(p[2].trim()) : defPrice;
                    c.entries.add(new EnchEntry(e, level, price));
                } catch (NumberFormatException ex) {
                    getLogger().warning("Bad enchant line '" + line + "' in " + key);
                }
            }
            enchCats.put(key, c);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("servershop.reload")) { sender.sendMessage("No permission."); return true; }
            load();
            sender.sendMessage("ServerShop reloaded.");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use the shop."); return true; }
        openMain(p);
        return true;
    }

    // ---------- helpers ----------
    private ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        meta.displayName(Text.c(name));
        meta.lore(lore.stream().map(Text::c).toList());
        s.setItemMeta(meta);
        return s;
    }

    private static String nice(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private static String enchName(EnchEntry e) {
        String base = nice(e.ench.getKey().getKey());
        if (e.level == 1 && e.ench.getMaxLevel() == 1) return base;
        return base + " " + (e.level < ROMAN.length ? ROMAN[e.level] : String.valueOf(e.level));
    }

    /** Puts items in centered rows of up to 7 starting at row startRow. Returns the slots in order. */
    private static List<Integer> centeredSlots(int count, int startRow) {
        List<Integer> slots = new ArrayList<>();
        int i = 0, row = startRow;
        while (i < count) {
            int k = Math.min(7, count - i);
            int start = (9 - k) / 2;
            for (int j = 0; j < k; j++, i++) slots.add(row * 9 + start + j);
            row++;
        }
        return slots;
    }

    // ---------- main menu ----------
    void openMain(Player p) {
        ShopHolder h = new ShopHolder(ShopHolder.Type.MAIN, null, 0);
        h.inv = Bukkit.createInventory(h, 45, Text.c(TITLE));
        for (Category c : categories.values()) {
            if (c.slot < 0 || c.slot >= 45) continue;
            h.inv.setItem(c.slot, icon(c.icon, "&a&l" + c.name, List.of("&7Click to browse &f" + c.view)));
            h.targets.put(c.slot, c);
        }
        if (sellSlot >= 0 && sellSlot < 45) {
            h.inv.setItem(sellSlot, icon(sellMat, "&a&lSELL", List.of("&7Click to open the sell menu")));
            h.targets.put(sellSlot, "SELL");
        }
        int es = enc.getInt("button.slot", 23);
        if (!enchCats.isEmpty() && es >= 0 && es < 45) {
            Material m = Material.matchMaterial(enc.getString("button.material", "ENCHANTED_BOOK"));
            h.inv.setItem(es, icon(m == null ? Material.ENCHANTED_BOOK : m,
                    enc.getString("button.name", "&d&lENCHANTMENTS"),
                    enc.getStringList("button.description")));
            h.targets.put(es, "ENCH");
        }
        p.openInventory(h.inv);
    }

    // ---------- item category menu ----------
    void openCategory(Player p, Category c, int page) {
        int pages = Math.max(1, (int) Math.ceil(c.items.size() / (double) c.perPage));
        page = Math.max(0, Math.min(page, pages - 1));
        ShopHolder h = new ShopHolder(ShopHolder.Type.CATEGORY, c, page);
        h.inv = Bukkit.createInventory(h, 54, Text.c(c.name));
        List<ShopItem> sub = c.items.subList(page * c.perPage, Math.min(c.items.size(), (page + 1) * c.perPage));
        List<Integer> slots = centeredSlots(sub.size(), 1);
        for (int i = 0; i < sub.size(); i++) {
            ShopItem it = sub.get(i);
            ItemStack s = it.create(1);
            int stack = it.material.getMaxStackSize();
            ItemMeta meta = s.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add("&7Price: &a" + Text.price(it.price) + " &8each");
            lore.add("&e&lLEFT &7buy 1");
            if (stack > 1) lore.add("&e&lRIGHT &7buy " + stack + " &8(" + Text.price(it.price * stack) + ")");
            meta.lore(lore.stream().map(Text::c).toList());
            s.setItemMeta(meta);
            h.inv.setItem(slots.get(i), s);
            h.targets.put(slots.get(i), it);
        }
        h.inv.setItem(45, icon(Material.ARROW, "&c&lBACK", List.of("&7Click to go back")));
        h.targets.put(45, "BACK");
        if (page > 0) {
            h.inv.setItem(48, icon(Material.ARROW, "&e&lPREVIOUS PAGE", List.of("&7Click to go to the previous page")));
            h.targets.put(48, "PREV");
        }
        if (page < pages - 1) {
            h.inv.setItem(50, icon(Material.ARROW, "&e&lNEXT PAGE", List.of("&7Click to go to the next page")));
            h.targets.put(50, "NEXT");
        }
        p.openInventory(h.inv);
    }

    // ---------- enchant menus ----------
    void openEnchants(Player p) {
        ShopHolder h = new ShopHolder(ShopHolder.Type.ENCHANTS, null, 0);
        h.inv = Bukkit.createInventory(h, 45, Text.c("ENCHANTMENTS"));
        for (EnchCat c : enchCats.values()) {
            if (c.slot < 0 || c.slot >= 45) continue;
            h.inv.setItem(c.slot, icon(c.icon, "&d&l" + c.name, List.of("&7Click to view the enchants")));
            h.targets.put(c.slot, c);
        }
        h.inv.setItem(36, icon(Material.ARROW, "&c&lBACK", List.of("&7Click to go back")));
        h.targets.put(36, "BACK");
        p.openInventory(h.inv);
    }

    void openEnchCat(Player p, EnchCat c) {
        ShopHolder h = new ShopHolder(ShopHolder.Type.ENCH_CAT, c, 0);
        h.inv = Bukkit.createInventory(h, 36, Text.c(c.name + " ENCHANTS"));
        List<Integer> slots = centeredSlots(Math.min(14, c.entries.size()), 1);
        for (int i = 0; i < slots.size(); i++) {
            EnchEntry e = c.entries.get(i);
            ItemStack s = new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta m = (EnchantmentStorageMeta) s.getItemMeta();
            m.addStoredEnchant(e.ench, e.level, true);
            m.displayName(Text.c("&b&l" + enchName(e)));
            m.lore(List.of(
                    Text.c("&7Price: &a" + Text.price(e.price)),
                    Text.c(""),
                    Text.c("&7Hold your " + c.name.toLowerCase() + " in your main hand"),
                    Text.c("&e&lCLICK &7to enchant it")));
            s.setItemMeta(m);
            h.inv.setItem(slots.get(i), s);
            h.targets.put(slots.get(i), e);
        }
        h.inv.setItem(27, icon(Material.ARROW, "&c&lBACK", List.of("&7Click to go back")));
        h.targets.put(27, "BACK");
        p.openInventory(h.inv);
    }

    // ---------- clicks ----------
    @EventHandler
    public void onClick(InventoryClickEvent ev) {
        if (!(ev.getInventory().getHolder() instanceof ShopHolder h)) return;
        ev.setCancelled(true);
        if (!(ev.getWhoClicked() instanceof Player p)) return;
        if (ev.getClickedInventory() != ev.getView().getTopInventory()) return;
        Object t = h.targets.get(ev.getSlot());
        if (t == null) return;

        if (h.type == ShopHolder.Type.MAIN) {
            if (t instanceof Category c) openCategory(p, c, 0);
            else if ("SELL".equals(t)) sell(p);
            else if ("ENCH".equals(t)) openEnchants(p);
        } else if (h.type == ShopHolder.Type.CATEGORY) {
            Category cat = (Category) h.data;
            if (t instanceof ShopItem it) {
                boolean right = ev.getClick() == ClickType.RIGHT || ev.getClick() == ClickType.SHIFT_RIGHT;
                buy(p, it, right ? it.material.getMaxStackSize() : 1);
            } else if ("BACK".equals(t)) openMain(p);
            else if ("PREV".equals(t)) openCategory(p, cat, h.page - 1);
            else if ("NEXT".equals(t)) openCategory(p, cat, h.page + 1);
        } else if (h.type == ShopHolder.Type.ENCHANTS) {
            if (t instanceof EnchCat c) openEnchCat(p, c);
            else openMain(p);
        } else if (h.type == ShopHolder.Type.ENCH_CAT) {
            if (t instanceof EnchEntry e) applyEnchant(p, (EnchCat) h.data, e);
            else openEnchants(p);
        }
    }

    private void sell(Player p) {
        p.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.performCommand(sellCmd)) p.sendMessage("The sell menu isn't available right now.");
        });
    }

    private void buy(Player p, ShopItem it, int amt) {
        if (!money.ready()) { p.sendMessage("The shop can't reach the economy. Tell an admin (needs Vault + an economy plugin)."); return; }
        double cost = it.price * amt;
        if (money.balance(p) < cost) { p.sendMessage("Not enough money. You need " + Text.price(cost) + "."); return; }
        if (!money.withdraw(p, cost)) { p.sendMessage("Payment failed."); return; }
        Map<Integer, ItemStack> left = p.getInventory().addItem(it.create(amt));
        int notFit = left.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (notFit > 0) money.deposit(p, it.price * notFit);
        int bought = amt - notFit;
        if (bought <= 0) p.sendMessage("Your inventory is full.");
        else p.sendMessage("Bought " + bought + "x " + it.material.name().toLowerCase().replace('_', ' ') + " for " + Text.price(it.price * bought) + ".");
    }

    private void applyEnchant(Player p, EnchCat cat, EnchEntry en) {
        PlayerInventory inv = p.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (hand.getType().isAir() || !hand.getType().name().endsWith(cat.suffix)) {
            p.sendMessage("Hold a " + cat.name.toLowerCase() + " in your main hand first.");
            return;
        }
        String label = enchName(en);
        if (hand.getEnchantmentLevel(en.ench) >= en.level) {
            p.sendMessage("Your item already has " + label + " (or higher).");
            return;
        }
        for (Enchantment ex : hand.getEnchantments().keySet()) {
            if (!ex.equals(en.ench) && en.ench.conflictsWith(ex)) {
                p.sendMessage(label + " conflicts with " + nice(ex.getKey().getKey()) + " on your item.");
                return;
            }
        }
        if (!money.ready()) { p.sendMessage("The shop can't reach the economy. Tell an admin (needs Vault + an economy plugin)."); return; }
        if (money.balance(p) < en.price) { p.sendMessage("Not enough money. You need " + Text.price(en.price) + "."); return; }
        if (!money.withdraw(p, en.price)) { p.sendMessage("Payment failed."); return; }
        ItemStack out = hand.clone();
        out.addUnsafeEnchantment(en.ench, en.level);
        inv.setItemInMainHand(out);
        p.sendMessage("Applied " + label + " for " + Text.price(en.price) + ".");
    }
}
