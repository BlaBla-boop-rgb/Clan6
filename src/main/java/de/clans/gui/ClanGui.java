package de.clans.gui;

import de.clans.Clan;
import de.clans.ClanColor;
import de.clans.ClanManager;
import de.clans.ClansPlugin;
import de.clans.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Eine geöffnete Clan-GUI (54 Slots, komplett aus Kacheln aufgebaut, statisch - keine Animation).
 *
 * Layout:
 *   Reihe 0      4 Tabs (je 2 Slots breit) | Schließen
 *   Reihe 1-4    Inhalt (Spalten 0-7: 4x2 große 2x2-Karten ODER 32 kleine Einträge) + Scrollleiste (Spalte 8)
 *   Reihe 5      Zurück | Aktionen | Meldung | Aktionen
 *
 * Es wird nur gesendet, was sich wirklich geändert hat (Diff) - dadurch kein Flackern und keine
 * zurückspringenden Tooltips.
 */
public final class ClanGui implements InventoryHolder {

    private static final int PER_PAGE = 32;
    private static final int[] SCROLL = {17, 26, 35, 44};
    private static final int SLOT_CLOSE = 8;
    private static final int BACK = 45;
    private static final int ACT_A = 47;
    private static final int TOAST = 49;
    private static final int ACT_B = 51;
    private static final int ACT_C = 52;
    private static final NamespacedKey TOOLTIP = new NamespacedKey("clans", "gui");
    private static final String DEFAULT_ACCENT = "#ffb347";

    private record Button(ItemStack item, Consumer<ClickType> action) {}

    private record Tab(View view, int slot, Icon icon, String name, String desc) {}

    private record AllyEntry(Clan clan, boolean ally) {}

    private final ClansPlugin plugin;
    private final Player player;
    private final Inventory inventory;
    private final boolean custom;
    private final boolean sounds;
    private final String stamp;

    private final Map<Integer, Button> buttons = new HashMap<>();
    private final Map<Integer, String> marked = new HashMap<>();
    private final ItemStack[] stage = new ItemStack[54];
    private final ItemStack[] sent = new ItemStack[54];

    private View view;
    private int page;
    private String selected;
    private String c1 = "";
    private String c2 = "";

    private String confirmText = "";
    private Supplier<Boolean> confirmAct;
    private View confirmBack = View.HOME;
    private View confirmNext = View.HOME;

    private String toast;
    private long toastUntil;

    private int sinceRefresh;
    private int reveal = 9;
    private BukkitTask revealTask;
    private Sound confirmSound = Sound.BLOCK_NOTE_BLOCK_PLING;
    private float confirmPitch = 1.6f;

