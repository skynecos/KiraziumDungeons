package com.kirazium.dungeons.modules;

import com.kirazium.dungeons.KiraziumDungeonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * PhoenixCrates burada sadece render + phase animasyon motorudur.
 * Phoenix openCrate/reward/key/cost API'si bilerek hic cagrilmaz.
 * Dungeons+ kendi LootChest inventory/reward akisini sahiplenmeye devam eder.
 */
public final class PhoenixLootChestBridge implements Listener {
    private final KiraziumDungeonsPlugin plugin;
    private final Map<LocationKey, DisplayHandle> displays = new HashMap<>();
    private final Map<UUID, CachedInteract> recentInteract = new ConcurrentHashMap<>();
    private final Set<OpenKey> bypass = new HashSet<>();
    private final Set<OpenKey> animating = new HashSet<>();
    private Plugin phoenixPlugin;
    private Plugin dungeonsPlugin;
    private Object cratesManager;
    private Listener dungeonEventListener;
    private BukkitTask scanTask;
    private String selectedAutoCrateId;
    private boolean eventHookInstalled;
    private final Set<String> discoveredLootStructures = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> warnedInvalidCrateSelections = new HashSet<>();
    private final Set<String> warnedInvalidTransforms = new HashSet<>();
    private final Map<String, String> discoveredTransforms = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public PhoenixLootChestBridge(KiraziumDungeonsPlugin plugin) { this.plugin = plugin; }

    public void enable() {
        dungeonsPlugin = Bukkit.getPluginManager().getPlugin("Dungeons");
        phoenixPlugin = Bukkit.getPluginManager().getPlugin("PhoenixCrates");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        installDungeonLootEventHook();
        resolvePhoenixManager();
        scheduleScan();
    }

    public void disable() {
        if (scanTask != null) scanTask.cancel();
        scanTask = null;
        for (DisplayHandle h : new ArrayList<>(displays.values())) unloadDisplay(h);
        displays.clear(); recentInteract.clear(); bypass.clear(); animating.clear(); discoveredLootStructures.clear(); warnedInvalidCrateSelections.clear(); warnedInvalidTransforms.clear(); discoveredTransforms.clear();
    }

    public void reload() {
        resolvePhoenixManager();
        selectedAutoCrateId = null;
        warnedInvalidCrateSelections.clear();
        warnedInvalidTransforms.clear();
        discoveredTransforms.clear();
        // Transform/crate config changes must take effect immediately.
        for (DisplayHandle h : new ArrayList<>(displays.values())) unloadDisplay(h);
        displays.clear();
        if (scanTask != null) scanTask.cancel();
        scheduleScan();
        scanSafe();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("phoenix-lootchest.enabled", true)
                && phoenixPlugin != null && phoenixPlugin.isEnabled()
                && dungeonsPlugin != null && dungeonsPlugin.isEnabled();
    }

