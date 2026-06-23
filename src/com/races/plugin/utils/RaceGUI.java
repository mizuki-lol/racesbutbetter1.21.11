package com.races.plugin.utils;

import com.races.plugin.races.Race;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class RaceGUI {

    public static final String TITLE = "⚔ Выбор расы ⚔";

    private static final int[] SLOTS   = { 19, 21, 23, 25, 30, 32 };
    private static final Material[] ICONS = {
        Material.FEATHER, Material.HEART_OF_THE_SEA,
        Material.ROTTEN_FLESH, Material.LIGHTNING_ROD,
        Material.DRAGON_EGG, Material.PACKED_ICE
    };
    private static final NamedTextColor[] COLORS = {
        NamedTextColor.WHITE, NamedTextColor.AQUA,
        NamedTextColor.DARK_GREEN, NamedTextColor.YELLOW,
        NamedTextColor.DARK_PURPLE, NamedTextColor.DARK_AQUA
    };
    private static final List<List<Component>> LORE = List.of(
        List.of(l("✦ Иммунитет к урону от падения"),
                l("✦ 2×Shift — рывок 5 блоков (КД 10с)"),
                l("✦ 5% шанс: замедл.+слабость врагу 7с+подсветка"),
                l("§eV2: §72 рывка подряд → КД 10с")),
        List.of(l("✦ Дыхание под водой + скорость в воде"),
                l("✦ 10% шанс: игнорировать 50% брони"),
                l("§eV2: §7Сила2+Скорость2+Сопр1 в воде"),
                l("§eV2: §72×Shift — режим воды 10с")),
        List.of(l("✦ 10% шанс: украсть 1 сердце + подсветка"),
                l("✦ 2×Shift — гарпун (КД 8с)"),
                l("§eV2: §7+10% стан при ударе")),
        List.of(l("✦ Пассивная Скорость 1"),
                l("✦ 10% → цепь: 2x→+25%→+50%→+100%→КД 90с"),
                l("✦ Частицы дыма+подсветка при ударе"),
                l("§eV2: §72×Shift — Форма Бога Молний 10с")),
        List.of(l("✦ Постоянная огнеупорность"),
                l("✦ 2×Shift — фаербол (4 серд.+стан, игнор брони)"),
                l("✦ 10% шанс: удар ×1.5"),
                l("§eV2: §72×Shift — неуязвимость 5с (КД 45с)")),
        List.of(l("✦ 2×Shift — луч заморозки 15 блоков (5с)"),
                l("✦ 10% шанс: стан 1с + звук наковальни + подсветка"),
                l("§eV2: §73 удара подряд — гарантированный стан"))
    );

    public static void open(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54,
            Component.text(TITLE, NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));

        ItemStack glass = glass();
        for (int i = 0; i < 54; i++) inv.setItem(i, glass);

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta bm = book.getItemMeta();
        bm.displayName(Component.text("Выбор расы", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        bm.lore(List.of(
            l("Нажмите на расу чтобы выбрать её."),
            Component.text("/racechange <игрок> <раса>", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
        ));
        book.setItemMeta(bm);
        inv.setItem(4, book);

        Race[] races = Race.values();
        for (int i = 0; i < races.length; i++) inv.setItem(SLOTS[i], buildItem(races[i], i));
        p.openInventory(inv);
    }

    private static ItemStack buildItem(Race race, int idx) {
        ItemStack item = new ItemStack(ICONS[idx]);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text("✦ " + race.getDisplay(), COLORS[idx])
            .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        m.lore(LORE.get(idx));
        item.setItemMeta(m);
        return item;
    }

    public static Race getBySlot(int slot) {
        Race[] races = Race.values();
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) return races[i];
        return null;
    }

    private static ItemStack glass() {
        ItemStack g = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m  = g.getItemMeta();
        m.displayName(Component.text(" "));
        g.setItemMeta(m);
        return g;
    }

    private static Component l(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
