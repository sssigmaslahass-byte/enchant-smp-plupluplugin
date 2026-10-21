package com.example.infinityenchants;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class InfinityEnchants extends JavaPlugin implements Listener, TabExecutor {

    private enum Result { OK, NO_ITEM, NONE_LEFT }

    private record Option(Enchantment enchant, int level) {}

    private NamespacedKey managedKey;
    private NamespacedKey bookKey;
    private Enchantment infinity;
    private Enchantment impaling;
    private File dataFile;
    private YamlConfiguration data;
    private final Random random = new Random();
    private final Set<UUID> notified = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        managedKey = new NamespacedKey(this, "managed");
        bookKey = new NamespacedKey(this, "book");
        impaling = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("impaling"));
        infinity = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("infinity"));
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("ie");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        long minute = 20L * 60L;
        Bukkit.getScheduler().runTaskTimer(this, this::rewardTick, minute, minute);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------------
    // Infinity behaviour (food, potions, totems)
    // ------------------------------------------------------------------

    private boolean hasInfinity(ItemStack item) {
        if (infinity == null || item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasEnchant(infinity);
    }

    private void restoreNextTick(Player p, EquipmentSlot slot, ItemStack original) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) p.getInventory().setItem(slot, original);
        });
    }

    /** Food, drinkable potions, milk, honey... anything consumed with Infinity never runs out. */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack consumed = e.getItem();
        if (!hasInfinity(consumed)) return;
        Player p = e.getPlayer();
        PlayerInventory inv = p.getInventory();
        EquipmentSlot slot = inv.getItemInMainHand().isSimilar(consumed)
                ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        restoreNextTick(p, slot, inv.getItem(slot).clone());
    }

    /** Totem of Undying with Infinity is never used up. */
    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PlayerInventory inv = p.getInventory();
        EquipmentSlot slot = null;
        if (inv.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING) slot = EquipmentSlot.HAND;
        else if (inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) slot = EquipmentSlot.OFF_HAND;
        if (slot == null) return;
        ItemStack totem = inv.getItem(slot);
        if (!hasInfinity(totem)) return;
        restoreNextTick(p, slot, totem.clone());
    }

    /** Splash / lingering potions with Infinity are not used up when thrown. */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof ThrownPotion tp) || !(tp.getShooter() instanceof Player p)) return;
        ItemStack thrown = tp.getItem();
        if (!hasInfinity(thrown)) return;
        PlayerInventory inv = p.getInventory();
        EquipmentSlot slot = inv.getItemInMainHand().isSimilar(thrown)
                ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        restoreNextTick(p, slot, inv.getItem(slot).clone());
    }

    // ------------------------------------------------------------------
    // Granting enchants (kills + timed reward)
    // ------------------------------------------------------------------

    private List<Enchantment> pool() {
        List<Enchantment> list = new ArrayList<>();
        for (String s : getConfig().getStringList("pool")) {
            Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(s.toLowerCase(Locale.ROOT)));
            if (en == null) getLogger().warning("Unknown enchantment in pool: " + s);
            else list.add(en);
        }
        return list;
    }

    private String claimId(Enchantment en, int level) {
        return en.getKey().getKey() + "_" + level;
    }

    private String pretty(Enchantment en, int level) {
        StringBuilder sb = new StringBuilder();
        for (String part : en.getKey().getKey().split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb + " " + level;
    }

    /** Gives the player one random, unclaimed enchant on the item they are holding. */
    private Result grant(Player p) {
        PlayerInventory inv = p.getInventory();
        EquipmentSlot slot = EquipmentSlot.HAND;
        ItemStack item = inv.getItemInMainHand();
        if (item.getType().isAir()) {
            slot = EquipmentSlot.OFF_HAND;
            item = inv.getItemInOffHand();
        }
        if (item.getType().isAir()) return Result.NO_ITEM;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Result.NO_ITEM;

        List<Option> options = new ArrayList<>();
        for (Enchantment en : pool()) {
            if (meta.hasEnchant(en)) continue;
            for (int lvl = 1; lvl <= en.getMaxLevel(); lvl++) {
                if (!data.contains("claims." + claimId(en, lvl))) options.add(new Option(en, lvl));
            }
        }
        if (options.isEmpty()) return Result.NONE_LEFT;

        Option pick = options.get(random.nextInt(options.size()));
        String id = claimId(pick.enchant(), pick.level());

        meta.addEnchant(pick.enchant(), pick.level(), true);
        String existing = meta.getPersistentDataContainer().get(managedKey, PersistentDataType.STRING);
        meta.getPersistentDataContainer().set(managedKey, PersistentDataType.STRING,
                existing == null ? id : existing + "," + id);
        item.setItemMeta(meta);
        inv.setItem(slot, item);

        data.set("claims." + id, p.getUniqueId().toString());
        saveData();
        p.sendMessage(ChatColor.GREEN + "You received " + ChatColor.GOLD + pretty(pick.enchant(), pick.level())
                + ChatColor.GREEN + " on your " + item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "!");
        return Result.OK;
    }

    private void reward(Player killer) {
        Result r = grant(killer);
        if (r == Result.NO_ITEM) killer.sendMessage(ChatColor.YELLOW + "Hold an item when you get a kill to receive an enchant.");
        else if (r == Result.NONE_LEFT) killer.sendMessage(ChatColor.YELLOW + "No free enchants are left for that item.");
    }

    private boolean killRewardsOn() {
        return getConfig().getBoolean("kill-rewards.enabled", true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();

        if (getConfig().getBoolean("strip-on-death", true)) {
            // drop (lose) every enchant this plugin gave them
            for (ItemStack drop : e.getDrops()) strip(drop);
            if (e.getKeepInventory()) {
                PlayerInventory inv = victim.getInventory();
                ItemStack[] contents = inv.getContents();
                for (ItemStack it : contents) strip(it);
                inv.setContents(contents);
            }
            ItemStack[] ender = victim.getEnderChest().getContents();
            for (ItemStack it : ender) strip(it);
            victim.getEnderChest().setContents(ender);
            // every enchant they owned drops as a book (items are already unenchanted above)
            List<ItemStack> books = claimsToBooks(victim.getUniqueId());
            if (e.getKeepInventory()) {
                for (ItemStack b : books) victim.getWorld().dropItemNaturally(victim.getLocation(), b);
            } else {
                e.getDrops().addAll(books);
            }
        }

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim) && killRewardsOn()) reward(killer);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (!getConfig().getBoolean("kill-rewards.mobs", false) || !killRewardsOn()) return;
        LivingEntity dead = e.getEntity();
        if (dead instanceof Player) return;
        Player killer = dead.getKiller();
        if (killer != null) reward(killer);
    }

    private boolean strip(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String value = meta.getPersistentDataContainer().get(managedKey, PersistentDataType.STRING);
        if (value == null) return false;
        for (String id : value.split(",")) {
            int i = id.lastIndexOf('_');
            if (i < 0) continue;
            Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(id.substring(0, i)));
            if (en == null) continue;
            int lvl;
            try {
                lvl = Integer.parseInt(id.substring(i + 1));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (meta.getEnchantLevel(en) == lvl) meta.removeEnchant(en);
        }
        meta.getPersistentDataContainer().remove(managedKey);
        item.setItemMeta(meta);
        return true;
    }

    /** Turns every claim owned by this player into a drop-able book; the claim stays reserved as "BOOK". */
    private List<ItemStack> claimsToBooks(UUID uuid) {
        List<ItemStack> books = new ArrayList<>();
        ConfigurationSection claims = data.getConfigurationSection("claims");
        if (claims == null) return books;
        for (String key : new ArrayList<>(claims.getKeys(false))) {
            if (!uuid.toString().equals(claims.getString(key))) continue;
            ItemStack book = makeBook(key);
            if (book == null) {
                data.set("claims." + key, null);
                continue;
            }
            data.set("claims." + key, "BOOK");
            books.add(book);
        }
        saveData();
        return books;
    }

    private Option parseId(String id) {
        int i = id.lastIndexOf('_');
        if (i < 0) return null;
        Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(id.substring(0, i)));
        if (en == null) return null;
        try {
            return new Option(en, Integer.parseInt(id.substring(i + 1)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ItemStack makeBook(String id) {
        Option o = parseId(id);
        if (o == null) return null;
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(o.enchant(), o.level(), true);
        meta.setLore(List.of(ChatColor.GRAY + "Click this book onto any item in your inventory to apply it."));
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, id);
        book.setItemMeta(meta);
        return book;
    }

    private boolean isManagedBook(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(bookKey, PersistentDataType.STRING);
    }

    private boolean isManagedItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(managedKey, PersistentDataType.STRING);
    }

    private String bookId(ItemStack item) {
        if (!isManagedBook(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
    }

    /** Pick up a book with the cursor and click it onto an item in your own inventory to apply it. */
    @EventHandler
    public void onBookClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String id = bookId(e.getCursor());
        if (id == null) return;
        ItemStack target = e.getCurrentItem();
        if (target == null || target.getType().isAir() || target.getType() == Material.ENCHANTED_BOOK) return;
        if (e.getClickedInventory() != p.getInventory()) return;
        if (e.getSlotType() == InventoryType.SlotType.RESULT) return;
        e.setCancelled(true);

        Option o = parseId(id);
        if (o == null) return;
        if (!"BOOK".equals(data.getString("claims." + id))) {
            p.sendMessage(ChatColor.RED + "This book is no longer valid.");
            return;
        }
        ItemMeta meta = target.getItemMeta();
        if (meta == null) return;
        if (meta.hasEnchant(o.enchant())) {
            p.sendMessage(ChatColor.RED + "That item already has " + pretty(o.enchant(), 0).trim() + ".");
            return;
        }
        meta.addEnchant(o.enchant(), o.level(), true);
        String existing = meta.getPersistentDataContainer().get(managedKey, PersistentDataType.STRING);
        meta.getPersistentDataContainer().set(managedKey, PersistentDataType.STRING,
                existing == null ? id : existing + "," + id);
        target.setItemMeta(meta);
        e.setCurrentItem(target);
        e.getView().setCursor(null);

        data.set("claims." + id, p.getUniqueId().toString());
        saveData();
        p.sendMessage(ChatColor.GREEN + "Applied " + ChatColor.GOLD + pretty(o.enchant(), o.level()) + ChatColor.GREEN + "!");
    }

    // Managed books must not be eaten by anvils/grindstones, or the enchant would be lost or duplicated.
    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack[] c = e.getInventory().getContents();
        for (int i = 0; i < Math.min(2, c.length); i++) {
            if (isManagedBook(c[i]) || (i == 1 && isManagedItem(c[i]))) {
                e.setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onGrindstone(PrepareGrindstoneEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            if (isManagedBook(it) || isManagedItem(it)) {
                e.setResult(null);
                return;
            }
        }
    }

    // Books can't despawn or be burned/blown up, otherwise the enchant would be lost forever.
    @EventHandler
    public void onBookDespawn(ItemDespawnEvent e) {
        if (isManagedBook(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onBookDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item it && isManagedBook(it.getItemStack())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // Impaling goes through shields (melee, tridents, arrows, crossbow bolts)
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    @EventHandler(ignoreCancelled = true)
    public void onShieldPierce(EntityDamageByEntityEvent e) {
        if (impaling == null) return;
        if (!(e.getEntity() instanceof Player victim) || !victim.isBlocking()) return;
        ItemStack weapon = null;
        if (e.getDamager() instanceof Player attacker) weapon = attacker.getInventory().getItemInMainHand();
        else if (e.getDamager() instanceof AbstractArrow arrow) weapon = arrow.getWeapon();
        if (weapon == null || weapon.getType().isAir()) return;
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null || !meta.hasEnchant(impaling)) return;
        if (e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            e.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0);
        }
    }

    // ------------------------------------------------------------------
    // Timed reward (every N minutes of online time)
    // ------------------------------------------------------------------

    private void rewardTick() {
        if (!getConfig().getBoolean("reward.enabled", true)) return;
        int needed = getConfig().getInt("reward.minutes", 240);
        for (Player p : Bukkit.getOnlinePlayers()) {
            String path = "minutes." + p.getUniqueId();
            int minutes = data.getInt(path) + 1;
            data.set(path, minutes);
            if (minutes < needed) continue;
            Result r = grant(p);
            if (r == Result.OK) {
                data.set(path, 0);
                notified.remove(p.getUniqueId());
            } else if (r == Result.NO_ITEM && notified.add(p.getUniqueId())) {
                p.sendMessage(ChatColor.YELLOW + "Your enchant reward is ready! Hold an item to receive it.");
            }
        }
        saveData();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage(ChatColor.YELLOW + "/ie list | /ie killrewards <on|off> | /ie grant <player> | /ie release <enchant_level> | /ie reload");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("Players only.");
                    return true;
                }
                List<String> mine = new ArrayList<>();
                ConfigurationSection claims = data.getConfigurationSection("claims");
                if (claims != null) {
                    for (String key : claims.getKeys(false)) {
                        if (p.getUniqueId().toString().equals(claims.getString(key))) mine.add(key);
                    }
                }
                s.sendMessage(mine.isEmpty()
                        ? ChatColor.YELLOW + "You don't own any enchants yet."
                        : ChatColor.GOLD + "Your enchants: " + ChatColor.WHITE + String.join(", ", mine));
            }
            case "killrewards" -> {
                if (!s.hasPermission("infinityenchants.admin")) {
                    s.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                if (a.length < 2) {
                    s.sendMessage(ChatColor.YELLOW + "Kill rewards are " + (killRewardsOn() ? "ON" : "OFF")
                            + ". Use /ie killrewards <on|off>");
                    return true;
                }
                boolean on = a[1].equalsIgnoreCase("on");
                if (!on && !a[1].equalsIgnoreCase("off")) {
                    s.sendMessage(ChatColor.RED + "Use on or off.");
                    return true;
                }
                getConfig().set("kill-rewards.enabled", on);
                saveConfig();
                s.sendMessage(ChatColor.GREEN + "Kill rewards are now " + (on ? "ON" : "OFF") + ".");
            }
            case "grant" -> {
                if (!s.hasPermission("infinityenchants.admin")) {
                    s.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Usage: /ie grant <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) {
                    s.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }
                Result r = grant(target);
                s.sendMessage(r == Result.OK ? ChatColor.GREEN + "Granted."
                        : ChatColor.YELLOW + (r == Result.NO_ITEM ? "They aren't holding an item." : "No free enchants left for that item."));
            }
            case "release" -> {
                if (!s.hasPermission("infinityenchants.admin")) {
                    s.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Usage: /ie release <enchant_level> (e.g. sharpness_5)");
                    return true;
                }
                boolean had = data.contains("claims." + a[1]);
                data.set("claims." + a[1], null);
                saveData();
                s.sendMessage(had ? ChatColor.GREEN + "Freed " + a[1] + " (any existing book of it becomes invalid)."
                        : ChatColor.YELLOW + "No such claim.");
            }
            case "reload" -> {
                if (!s.hasPermission("infinityenchants.admin")) {
                    s.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                reloadConfig();
                s.sendMessage(ChatColor.GREEN + "Config reloaded.");
            }
            default -> s.sendMessage(ChatColor.RED + "Unknown subcommand.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("list", "killrewards", "grant", "release", "reload")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("killrewards")) {
            out.add("on");
            out.add("off");
        } else if (a.length == 2 && a[0].equalsIgnoreCase("grant")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        return out;
    }
}
