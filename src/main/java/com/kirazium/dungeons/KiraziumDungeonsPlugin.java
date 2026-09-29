package com.kirazium.dungeons;

import com.kirazium.dungeons.modules.DungeonGlowModule;
import com.kirazium.dungeons.modules.ExactNexoItemBridge;
import com.kirazium.dungeons.modules.PhoenixKeyBridge;
import com.kirazium.dungeons.modules.PhoenixLootChestBridge;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class KiraziumDungeonsPlugin extends JavaPlugin {
    public static final String REQUIRED_DUNGEONS_VERSION = "4.1.10";
    public static final Set<String> SUPPORTED_MINECRAFT_VERSIONS = Set.of("26.1.2", "26.2", "26.3");

    private StageMonitor monitor;
    private BukkitTask monitorTask;
    private PhoenixKeyBridge keyBridge;
    private PhoenixLootChestBridge lootBridge;
    private DungeonGlowModule glowModule;
    private ExactNexoItemBridge exactNexoItemBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        enforceLegacySafeTrackingDefaults();

        logServerCompatibility();

        Plugin dungeonsPlugin = Bukkit.getPluginManager().getPlugin("Dungeons");
        if (dungeonsPlugin == null) {
            getLogger().severe("Dungeons+ bulunamadi.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        String installed = dungeonsPlugin.getDescription().getVersion();
        if (!REQUIRED_DUNGEONS_VERSION.equals(installed)) {
            getLogger().severe("Bu build Dungeons+ " + REQUIRED_DUNGEONS_VERSION + " icin dogrulandi. Yuklu surum: " + installed);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            InternalAccess access = new InternalAccess();
            access.verify();
            monitor = new StageMonitor(this, access);
            scheduleStageMonitor();
            getLogger().info("StageMonitor: mevcut 0.2.1-beta davranisi binary olarak korundu.");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Dungeons+ stage entegrasyonu basarisiz; KiraziumDungeons kapatiliyor.", t);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Register exact-item interception before other bridge modules. It only acts in the Dungeons LootTable editor.
        exactNexoItemBridge = new ExactNexoItemBridge(this);
        safeEnable("ExactNexoItemBridge", exactNexoItemBridge::enable);

        keyBridge = new PhoenixKeyBridge(this);
        safeEnable("PhoenixKeyBridge", keyBridge::enable);

        lootBridge = new PhoenixLootChestBridge(this);
        safeEnable("PhoenixLootChestBridge", lootBridge::enable);

        glowModule = new DungeonGlowModule(this);
        safeEnable("DungeonGlow", glowModule::enable);

        getLogger().info("KiraziumDungeons " + getDescription().getVersion()
                + " etkin: Stage + ExactNexoItems + PhoenixKey + PhoenixLootChest + Glow tek JAR.");
    }

    private void scheduleStageMonitor() {
        if (monitorTask != null) monitorTask.cancel();
        long interval = Math.max(1L, getConfig().getLong("tracking.interval-ticks", 5L));
        monitorTask = Bukkit.getScheduler().runTaskTimer(this, monitor::tick, 1L, interval);
    }

    private void enforceLegacySafeTrackingDefaults() {
        if (getConfig().getBoolean("tracking.fall-failsafe.enabled", false)) {
            getLogger().warning("fall-failsafe Paper 26.x uyumlulugu icin kapatildi; stage/mob takibi eski guvenli davranisla devam edecek.");
        }
        getConfig().set("tracking.fall-failsafe.enabled", false);
        getConfig().set("tracking.fall-failsafe.max-blocks-below-spawner", 24.0);
        saveConfig();
    }

    private void safeEnable(String name, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, name + " baslatilamadi. Diger dungeon modulleri calismaya devam edecek.", t);
        }
    }

    @Override
    public void onDisable() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        if (exactNexoItemBridge != null) exactNexoItemBridge.disable();
        if (glowModule != null) glowModule.disable();
        if (lootBridge != null) lootBridge.disable();
        if (keyBridge != null) keyBridge.disable();
        if (monitor != null) monitor.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("kiraziumdungeons")) return false;

        if (!sender.hasPermission("kiraziumdungeons.admin")) {
            sender.sendMessage("\u00a7cBu komut icin yetkin yok.");
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                enforceLegacySafeTrackingDefaults();
                if (monitor != null) monitor.reload();
                scheduleStageMonitor();
                if (exactNexoItemBridge != null) exactNexoItemBridge.reload();
                if (keyBridge != null) keyBridge.reload();
                if (lootBridge != null) lootBridge.reload();
                if (glowModule != null) glowModule.reload();
                sender.sendMessage("\u00a7dKirazium \u00a78\u00bb \u00a7fTum dungeon modulleri yenilendi.");
            }
            case "exact", "items", "exactitems" -> {
                if (exactNexoItemBridge != null) exactNexoItemBridge.sendStatus(sender);
                else sender.sendMessage("\u00a7cExactNexoItemBridge aktif degil.");
            }
            case "keys", "key" -> {
                if (keyBridge != null) keyBridge.sendStatus(sender);
                else sender.sendMessage("\u00a7cPhoenixKeyBridge aktif degil.");
            }
            case "phoenix", "lootchest", "crates" -> {
                if (lootBridge != null) lootBridge.sendStatus(sender);
                else sender.sendMessage("\u00a7cPhoenix LootChest bridge aktif degil.");
            }
            case "glow", "glowing" -> {
                if (glowModule != null) glowModule.sendStatus(sender);
                else sender.sendMessage("\u00a7cDungeonGlow modulu aktif degil.");
            }
            case "status" -> sendStatus(sender);
            default -> sender.sendMessage("\u00a7dKiraziumDungeons \u00a77/ kdungeons status|reload|exact|keys|phoenix|glow");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        Plugin d = Bukkit.getPluginManager().getPlugin("Dungeons");
        Plugin n = Bukkit.getPluginManager().getPlugin("Nexo");
        Plugin p = Bukkit.getPluginManager().getPlugin("PhoenixCrates");
        Plugin m = Bukkit.getPluginManager().getPlugin("MythicMobs");
        sender.sendMessage("\u00a7dKiraziumDungeons \u00a7fv" + getDescription().getVersion());
        sender.sendMessage("\u00a77Minecraft/Paper: \u00a7f" + minecraftVersion() + " \u00a78| \u00a77Destek: \u00a7f" + supportLabel());
        sender.sendMessage("\u00a77Stage: \u00a7f" + (monitor != null) + " \u00a78| \u00a77Dungeons: \u00a7f" + version(d));
        sender.sendMessage("\u00a77Nexo: \u00a7f" + version(n) + " \u00a78| \u00a77Phoenix: \u00a7f" + version(p) + " \u00a78| \u00a77Mythic: \u00a7f" + version(m));
        if (exactNexoItemBridge != null) sender.sendMessage("\u00a77Exact Nexo: \u00a7f" + exactNexoItemBridge.statusLine());
        if (keyBridge != null) sender.sendMessage("\u00a77Key mappings: \u00a7f" + keyBridge.mappingCount());
        if (lootBridge != null) sender.sendMessage("\u00a77Phoenix LootChest display: \u00a7f" + lootBridge.activeDisplayCount());
        if (glowModule != null) sender.sendMessage("\u00a77Glow: \u00a7f" + glowModule.statusLine());
    }

    private void logServerCompatibility() {
        String mc = minecraftVersion();
        if (SUPPORTED_MINECRAFT_VERSIONS.contains(mc)) {
            if ("26.3".equals(mc)) {
                getLogger().warning("Minecraft/Paper 26.3 destekli. Paper 26.3 pre-release/beta build kullaniyorsan uretimde kullanmadan once tam test onerilir.");
            } else {
                getLogger().info("Minecraft/Paper " + mc + " KiraziumDungeons tarafindan destekleniyor.");
            }
            return;
        }
        getLogger().warning("Minecraft/Paper " + mc + " dogrulanmis destek listesinde degil. Plugin kapatilmadi; uyumluluk fail-open devam ediyor.");
    }

    private String supportLabel() {
        String mc = minecraftVersion();
        if ("26.3".equals(mc)) return "destekli (26.3)";
        if (SUPPORTED_MINECRAFT_VERSIONS.contains(mc)) return "destekli";
        return "dogrulanmamis";
    }

    private static String minecraftVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (Throwable ignored) {
            return "bilinmiyor";
        }
    }

    private static String version(Plugin plugin) {
        return plugin == null ? "yok" : plugin.getDescription().getVersion() + (plugin.isEnabled() ? " (aktif)" : " (pasif)");
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Throwable; }
}