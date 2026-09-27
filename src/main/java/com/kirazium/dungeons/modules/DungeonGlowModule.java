package com.kirazium.dungeons.modules;

import com.kirazium.dungeons.KiraziumDungeonsPlugin;
import com.kirazium.dungeons.internal.DungeonsMobSource;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

/**
 * Dungeon glow bridge.
 *
 * Verified paths used by this module:
 * - Dungeons 4.1.10: active session -> StructureManager -> placed SpawnerStructure -> private entities queue.
 * - ModelEngine R4.1.0: ModelEngineAPI.getModeledEntity(Entity), ModeledEntity#getModels(),
 *   ActiveModel#setGlowing(Boolean), setGlowColor(Integer), setGlowAudience(Set<UUID>).
 * - PhoenixCrates 6.0.0 ModelEngineDisplayComponent#getActiveModel() returns Phoenix MegActiveModel wrapper;
 *   its concrete wrapper exposes getObject() -> ModelEngine ActiveModel.
 * - PhoenixCrates 6.0.0 BetterModelDisplayComponent#getActiveModel() returns BetterModelDisplay,
 *   which contains the BetterModel EntityTracker in its verified private field "tracker".
 * - BetterModel v3 API: Tracker#update(TrackerUpdateAction), TrackerUpdateAction.glow(boolean), glowColor(int).
 * - PhoenixCrates 6.0.0 VanillaModelDisplayComponent stores its fake armor stand in field "entity";
 *   FakeEntity#getBukkitEntity() is used for vanilla glowing.
 *
 * VanillaBlockDisplayComponent is intentionally NOT approximated: blocks do not have Bukkit Entity glowing.
 */
public final class DungeonGlowModule {
    private static final String ME_COMPONENT = "com.phoenixplugins.phoenixcrates.managers.crates.engine.modelengine.ModelEngineDisplayComponent";
    private static final String BM_COMPONENT = "com.phoenixplugins.phoenixcrates.managers.crates.engine.bettermodel.BetterModelDisplayComponent";
    private static final String VANILLA_MODEL_COMPONENT = "com.phoenixplugins.phoenixcrates.managers.crates.engine.vanilla_model.VanillaModelDisplayComponent";
    private static final String VANILLA_BLOCK_COMPONENT = "com.phoenixplugins.phoenixcrates.managers.crates.engine.vanilla_block.VanillaBlockDisplayComponent";

    private final KiraziumDungeonsPlugin plugin;
    private BukkitTask task;
    private Plugin dungeonsPlugin;
    private Plugin modelEnginePlugin;

    private final Map<UUID, VanillaEntityState> vanillaEntityStates = new HashMap<>();
    private final IdentityHashMap<Object, ModelEngineState> modelEngineStates = new IdentityHashMap<>();
    private final IdentityHashMap<Object, ChestGlowState> chestStates = new IdentityHashMap<>();
    private final Set<String> unsupportedChestComponents = new TreeSet<>();
    private final Set<String> warned = new HashSet<>();

    private Field lootBridgeField;
    private Field lootDisplaysField;
    private final Map<Class<?>, Field> viewerDisplaysFieldCache = new HashMap<>();

    private int activeDungeonMobs;
    private int modelEngineMobs;
    private int vanillaMobs;
    private int chestDisplaysSeen;
    private int chestDisplaysGlowing;
    private int chestModelEngine;
    private int chestBetterModel;
    private int chestVanillaModel;
    private int chestUnsupported;

    public DungeonGlowModule(KiraziumDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        dungeonsPlugin = Bukkit.getPluginManager().getPlugin("Dungeons");
        modelEnginePlugin = Bukkit.getPluginManager().getPlugin("ModelEngine");
        if (dungeonsPlugin == null || !dungeonsPlugin.isEnabled()) {
            plugin.getLogger().warning("DungeonGlow: Dungeons aktif degil; glow modulu baslatilmadi.");
            return;
        }
        verifyDungeonsPath();
        verifyModelEnginePathIfPresent();
        schedule();
        plugin.getLogger().info("DungeonGlow aktif: Dungeons EntityController/session-region mob + Phoenix LootChest glow taramasi.");
    }