    private void resolvePhoenixManager() {
        phoenixPlugin = Bukkit.getPluginManager().getPlugin("PhoenixCrates");
        cratesManager = null;
        if (phoenixPlugin == null || !phoenixPlugin.isEnabled()) return;
        try {
            Class<?> api = Class.forName("com.phoenixplugins.phoenixcrates.api.PhoenixCratesAPI", true, phoenixPlugin.getClass().getClassLoader());
            cratesManager = api.getMethod("getCratesManager").invoke(null);
            if (cratesManager != null) plugin.getLogger().info("PhoenixLootChest: CratesManager API hazir: " + cratesManager.getClass().getName());
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "PhoenixLootChest: Phoenix CratesManager alinamadi.", t);
        }
    }

    @SuppressWarnings("unchecked")
    private void installDungeonLootEventHook() {
        if (eventHookInstalled || dungeonsPlugin == null) return;
        try {
            Class<?> raw = Class.forName("com.utilsmc.dungeons.events.DungeonLootchestOpenEvent", true, dungeonsPlugin.getClass().getClassLoader());
            Class<? extends Event> eventClass = (Class<? extends Event>) raw;
            dungeonEventListener = new Listener(){};
            EventExecutor exec = (listener, event) -> onDungeonLootOpen(event);
            Bukkit.getPluginManager().registerEvent(eventClass, dungeonEventListener, EventPriority.HIGHEST, exec, plugin, true);
            eventHookInstalled = true;
            plugin.getLogger().info("PhoenixLootChest: Dungeons+ DungeonLootchestOpenEvent hook aktif.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "PhoenixLootChest Dungeons event hook kurulamadı.", t);
        }
    }

    private void scheduleScan() {
        long ticks = Math.max(5L, plugin.getConfig().getLong("phoenix-lootchest.scan-ticks", 10L));
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanSafe, 20L, ticks);
    }

    private void scanSafe() {
        if (!enabled() || cratesManager == null) {
            if (!displays.isEmpty()) { for (DisplayHandle h:new ArrayList<>(displays.values())) unloadDisplay(h); displays.clear(); }
            return;
        }
        try { scan(); } catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "PhoenixLootChest scan hatasi", t); }
    }

    private void scan() throws Exception {
        Object controller = dungeonsPlugin.getClass().getMethod("getDungeonSessionController").invoke(dungeonsPlugin);
        Object rawSessions = controller.getClass().getMethod("getAllSessions").invoke(controller);
        Set<LocationKey> live = new HashSet<>();
        Map<LocationKey, Set<UUID>> viewers = new HashMap<>();

        if (rawSessions instanceof Iterable<?> sessions) {
            for (Object session : sessions) {
                if (session == null || !isActiveSession(session)) continue;
                Set<UUID> sessionPlayers = sessionPlayerIds(session);
                Object sm = invokeNoArgs(session, "getStructureManager");
                for (Object placed : asObjects(invokeNoArgs(sm, "getPlacedStructures"))) {
                    if (!isLootStructure(placed)) continue;
                    String placedName = structureName(placed);
                    if (!placedName.isBlank()) discoveredLootStructures.add(placedName);
                    Location loc = (Location) invokeNoArgs(placed, "getLocation");
                    if (loc == null || loc.getWorld() == null) continue;
                    LocationKey key = LocationKey.of(loc);
                    live.add(key);
                    viewers.computeIfAbsent(key, k -> new HashSet<>()).addAll(sessionPlayers);
                    DisplayHandle h = displays.get(key);
                    if (h == null) {
                        h = createDisplaySafe(placed, loc);
                        if (h != null) displays.put(key, h);
                    }
                }
            }
        }

        for (LocationKey key : new ArrayList<>(displays.keySet())) {
            if (!live.contains(key)) { DisplayHandle h=displays.remove(key); if(h!=null)unloadDisplay(h); }
        }

        for (var e : displays.entrySet()) syncViewers(e.getValue(), viewers.getOrDefault(e.getKey(), Set.of()));
    }

    private Set<UUID> sessionPlayerIds(Object session) {
        Set<UUID> ids = new HashSet<>();
        for (Object x : asObjects(invokeNoArgs(session, "getPlayers"))) if (x instanceof UUID u) ids.add(u);
        return ids;
    }

    private void syncViewers(DisplayHandle h, Set<UUID> desired) {
        for (UUID id : desired) {
            if (h.viewerDisplays.containsKey(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Object component = createViewerDisplay(h, p);
                if (component != null) h.viewerDisplays.put(id, component);
            }
        }
        for (UUID id : new ArrayList<>(h.viewerDisplays.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (!desired.contains(id) || p == null || !p.isOnline()) {
                destroyViewerDisplay(h.viewerDisplays.remove(id));
            }
        }
    }

    private Object createViewerDisplay(DisplayHandle h, Player player) {
        try {
            Object engineType = invokeNoArgs(h.type, "getEngineType");
            if (engineType == null) return null;
            Method factory = null;
            for (Method m : engineType.getClass().getMethods()) {
                if (!m.getName().equals("createDisplayComponent") || m.getParameterCount()!=2) continue;
                Class<?>[] p=m.getParameterTypes();
                if (p[0].isInstance(h.crate) && p[1].isInstance(player)) { factory=m; break; }
            }
            if (factory == null) return null;
            Object display=factory.invoke(engineType,h.crate,player);
            display.getClass().getMethod("create",boolean.class).invoke(display,false);
            return display;
        } catch(Throwable t){plugin.getLogger().log(Level.FINE,"Phoenix viewer display olusturulamadi: "+player.getName(),t);return null;}
    }

    private void destroyViewerDisplay(Object display) {
        if(display==null)return; try{display.getClass().getMethod("destroy",boolean.class).invoke(display,false);}catch(Throwable ignored){}
    }

    private boolean isActiveSession(Object session) {
        Object status=invokeNoArgs(session,"getStatus"); return status!=null&&"ACTIVE".equalsIgnoreCase(status.toString());
    }

    private boolean isLootStructure(Object placed) {
        String type = structureType(placed);
        if (type == null) return false;
        if (type.equals("LootchestStructure")) return plugin.getConfig().getBoolean("phoenix-lootchest.personal", true);
        if (type.equals("SharedLootchestStructure")) return plugin.getConfig().getBoolean("phoenix-lootchest.shared", true);
        return false;
    }

    private String structureType(Object placed) {
        Object structure=actualStructure(placed); return structure==null?null:structure.getClass().getSimpleName();
    }

    private Object actualStructure(Object placed) {
        Object sl=invokeNoArgs(placed,"getStructureLocation"); Object cfg=invokeNoArgs(sl,"getConfiguration"); return invokeNoArgs(cfg,"getStructure");
    }

    private Map<?,?> structureProperties(Object placed) {
        Object sl=invokeNoArgs(placed,"getStructureLocation"); Object props=invokeNoArgs(sl,"getProperties"); return props instanceof Map<?,?> m?m:Collections.emptyMap();
    }

    private String structureName(Object placed) {
        Object sl=invokeNoArgs(placed,"getStructureLocation"); Object n=invokeNoArgs(sl,"getName"); return n==null?"":n.toString();
    }

    private DisplayHandle createDisplaySafe(Object placed, Location loc) {
        try {
            String crateId = resolveCrateId(placed);
            if (crateId == null) return null;
            Object type = invoke(cratesManager, "getTypeByIdentifier", new Class<?>[]{String.class}, crateId);
            if (type == null) return null;

            ClassLoader cl = phoenixPlugin.getClass().getClassLoader();
            Class<?> crateClass = Class.forName("com.phoenixplugins.phoenixcrates.managers.crates.Crate", true, cl);
            Class<?> facingClass = Class.forName("com.phoenixplugins.phoenixcrates.internal.FacingDirection", true, cl);

            TransformSpec transform = resolveTransform(placed, loc, facingClass);
            if (transform == null) return null;
            Location renderLoc = loc.clone().add(transform.offsetX, transform.offsetY, transform.offsetZ);

            Constructor<?> ctor = null;
            for (Constructor<?> c : crateClass.getConstructors()) {
                if (c.getParameterCount() != 4) continue;
                Class<?>[] p = c.getParameterTypes();
                if (p[0].isInstance(cratesManager) && p[1].isInstance(type)
                        && p[2].isAssignableFrom(Location.class) && p[3].isInstance(transform.facing)) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) throw new NoSuchMethodException("Phoenix Crate(manager,type,Location,FacingDirection)");
            Object crate = ctor.newInstance(cratesManager, type, renderLoc, transform.facing);

            String structure = structureName(placed);
            discoveredTransforms.put(structure,
                    "facing=" + transform.facingName + " (" + transform.source + ")"
                            + ", offset=" + fmt(transform.offsetX) + "," + fmt(transform.offsetY) + "," + fmt(transform.offsetZ));

            // Bilerek crate.load()/manager.createCrate() YOK: fiziksel block ve Phoenix reward/open listener kaydi degismez.
            return new DisplayHandle(crate, type, crateId, loc.clone(), renderLoc.clone(), structure,
                    transform.facingName, transform.source, transform.offsetX, transform.offsetY, transform.offsetZ);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Phoenix LootChest display sablonu olusturulamadi @ " + loc, t);
            return null;
        }
    }

    private TransformSpec resolveTransform(Object placed, Location loc, Class<?> facingClass) {
        String name = structureName(placed);
        String base = "phoenix-lootchest.structure-transform." + name + ".";
        String global = "phoenix-lootchest.default-transform.";

        String facingRaw = configStringOrDefault(base + "facing", global + "facing", "AUTO");
        Double offsetX = configNumberOrDefault(base + "offset-x", global + "offset-x", 0.0, name);
        Double offsetY = configNumberOrDefault(base + "offset-y", global + "offset-y", 0.0, name);
        Double offsetZ = configNumberOrDefault(base + "offset-z", global + "offset-z", 0.0, name);
        if (offsetX == null || offsetY == null || offsetZ == null) return null;

        FacingChoice facing = resolveFacing(placed, loc, facingClass, facingRaw, name);
        if (facing == null) return null;
        return new TransformSpec(facing.value, facing.name, facing.source, offsetX, offsetY, offsetZ);
    }

    private FacingChoice resolveFacing(Object placed, Location loc, Class<?> facingClass, String raw, String structureName) {
        String requested = raw == null ? "AUTO" : raw.trim();
        if (requested.isEmpty()) requested = "AUTO";

        if (!requested.equalsIgnoreCase("AUTO")) {
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object facing = Enum.valueOf((Class<? extends Enum>) facingClass.asSubclass(Enum.class), requested.toUpperCase(Locale.ROOT));
                if ("UNKNOWN".equalsIgnoreCase(facing.toString())) {
                    warnInvalidTransform(structureName, "facing UNKNOWN manuel secilemez; AUTO veya gercek yon kullan.");
                    return null;
                }
                return new FacingChoice(facing, facing.toString(), "CONFIG");
            } catch (IllegalArgumentException ex) {
                warnInvalidTransform(structureName, "gecersiz facing='" + requested + "'. Phoenix FacingDirection enumunda yok.");
                return null;
            }
        }

        // 1) Exact live block state. Dungeons 4.1.10 LootchestStructure.activate() applies
        //    its "Block Direction" property to the Directional block at this location.
        try {
            BlockData data = loc.getBlock().getBlockData();
            if (data instanceof Directional directional) {
                BlockFace face = directional.getFacing();
                Object phoenixFacing = facingClass.getMethod("fromBukkit", BlockFace.class).invoke(null, face);
                if (phoenixFacing != null && !"UNKNOWN".equalsIgnoreCase(phoenixFacing.toString())) {
                    return new FacingChoice(phoenixFacing, phoenixFacing.toString(), "DUNGEONS_BLOCK:" + face.name());
                }
            }
        } catch (Throwable ignored) {
            // Exact property fallback below; no guessed direction is introduced here.
        }

        // 2) Exact Dungeons placement property fallback.
        Object directionValue = structureProperties(placed).get("Block Direction");
        Object rawValue = invokeNoArgs(directionValue, "getValue");
        if (rawValue != null) {
            String faceName = rawValue.toString().trim();
            try {
                BlockFace face = BlockFace.valueOf(faceName);
                Object phoenixFacing = facingClass.getMethod("fromBukkit", BlockFace.class).invoke(null, face);
                if (phoenixFacing != null && !"UNKNOWN".equalsIgnoreCase(phoenixFacing.toString())) {
                    return new FacingChoice(phoenixFacing, phoenixFacing.toString(), "DUNGEONS_PROPERTY:" + face.name());
                }
            } catch (IllegalArgumentException ex) {
                warnInvalidTransform(structureName, "Dungeons Block Direction gecersiz: '" + faceName + "'.");
                return null;
            } catch (Throwable t) {
                warnInvalidTransform(structureName, "Dungeons Block Direction Phoenix yonune cevrilemedi: '" + faceName + "'.");
                return null;
            }
        }

        warnInvalidTransform(structureName, "AUTO facing icin Directional block veya Dungeons 'Block Direction' bulunamadi; UNKNOWN kullanilmadi.");
        return null;
    }

    private String configStringOrDefault(String exactPath, String defaultPath, String fallback) {
        Object exact = plugin.getConfig().get(exactPath);
        if (exact != null) return exact.toString();
        Object global = plugin.getConfig().get(defaultPath);
        return global == null ? fallback : global.toString();
    }

    private Double configNumberOrDefault(String exactPath, String defaultPath, double fallback, String structureName) {
        Object raw = plugin.getConfig().get(exactPath);
        if (raw == null) raw = plugin.getConfig().get(defaultPath);
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.doubleValue();
        warnInvalidTransform(structureName, "sayisal olmayan offset @ " + exactPath + " = '" + raw + "'.");
        return null;
    }

    private void warnInvalidTransform(String structureName, String reason) {
        String key = structureName + "|" + reason;
        if (!warnedInvalidTransforms.add(key)) return;
        plugin.getLogger().warning("PhoenixLootChest transform gecersiz @ '" + structureName + "': " + reason + " Baska yon/offset tahmin edilmedi.");
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String resolveCrateId(Object placed) {
        String name = structureName(placed);

        // Exact placement-name mapping only. No normalization/guessing.
        // Example: phoenix-lootchest.structure-map.oda1odul-sol: Divina
        String mapped = plugin.getConfig().getString("phoenix-lootchest.structure-map." + name, "");
        if (mapped != null && !mapped.isBlank()) {
            mapped = mapped.trim();
            if (mapped.equalsIgnoreCase("auto")) return resolveAutoCrateId();
            if (crateTypeUsable(mapped)) return mapped;
            warnInvalidSelection("structure:" + name, mapped);
            return null; // strict: never silently fall back to another crate
        }

        String configured = plugin.getConfig().getString("phoenix-lootchest.default-crate-id", "auto");
        if (configured == null || configured.isBlank() || configured.equalsIgnoreCase("none") || configured.equalsIgnoreCase("off")) return null;
        configured = configured.trim();
        if (configured.equalsIgnoreCase("auto")) return resolveAutoCrateId();
        if (crateTypeUsable(configured)) return configured;
        warnInvalidSelection("default", configured);
        return null; // strict: invalid explicit ID never becomes some other crate
    }

    private String resolveAutoCrateId() {
        if (selectedAutoCrateId != null && crateTypeUsable(selectedAutoCrateId)) return selectedAutoCrateId;

        List<String> usable = new ArrayList<>();
        for (String id : availableCrateIds()) {
            if (crateTypeUsable(id)) usable.add(id);
        }

        // AUTO must never guess between multiple valid crate types.
        // Only a single unambiguous Phoenix crate can be selected automatically.
        if (usable.size() == 1) {
            selectedAutoCrateId = usable.get(0);
            plugin.getLogger().info("PhoenixLootChest AUTO kasa tipi tek ve kesin: " + selectedAutoCrateId);
            return selectedAutoCrateId;
        }

        String key = "auto-ambiguous:" + String.join(",", usable);
        if (warnedInvalidCrateSelections.add(key)) {
            if (usable.isEmpty()) {
                plugin.getLogger().warning("PhoenixLootChest AUTO: kullanilabilir Phoenix crate bulunamadi. Tahmin/fallback yapilmadi.");
            } else {
                plugin.getLogger().warning("PhoenixLootChest AUTO belirsiz: " + usable.size() + " kullanilabilir crate var ["
                        + String.join(", ", usable) + "]. Ilki secilmedi. /kd phoenix ile GERCEK ID'yi gorup default-crate-id veya structure-map'e birebir yaz.");
            }
        }
        return null;
    }

    private List<String> availableCrateIds() {
        List<String> ids = new ArrayList<>();
        for (Object x : asObjects(invokeNoArgs(cratesManager, "getCratesIdentifier"))) {
            if (x != null) ids.add(x.toString());
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    private void warnInvalidSelection(String source, String crateId) {
        String key = source + "=" + crateId;
        if (!warnedInvalidCrateSelections.add(key)) return;
        plugin.getLogger().warning("PhoenixLootChest: gecersiz Phoenix crate ID ('" + crateId + "') @ " + source + ". Baska kasaya otomatik dusulmedi.");
    }

    private boolean crateTypeUsable(String id) {
        Object type=invoke(cratesManager,"getTypeByIdentifier",new Class<?>[]{String.class},id); if(type==null)return false; Object enabled=invokeNoArgs(type,"isEnabled");return !(enabled instanceof Boolean b)||b;
    }

    private void unloadDisplay(DisplayHandle h) {
        if(h==null)return; for(Object d:new ArrayList<>(h.viewerDisplays.values()))destroyViewerDisplay(d);h.viewerDisplays.clear();
    }

    @EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=false)
    public void onInteract(PlayerInteractEvent event) {
        Block block=event.getClickedBlock(); if(block==null)return;
        recentInteract.put(event.getPlayer().getUniqueId(),new CachedInteract(event,LocationKey.of(block.getLocation()),System.currentTimeMillis()));
    }

    private void onDungeonLootOpen(Event event) {
        if(!enabled()||cratesManager==null)return;
        try {
            Object playerObj=invokeNoArgs(event,"getPlayer"), placed=invokeNoArgs(event,"getPlacedStructure"), session=invokeNoArgs(event,"getSession");
            if(!(playerObj instanceof Player player)||placed==null||session==null||!isLootStructure(placed))return;
            Location loc=(Location)invokeNoArgs(placed,"getLocation"); if(loc==null)return;
            OpenKey openKey=new OpenKey(player.getUniqueId(),LocationKey.of(loc));
            if(bypass.remove(openKey))return;
            if(animating.contains(openKey)){if(event instanceof Cancellable c)c.setCancelled(true);return;}
            CachedInteract cached=recentInteract.get(player.getUniqueId());
            if(cached==null||!cached.key.equals(openKey.location)||System.currentTimeMillis()-cached.timeMillis>2500L)return;
            if(event instanceof Cancellable c)c.setCancelled(true); else invoke(event,"setCancelled",new Class<?>[]{boolean.class},true);
            animating.add(openKey);
            DisplayHandle h=displays.get(openKey.location);
            if(h==null){h=createDisplaySafe(placed,loc);if(h!=null){displays.put(openKey.location,h);Object comp=createViewerDisplay(h,player);if(comp!=null)h.viewerDisplays.put(player.getUniqueId(),comp);}}
            else if(!h.viewerDisplays.containsKey(player.getUniqueId())){Object comp=createViewerDisplay(h,player);if(comp!=null)h.viewerDisplays.put(player.getUniqueId(),comp);}
            DisplayHandle finalHandle=h;
            runVisualSequence(finalHandle,player,()->{
                boolean consumed=false;
                try {
                    bypass.add(openKey);
                    Object structure=actualStructure(placed); Map<?,?> props=structureProperties(placed);
                    invokeCompatible(structure,"onInteract",loc,session,cached.event,props);
                    consumed=true;
                } catch(Throwable t){plugin.getLogger().log(Level.SEVERE,"Phoenix animasyonundan sonra Dungeons LootChest acilamadi",t);}
                finally {if(!consumed)bypass.remove(openKey);animating.remove(openKey);recentInteract.remove(player.getUniqueId(),cached);}
            });
        } catch(Throwable t){plugin.getLogger().log(Level.WARNING,"DungeonLootchestOpenEvent bridge hatasi",t);}
    }

    private void runVisualSequence(DisplayHandle h,Player player,Runnable done) {
        if(h==null){done.run();return;} Object display=h.viewerDisplays.get(player.getUniqueId()); if(display==null){done.run();return;}
        playPhase(display,"PRE_OPENING",()->playPhase(display,"OPENING",done));
    }

    private void playPhase(Object display,String phaseName,Runnable done) {
        try {
            ClassLoader cl=phoenixPlugin.getClass().getClassLoader(); Class<?> phaseClass=Class.forName("com.phoenixplugins.phoenixcrates.api.crate.animation.PhaseType",true,cl);
            @SuppressWarnings({"unchecked","rawtypes"}) Object phase=Enum.valueOf((Class<? extends Enum>)phaseClass.asSubclass(Enum.class),phaseName);
            Method play=display.getClass().getMethod("playAnimation",phaseClass), isPlaying=display.getClass().getMethod("isPlayingAnimation",phaseClass);
            play.invoke(display,phase);
            int max=Math.max(1,plugin.getConfig().getInt("phoenix-lootchest.max-animation-wait-ticks",60));
            pollAnimation(display,isPlaying,phase,max,done);
        } catch(Throwable t){done.run();}
    }

    private void pollAnimation(Object display,Method isPlaying,Object phase,int remaining,Runnable done) {
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            try {if(Boolean.TRUE.equals(isPlaying.invoke(display,phase))&&remaining>0){pollAnimation(display,isPlaying,phase,remaining-1,done);return;}}catch(Throwable ignored){}
            done.run();
        },1L);
    }

    private static void invokeCompatible(Object target,String name,Object...args) throws Exception {
        if(target==null)throw new NullPointerException(name+" target");
        outer: for(Method m:target.getClass().getMethods()){
            if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;Class<?>[]p=m.getParameterTypes();
            for(int i=0;i<p.length;i++)if(args[i]!=null&&!wrap(p[i]).isInstance(args[i]))continue outer;
            m.invoke(target,args);return;
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
    private static Class<?> wrap(Class<?> c){if(!c.isPrimitive())return c;if(c==boolean.class)return Boolean.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==double.class)return Double.class;if(c==float.class)return Float.class;if(c==short.class)return Short.class;if(c==byte.class)return Byte.class;if(c==char.class)return Character.class;return c;}
    private static Object invokeNoArgs(Object target,String name){if(target==null)return null;try{return target.getClass().getMethod(name).invoke(target);}catch(Throwable t){return null;}}
    private static Object invoke(Object target,String name,Class<?>[]params,Object...args){if(target==null)return null;try{return target.getClass().getMethod(name,params).invoke(target,args);}catch(Throwable t){return null;}}
    private static List<Object> asObjects(Object raw){if(raw==null)return List.of();List<Object>o=new ArrayList<>();if(raw instanceof Iterable<?>it)for(Object x:it)o.add(x);else if(raw.getClass().isArray())for(int i=0;i<Array.getLength(raw);i++)o.add(Array.get(raw,i));return o;}

    public int activeDisplayCount(){return displays.size();}
    public void sendStatus(CommandSender sender){
        sender.sendMessage("\u00a7dPhoenix LootChest \u00a77"+(enabled()?"aktif":"pasif"));
        sender.sendMessage("\u00a77Display: \u00a7f"+displays.size()+" \u00a78| \u00a77AUTO kasa: \u00a7f"+String.valueOf(selectedAutoCrateId)+" \u00a78| \u00a77Event hook: \u00a7f"+eventHookInstalled);
        sender.sendMessage("\u00a77Reward kaynagi: \u00a7fDungeons+ \u00a78| \u00a77Phoenix openCrate: \u00a7fKULLANILMIYOR");

        List<String> crateIds = availableCrateIds();
        sender.sendMessage("\u00a77Gercek Phoenix crate ID'leri (" + crateIds.size() + "): \u00a7f" + (crateIds.isEmpty() ? "yok" : String.join(", ", crateIds)));
        sender.sendMessage("\u00a77Gorulen Dungeons LootChest adlari (" + discoveredLootStructures.size() + "): \u00a7f" + (discoveredLootStructures.isEmpty() ? "aktif dungeon yok / henuz taranmadi" : String.join(", ", discoveredLootStructures)));
        if (!discoveredTransforms.isEmpty()) {
            sender.sendMessage("\u00a77Cozulen Phoenix transformlari:");
            for (var e : discoveredTransforms.entrySet()) sender.sendMessage("\u00a78- \u00a7f" + e.getKey() + " \u00a78=> \u00a7f" + e.getValue());
        }
        sender.sendMessage("\u00a78Esleme exact placement adi ile yapilir; gecersiz crate/facing/offset degerinde fallback/tahmin yapilmaz.");
    }

    private static final class DisplayHandle {
        final Object crate,type; final String crateId,structureName; final Location location,renderLocation;
        final String facingName,facingSource; final double offsetX,offsetY,offsetZ;
        final Map<UUID,Object> viewerDisplays=new HashMap<>();
        DisplayHandle(Object crate,Object type,String crateId,Location location,Location renderLocation,String structureName,
                      String facingName,String facingSource,double offsetX,double offsetY,double offsetZ){
            this.crate=crate;this.type=type;this.crateId=crateId;this.location=location;this.renderLocation=renderLocation;this.structureName=structureName;
            this.facingName=facingName;this.facingSource=facingSource;this.offsetX=offsetX;this.offsetY=offsetY;this.offsetZ=offsetZ;
        }
    }
    private record FacingChoice(Object value,String name,String source){}
    private record TransformSpec(Object facing,String facingName,String source,double offsetX,double offsetY,double offsetZ){}
    private record CachedInteract(PlayerInteractEvent event,LocationKey key,long timeMillis){}
    private record OpenKey(UUID playerId,LocationKey location){}
    private record LocationKey(UUID world,int x,int y,int z){static LocationKey of(Location l){World w=l.getWorld();return new LocationKey(w==null?new UUID(0,0):w.getUID(),l.getBlockX(),l.getBlockY(),l.getBlockZ());}}
}