    public ClanGui(ClansPlugin plugin, Player player, View start) {
        this.plugin = plugin;
        this.player = player;
        var cfg = plugin.getConfig();
        this.sounds = cfg.getBoolean("gui.sounds", true);
        this.custom = plugin.pack().custom(player);
        Clan c = clan();
        this.stamp = computeStamp();
        this.view = start != null ? start : (c != null ? View.OVERVIEW : View.HOME);
        if (c != null) {
            c1 = c.c1;
            c2 = c.c2;
        }
        this.reveal = custom && cfg.getBoolean("gui.open-animation", true) ? 0 : 9;
        this.inventory = Bukkit.createInventory(this, 54, title());
        render();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public Player player() {
        return player;
    }

    public View view() {
        return view;
    }

    // ------------------------------------------------------------ Grundlagen

    private Clan clan() {
        return plugin.clans().getClan(player);
    }

    private boolean leader() {
        return plugin.clans().isLeader(player);
    }

    private String computeStamp() {
        Clan c = clan();
        return c == null ? "-" : c.name + "|" + c.c1 + "|" + c.c2;
    }

    private Component title() {
        Clan c = clan();
        String g1 = DEFAULT_ACCENT, g2 = "#ffe29a", name = "CLANS";
        if (c != null) {
            name = c.name.toUpperCase(Locale.ROOT);
            String h1 = ClanColor.toHex(c.c1);
            if (h1 != null) {
                g1 = h1;
                String h2 = ClanColor.toHex(c.c2);
                g2 = h2 != null ? h2 : h1;
            }
        }
        // Mit Pack liegt der Titel auf der roten Leiste -> weiß; ohne Pack Farbverlauf
        if (custom) return Text.MM.deserialize("<white><bold>" + name + "</bold>");
        return Text.MM.deserialize("<gradient:" + g1 + ":" + g2 + "><bold>" + name + "</bold></gradient>");
    }

    private ClanColor accentColor() {
        Clan c = clan();
        if (c != null) {
            ClanColor col = ClanColor.nearest(c.c1);
            if (col != null) return col;
        }
        return ClanColor.ORANGE;
    }

    private String accentHex() {
        Clan c = clan();
        if (c != null) {
            String h = ClanColor.toHex(c.c1);
            if (h != null) return h;
        }
        return DEFAULT_ACCENT;
    }

    private void snd(Sound s, float pitch) {
        if (sounds) player.playSound(player.getLocation(), s, 0.6f, pitch);
    }

    private void click() {
        snd(Sound.UI_BUTTON_CLICK, 1.3f);
    }

    private void pick() {
        snd(Sound.BLOCK_NOTE_BLOCK_CHIME, 1.5f);
    }

    private void result(boolean ok, Sound okSound, float pitch) {
        if (ok) snd(okSound, pitch);
        else snd(Sound.ENTITY_VILLAGER_NO, 1.0f);
    }

    private void tabClick() {
        snd(Sound.ITEM_BOOK_PAGE_TURN, 1.3f);
    }

    private void pickScale(int idx) {
        snd(Sound.BLOCK_NOTE_BLOCK_CHIME, Math.min(2.0f, 0.7f + idx * 0.09f));
    }

    private void result(boolean ok) {
        if (ok) snd(Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        else snd(Sound.ENTITY_VILLAGER_NO, 1.0f);
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private void prompt(String hint, Consumer<String> cb, View backTo) {
        later(() -> plugin.prompts().ask(player, hint, cb, () -> plugin.gui().open(player, backTo)));
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** i-ter Inhalts-Slot: Reihen 1-4, Spalten 0-7. */
    private static int cs(int i) {
        return (1 + i / 8) * 9 + (i % 8);
    }

    private void set(int slot, ItemStack it, Consumer<ClickType> act) {
        if (custom && it != null) {
            ItemMeta m = it.getItemMeta();
            if (m != null && !m.isHideTooltip()) {
                m.setTooltipStyle(TOOLTIP);
                it.setItemMeta(m);
            }
        }
        buttons.put(slot, new Button(it, act));
        stage[slot] = it;
    }

    /** Große 2x2-Karte mit Icon; row/col = linke obere Ecke. Alle vier Teile zeigen denselben Tooltip. */
    private void big(int row, int col, Icon icon, String name, Consumer<ClickType> act, String... lore) {
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                set((row + r) * 9 + col + c, Items.big(custom, icon, r, c, Text.mm(name), lore), act);
            }
        }
    }

    private void bigBanner(int row, int col, ClanColor color, Component name, String... lore) {
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                set((row + r) * 9 + col + c, Items.bigBanner(custom, color, r, c, name, lore), null);
            }
        }
    }

    private ItemStack btn(Icon icon, String name, String... lore) {
        return Items.btn(custom, icon, name, lore);
    }

    private void go(View v) {
        view = v;
        page = 0;
        selected = null;
        render();
    }

    private void back(View v) {
        set(BACK, btn(Icon.ARROW_LEFT, "<yellow>Zurück"), t -> {
            click();
            go(v);
        });
    }