    public void disable() {
        if (task != null) task.cancel();
        task = null;
        restoreAll();
        unsupportedChestComponents.clear();
        warned.clear();
    }

    public void reload() {
        restoreAll();
        unsupportedChestComponents.clear();
        warned.clear();
        modelEnginePlugin = Bukkit.getPluginManager().getPlugin("ModelEngine");
        if (task != null) task.cancel();
        task = null;
        if (dungeonsPlugin == null) dungeonsPlugin = Bukkit.getPluginManager().getPlugin("Dungeons");
        if (dungeonsPlugin != null && dungeonsPlugin.isEnabled()) schedule();
    }

    private void schedule() {
        long ticks = Math.max(1L, plugin.getConfig().getLong("glow.scan-ticks", 5L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scanSafe, 2L, ticks);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("glow.enabled", true);
    }

    private void scanSafe() {
        if (!enabled()) {
            restoreAll();
            return;
        }
        try {
            scan();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "DungeonGlow tarama hatasi", t);
        }
    }

    private void scan() throws Exception {
        activeDungeonMobs = 0;
        modelEngineMobs = 0;
        vanillaMobs = 0;
        chestDisplaysSeen = 0;
        chestDisplaysGlowing = 0;
        chestModelEngine = 0;
        chestBetterModel = 0;
        chestVanillaModel = 0;
        chestUnsupported = 0;

        Set<UUID> liveVanillaEntities = new HashSet<>();
        Set<Object> liveModels = Collections.newSetFromMap(new IdentityHashMap<>());

        if (plugin.getConfig().getBoolean("glow.mobs.enabled", true)) {
            scanMobGlow(liveVanillaEntities, liveModels);
        }
        cleanupMobGlow(liveVanillaEntities, liveModels);

        Set<Object> liveChestComponents = Collections.newSetFromMap(new IdentityHashMap<>());
        if (plugin.getConfig().getBoolean("glow.lootchests.enabled", true)) {
            scanLootChestGlow(liveChestComponents);
        }
        cleanupChestGlow(liveChestComponents);
    }

    private void scanMobGlow(Set<UUID> liveVanillaEntities, Set<Object> liveModels) throws Exception {
        Object controller = dungeonsPlugin.getClass().getMethod("getDungeonSessionController").invoke(dungeonsPlugin);
        Object rawSessions = controller.getClass().getMethod("getAllSessions").invoke(controller);
        if (!(rawSessions instanceof Iterable<?> sessions)) return;

        int color = color("glow.mobs.color", 0xFF5555);
        boolean vanillaFallback = plugin.getConfig().getBoolean("glow.mobs.vanilla-fallback", true);

        for (Object session : sessions) {
            if (session == null || !isActiveSession(session)) continue;
            Set<UUID> audience = sessionPlayerIds(session);
            for (Entity entity : DungeonsMobSource.collect(dungeonsPlugin, session)) {
                activeDungeonMobs++;
                if (applyModelEngineMob(entity, audience, color, liveModels)) {
                    modelEngineMobs++;
                } else if (vanillaFallback) {
                    applyVanillaEntityGlow(entity, liveVanillaEntities);
                    vanillaMobs++;
                }
            }
        }
    }

