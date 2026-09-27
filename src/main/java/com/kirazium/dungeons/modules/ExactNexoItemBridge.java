package com.kirazium.dungeons.modules;

import com.kirazium.dungeons.KiraziumDungeonsPlugin;
import com.utilsmc.dungeons.Dungeons;
import com.utilsmc.dungeons.Utils;
import com.utilsmc.dungeons.controllers.models.IModelController;
import com.utilsmc.dungeons.models.loottables.LootTableConfiguration;
import com.utilsmc.dungeons.models.loottables.loot.VanillaLoot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.denizenmc.menus.Menus;
import org.denizenmc.menus.components.Element;
import org.denizenmc.menus.components.Menu;
import org.denizenmc.menus.components.Session;
import org.denizenmc.menus.components.actions.Action;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.lang.reflect.Method;

/**
 * Prevents Dungeons+ 4.1.10 from reducing a Nexo ItemStack to only its Nexo ID.
 *
 * Dungeons' AddLootAction normally routes Nexo items through NexoLoot, whose
 * generate() method rebuilds the item from Nexo ID. That loses ItemEdit changes
 * and any metadata/components/PDC added after the base Nexo item was built.
 *
 * This listener intercepts ONLY the Dungeons "dungeons-add-loot" action when the
 * cursor is a Nexo item. It stores the exact cursor clone as VanillaLoot. The
 * name "VanillaLoot" is Dungeons' full-ItemStack persistence format; the item
 * itself remains a Nexo item because all ItemStack metadata is preserved.
 *
 * Both LootChest rewards and dungeon mob drops consume the same
 * LootTableConfiguration.generate(...) path, so one exact persisted ItemStack
 * covers both reward paths.
 */
public final class ExactNexoItemBridge implements Listener {
    private static final String ADD_LOOT_ACTION = "dungeons-add-loot";

    private final KiraziumDungeonsPlugin plugin;
    private final Map<InventoryClickEvent, Suppression> suppressed = new IdentityHashMap<>();

    private boolean enabled;
    private boolean verifyYamlRoundTrip;
    private long exactItemsSaved;
    private long rejectedItems;
    private Method nexoIdFromItem;

    public ExactNexoItemBridge(KiraziumDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        reloadSettings();
        if (!enabled) {
            plugin.getLogger().info("ExactNexoItemBridge config ile devre disi.");
            return;
        }
        Plugin nexo = Bukkit.getPluginManager().getPlugin("Nexo");
        if (nexo == null || !nexo.isEnabled()) {
            plugin.getLogger().warning("ExactNexoItemBridge aktif olmadi: Nexo bulunamadi/aktif degil.");
            enabled = false;
            return;
        }
        try {
            Class<?> nexoItems = Class.forName("com.nexomc.nexo.api.NexoItems", true, nexo.getClass().getClassLoader());
            nexoIdFromItem = nexoItems.getMethod("idFromItem", ItemStack.class);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "ExactNexoItemBridge aktif olmadi: NexoItems.idFromItem API bulunamadi.", t);
            enabled = false;
            return;
        }
        Plugin menus = Bukkit.getPluginManager().getPlugin("Menus");
        if (menus == null || !menus.isEnabled()) {
            plugin.getLogger().warning("ExactNexoItemBridge aktif olmadi: Menus bulunamadi/aktif degil.");
            enabled = false;
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("ExactNexoItemBridge aktif: Nexo loot ItemStack'lari exact olarak korunacak (LootChest + mob drop).");
    }

    public void disable() {
        restoreAllSuppressedActions();
        HandlerList.unregisterAll(this);
    }

    public void reload() {
        reloadSettings();
    }

    private void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("exact-nexo-items.enabled", true);
        verifyYamlRoundTrip = plugin.getConfig().getBoolean("exact-nexo-items.verify-yaml-roundtrip", true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onLootAddLowest(InventoryClickEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        if (!(event.getClickedInventory().getHolder() instanceof Menu menu)) return;

        Element element = menu.getContent().get(event.getSlot());
        if (element == null || element.getActions() == null || element.getActions().isEmpty()) return;

        Action addAction = null;
        int actionIndex = -1;
        List<Action> actions = element.getActions();
        for (int i = 0; i < actions.size(); i++) {
            Action candidate = actions.get(i);
            if (candidate == null) continue;
            if (!ADD_LOOT_ACTION.equalsIgnoreCase(candidate.getName())) continue;
            if (candidate.getClicks() == null || !candidate.getClicks().contains(event.getClick())) continue;
            addAction = candidate;
            actionIndex = i;
            break;
        }
        if (addAction == null) return;

        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        String nexoId;
        try {
            Object value = nexoIdFromItem.invoke(null, cursor);
            nexoId = value instanceof String str ? str : null;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Nexo item kimligi okunamadi; exact loot islemi reddedildi.", t);
            suppressBuiltIn(event, element, addAction, actionIndex, null, false);
            event.setCancelled(true);
            player.sendMessage("\u00a7cKirazium \u00a78\u00bb \u00a7fNexo item dogrulanamadi; bozulmamasi icin loot'a eklenmedi.");
            rejectedItems++;
            return;
        }
        if (nexoId == null || nexoId.isBlank()) return; // Not a Nexo item: let Dungeons handle it normally.

        Session session = Menus.getAPI().getSession(player);
        if (session == null) {
            rejectAndSuppress(event, element, addAction, actionIndex, null, player, nexoId,
                    "Menus session bulunamadi");
            return;
        }

        LootTableConfiguration cfg = resolveCurrentLootTable(session);
        if (cfg == null) {
            rejectAndSuppress(event, element, addAction, actionIndex, session, player, nexoId,
                    "Dungeons LootTable context bulunamadi");
            return;
        }

        ItemStack exact = cursor.clone();
        if (verifyYamlRoundTrip && !passesYamlRoundTrip(exact)) {
            rejectAndSuppress(event, element, addAction, actionIndex, session, player, nexoId,
                    "ItemStack YAML round-trip birebir degil");
            return;
        }

        VanillaLoot exactLoot = new VanillaLoot(exact, cfg);
        boolean added = false;
        try {
            cfg.addLoot(exactLoot);
            added = true;
            IModelController controller = Dungeons.getInstance().getModelControllerFromModifiable(cfg);
            if (controller == null) throw new IllegalStateException("Dungeons model controller null");
            controller.save(cfg);
        } catch (Throwable t) {
            if (added) {
                try { cfg.removeLoot(exactLoot); } catch (Throwable ignored) {}
            }
            plugin.getLogger().log(Level.SEVERE,
                    "Exact Nexo loot kaydedilemedi (id=" + nexoId + "); built-in NexoLoot yolu fail-closed engellendi.", t);
            rejectAndSuppress(event, element, addAction, actionIndex, session, player, nexoId,
                    "Exact loot kaydi basarisiz");
            return;
        }

        // Suppress Dungeons' normal AddLootAction for this one event, otherwise it would add a second NexoLoot entry.
        suppressBuiltIn(event, element, addAction, actionIndex, session, true);
        event.setCancelled(true);
        exactItemsSaved++;
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 10.0f, 10.0f);
        } catch (Throwable ignored) {}
        player.sendMessage("\u00a7dKirazium \u00a78\u00bb \u00a7fNexo item exact olarak kaydedildi: \u00a7d" + nexoId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLootAddMonitor(InventoryClickEvent event) {
        Suppression suppression = suppressed.remove(event);
        if (suppression == null) return;
        restore(suppression);
        if (suppression.refresh && suppression.session != null) {
            try {
                suppression.session.refresh();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "Exact loot sonrasi menu refresh basarisiz.", t);
            }
        }
    }