    public void opened() {
        snd(Sound.BLOCK_ENDER_CHEST_OPEN, 1.5f);
        if (reveal >= 9) return;
        // Aufbau: die Spalten blenden von links nach rechts ein (einmalig, danach statisch)
        revealTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            reveal += 2;
            boolean done = reveal >= 9 || inventory.getViewers().isEmpty();
            if (done) {
                reveal = 9;
                if (revealTask != null) revealTask.cancel();
            }
            flush();
        }, 1L, 1L);
    }

    public void toast(String mini) {
        toast = mini;
        toastUntil = System.currentTimeMillis() + 6000L;
        render();
    }

    // ------------------------------------------------------------ Klicks

    public void click(int slot, ClickType type) {
        Button b = buttons.get(slot);
        if (b == null || b.action() == null) return;
        if (!(type.isLeftClick() || type.isRightClick())) return;
        b.action().accept(type);
    }

    // ------------------------------------------------------------ Aktualisierung

    /** Wird periodisch aufgerufen; sendet dank Diff nur dann etwas, wenn sich Daten geändert haben. */
    public void tick(int refreshEvery) {
        if (++sinceRefresh >= refreshEvery) {
            sinceRefresh = 0;
            refresh();
        }
    }

    /** Daten haben sich geändert -> neu zeichnen (oder komplett neu öffnen, wenn sich der Titel ändert). */
    public void refresh() {
        if (!stamp.equals(computeStamp())) {
            plugin.gui().open(player, view);
            return;
        }
        render();
    }

    private String tileFor(int s) {
        int row = s / 9;
        if (row == 0) return "bar";
        if (row == 5) return "foot";
        return "bg";
    }

    /** Füllt alle Slots ohne Button mit der statischen Hintergrund-Kachel. */
    private void paintFree() {
        for (int s = 0; s < 54; s++) {
            if (stage[s] == null) stage[s] = Items.tile(custom, tileFor(s));
        }
    }

    /** Sendet nur die Slots, die sich gegenüber dem letzten Stand geändert haben. */
    private void flush() {
        for (int s = 0; s < 54; s++) {
            ItemStack n = stage[s];
            if (s % 9 >= reveal) n = Items.tile(custom, tileFor(s));
            if (!java.util.Objects.equals(sent[s], n)) {
                inventory.setItem(s, n);
                sent[s] = n;
            }
        }
    }

    // ------------------------------------------------------------ Rendern

    private void validate() {
        boolean hasClan = clan() != null;
        switch (view) {
            case HOME, INVITES -> {
                if (hasClan) view = View.OVERVIEW;
            }
            case OVERVIEW, MEMBERS, ALLIES, COLOR -> {
                if (!hasClan) view = View.HOME;
            }
            case ALLY_PICK -> {
                if (!hasClan) view = View.HOME;
                else if (!leader()) view = View.ALLIES;
            }
            case INVITE_PICK -> {
                if (!hasClan) view = View.HOME;
                else if (!leader()) view = View.OVERVIEW;
            }
            case CONFIRM -> {
                if (confirmAct == null) view = confirmBack;
            }
            default -> { }
        }
    }

    public void render() {
        validate();
        buttons.clear();
        marked.clear();
        Arrays.fill(stage, null);

        buildHeader();
        switch (view) {
            case HOME -> buildHome();
            case INVITES -> buildInvites();
            case CLANS -> buildClans();
            case OVERVIEW -> buildOverview();
            case MEMBERS -> buildMembers();
            case ALLIES -> buildAllies();
            case ALLY_PICK -> buildAllyPick();
            case INVITE_PICK -> buildInvitePick();
            case COLOR -> buildColor();
            case CONFIRM -> buildConfirm();
        }
        buildTabs();

        if (toast != null) {
            if (System.currentTimeMillis() < toastUntil) set(TOAST, btn(Icon.CHAT, toast), null);
            else toast = null;
        }

        for (Map.Entry<Integer, String> e : marked.entrySet()) {
            Button b = buttons.get(e.getKey());
            if (b == null) continue;
            ItemStack it = Items.select(b.item(), custom, accentHex(), e.getValue());
            buttons.put(e.getKey(), new Button(it, b.action()));
            stage[e.getKey()] = it;
        }
        paintFree();
        flush();
    }

    private void buildHeader() {
        set(SLOT_CLOSE, btn(Icon.CLOSE, "<red><bold>Schließen"), t -> {
            snd(Sound.BLOCK_ENDER_CHEST_CLOSE, 1.5f);
            later(player::closeInventory);
        });
    }

    // ------------------------------------------------------------ Tabs

    private View tabOf(View v) {
        return switch (v) {
            case INVITE_PICK, COLOR -> View.OVERVIEW;
            case ALLY_PICK -> View.ALLIES;
            case CONFIRM -> tabOf(confirmBack);
            default -> v;
        };
    }

    private void buildTabs() {
        List<Tab> tabs;
        if (clan() == null) {
            tabs = List.of(
                    new Tab(View.HOME, 0, Icon.HOME, "Start", "Gründe einen Clan oder tritt einem bei"),
                    new Tab(View.INVITES, 2, Icon.INVITES, "Einladungen", "Deine offenen Einladungen"),
                    new Tab(View.CLANS, 4, Icon.CLANS, "Alle Clans", "Alle Clans des Servers"));
        } else {
            tabs = List.of(
                    new Tab(View.OVERVIEW, 0, Icon.OVERVIEW, "Übersicht", "Statistiken & Aktionen"),
                    new Tab(View.MEMBERS, 2, Icon.MEMBERS, "Mitglieder", "Mitglieder verwalten"),
                    new Tab(View.ALLIES, 4, Icon.ALLIES, "Allianzen", "Verbündete Clans"),
                    new Tab(View.CLANS, 6, Icon.CLANS, "Alle Clans", "Alle Clans des Servers"));
        }
        View active = tabOf(view);
        for (Tab t : tabs) {
            boolean act = t.view() == active;
            for (int c = 0; c < 2; c++) {
                ItemStack it = Items.tab(custom, t.icon(), c,
                        (act ? "<color:" + accentHex() + ">" : "<gray>") + "<bold>" + t.name(),
                        "<dark_gray>" + t.desc());
                if (act) it = Items.select(it, custom, accentHex(), null);
                set(t.slot() + c, it, x -> {
                    tabClick();
                    go(t.view());
                });
            }
        }
    }

    // ------------------------------------------------------------ Listen-Helfer

    private <T> List<T> slice(List<T> all) {
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        scroll(pages);
        return all.subList(page * PER_PAGE, Math.min(all.size(), page * PER_PAGE + PER_PAGE));
    }

    /** Scrollleiste rechts: Pfeil hoch, Schiene mit Griff, Pfeil runter. */
    private void scroll(int pages) {
        for (int s : SCROLL) set(s, Items.tile(custom, "scroll_track"), null);
        if (pages <= 1) return;
        String info = "<gray>Seite <white>" + (page + 1) + "<gray>/<white>" + pages;
        if (page > 0) {
            set(SCROLL[0], btn(Icon.ARROW_UP, "<yellow>Vorherige Seite", info), t -> {
                snd(Sound.ITEM_BOOK_PAGE_TURN, 1.0f);
                page--;
                render();
            });
        }
        if (page < pages - 1) {
            set(SCROLL[3], btn(Icon.ARROW_DOWN, "<yellow>Nächste Seite", info), t -> {
                snd(Sound.ITEM_BOOK_PAGE_TURN, 1.0f);
                page++;
                render();
            });
        }
        double ratio = page / (double) (pages - 1);
        set(ratio < 0.5 ? SCROLL[1] : SCROLL[2], Items.tile(custom, "scroll_handle"), null);
    }

    private void entry(int idx, ItemStack it, String id) {
        int slot = cs(idx);
        set(slot, it, t -> {
            pick();
            selected = id;
            render();
        });
        if (id.equals(selected)) marked.put(slot, "Ausgewählt");
    }

    private void emptyHint(String text) {
        big(2, 3, Icon.INFO, "<red><bold>" + text, null);
    }

    private ItemStack clanItem(Clan c, String... extra) {
        ClanManager m = plugin.clans();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Leiter: <gold>" + Text.esc(c.leaderName()));
        lore.add("<gray>Mitglieder: <yellow>" + c.members.size());
        lore.add("<gray>Kills: <green>" + c.kills + " <dark_gray>| <gray>Tode: <red>" + c.deaths
                + " <dark_gray>| <gray>K/D: <yellow>" + String.format(Locale.ROOT, "%.2f", c.kd()));
        lore.add("<gray>Allianzen: <aqua>" + c.allies.size());
        Clan mine = m.getClan(player);
        if (mine == c) lore.add("<gold>★ Dein Clan");
        else if (mine != null && mine.allies.contains(c.key)) lore.add("<aqua>✦ Verbündet mit deinem Clan");
        lore.addAll(Arrays.asList(extra));
        return Items.banner(custom, ClanColor.nearest(c.c1), c.tag, lore.toArray(new String[0]));
    }

    // ------------------------------------------------------------ HOME (ohne Clan)

    private void buildHome() {
        ClanManager m = plugin.clans();
        int min = plugin.getConfig().getInt("min-length", 3);
        int max = plugin.getConfig().getInt("max-length", 10);

        big(2, 0, Icon.PLUS, "<green><bold>Clan gründen", t -> {
            click();
            prompt("<gray>Schreibe den <gold>Clannamen <gray>in den Chat.", name -> {
                boolean ok = m.create(player, name);
                if (ok) snd(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
                plugin.gui().open(player, ok ? View.OVERVIEW : View.HOME);
            }, View.HOME);
        }, "<gray>Gründe deinen eigenen Clan.", "",
                "<dark_gray>Länge: <gray>" + min + "-" + max + " Zeichen",
                "<dark_gray>Erlaubt: <gray>A-Z, 0-9 und _", "",
                "<yellow>» Klicken, dann Namen in den Chat schreiben");

        int inv = m.invitesFor(player.getUniqueId()).size();
        big(2, 2, Icon.INVITES, "<yellow><bold>Einladungen", t -> {
            click();
            go(View.INVITES);
        }, "<gray>Offene Einladungen: " + (inv > 0 ? "<green>" : "<red>") + inv, "",
                "<yellow>» Klicken zum Ansehen");

        big(2, 4, Icon.CLANS, "<aqua><bold>Alle Clans", t -> {
            click();
            go(View.CLANS);
        }, "<gray>Clans auf dem Server: <white>" + m.all().size(), "",
                "<yellow>» Klicken zum Ansehen");

        big(2, 6, Icon.INFO, "<gold><bold>So funktioniert's", null,
                "<gray>Gründe einen Clan oder tritt", "<gray>über eine Einladung einem bei.", "",
                "<dark_gray>Danach findest du hier Übersicht,", "<dark_gray>Mitglieder, Allianzen und Farbe.");
    }

    // ------------------------------------------------------------ INVITES

    private void buildInvites() {
        ClanManager m = plugin.clans();
        List<Clan> invs = new ArrayList<>(m.invitesFor(player.getUniqueId()));
        invs.sort(Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT)));
        if (invs.isEmpty()) {
            emptyHint("Keine offenen Einladungen");
            return;
        }
        List<Clan> sub = slice(invs);
        for (int i = 0; i < sub.size(); i++) {
            entry(i, clanItem(sub.get(i)), sub.get(i).key);
        }
        Clan sel = m.byKey(selected);
        if (sel != null && sel.invites.containsKey(player.getUniqueId())) {
            set(ACT_A, btn(Icon.CROSS, "<red><bold>Ablehnen", "<gray>Einladung von <gold>" + sel.name + " <gray>ablehnen."), t -> {
                result(m.declineInvite(player, sel.key));
                selected = null;
                render();
            });
            set(ACT_B, btn(Icon.CHECK, "<green><bold>Annehmen", "<gray>Dem Clan <gold>" + sel.name + " <gray>beitreten."), t -> {
                boolean ok = m.accept(player, sel.key);
                result(ok, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
                later(() -> plugin.gui().open(player, ok ? View.OVERVIEW : View.INVITES));
            });
        }
    }

    // ------------------------------------------------------------ CLANS

    private void buildClans() {
        ClanManager m = plugin.clans();
        List<Clan> all = new ArrayList<>(m.all());
        all.sort(Comparator.<Clan>comparingInt(c -> -c.members.size()).thenComparing(c -> c.name.toLowerCase(Locale.ROOT)));
        if (all.isEmpty()) {
            emptyHint("Es gibt noch keine Clans");
            return;
        }
        List<Clan> sub = slice(all);
        for (int i = 0; i < sub.size(); i++) {
            entry(i, clanItem(sub.get(i)), sub.get(i).key);
        }
        Clan sel = m.byKey(selected);
        Clan mine = m.getClan(player);
        if (sel != null && mine != null && mine != sel && leader() && !mine.allies.contains(sel.key)) {
            set(ACT_B, btn(Icon.ALLIES, "<aqua><bold>Allianz anfragen", "<gray>Schickt <gold>" + sel.name + " <gray>eine Anfrage."), t -> {
                result(m.allyInvite(player, sel.key), Sound.BLOCK_BEACON_ACTIVATE, 1.4f);
            });
        }
    }

    // ------------------------------------------------------------ OVERVIEW

    private void buildOverview() {
        ClanManager m = plugin.clans();
        Clan c = clan();
        if (c == null) return;
        boolean lead = leader();
        String since = new SimpleDateFormat("dd.MM.yyyy").format(new Date(c.created));

        bigBanner(1, 0, ClanColor.nearest(c.c1) != null ? ClanColor.nearest(c.c1) : ClanColor.GREEN, c.tag,
                "<gray>Leiter: <gold>" + Text.esc(c.leaderName()),
                "<gray>Mitglieder: <yellow>" + c.members.size(),
                "<gray>Allianzen: <aqua>" + c.allies.size(),
                "<gray>Abgebaute Blöcke: <white>" + c.blocks,
                "<gray>Gegründet: <white>" + since,
                "", lead ? "<gold>★ Du bist Clanleiter" : "<gray>Du bist Mitglied");

        big(1, 2, Icon.SWORD, "<green><bold>Kills", null, "<white>" + c.kills);
        big(1, 4, Icon.SKULL, "<red><bold>Tode", null, "<white>" + c.deaths);
        big(1, 6, Icon.KD, "<yellow><bold>K/D", null, "<white>" + String.format(Locale.ROOT, "%.2f", c.kd()));

        boolean vip = m.hasVip(player);
        big(3, 0, Icon.COLOR, "<gold><bold>Clanfarbe", t -> {
            click();
            go(View.COLOR);
        }, "<gray>Farbe & Farbverlauf deines Clan-Tags.", "",
                vip ? "<yellow>» Klicken" : "<red>Nur für VIP-Booster");
        big(3, 2, Icon.CHAT, "<aqua><bold>Clan-Chat", t -> {
            click();
            prompt("<gray>Schreibe deine <aqua>Clan-Nachricht <gray>in den Chat.", msg -> {
                m.chat(player, msg);
                plugin.gui().open(player, View.OVERVIEW);
            }, View.OVERVIEW);
        }, "<gray>Sende eine Nachricht an alle", "<gray>Clan-Mitglieder.", "",
                "<dark_gray>Tipp: <gray>/cc [Nachricht]", "",
                "<yellow>» Klicken, dann in den Chat schreiben");

        if (lead) {
            big(3, 4, Icon.INVITE, "<green><bold>Spieler einladen", t -> {
                click();
                go(View.INVITE_PICK);
            }, "<gray>Lade einen Spieler ohne Clan ein.", "", "<yellow>» Klicken");
            big(3, 6, Icon.TNT, "<red><bold>Clan auflösen", t -> {
                click();
                confirm("Willst du den Clan <gold>" + c.name + " <gray>wirklich auflösen?", View.OVERVIEW, View.HOME,
                        () -> m.disband(player), Sound.ENTITY_GENERIC_EXPLODE, 1.3f);
            }, "<gray>Löscht den Clan unwiderruflich.", "", "<yellow>» Klicken");
        } else {
            big(3, 4, Icon.LOCK, "<dark_gray><bold>Spieler einladen", null, "<red>Nur der Clanleiter kann einladen.");
            big(3, 6, Icon.DOOR, "<red><bold>Clan verlassen", t -> {
                click();
                confirm("Willst du den Clan <gold>" + c.name + " <gray>wirklich verlassen?", View.OVERVIEW, View.HOME,
                        () -> m.leave(player), Sound.BLOCK_WOODEN_DOOR_CLOSE, 1.0f);
            }, "<gray>Du verlässt den Clan <gold>" + c.name + "<gray>.", "", "<yellow>» Klicken");
        }
    }

    // ------------------------------------------------------------ MEMBERS

    private void buildMembers() {
        ClanManager m = plugin.clans();
        Clan c = clan();
        if (c == null) return;
        List<UUID> ids = new ArrayList<>(c.members.keySet());
        ids.sort(Comparator.<UUID>comparingInt(u -> u.equals(c.owner) ? 0 : (Bukkit.getPlayer(u) != null ? 1 : 2))
                .thenComparing(u -> c.members.get(u).toLowerCase(Locale.ROOT)));
        List<UUID> sub = slice(ids);
        for (int i = 0; i < sub.size(); i++) {
            UUID u = sub.get(i);
            boolean on = Bukkit.getPlayer(u) != null;
            boolean owner = u.equals(c.owner);
            boolean me = u.equals(player.getUniqueId());
            ItemStack head = Items.head(Bukkit.getOfflinePlayer(u),
                    (on ? "<green>" : "<gray>") + Text.esc(c.members.get(u)) + (me ? " <dark_gray>(du)" : ""),
                    owner ? "<gold>★ Clanleiter" : "<gray>Mitglied",
                    on ? "<green>● Online" : "<dark_gray>● Offline");
            entry(i, head, u.toString());
        }
        UUID sel = uuid(selected);
        if (leader() && sel != null && c.members.containsKey(sel) && !sel.equals(player.getUniqueId())) {
            String sn = Text.esc(c.members.get(sel));
            set(ACT_A, btn(Icon.KICK, "<red><bold>Kicken", "<gray>Entfernt <yellow>" + sn + " <gray>aus dem Clan."), t -> {
                click();
                confirm("Willst du <yellow>" + sn + " <gray>wirklich kicken?", View.MEMBERS, View.MEMBERS,
                        () -> m.kick(player, sel), Sound.BLOCK_ANVIL_LAND, 1.2f);
            });
            set(ACT_B, btn(Icon.CROWN, "<gold><bold>Zum Leiter machen", "<gray>Übergibt die Leitung an <yellow>" + sn + "<gray>."), t -> {
                click();
                confirm("Willst du die Leitung wirklich an <yellow>" + sn + " <gray>übergeben?", View.MEMBERS, View.MEMBERS,
                        () -> m.transferLeader(player, sel), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
            });
        }
    }

    // ------------------------------------------------------------ ALLIES

    private void buildAllies() {
        ClanManager m = plugin.clans();
        Clan me = clan();
        if (me == null) return;
        boolean lead = leader();

        List<AllyEntry> list = new ArrayList<>();
        for (String k : me.allyRequests) {
            Clan o = m.byKey(k);
            if (o != null) list.add(new AllyEntry(o, false));
        }
        for (String k : me.allies) {
            Clan o = m.byKey(k);
            if (o != null) list.add(new AllyEntry(o, true));
        }

        if (list.isEmpty()) emptyHint("Keine Allianzen oder Anfragen");
        List<AllyEntry> sub = slice(list);
        for (int i = 0; i < sub.size(); i++) {
            AllyEntry e = sub.get(i);
            ItemStack it = e.ally()
                    ? clanItem(e.clan())
                    : clanItem(e.clan(), "", "<yellow>✉ Allianzanfrage an euch");
            entry(i, it, e.clan().key);
        }

        if (!lead) return;
        set(ACT_A, btn(Icon.PLUS, "<aqua><bold>Allianz anfragen", "<gray>Wähle einen Clan aus.", "", "<yellow>» Klicken"), t -> {
            click();
            go(View.ALLY_PICK);
        });
        Clan sel = m.byKey(selected);
        if (sel == null) return;
        if (me.allies.contains(sel.key)) {
            set(ACT_B, btn(Icon.CROSS, "<red><bold>Allianz beenden", "<gray>Beendet die Allianz mit <gold>" + sel.name + "<gray>."), t -> {
                click();
                confirm("Allianz mit <gold>" + sel.name + " <gray>wirklich beenden?", View.ALLIES, View.ALLIES,
                        () -> m.allyRemove(player, sel.key), Sound.BLOCK_GLASS_BREAK, 1.0f);
            });
        } else if (me.allyRequests.contains(sel.key)) {
            set(ACT_B, btn(Icon.CHECK, "<green><bold>Annehmen", "<gray>Allianz mit <gold>" + sel.name + " <gray>schließen."), t -> {
                result(m.allyAccept(player, sel.key), Sound.BLOCK_BEACON_ACTIVATE, 1.2f);
                selected = null;
                render();
            });
            set(ACT_C, btn(Icon.CROSS, "<red><bold>Ablehnen", "<gray>Anfrage von <gold>" + sel.name + " <gray>ablehnen."), t -> {
                result(m.allyDecline(player, sel.key));
                selected = null;
                render();
            });
        }
    }

    private void buildAllyPick() {
        ClanManager m = plugin.clans();
        Clan me = clan();
        if (me == null) return;
        List<Clan> list = new ArrayList<>();
        for (Clan c : m.all()) {
            if (c != me && !me.allies.contains(c.key)) list.add(c);
        }
        list.sort(Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT)));
        back(View.ALLIES);
        if (list.isEmpty()) {
            emptyHint("Keine Clans verfügbar");
            return;
        }
        List<Clan> sub = slice(list);
        for (int i = 0; i < sub.size(); i++) {
            Clan c = sub.get(i);
            ItemStack it = c.allyRequests.contains(me.key)
                    ? clanItem(c, "", "<yellow>✉ Anfrage bereits gesendet")
                    : clanItem(c);
            entry(i, it, c.key);
        }
        Clan sel = m.byKey(selected);
        if (sel != null) {
            set(ACT_B, btn(Icon.CHECK, "<green><bold>Anfrage senden", "<gray>An <gold>" + sel.name + "<gray>."), t -> {
                result(m.allyInvite(player, sel.key), Sound.BLOCK_BEACON_ACTIVATE, 1.4f);
                render();
            });
        }
    }

    // ------------------------------------------------------------ INVITE_PICK

    private void buildInvitePick() {
        ClanManager m = plugin.clans();
        back(View.OVERVIEW);
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(player) && m.getClan(p) == null) list.add(p);
        }
        list.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        if (list.isEmpty()) {
            emptyHint("Keine Spieler ohne Clan online");
            return;
        }
        List<Player> sub = slice(list);
        Clan me = clan();
        for (int i = 0; i < sub.size(); i++) {
            Player p = sub.get(i);
            boolean invited = me != null && me.invites.containsKey(p.getUniqueId());
            ItemStack head = Items.head(p, "<green>" + Text.esc(p.getName()),
                    "<gray>Hat keinen Clan",
                    invited ? "<yellow>✉ Bereits eingeladen" : "<dark_gray>Noch nicht eingeladen");
            entry(i, head, p.getUniqueId().toString());
        }
        UUID sel = uuid(selected);
        Player target = sel == null ? null : Bukkit.getPlayer(sel);
        if (target != null) {
            set(ACT_B, btn(Icon.INVITE, "<green><bold>Einladen", "<gray>Lädt <yellow>" + Text.esc(target.getName()) + " <gray>ein."), t -> {
                result(m.invite(player, target), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
                render();
            });
        }
    }

    // ------------------------------------------------------------ COLOR

    private void buildColor() {
        ClanManager m = plugin.clans();
        Clan c = clan();
        if (c == null) return;
        boolean vip = m.hasVip(player);
        boolean needsLeader = plugin.getConfig().getBoolean("color-needs-leader", true);
        boolean can = vip && (!needsLeader || leader());
        back(View.OVERVIEW);

        if (!can) {
            String why = !vip ? "Die Clanfarbe ist nur für <gold>VIP-Booster <gray>verfügbar."
                    : "Nur der <gold>Clanleiter <gray>kann die Farbe ändern.";
            big(1, 3, Icon.LOCK, "<red><bold>Gesperrt", null, "<gray>" + why);
            set(40, Items.named(custom, Icon.PREVIEW, c.tag, "<gray>Aktueller Clan-Tag"), null);
            return;
        }

        ClanColor[] cols = ClanColor.values();
        ClanColor sel1 = ClanColor.parse(c1);
        ClanColor sel2 = ClanColor.parse(c2);
        for (int i = 0; i < cols.length; i++) {
            ClanColor col = cols[i];
            int slot = (1 + i / 5) * 9 + 2 + (i % 5);
            ItemStack it = Items.swatch(custom, col, "<color:" + col.hex() + "><bold>" + col.german(),
                    "<dark_gray>" + col.hex(), "",
                    "<gray>Linksklick: <white>Farbe 1", "<gray>Rechtsklick: <white>Farbe 2 (Verlauf)");
            final int idx = i;
            set(slot, it, t -> {
                pickScale(idx);
                if (t.isRightClick()) c2 = col.id();
                else c1 = col.id();
                render();
            });
            if (col == sel1 && col == sel2) marked.put(slot, "Farbe 1 + Farbe 2");
            else if (col == sel1) marked.put(slot, "Farbe 1");
            else if (col == sel2) marked.put(slot, "Farbe 2");
        }

        set(38, btn(Icon.RESET, "<red><bold>Zurücksetzen", "<gray>Standardfarbe wiederherstellen."), t -> {
            click();
            boolean ok = m.setColor(player, "reset", null);
            if (ok) {
                c1 = "";
                c2 = "";
            }
            result(ok);
            render();
        });
        set(39, btn(Icon.HEX, "<aqua><bold>Eigene Farbe (Hex)",
                "<gray>Gib im Chat Farben ein, z.B.:", "<white>#ff8800 #ffee00", "<white>red blue", "", "<yellow>» Klicken"), t -> {
            click();
            prompt("<gray>Schreibe <aqua>Farbe 1 <gray>und optional <aqua>Farbe 2 <gray>(z.B. <white>#ff8800 #ffee00<gray>).", text -> {
                String[] parts = text.trim().split("\\s+");
                m.setColor(player, parts[0], parts.length > 1 ? parts[1] : null);
                plugin.gui().open(player, View.COLOR);
            }, View.COLOR);
        });
        set(40, Items.named(custom, Icon.PREVIEW, m.buildTag(c.name, c1, c2),
                "<gray>Vorschau deines Clan-Tags", "",
                "<gray>Farbe 1: <white>" + (c1.isEmpty() ? "Standard" : Text.esc(c1)),
                "<gray>Farbe 2: <white>" + (c2.isEmpty() ? "–" : Text.esc(c2))), null);
        if (!c2.isEmpty()) {
            set(41, btn(Icon.CROSS, "<gray><bold>Farbe 2 entfernen", "<gray>Nur eine Farbe, kein Verlauf."), t -> {
                click();
                c2 = "";
                render();
            });
        }
        set(42, btn(Icon.CHECK, "<green><bold>Übernehmen", "<gray>Speichert die gewählte Farbe.", "", "<yellow>» Klicken"), t -> {
            if (c1.isEmpty()) {
                plugin.err(player, "Wähle zuerst Farbe 1 aus.");
                result(false);
                return;
            }
            result(m.setColor(player, c1, c2.isEmpty() ? null : c2), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
            render();
        });
    }

    // ------------------------------------------------------------ CONFIRM

    private void confirm(String text, View back, View next, Supplier<Boolean> act, Sound okSound, float pitch) {
        confirmSound = okSound;
        confirmPitch = pitch;
        confirmText = text;
        confirmBack = back;
        confirmNext = next;
        confirmAct = act;
        view = View.CONFIRM;
        page = 0;
        selected = null;
        render();
    }

    private void buildConfirm() {
        big(1, 3, Icon.INFO, "<gold><bold>Bestätigung", null, "<gray>" + confirmText);
        big(3, 2, Icon.CHECK, "<green><bold>Ja", t -> {
            Supplier<Boolean> act = confirmAct;
            confirmAct = null;
            boolean ok = act != null && act.get();
            result(ok, confirmSound, confirmPitch);
            View next = ok ? confirmNext : confirmBack;
            later(() -> plugin.gui().open(player, next));
        }, "<gray>Bestätigen");
        big(3, 5, Icon.CROSS, "<red><bold>Nein", t -> {
            click();
            confirmAct = null;
            go(confirmBack);
        }, "<gray>Abbrechen");
    }
}