    private boolean applyModelEngineMob(Entity entity, Set<UUID> audience, int color, Set<Object> liveModels) {
        if (modelEnginePlugin == null || !modelEnginePlugin.isEnabled()) return false;
        try {
            ClassLoader cl = modelEnginePlugin.getClass().getClassLoader();
            Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI", true, cl);
            Method getModeledEntity = api.getMethod("getModeledEntity", Entity.class);
            Object modeled = getModeledEntity.invoke(null, entity);
            if (modeled == null) return false;
            Object rawModels = invokeNoArgs(modeled, "getModels");
            if (!(rawModels instanceof Map<?, ?> models) || models.isEmpty()) return false;

            boolean applied = false;
            for (Object model : models.values()) {
                if (model == null) continue;
                if (!supportsModelEngineGlow(model)) continue;
                modelEngineStates.computeIfAbsent(model, this::captureModelEngineState);
                setModelEngineGlow(model, true, color, audience);
                liveModels.add(model);
                applied = true;
            }
            return applied;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            warnOnce("me-api", "DungeonGlow: ModelEngine R4.1.0 glow API yolu bulunamadi; ModelEngine moblarina tahmini fallback uygulanmayacak.");
            return false;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "DungeonGlow ModelEngine mob uygulamasi", t);
            return false;
        }
    }

    private boolean supportsModelEngineGlow(Object model) {
        return hasMethod(model.getClass(), "setGlowing", Boolean.class)
                && hasMethod(model.getClass(), "setGlowColor", Integer.class)
                && hasMethod(model.getClass(), "setGlowAudience", Set.class)
                && hasNoArgMethod(model.getClass(), "isGlowing")
                && hasNoArgMethod(model.getClass(), "getGlowColor")
                && hasNoArgMethod(model.getClass(), "getGlowAudience");
    }

    private ModelEngineState captureModelEngineState(Object model) {
        boolean glowing = Boolean.TRUE.equals(invokeNoArgs(model, "isGlowing"));
        Object rawColor = invokeNoArgs(model, "getGlowColor");
        Integer color = rawColor instanceof Number n ? n.intValue() : null;
        Set<UUID> audience = new HashSet<>();
        Object rawAudience = invokeNoArgs(model, "getGlowAudience");
        if (rawAudience instanceof Iterable<?> it) {
            for (Object x : it) if (x instanceof UUID u) audience.add(u);
        }
        return new ModelEngineState(glowing, color, audience);
    }

    private void setModelEngineGlow(Object model, boolean glowing, Integer color, Set<UUID> audience) throws Exception {
        invokeCompatible(model, "setGlowing", Boolean.valueOf(glowing));
        if (color != null) invokeCompatible(model, "setGlowColor", color);
        invokeCompatible(model, "setGlowAudience", new HashSet<>(audience));
    }

    private void applyVanillaEntityGlow(Entity entity, Set<UUID> live) {
        UUID id = entity.getUniqueId();
        vanillaEntityStates.computeIfAbsent(id, k -> new VanillaEntityState(entity, entity.isGlowing()));
        if (!entity.isGlowing()) entity.setGlowing(true);
        live.add(id);
    }

    private void cleanupMobGlow(Set<UUID> liveVanillaEntities, Set<Object> liveModels) {
        for (Iterator<Map.Entry<UUID, VanillaEntityState>> it = vanillaEntityStates.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, VanillaEntityState> e = it.next();
            if (liveVanillaEntities.contains(e.getKey())) continue;
            restoreVanillaEntity(e.getValue());
            it.remove();
        }
        for (Iterator<Map.Entry<Object, ModelEngineState>> it = modelEngineStates.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Object, ModelEngineState> e = it.next();
            if (liveModels.contains(e.getKey())) continue;
            restoreModelEngine(e.getKey(), e.getValue());
            it.remove();
        }
    }

    private void scanLootChestGlow(Set<Object> liveComponents) {
        Object lootBridge = resolveLootBridge();
        if (lootBridge == null) return;
        Object rawDisplays = readField(lootBridge, resolveFieldCached(lootBridge.getClass(), "displays", true));
        if (!(rawDisplays instanceof Map<?, ?> displayMap)) return;

        int color = color("glow.lootchests.color", 0xFF55FF);
        for (Object handle : displayMap.values()) {
            if (handle == null) continue;
            Field viewerField = viewerDisplaysFieldCache.computeIfAbsent(handle.getClass(), c -> resolveFieldCached(c, "viewerDisplays", true));
            Object rawViewerDisplays = readField(handle, viewerField);
            if (!(rawViewerDisplays instanceof Map<?, ?> viewerDisplays)) continue;
            for (Map.Entry<?, ?> entry : viewerDisplays.entrySet()) {
                if (!(entry.getKey() instanceof UUID viewerId)) continue;
                Object component = entry.getValue();
                if (component == null) continue;
                chestDisplaysSeen++;
                liveComponents.add(component);
                if (applyChestGlow(component, viewerId, color)) chestDisplaysGlowing++;
            }
        }
    }

    private boolean applyChestGlow(Object component, UUID viewerId, int color) {
        String className = component.getClass().getName();
        try {
            if (className.equals(ME_COMPONENT)) {
                chestModelEngine++;
                Object wrapped = invokeNoArgs(component, "getActiveModel");
                if (wrapped == null) return false;
                Object model = invokeNoArgs(wrapped, "getObject");
                if (model == null || !supportsModelEngineGlow(model)) {
                    unsupportedChestComponents.add(className + " (ActiveModel glow API yok)");
                    chestUnsupported++;
                    return false;
                }
                chestStates.computeIfAbsent(component, c -> new ChestGlowState(ChestAdapter.MODEL_ENGINE, model, captureModelEngineState(model), null));
                setModelEngineGlow(model, true, color, Set.of(viewerId));
                return true;
            }

            if (className.equals(BM_COMPONENT)) {
                chestBetterModel++;
                Object display = invokeNoArgs(component, "getActiveModel");
                if (display == null) return false;
                Field trackerField = resolveFieldCached(display.getClass(), "tracker", true);
                Object tracker = readField(display, trackerField);
                if (tracker == null) return false;
                if (!applyBetterModelGlow(tracker, true, color)) {
                    unsupportedChestComponents.add(className + " (TrackerUpdateAction API yok)");
                    chestUnsupported++;
                    return false;
                }
                chestStates.putIfAbsent(component, new ChestGlowState(ChestAdapter.BETTER_MODEL, tracker, null, null));
                return true;
            }

            if (className.equals(VANILLA_MODEL_COMPONENT)) {
                chestVanillaModel++;
                Field entityField = resolveFieldCached(component.getClass(), "entity", true);
                Object fake = readField(component, entityField);
                Object bukkit = invokeNoArgs(fake, "getBukkitEntity");
                if (!(bukkit instanceof Entity entity)) return false;
                chestStates.putIfAbsent(component, new ChestGlowState(ChestAdapter.VANILLA_ENTITY, entity, null, entity.isGlowing()));
                entity.setGlowing(true);
                // FakeEntity watcher needs metadata dirtied after direct Bukkit metadata mutation.
                invokeCompatibleIfPresent(fake, "setMetadataChanged", Boolean.TRUE);
                return true;
            }

            if (className.equals(VANILLA_BLOCK_COMPONENT)) {
                chestUnsupported++;
                unsupportedChestComponents.add(className + " (blok native Entity glow desteklemez)");
                return false;
            }

            chestUnsupported++;
            unsupportedChestComponents.add(className);
            return false;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "DungeonGlow Phoenix component: " + className, t);
            unsupportedChestComponents.add(className + " (adapter hata)");
            chestUnsupported++;
            return false;
        }
    }

    private boolean applyBetterModelGlow(Object tracker, boolean glowing, int color) {
        try {
            ClassLoader cl = tracker.getClass().getClassLoader();
            Class<?> action = Class.forName("kr.toxicity.model.api.tracker.TrackerUpdateAction", true, cl);
            Object glowAction = action.getMethod("glow", boolean.class).invoke(null, glowing);
            Method update = tracker.getClass().getMethod("update", action);
            update.invoke(tracker, glowAction);
            if (glowing) {
                Object colorAction = action.getMethod("glowColor", int.class).invoke(null, color);
                update.invoke(tracker, colorAction);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void cleanupChestGlow(Set<Object> liveComponents) {
        for (Iterator<Map.Entry<Object, ChestGlowState>> it = chestStates.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Object, ChestGlowState> e = it.next();
            if (liveComponents.contains(e.getKey())) continue;
            restoreChestState(e.getValue());
            it.remove();
        }
    }

    private void restoreChestState(ChestGlowState state) {
        try {
            switch (state.adapter) {
                case MODEL_ENGINE -> restoreModelEngine(state.target, state.modelEngineState);
                case BETTER_MODEL -> applyBetterModelGlow(state.target, false, 0);
                case VANILLA_ENTITY -> {
                    if (state.target instanceof Entity entity && entity.isValid() && state.originalVanillaGlow != null) {
                        entity.setGlowing(state.originalVanillaGlow);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void restoreAll() {
        for (VanillaEntityState state : vanillaEntityStates.values()) restoreVanillaEntity(state);
        vanillaEntityStates.clear();
        for (Map.Entry<Object, ModelEngineState> e : modelEngineStates.entrySet()) restoreModelEngine(e.getKey(), e.getValue());
        modelEngineStates.clear();
        for (ChestGlowState state : chestStates.values()) restoreChestState(state);
        chestStates.clear();
        resetCounts();
    }

    private void restoreVanillaEntity(VanillaEntityState state) {
        try {
            if (state.entity != null && state.entity.isValid()) state.entity.setGlowing(state.originalGlow);
        } catch (Throwable ignored) {
        }
    }

    private void restoreModelEngine(Object model, ModelEngineState state) {
        if (model == null || state == null) return;
        try {
            setModelEngineGlow(model, state.glowing, state.color, state.audience);
        } catch (Throwable ignored) {
        }
    }

    private void resetCounts() {
        activeDungeonMobs = modelEngineMobs = vanillaMobs = 0;
        chestDisplaysSeen = chestDisplaysGlowing = chestModelEngine = chestBetterModel = chestVanillaModel = chestUnsupported = 0;
    }

    private Object resolveLootBridge() {
        try {
            if (lootBridgeField == null) {
                lootBridgeField = plugin.getClass().getDeclaredField("lootBridge");
                lootBridgeField.setAccessible(true);
            }
            return lootBridgeField.get(plugin);
        } catch (Throwable t) {
            warnOnce("loot-field", "DungeonGlow: KiraziumDungeons lootBridge alani bulunamadi; LootChest glow uygulanmayacak.");
            return null;
        }
    }

    private void verifyDungeonsPath() {
        try {
            Object controller = dungeonsPlugin.getClass().getMethod("getDungeonSessionController").invoke(dungeonsPlugin);
            controller.getClass().getMethod("getAllSessions");
            if (!DungeonsMobSource.verify(dungeonsPlugin)) {
                throw new IllegalStateException("Dungeons EntityController.entities yolu bulunamadi");
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Dungeons 4.1.10 session/entity API yolu bulunamadi", t);
        }
    }

    private void verifyModelEnginePathIfPresent() {
        if (modelEnginePlugin == null || !modelEnginePlugin.isEnabled()) return;
        try {
            Class<?> api = Class.forName("com.ticxo.modelengine.api.ModelEngineAPI", true, modelEnginePlugin.getClass().getClassLoader());
            api.getMethod("getModeledEntity", Entity.class);
        } catch (Throwable t) {
            plugin.getLogger().warning("DungeonGlow: ModelEngine mevcut fakat R4.1.0 getModeledEntity(Entity) API yolu dogrulanamadi; ME glow devre disi kalabilir.");
        }
    }

    private Set<UUID> sessionPlayerIds(Object session) {
        Set<UUID> ids = new HashSet<>();
        for (Object x : asObjects(invokeNoArgs(session, "getPlayers"))) if (x instanceof UUID u) ids.add(u);
        return ids;
    }

    private boolean isActiveSession(Object session) {
        Object status = invokeNoArgs(session, "getStatus");
        return status != null && "ACTIVE".equalsIgnoreCase(status.toString());
    }

    private static boolean alive(Entity entity) {
        return entity != null && entity.isValid() && !entity.isDead();
    }

    private int color(String path, int fallback) {
        String raw = plugin.getConfig().getString(path, String.format(Locale.ROOT, "%06X", fallback));
        if (raw == null) return fallback;
        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
        if (!value.matches("[0-9A-Fa-f]{6}")) {
            warnOnce("color:" + path + ":" + raw, "DungeonGlow: gecersiz RGB @ " + path + "='" + raw + "'; bu alan icin varsayilan renk kullaniliyor.");
            return fallback;
        }
        return Integer.parseInt(value, 16);
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.getLogger().warning(message);
    }

    public void sendStatus(CommandSender sender) {
        sender.sendMessage("\u00a7dDungeon Glow \u00a77" + (enabled() ? "aktif" : "pasif"));
        sender.sendMessage("\u00a77Moblar: \u00a7f" + activeDungeonMobs + " \u00a78| \u00a77ModelEngine: \u00a7f" + modelEngineMobs + " \u00a78| \u00a77Vanilla fallback: \u00a7f" + vanillaMobs);
        sender.sendMessage("\u00a77LootChest display: \u00a7f" + chestDisplaysSeen + " \u00a78| \u00a77Glow uygulanan: \u00a7f" + chestDisplaysGlowing);
        sender.sendMessage("\u00a77Engine: \u00a7fME=" + chestModelEngine + " BM=" + chestBetterModel + " VanillaModel=" + chestVanillaModel + " Unsupported=" + chestUnsupported);
        if (!unsupportedChestComponents.isEmpty()) {
            sender.sendMessage("\u00a7cDesteklenmeyen/uygulanmayan Phoenix display siniflari:");
            for (String s : unsupportedChestComponents) sender.sendMessage("\u00a78- \u00a7f" + s);
        }
        sender.sendMessage("\u00a78ModelEngine glow audience aktif dungeon oyuncularina sinirlanir. Vanilla entity fallback glow Bukkit seviyesinde globaldir.");
    }

    public String statusLine() {
        return "mobs=" + activeDungeonMobs + "(ME=" + modelEngineMobs + ",vanilla=" + vanillaMobs + ") chests=" + chestDisplaysGlowing + "/" + chestDisplaysSeen;
    }

    private static Field resolveFieldCached(Class<?> start, String name, boolean accessible) {
        if (start == null) return null;
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (accessible) f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Object readField(Object target, Field field) {
        if (target == null || field == null) return null;
        try { return field.get(target); } catch (Throwable t) { return null; }
    }

    private static boolean hasMethod(Class<?> c, String name, Class<?> param) {
        try { c.getMethod(name, param); return true; } catch (Throwable t) { return false; }
    }

    private static boolean hasNoArgMethod(Class<?> c, String name) {
        try { c.getMethod(name); return true; } catch (Throwable t) { return false; }
    }

    private static Object invokeNoArgs(Object target, String name) {
        if (target == null) return null;
        try { return target.getClass().getMethod(name).invoke(target); } catch (Throwable t) { return null; }
    }

    private static void invokeCompatibleIfPresent(Object target, String name, Object... args) {
        try { invokeCompatible(target, name, args); } catch (Throwable ignored) { }
    }

    private static void invokeCompatible(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new NullPointerException(name + " target");
        outer:
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            Class<?>[] p = m.getParameterTypes();
            for (int i = 0; i < p.length; i++) {
                if (args[i] != null && !wrap(p[i]).isInstance(args[i])) continue outer;
            }
            m.invoke(target, args);
            return;
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    private static List<Object> asObjects(Object raw) {
        if (raw == null) return List.of();
        List<Object> out = new ArrayList<>();
        if (raw instanceof Iterable<?> it) for (Object x : it) out.add(x);
        else if (raw.getClass().isArray()) for (int i = 0; i < Array.getLength(raw); i++) out.add(Array.get(raw, i));
        return out;
    }

    private record VanillaEntityState(Entity entity, boolean originalGlow) { }
    private record ModelEngineState(boolean glowing, Integer color, Set<UUID> audience) { }
    private enum ChestAdapter { MODEL_ENGINE, BETTER_MODEL, VANILLA_ENTITY }
    private record ChestGlowState(ChestAdapter adapter, Object target, ModelEngineState modelEngineState, Boolean originalVanillaGlow) { }
}