    private LootTableConfiguration resolveCurrentLootTable(Session session) {
        try {
            Object value = session.getContext().getValue(Utils.EDIT_MODEL_CONTEXT_KEY, Dungeons.getInstance());
            if (!(value instanceof List<?> list) || list.isEmpty()) return null;
            Object last = list.get(list.size() - 1);
            return last instanceof LootTableConfiguration cfg ? cfg : null;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Dungeons LootTable context okunamadi.", t);
            return null;
        }
    }

    /**
     * Verifies the exact persistence mechanism Dungeons FileLootTableDAO will use for ItemValue:
     * Bukkit YamlConfiguration -> ItemStack ConfigurationSerializable -> YamlConfiguration.
     * If Paper cannot round-trip this exact stack, we reject it instead of silently corrupting it.
     */
    private boolean passesYamlRoundTrip(ItemStack input) {
        try {
            YamlConfiguration out = new YamlConfiguration();
            out.set("item", input.clone());
            String serialized = out.saveToString();

            YamlConfiguration in = new YamlConfiguration();
            in.loadFromString(serialized);
            ItemStack restored = in.getItemStack("item");
            return restored != null && input.equals(restored);
        } catch (InvalidConfigurationException | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Exact Nexo ItemStack YAML round-trip testi basarisiz.", ex);
            return false;
        }
    }

    private void rejectAndSuppress(InventoryClickEvent event,
                                   Element element,
                                   Action action,
                                   int actionIndex,
                                   Session session,
                                   Player player,
                                   String nexoId,
                                   String reason) {
        suppressBuiltIn(event, element, action, actionIndex, session, false);
        event.setCancelled(true);
        rejectedItems++;
        plugin.getLogger().warning("Exact Nexo loot reddedildi: " + reason + " (id=" + nexoId + ")");
        player.sendMessage("\u00a7cKirazium \u00a78\u00bb \u00a7fItem metadata kaybi riski nedeniyle loot'a eklenmedi. Konsolu kontrol et.");
    }

    private void suppressBuiltIn(InventoryClickEvent event,
                                 Element element,
                                 Action action,
                                 int actionIndex,
                                 Session session,
                                 boolean refresh) {
        if (actionIndex >= 0 && actionIndex < element.getActions().size()
                && element.getActions().get(actionIndex) == action) {
            element.getActions().remove(actionIndex);
        } else {
            element.getActions().remove(action);
        }
        suppressed.put(event, new Suppression(element, action, actionIndex, session, refresh));
    }

    private void restore(Suppression suppression) {
        List<Action> actions = suppression.element.getActions();
        if (actions.contains(suppression.action)) return;
        int idx = Math.max(0, Math.min(suppression.index, actions.size()));
        actions.add(idx, suppression.action);
    }

    private void restoreAllSuppressedActions() {
        for (Suppression suppression : suppressed.values()) {
            try { restore(suppression); } catch (Throwable ignored) {}
        }
        suppressed.clear();
    }

    public String statusLine() {
        return (enabled ? "aktif" : "pasif")
                + " | verify=" + verifyYamlRoundTrip
                + " | exact=" + exactItemsSaved
                + " | rejected=" + rejectedItems;
    }

    public void sendStatus(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("\u00a7dExact Nexo Items \u00a78» \u00a7f" + statusLine());
        sender.sendMessage("\u00a77Yeni eklenen Nexo loot: tam ItemStack olarak saklanir (LootChest + mob drop).");
        sender.sendMessage("\u00a77Fail-closed: round-trip dogrulamasi gecmezse item loot'a EKLENMEZ.");
    }

    private record Suppression(Element element, Action action, int index, Session session, boolean refresh) {}
}