package com.smp.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class SmpWeapons extends JavaPlugin implements Listener, TabExecutor {

    record Weapon(String id, Material base, String name, TextColor color, List<String> lore) {}

    static class MenuHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    static class StorageHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("smpweapons-pack".getBytes());
    private static final int MAX_ARROWS = 32;

    private final Map<String, Weapon> weapons = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> dragonShots = new HashMap<>();
    private final Set<UUID> resized = new HashSet<>();
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();
    private NamespacedKey weaponKey;
    private NamespacedKey arrowsKey;
    private NamespacedKey resizedKey;
    private NamespacedKey boomKey;

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        weaponKey = new NamespacedKey(this, "weapon_id");
        arrowsKey = new NamespacedKey(this, "stored_arrows");
        resizedKey = new NamespacedKey(this, "resized");
        boomKey = new NamespacedKey(this, "boom_arrow");

        registerWeapons();
        registerRecipes();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("smpweapons").setExecutor(this);
        getCommand("smpweapons").setTabCompleter(this);
        getCommand("rc").setExecutor(this);
        getCommand("rc").setTabCompleter(this);

        // Passive: Fire Blade grants fire resistance while held
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if ("fire_blade".equals(weaponId(p.getInventory().getItemInMainHand()))) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, true, false));
                }
            }
        }, 20L, 20L);

        // Ambient aura particles around whoever holds a weapon
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                String wid = weaponId(p.getInventory().getItemInMainHand());
                if (wid == null) continue;
                Particle part = switch (wid) {
                    case "dragon_blade" -> Particle.WITCH;
                    case "fire_blade" -> Particle.FLAME;
                    case "titan_blade" -> Particle.END_ROD;
                    case "doom_hammer" -> Particle.ELECTRIC_SPARK;
                    case "sonic_crossbow" -> Particle.SCULK_SOUL;
                    case "auto_bow" -> Particle.SOUL_FIRE_FLAME;
                    default -> null;
                };
                if (part == null) continue;
                Location l = p.getLocation().add(p.getLocation().getDirection().multiply(0.7)).add(0, 1.2, 0);
                p.getWorld().spawnParticle(part, l, 3, 0.35, 0.5, 0.35, 0.01);
            }
        }, 4L, 4L);

        getLogger().info("SmpWeapons enabled with " + weapons.size() + " weapons.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(resized)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) resetScale(p);
        }
    }

    private void addWeapon(String id, Material base, String name, int rgb, String... lore) {
        weapons.put(id, new Weapon(id, base, name, TextColor.color(rgb), List.of(lore)));
    }

    private void registerWeapons() {
        addWeapon("titan_blade", Material.NETHERITE_SWORD, "Titan Blade", 0x4FD8FF,
                "Right-Click (Sneak): Giant's Curse",
                "Left-Click (Sneak): Titan Form",
                "On-Hit: Heavy knockback");
        addWeapon("fire_blade", Material.NETHERITE_SWORD, "Fire Blade", 0xFF8A1E,
                "Passive: Fire Resistance while held",
                "Right-Click (Sneak): Inferno Wave",
                "Left-Click (Sneak): Phoenix Rebirth");
        addWeapon("dragon_blade", Material.NETHERITE_SWORD, "Dragon Blade", 0xC45CFF,
                "Passive: Kills heal you",
                "Right-Click (Sneak): Dragon's Wrath",
                "Left-Click (Sneak): Wyrm Dash");
        addWeapon("doom_hammer", Material.NETHERITE_AXE, "Doom Hammer", 0xFFE24A,
                "Right-Click (Sneak): Meteor Slam",
                "Left-Click (Sneak): Chain Lightning",
                "On-Hit: Heavy knockback");
        addWeapon("sonic_crossbow", Material.CROSSBOW, "Sonic Crossbow", 0x5CF0F5,
                "Right-Click (Sneak): Sonic Railgun",
                "Left-Click (Sneak): Echo Heal");
        addWeapon("auto_bow", Material.BOW, "Auto Bow", 0x7CFF8C,
                "Left-Click (Sneak): Open quiver",
                "Right-Click (Sneak): Arrow Storm (explosive)");
    }

    private void registerRecipes() {
        ConfigurationSection sec = getConfig().getConfigurationSection("recipes");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            Weapon w = weapons.get(id);
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (w == null || r == null) {
                getLogger().warning("Unknown weapon in recipes: " + id);
                continue;
            }
            List<String> shape = r.getStringList("shape");
            ConfigurationSection ing = r.getConfigurationSection("ingredients");
            if (shape.size() != 3 || ing == null) {
                getLogger().warning("Bad recipe for " + id + " (needs 3 shape rows and ingredients)");
                continue;
            }
            try {
                NamespacedKey key = new NamespacedKey(this, id);
                Bukkit.removeRecipe(key);
                ShapedRecipe rec = new ShapedRecipe(key, createItem(id));
                rec.shape(shape.get(0), shape.get(1), shape.get(2));
                for (String k : ing.getKeys(false)) {
                    Material m = Material.matchMaterial(ing.getString(k, ""));
                    if (m == null) throw new IllegalArgumentException("Unknown material for " + k);
                    rec.setIngredient(k.charAt(0), m);
                }
                Bukkit.addRecipe(rec);
                recipeKeys.add(key);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Could not register recipe " + id + ": " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    private List<Component> buildLore(Weapon w, int storedArrows) {
        List<Component> lore = new ArrayList<>();
        for (String line : w.lore()) {
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (storedArrows >= 0) {
            lore.add(Component.text("Stored arrows: " + storedArrows + "/" + MAX_ARROWS, NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return lore;
    }

    private ItemStack createItem(String id) {
        Weapon w = weapons.get(id);
        ItemStack item = new ItemStack(w.base());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(w.name(), w.color())
                .decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(buildLore(w, id.equals("auto_bow") ? 0 : -1));
        meta.setUnbreakable(true);
        if (w.base() != Material.BOW && w.base() != Material.CROSSBOW) {
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                    new NamespacedKey(this, "dmg_" + id), 11.0,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                    new NamespacedKey(this, "spd_" + id), id.equals("doom_hammer") ? -3.0 : -2.4,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        }
        meta.setItemModel(new NamespacedKey(this, id));
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private String weaponId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
    }

    private int getStored(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(arrowsKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private void setStored(ItemStack it, int n) {
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(arrowsKey, PersistentDataType.INTEGER, n);
        m.lore(buildLore(weapons.get("auto_bow"), n));
        it.setItemMeta(m);
    }

    private void give(Player p, ItemStack it) {
        for (ItemStack rest : p.getInventory().addItem(it).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------------
    // Cooldowns & helpers
    // ------------------------------------------------------------------

    private long remainingMs(Player p, String key) {
        Map<String, Long> m = cooldowns.get(p.getUniqueId());
        if (m == null) return 0;
        Long until = m.get(key);
        if (until == null) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    private void setCd(Player p, String key, long ms) {
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(key, System.currentTimeMillis() + ms);
    }

    private void cdMsg(Player p, long rem) {
        bar(p, "Cooldown: " + String.format("%.1f", rem / 1000.0) + "s", NamedTextColor.RED);
    }

    private boolean tryUse(Player p, String key, int seconds) {
        long rem = remainingMs(p, key);
        if (rem > 0) {
            cdMsg(p, rem);
            return false;
        }
        setCd(p, key, seconds * 1000L);
        return true;
    }

    private void resetCd(Player p) {
        cooldowns.remove(p.getUniqueId());
        dragonShots.remove(p.getUniqueId());
    }

    private void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    private void bar(Player p, String text, NamedTextColor c) {
        p.sendActionBar(Component.text(text, c));
    }

    private Vector flatDir(Player p) {
        Vector d = p.getLocation().getDirection().setY(0);
        if (d.lengthSquared() < 0.0001) return new Vector(0, 0, 1);
        return d.normalize();
    }

    private void setScale(Player p, double v) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null) a.setBaseValue(v);
    }

    private void markResized(Player p) {
        p.getPersistentDataContainer().set(resizedKey, PersistentDataType.BYTE, (byte) 1);
        resized.add(p.getUniqueId());
    }

    private void resetScale(Player p) {
        setScale(p, 1.0);
        p.getPersistentDataContainer().remove(resizedKey);
        resized.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.discoverRecipes(recipeKeys);
        if (p.getPersistentDataContainer().has(resizedKey, PersistentDataType.BYTE)) {
            resetScale(p);
        }
        String url = getConfig().getString("resource-pack.url", "");
        if (!url.isBlank()) {
            Bukkit.getScheduler().runTaskLater(this, () -> sendPack(p, url), 40L);
        }
    }

    private void sendPack(Player p, String url) {
        if (!p.isOnline()) return;
        byte[] hash = null;
        String sha = getConfig().getString("resource-pack.sha1", "").trim();
        if (!sha.isEmpty()) {
            try {
                hash = HexFormat.of().parseHex(sha);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("resource-pack.sha1 in config.yml is not valid hex, ignoring it.");
            }
        }
        p.setResourcePack(PACK_ID, url, hash,
                Component.text("Custom weapon textures", NamedTextColor.GOLD),
                getConfig().getBoolean("resource-pack.force", true));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (resized.contains(p.getUniqueId())) resetScale(p);
        dragonShots.remove(p.getUniqueId());
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (weaponId(e.getBow()) == null) return;
        if (e.getProjectile() instanceof AbstractArrow arrow) arrow.setDamage(4.0);
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        if (!"dragon_blade".equals(weaponId(k.getInventory().getItemInMainHand()))) return;
        AttributeInstance max = k.getAttribute(Attribute.MAX_HEALTH);
        double mx = max != null ? max.getValue() : 20.0;
        k.setHealth(Math.min(mx, k.getHealth() + 6.0));
        k.getWorld().spawnParticle(Particle.HEART, k.getLocation().add(0, 1.5, 0), 5, 0.4, 0.4, 0.4, 0);
    }

    @EventHandler
    public void onArrowHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow arrow)) return;
        if (!arrow.getPersistentDataContainer().has(boomKey, PersistentDataType.BYTE)) return;
        Location l = arrow.getLocation();
        World w = l.getWorld();
        w.spawnParticle(Particle.EXPLOSION, l, 1);
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f);
        Player shooter = arrow.getShooter() instanceof Player sp ? sp : null;
        for (LivingEntity le : w.getNearbyLivingEntities(l, 3)) {
            if (le instanceof ArmorStand || (shooter != null && le.equals(shooter))) continue;
            if (shooter != null) le.damage(5.0, shooter);
            else le.damage(5.0);
        }
        Bukkit.getScheduler().runTask(this, arrow::remove);
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it != null && weaponId(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != null && e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        String id = weaponId(p.getInventory().getItemInMainHand());
        if (id == null) return;
        if (!p.hasPermission("smpweapons.use")) return;
        if (!p.isSneaking()) return;
        if (a.isRightClick()) {
            e.setCancelled(true);
            cast(p, id, true);
        } else if (a.isLeftClick()) {
            cast(p, id, false);
        }
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        String id = weaponId(p.getInventory().getItemInMainHand());
        if (id == null) return;

        if (e.getEntity() instanceof LivingEntity target
                && (id.equals("doom_hammer") || id.equals("titan_blade"))) {
            Vector kb = flatDir(p).multiply(0.9).setY(0.25);
            Bukkit.getScheduler().runTask(this, () -> target.setVelocity(target.getVelocity().add(kb)));
        }

        if (p.isSneaking() && p.hasPermission("smpweapons.use")
                && e.getCause() == EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK) {
            cast(p, id, false);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getInventory())) return;
        String id = weaponId(e.getCurrentItem());
        if (id == null || !weapons.containsKey(id)) return;
        give(p, createItem(id));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof StorageHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        ItemStack bow = p.getInventory().getItemInMainHand();
        boolean holdingBow = "auto_bow".equals(weaponId(bow));
        int stored = holdingBow ? getStored(bow) : 0;
        int add = 0;
        for (ItemStack it : e.getInventory().getContents()) {
            if (it == null) continue;
            if (holdingBow && it.getType() == Material.ARROW) {
                int room = MAX_ARROWS - stored - add;
                int take = Math.max(0, Math.min(room, it.getAmount()));
                add += take;
                int left = it.getAmount() - take;
                if (left > 0) {
                    ItemStack r = it.clone();
                    r.setAmount(left);
                    give(p, r);
                }
            } else {
                give(p, it);
            }
        }
        if (holdingBow) {
            setStored(bow, stored + add);
            p.getInventory().setItemInMainHand(bow);
            bar(p, "Stored arrows: " + (stored + add) + "/" + MAX_ARROWS, NamedTextColor.GREEN);
        }
    }

    // ------------------------------------------------------------------
    // Ability dispatch
    // ------------------------------------------------------------------

    private void cast(Player p, String id, boolean right) {
        String k = id + (right ? ":right" : ":left");
        switch (k) {
            case "fire_blade:right" -> infernoWave(p);
            case "fire_blade:left" -> phoenixRebirth(p);
            case "dragon_blade:right" -> dragonWrath(p);
            case "dragon_blade:left" -> dragonDash(p);
            case "sonic_crossbow:right" -> sonicRailgun(p);
            case "sonic_crossbow:left" -> echoRecovery(p);
            case "doom_hammer:right" -> meteorSlam(p);
            case "doom_hammer:left" -> chainLightning(p);
            case "titan_blade:right" -> giantsCurse(p);
            case "titan_blade:left" -> titanForm(p);
            case "auto_bow:right" -> arrowStorm(p);
            case "auto_bow:left" -> openQuiver(p);
            default -> { }
        }
    }

    // ---------------- Fire Blade ----------------

    private void infernoWave(Player p) {
        if (!tryUse(p, "fire_blade:right", 12)) return;
        final Vector dir = flatDir(p);
        final World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
        w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.4f);
        for (LivingEntity le : w.getNearbyLivingEntities(p.getLocation(), 10)) {
            if (le.equals(p) || le instanceof ArmorStand) continue;
            Vector to = le.getLocation().toVector().subtract(p.getLocation().toVector());
            to.setY(0);
            double dist = to.length();
            if (dist < 0.01 || dist > 9) continue;
            if (to.normalize().dot(dir) < 0.55) continue;
            le.damage(10.0, p);
            le.setFireTicks(120);
            le.setVelocity(dir.clone().multiply(1.8).setY(0.7));
        }
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= 7 || !p.isOnline()) { cancel(); return; }
                double r = 1.5 + t * 1.2;
                Location base = p.getLocation().add(0, 1.0, 0);
                for (int a = -50; a <= 50; a += 6) {
                    Vector v = dir.clone().rotateAroundY(Math.toRadians(a)).multiply(r);
                    Location l = base.clone().add(v);
                    w.spawnParticle(Particle.FLAME, l, 2, 0.15, 0.3, 0.15, 0.02);
                    if (a % 18 == 0) w.spawnParticle(Particle.LAVA, l, 1);
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void phoenixRebirth(Player p) {
        if (!tryUse(p, "fire_blade:left", 25)) return;
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max != null ? max.getValue() : 20.0);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 2));
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.FLAME, l, 80, 0.6, 0.9, 0.6, 0.08);
        p.getWorld().spawnParticle(Particle.HEART, l, 8, 0.5, 0.8, 0.5, 0);
        p.getWorld().playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
    }

    // ---------------- Dragon Blade ----------------

    private void dragonWrath(Player p) {
        if (!tryUse(p, "dragon_blade:right", 25)) return;
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 0.8f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || t >= 60) { cancel(); return; }
                t += 2;
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection().normalize();
                World w = p.getWorld();
                double len = 20;
                for (double d = 1.5; d <= 20; d += 0.5) {
                    Location pt = eye.clone().add(dir.clone().multiply(d));
                    if (pt.getBlock().getType().isSolid()) { len = d; break; }
                    w.spawnParticle(Particle.WITCH, pt, 3, 0.25, 0.25, 0.25, 0.02);
                    w.spawnParticle(Particle.PORTAL, pt, 2, 0.3, 0.3, 0.3, 0.3);
                }
                if (t % 4 == 0) {
                    for (LivingEntity le : w.getNearbyLivingEntities(eye, 21)) {
                        if (le.equals(p) || le instanceof ArmorStand) continue;
                        Vector to = le.getBoundingBox().getCenter().subtract(eye.toVector());
                        double along = to.dot(dir);
                        if (along < 0 || along > len) continue;
                        if (to.subtract(dir.clone().multiply(along)).length() > 1.8) continue;
                        le.damage(2.5, p);
                        le.setFireTicks(60);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void dragonDash(Player p) {
        if (!tryUse(p, "dragon_blade:left", 10)) return;
        p.setVelocity(flatDir(p).multiply(2.2).setY(0.2));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1f);
        dashHit(p, Particle.WITCH, 10.0, true, 10);
    }

    // ---------------- Sonic Crossbow ----------------

    private void sonicRailgun(Player p) {
        if (!tryUse(p, "sonic_crossbow:right", 15)) return;
        final Location start = p.getEyeLocation();
        final Vector dir = start.getDirection().normalize();
        p.getWorld().playSound(start, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1.0f);
        new BukkitRunnable() {
            int step = 0;
            final Set<UUID> hit = new HashSet<>();
            @Override public void run() {
                step++;
                Location pt = start.clone().add(dir.clone().multiply(step * 3.0));
                if (step > 10 || pt.getBlock().getType().isSolid()) { cancel(); return; }
                World w = pt.getWorld();
                w.spawnParticle(Particle.SONIC_BOOM, pt, 1);
                w.spawnParticle(Particle.END_ROD, pt, 6, 0.4, 0.4, 0.4, 0.05);
                for (LivingEntity le : w.getNearbyLivingEntities(pt, 2.6)) {
                    if (le.equals(p) || le instanceof ArmorStand || !hit.add(le.getUniqueId())) continue;
                    le.damage(14.0, p);
                    le.setVelocity(dir.clone().multiply(3.0).setY(0.7));
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void echoRecovery(Player p) {
        if (!tryUse(p, "sonic_crossbow:left", 8)) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 2));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 2));
        Location l = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.SONIC_BOOM, l, 1);
        p.getWorld().spawnParticle(Particle.HEART, l, 6, 0.5, 0.8, 0.5, 0);
        p.getWorld().playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
    }

    // ---------------- Doom Hammer ----------------

    private void meteorSlam(Player p) {
        if (!tryUse(p, "doom_hammer:right", 10)) return;
        p.setVelocity(flatDir(p).multiply(0.6).setY(1.5));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.5f);
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) { cancel(); return; }
                t++;
                p.setFallDistance(0f);
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation(), 8, 0.4, 0.2, 0.4, 0.05);
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation(), 4, 0.3, 0.1, 0.3, 0.02);
                if (t == 14) p.setVelocity(new Vector(0, -2.6, 0));
                if (t > 6 && (p.isOnGround() || t > 60)) {
                    slam(p);
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void slam(Player p) {
        final Location loc = p.getLocation();
        final World w = loc.getWorld();
        w.strikeLightningEffect(loc);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
        for (LivingEntity le : w.getNearbyLivingEntities(loc, 8)) {
            if (le.equals(p) || le instanceof ArmorStand) continue;
            le.damage(14.0, p);
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 2));
            Vector kb = le.getLocation().toVector().subtract(loc.toVector()).setY(0);
            if (kb.lengthSquared() > 0.01) kb.normalize();
            le.setVelocity(kb.multiply(1.6).setY(0.9));
        }
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (t >= 8) { cancel(); return; }
                double r = 1 + t;
                for (int a = 0; a < 360; a += 12) {
                    double rad = Math.toRadians(a);
                    Location l = loc.clone().add(Math.cos(rad) * r, 0.2, Math.sin(rad) * r);
                    w.spawnParticle(Particle.CLOUD, l, 1, 0.1, 0.1, 0.1, 0.02);
                    w.spawnParticle(Particle.ELECTRIC_SPARK, l, 1, 0.1, 0.1, 0.1, 0.05);
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void beam(Location a, Location b, Particle part) {
        Vector v = b.toVector().subtract(a.toVector());
        double len = v.length();
        if (len < 0.1) return;
        v.normalize();
        for (double d = 0; d < len; d += 0.5) {
            a.getWorld().spawnParticle(part, a.clone().add(v.clone().multiply(d)), 1, 0, 0, 0, 0);
        }
    }

    private void chainLightning(Player p) {
        long rem = remainingMs(p, "doom_hammer:left");
        if (rem > 0) { cdMsg(p, rem); return; }
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 25, 1.0,
                en -> en instanceof LivingEntity && !en.equals(p) && !(en instanceof ArmorStand));
        if (r == null || !(r.getHitEntity() instanceof LivingEntity first)) {
            bar(p, "No target in sight", NamedTextColor.RED);
            return;
        }
        setCd(p, "doom_hammer:left", 12_000);
        final Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            LivingEntity cur = first;
            Location prev = p.getEyeLocation().add(0, -0.3, 0);
            int n = 0;
            @Override public void run() {
                if (cur == null || n >= 5 || !cur.isValid()) { cancel(); return; }
                hit.add(cur.getUniqueId());
                Location cl = cur.getLocation().add(0, 1, 0);
                beam(prev, cl, Particle.ELECTRIC_SPARK);
                cur.getWorld().strikeLightningEffect(cur.getLocation());
                cur.damage(8.0, p);
                prev = cl;
                n++;
                LivingEntity next = null;
                double best = Double.MAX_VALUE;
                for (LivingEntity le : cur.getWorld().getNearbyLivingEntities(cur.getLocation(), 8)) {
                    if (le.equals(p) || le instanceof ArmorStand || hit.contains(le.getUniqueId())) continue;
                    double d = le.getLocation().distanceSquared(cur.getLocation());
                    if (d < best) { best = d; next = le; }
                }
                cur = next;
            }
        }.runTaskTimer(this, 0L, 4L);
    }

    // ---------------- Titan Blade ----------------

    private void giantsCurse(Player p) {
        long rem = remainingMs(p, "titan_blade:right");
        if (rem > 0) { cdMsg(p, rem); return; }
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 25, 0.8,
                en -> en instanceof Player && !en.equals(p));
        if (r == null || !(r.getHitEntity() instanceof Player target)) {
            bar(p, "No player in sight", NamedTextColor.RED);
            return;
        }
        setCd(p, "titan_blade:right", 30_000);
        setScale(target, 2.5);
        markResized(target);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.2f);
        bar(target, "You are cursed! You will explode in 4 seconds!", NamedTextColor.RED);

        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!target.isOnline() || target.isDead()) {
                    if (target.isOnline()) resetScale(target);
                    cancel();
                    return;
                }
                ticks += 4;
                target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, 1.5, 0), 8, 0.6, 0.9, 0.6, 0.02);
                if (ticks % 20 == 0) {
                    target.getWorld().playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f + ticks / 80f);
                }
                if (ticks >= 80) {
                    Location l = target.getLocation().add(0, 1, 0);
                    resetScale(target);
                    World tw = l.getWorld();
                    tw.spawnParticle(Particle.EXPLOSION_EMITTER, l, 1);
                    tw.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.8f);
                    for (LivingEntity le : tw.getNearbyLivingEntities(l, 5)) {
                        if (le.equals(p) || le instanceof ArmorStand) continue;
                        le.damage(12.0, p);
                        Vector kb = le.getLocation().toVector().subtract(l.toVector());
                        kb.setY(0.3);
                        le.setVelocity(kb.normalize().multiply(1.2));
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 4L);
    }

    private void titanForm(Player p) {
        if (!tryUse(p, "titan_blade:left", 40)) return;
        setScale(p, 1.8);
        markResized(p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, 200, 3));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            AttributeInstance m = p.getAttribute(Attribute.MAX_HEALTH);
            if (p.isOnline() && m != null) p.setHealth(Math.min(m.getValue(), p.getHealth() + 16));
        }, 2L);
        Location l = p.getLocation();
        p.getWorld().playSound(l, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.7f);
        p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, l, 1);
        for (LivingEntity le : p.getWorld().getNearbyLivingEntities(l, 5)) {
            if (le.equals(p) || le instanceof ArmorStand) continue;
            le.damage(6.0, p);
            Vector kb = le.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (kb.lengthSquared() > 0.01) kb.normalize();
            le.setVelocity(kb.multiply(1.3).setY(0.6));
        }
        bar(p, "TITAN FORM for 10 seconds", NamedTextColor.AQUA);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && resized.contains(p.getUniqueId())) resetScale(p);
        }, 200L);
    }

    // ---------------- Auto Bow ----------------

    private void arrowStorm(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        long rem = remainingMs(p, "auto_bow:right");
        if (rem > 0) { cdMsg(p, rem); return; }
        int stored = getStored(held);
        if (stored <= 0) {
            int have = 0;
            for (ItemStack it : p.getInventory().getContents()) {
                if (it != null && it.getType() == Material.ARROW) have += it.getAmount();
            }
            int take = Math.min(have, MAX_ARROWS);
            if (take <= 0) {
                bar(p, "No arrows! Keep arrows in your inventory (or sneak + left-click to fill the quiver)", NamedTextColor.RED);
                return;
            }
            p.getInventory().removeItem(new ItemStack(Material.ARROW, take));
            setStored(held, take);
            p.getInventory().setItemInMainHand(held);
            stored = take;
        }
        setCd(p, "auto_bow:right", 10_000);
        final int shots = Math.min(stored, MAX_ARROWS);
        new BukkitRunnable() {
            int fired = 0;
            @Override public void run() {
                ItemStack cur = p.getInventory().getItemInMainHand();
                if (!p.isOnline() || p.isDead() || !"auto_bow".equals(weaponId(cur)) || fired >= shots) {
                    cancel();
                    return;
                }
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                Vector spread = new Vector((rnd.nextDouble() - 0.5) * 0.2, (rnd.nextDouble() - 0.5) * 0.2,
                        (rnd.nextDouble() - 0.5) * 0.2);
                Arrow a = p.launchProjectile(Arrow.class, p.getEyeLocation().getDirection().multiply(3.0).add(spread));
                a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                a.setDamage(4.0);
                a.getPersistentDataContainer().set(boomKey, PersistentDataType.BYTE, (byte) 1);
                fired++;
                setStored(cur, Math.max(0, getStored(cur) - 1));
                p.getInventory().setItemInMainHand(cur);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.8f, 1.3f);
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void openQuiver(Player p) {
        StorageHolder h = new StorageHolder();
        Inventory inv = Bukkit.createInventory(h, 9, Component.text("Quiver - put arrows here"));
        h.inv = inv;
        p.openInventory(inv);
    }

    // ---------------- Shared dash helper ----------------

    private void dashHit(Player p, Particle particle, double dmg, boolean fire, int ticks) {
        new BukkitRunnable() {
            int t = 0;
            final Set<UUID> hit = new HashSet<>();
            @Override public void run() {
                if (!p.isOnline() || p.isDead() || t++ >= ticks) { cancel(); return; }
                Location l = p.getLocation().add(0, 1, 0);
                p.getWorld().spawnParticle(particle, l, 12, 0.4, 0.4, 0.4, 0.02);
                for (LivingEntity le : p.getWorld().getNearbyLivingEntities(l, 2.5)) {
                    if (le.equals(p) || le instanceof ArmorStand || !hit.add(le.getUniqueId())) continue;
                    le.damage(dmg, p);
                    if (fire) le.setFireTicks(60);
                    Vector kb = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                    if (kb.lengthSquared() > 0.01) kb.normalize();
                    le.setVelocity(kb.multiply(1.0).setY(0.4));
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private void openMenu(Player p) {
        MenuHolder h = new MenuHolder();
        Inventory inv = Bukkit.createInventory(h, 9, Component.text("SmpWeapons - click to take"));
        h.inv = inv;
        int i = 0;
        for (String id : weapons.keySet()) inv.setItem(i++, createItem(id));
        p.openInventory(inv);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equalsIgnoreCase("rc")) {
            if (!(s instanceof Player self)) {
                msg(s, "Only players can use this command.", NamedTextColor.RED);
                return true;
            }
            resetCd(self);
            msg(self, "All your weapon cooldowns were reset.", NamedTextColor.GREEN);
            return true;
        }

        if (a.length == 0) {
            if (s instanceof Player pl) openMenu(pl);
            else msg(s, "Usage: /smpweapons give <player> <weapon>", NamedTextColor.YELLOW);
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "give" -> {
                if (a.length < 3) {
                    msg(s, "Usage: /smpweapons give <player> <weapon>", NamedTextColor.YELLOW);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) {
                    msg(s, "Player not found.", NamedTextColor.RED);
                    return true;
                }
                String id = a[2].toLowerCase();
                if (!weapons.containsKey(id)) {
                    msg(s, "Unknown weapon. Use /smpweapons list", NamedTextColor.RED);
                    return true;
                }
                give(target, createItem(id));
                msg(s, "Gave " + weapons.get(id).name() + " to " + target.getName() + ".", NamedTextColor.GREEN);
            }
            case "list" -> msg(s, "Weapons: " + String.join(", ", weapons.keySet()), NamedTextColor.GOLD);
            case "reset" -> {
                Player target = null;
                if (a.length >= 2) target = Bukkit.getPlayerExact(a[1]);
                else if (s instanceof Player pl) target = pl;
                if (target == null) {
                    msg(s, "Player not found.", NamedTextColor.RED);
                    return true;
                }
                resetCd(target);
                msg(s, "Cooldowns reset for " + target.getName() + ".", NamedTextColor.GREEN);
            }
            case "start" -> {
                int secs = 10;
                if (a.length >= 2) {
                    try {
                        secs = Math.max(1, Math.min(60, Integer.parseInt(a[1])));
                    } catch (NumberFormatException ex) {
                        msg(s, "Seconds must be a number.", NamedTextColor.RED);
                        return true;
                    }
                }
                startCountdown(secs);
            }
            default -> msg(s, "Usage: /smpweapons [give <player> <weapon> | list | reset [player] | start [seconds]]",
                    NamedTextColor.YELLOW);
        }
        return true;
    }

    private void startCountdown(int seconds) {
        new BukkitRunnable() {
            int t = seconds;
            @Override public void run() {
                if (t <= 0) {
                    Title go = Title.title(
                            Component.text("THE SMP HAS BEGUN!", NamedTextColor.GREEN, TextDecoration.BOLD),
                            Component.text("Good luck, survivors", NamedTextColor.YELLOW),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofMillis(500)));
                    for (Player pl : Bukkit.getOnlinePlayers()) {
                        pl.showTitle(go);
                        pl.playSound(pl.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
                    }
                    cancel();
                    return;
                }
                Title tick = Title.title(
                        Component.text(String.valueOf(t), NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("The SMP begins soon...", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    pl.showTitle(tick);
                    pl.playSound(pl.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, t <= 3 ? 1.6f : 1.0f);
                }
                t--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!c.getName().equalsIgnoreCase("smpweapons") || a.length == 0) return out;
        if (a.length == 1) {
            out.addAll(List.of("give", "list", "reset", "start"));
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("reset"))) {
            for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            out.addAll(weapons.keySet());
        }
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
