package com.races.plugin.listeners;

import com.races.plugin.RacesPlugin;
import com.races.plugin.managers.CooldownManager;
import com.races.plugin.races.Race;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class AbilityListener implements Listener {

    private final RacesPlugin plugin;
    private final CooldownManager cd;
    private final Random rng = new Random();

    // Double-shift detection
    private final Map<UUID, Long> lastShift = new HashMap<>();
    private static final long DOUBLE_MS = 400;

    // Angel V2 dash counter
    private final Map<UUID, Integer> angelDashUsed = new HashMap<>();

    // Electro chain stage: 0=idle 1=next×2 2=+25% 3=+50% 4=+100%
    private final Map<UUID, Integer> electroStage = new HashMap<>();

    // Tundra V2 combo counter per attacker→victim
    private final Map<UUID, Map<UUID, Integer>> tundraCombo = new HashMap<>();

    // Active flags
    private final Set<UUID> electroGodMode   = new HashSet<>();
    private final Set<UUID> dragonInvincible = new HashSet<>();

    public AbilityListener(RacesPlugin plugin) {
        this.plugin = plugin;
        this.cd     = plugin.getCooldowns();
        startPassiveTick();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PASSIVE TICK every 10 ticks (0.5s)
    // ═══════════════════════════════════════════════════════════════════════════
    private void startPassiveTick() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) applyPassives(p);
        }, 20L, 10L);
    }

    public void applyPassives(Player p) {
        Race race = plugin.getRaceManager().getRace(p);
        if (race == null) return;
        boolean v2 = plugin.getRaceManager().isV2(p);
        switch (race) {
            case SHARK   -> tickShark(p, v2);
            case ELECTRO -> add(p, PotionEffectType.SPEED, 40, 0);
            case DRAGON  -> add(p, PotionEffectType.FIRE_RESISTANCE, 40, 0);
            default -> {}
        }
    }

    private void tickShark(Player p, boolean v2) {
        if (!p.isInWater()) return;
        add(p, PotionEffectType.WATER_BREATHING, 40, 0);
        add(p, PotionEffectType.DOLPHINS_GRACE,  40, 0);
        if (v2) {
            add(p, PotionEffectType.SPEED,     40, 1);
            add(p, PotionEffectType.STRENGTH,  40, 1);
            add(p, PotionEffectType.RESISTANCE,40, 0);
        } else {
            add(p, PotionEffectType.SPEED, 40, 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  FALL DAMAGE — ANGEL immune
    // ═══════════════════════════════════════════════════════════════════════════
    @EventHandler(priority = EventPriority.HIGH)
    public void onFall(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (is(p, Race.ANGEL)) e.setCancelled(true);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DRAGON INVINCIBILITY
    // ═══════════════════════════════════════════════════════════════════════════
    @EventHandler(priority = EventPriority.HIGH)
    public void onDragonInvincible(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (dragonInvincible.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DOUBLE-SHIFT
    // ═══════════════════════════════════════════════════════════════════════════
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p   = e.getPlayer();
        UUID   uid = p.getUniqueId();
        long   now = System.currentTimeMillis();

        if (now - lastShift.getOrDefault(uid, 0L) < DOUBLE_MS) {
            lastShift.put(uid, 0L);
            onDoubleShift(p);
        } else {
            lastShift.put(uid, now);
        }
    }

    private void onDoubleShift(Player p) {
        Race r = plugin.getRaceManager().getRace(p);
        if (r == null) return;
        boolean v2 = plugin.getRaceManager().isV2(p);
        switch (r) {
            case ANGEL   -> doAngelDash(p, v2);
            case SHARK   -> { if (v2) doSharkMode(p); }
            case GHOUL   -> doGhoulHarpoon(p);
            case ELECTRO -> { if (v2) doElectroGod(p); }
            case DRAGON  -> { if (v2) doDragonShield(p); else doDragonFireball(p); }
            case TUNDRA  -> doTundraBeam(p);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ANGEL — dash
    // ═══════════════════════════════════════════════════════════════════════════
    private void doAngelDash(Player p, boolean v2) {
        UUID uid = p.getUniqueId();
        if (cd.has(uid, "dash")) {
            p.sendActionBar(Component.text("Рывок: " + cd.remaining(uid, "dash") + "с", NamedTextColor.RED));
            return;
        }
        if (v2) {
            int used = angelDashUsed.getOrDefault(uid, 0);
            dash(p);
            used++;
            if (used >= 2) { angelDashUsed.put(uid, 0); cd.set(uid, "dash", 10_000); }
            else { angelDashUsed.put(uid, used); p.sendActionBar(Component.text("✦ Рывок 1/2!", NamedTextColor.WHITE)); }
        } else {
            dash(p);
            cd.set(uid, "dash", 10_000);
        }
    }

    private void dash(Player p) {
        Vector dir = p.getLocation().getDirection().normalize().multiply(5.0);
        dir.setY(Math.max(dir.getY(), 0.2));
        p.setVelocity(dir);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 25, 0.3, 0.3, 0.3, 0.05);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 1f, 1.5f);
        p.sendActionBar(Component.text("✦ Рывок!", NamedTextColor.WHITE));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SHARK V2 — water mode on land
    // ═══════════════════════════════════════════════════════════════════════════
    private void doSharkMode(Player p) {
        UUID uid = p.getUniqueId();
        if (cd.has(uid, "sharkmode")) {
            p.sendActionBar(Component.text("Режим воды: " + cd.remaining(uid, "sharkmode") + "с", NamedTextColor.RED));
            return;
        }
        add(p, PotionEffectType.SPEED,      200, 1);
        add(p, PotionEffectType.STRENGTH,   200, 1);
        add(p, PotionEffectType.RESISTANCE, 200, 0);
        add(p, PotionEffectType.DOLPHINS_GRACE, 200, 0);
        p.sendActionBar(Component.text("✦ Режим глубины — 10с!", NamedTextColor.AQUA));
        p.getWorld().spawnParticle(Particle.DRIPPING_WATER, p.getLocation(), 30, 0.5, 1, 0.5, 0);
        cd.set(uid, "sharkmode", 30_000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  GHOUL — harpoon
    // ═══════════════════════════════════════════════════════════════════════════
    private void doGhoulHarpoon(Player p) {
        UUID uid = p.getUniqueId();
        if (cd.has(uid, "harpoon")) {
            p.sendActionBar(Component.text("Гарпун: " + cd.remaining(uid, "harpoon") + "с", NamedTextColor.RED));
            return;
        }
        RayTraceResult hit = p.getWorld().rayTrace(
            p.getEyeLocation(), p.getLocation().getDirection(), 20,
            FluidCollisionMode.NEVER, true, 0.5, e -> e instanceof LivingEntity && e != p);

        if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
            Vector v = p.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(1.6);
            v.setY(0.5);
            target.setVelocity(v);
            p.sendActionBar(Component.text("✦ Гарпун — цель притянута!", NamedTextColor.DARK_GREEN));
        } else if (hit != null && hit.getHitBlock() != null) {
            Vector v = hit.getHitBlock().getLocation().add(0.5,0.5,0.5).toVector()
                .subtract(p.getLocation().toVector()).normalize().multiply(1.6);
            v.setY(0.6);
            p.setVelocity(v);
            p.sendActionBar(Component.text("✦ Гарпун — рывок к блоку!", NamedTextColor.DARK_GREEN));
        } else {
            p.sendActionBar(Component.text("Нет цели для гарпуна!", NamedTextColor.GRAY));
            return;
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1f, 0.5f);
        cd.set(uid, "harpoon", 8_000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ELECTRO V2 — god mode
    // ═══════════════════════════════════════════════════════════════════════════
    private void doElectroGod(Player p) {
        UUID uid = p.getUniqueId();
        if (cd.has(uid, "godmode")) {
            p.sendActionBar(Component.text("Форма бога: " + cd.remaining(uid, "godmode") + "с", NamedTextColor.RED));
            return;
        }
        electroGodMode.add(uid);
        add(p, PotionEffectType.ABSORPTION, 200, 1); // +4 hearts
        add(p, PotionEffectType.GLOWING,    200, 0);
        p.getWorld().strikeLightningEffect(p.getLocation());
        p.sendActionBar(Component.text("⚡ ФОРМА БОГА МОЛНИЙ — 10с!", NamedTextColor.YELLOW));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            electroGodMode.remove(uid);
            if (p.isOnline()) p.sendActionBar(Component.text("Форма бога закончилась", NamedTextColor.GRAY));
        }, 200L);
        cd.set(uid, "godmode", 60_000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DRAGON — fireball
    // ═══════════════════════════════════════════════════════════════════════════
    private void doDragonFireball(Player p) {
        UUID uid = p.getUniqueId();
        if (cd.has(uid, "fireball")) {
            p.sendActionBar(Component.text("Фаербол: " + cd.remaining(uid, "fireball") + "с", NamedTextColor.RED));
            return;
        }
        Location eye = p.getEyeLocation();
        Vector   dir = eye.getDirection().normalize();
        p.getWorld().spawn(eye.add(dir.clone().multiply(2)), SmallFireball.class, f -> {
            f.setShooter(p);
            f.setDirection(dir.clone().multiply(2));
            f.setIsIncendiary(false);
            f.setYield(0f);
        });
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
        p.sendActionBar(Component.text("✦ Фаербол!", NamedTextColor.DARK_PURPLE));
        cd.set(uid, "fireball", 12_000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DRAGON V2 — shield
    // ═══════════════════════════════════════════════════════════════════════════
    private void doDragonShield(Player p) {
        UUID uid = p.getUniqueId();
        if (cd.has(uid, "dragonshield")) {
            p.sendActionBar(Component.text("Щит: " + cd.remaining(uid, "dragonshield") + "с", NamedTextColor.RED));
            return;
        }
        dragonInvincible.add(uid);
        add(p, PotionEffectType.GLOWING, 100, 0);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 0.7f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation(), 50, 0.5, 0.8, 0.5, 0.2);
        p.sendActionBar(Component.text("✦ Неуязвимость — 5с!", NamedTextColor.DARK_PURPLE));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            dragonInvincible.remove(uid);
            if (p.isOnline()) p.sendActionBar(Component.text("Неуязвимость закончилась", NamedTextColor.GRAY));
        }, 100L);
        cd.set(uid, "dragonshield", 45_000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TUNDRA — beam
    // ═══════════════════════════════════════════════════════════════════════════
    private void doTundraBeam(Player p) {
        UUID uid = p.getUniqueId();
        if (cd.has(uid, "beam")) {
            p.sendActionBar(Component.text("Луч: " + cd.remaining(uid, "beam") + "с", NamedTextColor.RED));
            return;
        }
        Location origin = p.getEyeLocation();
        Vector   dir    = origin.getDirection().normalize();
        Set<LivingEntity> hit = new HashSet<>();

        for (int i = 1; i <= 15; i++) {
            Location pt = origin.clone().add(dir.clone().multiply(i));
            p.getWorld().spawnParticle(Particle.SNOWFLAKE, pt, 4, 0.3, 0.3, 0.3, 0);
            for (Entity ent : pt.getWorld().getNearbyEntities(pt, 1.5, 1.5, 1.5)) {
                if (ent instanceof LivingEntity le && le != p && !hit.contains(le)) {
                    hit.add(le);
                    add(le, PotionEffectType.SLOWNESS,       100, 9);
                    add(le, PotionEffectType.MINING_FATIGUE, 100, 3);
                    add(le, PotionEffectType.WEAKNESS,       100, 2);
                    if (le instanceof Player vp)
                        vp.sendActionBar(Component.text("✦ Заморожены Тундрой на 5с!", NamedTextColor.DARK_AQUA));
                }
            }
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.5f);
        p.sendActionBar(Component.text("✦ Луч заморозки!", NamedTextColor.DARK_AQUA));
        cd.set(uid, "beam", 20_000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ON HIT — Dragon fireball
    // ═══════════════════════════════════════════════════════════════════════════
    @EventHandler(priority = EventPriority.HIGH)
    public void onFireballHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof SmallFireball fb)) return;
        if (!(fb.getShooter() instanceof Player shooter)) return;
        if (!is(shooter, Race.DRAGON)) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        e.setCancelled(true);
        target.damage(8.0, shooter); // 4 hearts, true damage
        add(target, PotionEffectType.SLOWNESS,       40, 9);
        add(target, PotionEffectType.MINING_FATIGUE, 40, 3);
        target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0,1,0), 15, 0.3, 0.3, 0.3, 0.05);
        if (target instanceof Player vp)
            vp.sendActionBar(Component.text("✦ Оглушение от фаербола Дракона!", NamedTextColor.DARK_PURPLE));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ON HIT — melee
    // ═══════════════════════════════════════════════════════════════════════════
    @EventHandler(priority = EventPriority.HIGH)
    public void onMeleeHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player a)) return;
        if (!(e.getEntity() instanceof LivingEntity v)) return;
        Race race = plugin.getRaceManager().getRace(a);
        if (race == null) return;
        boolean v2 = plugin.getRaceManager().isV2(a);
        switch (race) {
            case ANGEL   -> hitAngel(e, a, v);
            case SHARK   -> hitShark(e, a, v);
            case GHOUL   -> hitGhoul(e, a, v, v2);
            case ELECTRO -> hitElectro(e, a, v, v2);
            case DRAGON  -> hitDragon(e, a);
            case TUNDRA  -> hitTundra(e, a, v, v2);
        }
    }

    private void hitAngel(EntityDamageByEntityEvent e, Player a, LivingEntity v) {
        if (rng.nextInt(100) < 5) {
            add(v, PotionEffectType.SLOWNESS, 140, 1);
            add(v, PotionEffectType.WEAKNESS, 140, 0);
            glow(v, 140);
            a.sendActionBar(Component.text("✦ Ангельское проклятие!", NamedTextColor.WHITE));
            if (v instanceof Player vp) vp.sendActionBar(Component.text("✦ Проклятие Ангела!", NamedTextColor.WHITE));
        }
    }

    private void hitShark(EntityDamageByEntityEvent e, Player a, LivingEntity v) {
        if (rng.nextInt(100) < 10) {
            e.setCancelled(true);
            v.damage(e.getDamage() * 2.0, a);
            a.sendActionBar(Component.text("✦ Пробитие брони!", NamedTextColor.AQUA));
            v.getWorld().spawnParticle(Particle.CRIT, v.getLocation().add(0,1,0), 12, 0.3, 0.3, 0.3, 0.1);
        }
    }

    private void hitGhoul(EntityDamageByEntityEvent e, Player a, LivingEntity v, boolean v2) {
        if (rng.nextInt(100) < 10) {
            var attr = a.getAttribute(Attribute.MAX_HEALTH);
            double max = attr != null ? attr.getValue() : 20.0;
            a.setHealth(Math.min(a.getHealth() + 2.0, max));
            v.getWorld().spawnParticle(Particle.HEART, a.getLocation().add(0,2,0), 6, 0.3, 0.3, 0.3, 0);
            glow(v, 100);
            a.sendActionBar(Component.text("✦ Кража жизни!", NamedTextColor.DARK_GREEN));
            if (v instanceof Player vp) vp.sendActionBar(Component.text("✦ Гуль украл ваше сердце!", NamedTextColor.DARK_GREEN));
        }
        if (v2 && rng.nextInt(100) < 10) stun(v, a);
    }

    private void hitElectro(EntityDamageByEntityEvent e, Player a, LivingEntity v, boolean v2) {
        UUID uid = a.getUniqueId();
        v.getWorld().spawnParticle(Particle.LARGE_SMOKE, v.getLocation().add(0,1,0), 8, 0.3, 0.3, 0.3, 0.05);
        glow(v, 60);

        double base = e.getDamage();
        if (electroGodMode.contains(uid)) base *= 1.2;

        int stage = electroStage.getOrDefault(uid, 0);
        if (cd.has(uid, "electrochain")) { if (electroGodMode.contains(uid)) e.setDamage(base); return; }

        if (stage == 0) {
            if (rng.nextInt(100) < 10) { electroStage.put(uid, 1); a.sendActionBar(Component.text("✦ Цепь начата! Следующий: ×2", NamedTextColor.YELLOW)); }
        } else if (stage == 1) {
            e.setDamage(base * 2.0); electroStage.put(uid, 2);
            a.getWorld().strikeLightningEffect(v.getLocation());
            a.sendActionBar(Component.text("✦ Удар ×2! Следующий: +25%", NamedTextColor.YELLOW));
        } else if (stage == 2) {
            e.setDamage(base * 1.25); electroStage.put(uid, 3);
            a.sendActionBar(Component.text("✦ Удар +25%! Следующий: +50%", NamedTextColor.YELLOW));
        } else if (stage == 3) {
            e.setDamage(base * 1.5); electroStage.put(uid, 4);
            a.sendActionBar(Component.text("✦ Удар +50%! Следующий: +100%", NamedTextColor.YELLOW));
        } else if (stage == 4) {
            e.setDamage(base * 2.0); electroStage.put(uid, 0);
            a.getWorld().strikeLightningEffect(v.getLocation());
            a.getWorld().strikeLightningEffect(a.getLocation());
            a.sendActionBar(Component.text("✦ ФИНАЛ ×2! КД 90с", NamedTextColor.GOLD));
            cd.set(uid, "electrochain", 90_000);
        }
    }

    private void hitDragon(EntityDamageByEntityEvent e, Player a) {
        if (rng.nextInt(100) < 10) {
            e.setDamage(e.getDamage() * 1.5);
            a.sendActionBar(Component.text("✦ Драконий удар ×1.5!", NamedTextColor.DARK_PURPLE));
        }
    }

    private void hitTundra(EntityDamageByEntityEvent e, Player a, LivingEntity v, boolean v2) {
        UUID aUid = a.getUniqueId();
        UUID vUid = v.getUniqueId();
        if (v2) {
            Map<UUID, Integer> combo = tundraCombo.computeIfAbsent(aUid, k -> new HashMap<>());
            // reset combo if different target
            combo.keySet().removeIf(k -> !k.equals(vUid));
            int hits = combo.getOrDefault(vUid, 0) + 1;
            combo.put(vUid, hits);
            if (hits >= 3) { combo.put(vUid, 0); stun(v, a); a.sendActionBar(Component.text("✦ Гарантированный стан (3/3)!", NamedTextColor.DARK_AQUA)); return; }
            else a.sendActionBar(Component.text("Удар " + hits + "/3", NamedTextColor.DARK_AQUA));
        }
        if (rng.nextInt(100) < 10) stun(v, a);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ITEM USE — Upgrader & Switcher
    // ═══════════════════════════════════════════════════════════════════════════
    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getHand() == EquipmentSlot.OFF_HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return;

        if (plugin.getItemManager().isUpgrader(item)) {
            e.setCancelled(true);
            if (!plugin.getRaceManager().hasRace(p)) { p.sendMessage(Component.text(plugin.prefix() + "Сначала выберите расу!", NamedTextColor.RED)); return; }
            if (plugin.getRaceManager().isV2(p))     { p.sendMessage(Component.text(plugin.prefix() + "Ваша раса уже версии 2!", NamedTextColor.YELLOW)); return; }
            plugin.getRaceManager().upgrade(p);
            consume(p, item);
            Race r = plugin.getRaceManager().getRace(p);
            p.sendMessage(Component.text(plugin.prefix() + "Раса ", NamedTextColor.GOLD)
                .append(Component.text(r.getDisplay(), NamedTextColor.YELLOW))
                .append(Component.text(" улучшена до версии 2!", NamedTextColor.GOLD)));
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation(), 60, 0.5, 0.8, 0.5, 0.2);
            p.getWorld().playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            return;
        }
        if (plugin.getItemManager().isSwitcher(item)) {
            e.setCancelled(true);
            Race newRace = Race.random();
            plugin.getRaceManager().setRace(p, newRace);
            consume(p, item);
            p.sendMessage(Component.text(plugin.prefix() + "Ваша раса изменена на ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(newRace.getDisplay(), NamedTextColor.YELLOW)));
            p.getWorld().spawnParticle(Particle.WITCH, p.getLocation(), 30, 0.5, 0.8, 0.5, 0.1);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 1f, 0.5f);
            applyPassives(p);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    private boolean is(Player p, Race race) { return race == plugin.getRaceManager().getRace(p); }

    private void add(LivingEntity e, PotionEffectType t, int dur, int amp) {
        e.addPotionEffect(new PotionEffect(t, dur, amp, false, false, false));
    }

    private void glow(LivingEntity e, int ticks) {
        add(e, PotionEffectType.GLOWING, ticks, 0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> e.removePotionEffect(PotionEffectType.GLOWING), ticks);
    }

    private void stun(LivingEntity v, Player a) {
        add(v, PotionEffectType.SLOWNESS,       20, 9);
        add(v, PotionEffectType.MINING_FATIGUE, 20, 3);
        glow(v, 40);
        v.getWorld().playSound(v.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 1.5f);
        v.getWorld().spawnParticle(Particle.SNOWFLAKE, v.getLocation().add(0,1,0), 12, 0.4, 0.4, 0.4, 0);
        if (v instanceof Player vp) vp.sendActionBar(Component.text("✦ Стан от Тундры!", NamedTextColor.DARK_AQUA));
        a.sendActionBar(Component.text("✦ Стан!", NamedTextColor.DARK_AQUA));
    }

    private void consume(Player p, ItemStack item) {
        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else p.getInventory().setItemInMainHand(null);
    }
}
