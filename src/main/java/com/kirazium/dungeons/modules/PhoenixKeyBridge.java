package com.kirazium.dungeons.modules;

import com.kirazium.dungeons.KiraziumDungeonsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PhoenixKeyBridge implements Listener {
    private final KiraziumDungeonsPlugin plugin;
    private final Map<String, AutoKeyMapping> mappingsByNexoId = new LinkedHashMap<>();
    private final Set<String> ambiguousNexoIds = new HashSet<>();
    private Method nexoIdFromItem;
    private Method phoenixItemBuilderOf, phoenixGetNbtTagString;
    private Plugin phoenixPlugin;
    private boolean nexoAvailable, phoenixAvailable, dungeonsAvailable;
    private long lastRefreshMillis;
    private int discoveredPhoenixKeys, discoveredNexoPhysicalKeys;
    private BukkitTask initialTask, refreshTask;

    private static final List<String> ITEMSTACK_METHOD_NAMES = List.of(
            "getItemStack", "itemStack", "toItemStack", "asItemStack", "getBukkitItem",
            "getItem", "item", "getStack", "stack", "buildItemStack", "createItemStack",
            "build", "createItem", "toBukkitItemStack"
    );

    public PhoenixKeyBridge(KiraziumDungeonsPlugin plugin) { this.plugin = plugin; }

    public void enable() {
        detectDependencies();
        resolveNexoApi();
        resolvePhoenixItemTagApi();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        scheduleRefresh();
    }

    public void disable() {
        if (initialTask != null) initialTask.cancel();
        if (refreshTask != null) refreshTask.cancel();
        mappingsByNexoId.clear();
        ambiguousNexoIds.clear();
    }

    public void reload() {
        detectDependencies();
        resolveNexoApi();
        resolvePhoenixItemTagApi();
        scheduleRefresh();
        refreshMappingsSafe();
    }

    private void scheduleRefresh() {
        if (initialTask != null) initialTask.cancel();
        if (refreshTask != null) refreshTask.cancel();
        long delay = Math.max(1L, plugin.getConfig().getLong("phoenix-keys.initial-refresh-delay-ticks", 20L));
        initialTask = Bukkit.getScheduler().runTaskLater(plugin, this::refreshMappingsSafe, delay);
        long seconds = Math.max(10L, plugin.getConfig().getLong("phoenix-keys.auto-refresh-seconds", 60L));
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshMappingsSafe, delay + 20L * seconds, 20L * seconds);
    }

    private void detectDependencies() {
        Plugin d = firstPlugin("Dungeons", "DungeonsPlus", "Dungeons+");
        Plugin n = firstPlugin("Nexo");
        phoenixPlugin = firstPlugin("PhoenixCrates", "PhoenixCratesLite");
        dungeonsAvailable = d != null && d.isEnabled();
        nexoAvailable = n != null && n.isEnabled();
        phoenixAvailable = phoenixPlugin != null && phoenixPlugin.isEnabled();
    }

    private Plugin firstPlugin(String... names) {
        for (String name : names) {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p != null) return p;
        }
        return null;
    }

    private void resolveNexoApi() {
        nexoIdFromItem = null;
        if (!nexoAvailable) return;
        try {
            ClassLoader cl = firstPlugin("Nexo").getClass().getClassLoader();
            Class<?> clazz = Class.forName("com.nexomc.nexo.api.NexoItems", true, cl);
            nexoIdFromItem = clazz.getMethod("idFromItem", ItemStack.class);
            plugin.getLogger().fine("PhoenixKeyBridge: NexoItems.idFromItem hazir.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "PhoenixKeyBridge: Nexo API bulunamadi.", t);
        }
    }

    private void resolvePhoenixItemTagApi() {
        phoenixItemBuilderOf = null;
        phoenixGetNbtTagString = null;
        if (!phoenixAvailable || phoenixPlugin == null) return;
        try {
            ClassLoader cl = phoenixPlugin.getClass().getClassLoader();
            Class<?> itemBuilder = Class.forName(
                    "com.phoenixplugins.phoenixcrates.lib.common.utils.inventory.ItemBuilder", true, cl);
            phoenixItemBuilderOf = itemBuilder.getMethod("of", ItemStack.class);
            phoenixGetNbtTagString = itemBuilder.getMethod("getNbtTagString", String.class);
            plugin.getLogger().fine("PhoenixKeyBridge: Phoenix key NBT okuyucu hazir.");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "PhoenixKeyBridge: Phoenix key NBT okuyucu bulunamadi; exact key guvenligi icin legacy key donusumu kapatilacak.", t);
        }
    }

    public void refreshMappingsSafe() {
        if (!plugin.getConfig().getBoolean("phoenix-keys.enabled", true)) return;
        detectDependencies();
        if (nexoIdFromItem == null && nexoAvailable) resolveNexoApi();
        if (!phoenixAvailable || !nexoAvailable || nexoIdFromItem == null) {
            mappingsByNexoId.clear();
            ambiguousNexoIds.clear();
            lastRefreshMillis = System.currentTimeMillis();
            return;
        }
        try { refreshMappings(); }
        catch (Throwable t) { plugin.getLogger().log(Level.SEVERE, "Phoenix key taramasi basarisiz.", t); }
        lastRefreshMillis = System.currentTimeMillis();
    }

    private void refreshMappings() throws Exception {
        Map<String, AutoKeyMapping> fresh = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Object keysManager = resolvePhoenixKeysManager();
        if (keysManager == null) {
            mappingsByNexoId.clear(); ambiguousNexoIds.clear(); discoveredPhoenixKeys = 0; discoveredNexoPhysicalKeys = 0; return;
        }

        Set<String> identifiers = discoverKeyIdentifiers(keysManager);
        discoveredPhoenixKeys = identifiers.size();
        int compatible = 0;
        for (String keyId : identifiers) {
            Object key = invoke(keysManager, "getKeyByIdentifier", new Class<?>[]{String.class}, keyId);
            if (key == null || !booleanMethod(key, "isEnabled", true) || booleanMethod(key, "isVirtual", false)) continue;
            ItemStack realKey = extractItemStack(invokeNoArgs(key, "getPlainItem"));
            if (isEmpty(realKey)) continue;
            realKey.setAmount(1);
            String nexoId = nexoId(realKey);
            if (nexoId == null || nexoId.isBlank()) continue;
            compatible++;
            String normalized = nexoId.toLowerCase(Locale.ROOT);
            if (fresh.containsKey(normalized)) {
                AutoKeyMapping previous = fresh.remove(normalized);
                ambiguous.add(normalized);
                plugin.getLogger().warning("PhoenixKeyBridge: ayni Nexo ID birden fazla key kullaniyor, otomatik fix kapatildi: " + nexoId + " (" + previous.phoenixKeyId + ", " + keyId + ")");
                continue;
            }
            if (!ambiguous.contains(normalized)) fresh.put(normalized, new AutoKeyMapping(nexoId, keyId, realKey.clone()));
        }
        mappingsByNexoId.clear(); mappingsByNexoId.putAll(fresh);
        ambiguousNexoIds.clear(); ambiguousNexoIds.addAll(ambiguous);
        discoveredNexoPhysicalKeys = compatible;
        plugin.getLogger().fine("PhoenixKeyBridge AUTO: Phoenix=" + discoveredPhoenixKeys + ", Nexo-physical=" + compatible + ", mapping=" + mappingsByNexoId.size());
    }

    private Object resolvePhoenixKeysManager() {
        if (phoenixPlugin == null) return null;
        try {
            Class<?> api = Class.forName("com.phoenixplugins.phoenixcrates.api.PhoenixCratesAPI", true, phoenixPlugin.getClass().getClassLoader());
            return api.getMethod("getKeysManager").invoke(null);
        } catch (Throwable ignored) { }
        return invokeAnyNoArgs(phoenixPlugin, "getKeysManager", "getKeyManager", "keysManager", "keyManager");
    }

    private Set<String> discoverKeyIdentifiers(Object keysManager) {
        Set<String> ids = new LinkedHashSet<>();
        addStrings(ids, invokeAnyNoArgs(keysManager, "getKeysIdentifier", "getKeyIdentifiers", "getIdentifiers"));
        if (ids.isEmpty()) {
            Object keys = invokeAnyNoArgs(keysManager, "getRegisteredKeys", "getKeys", "registeredKeys");
            for (Object key : asObjects(keys)) {
                Object id = invokeAnyNoArgs(key, "getIdentifier", "identifier", "getId", "id");
                if (id != null && !id.toString().isBlank()) ids.add(id.toString());
            }
        }
        if (ids.isEmpty() && phoenixPlugin != null) {
            File folder = new File(phoenixPlugin.getDataFolder(), "keys");
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml") || name.toLowerCase(Locale.ROOT).endsWith(".yaml"));
            if (files != null) {
                Pattern identifier = Pattern.compile("(?im)^\\s*identifier\\s*:\\s*['\\\"]?([^'\\\"#\\r\\n]+)");
                for (File file : files) try {
                    String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    Matcher matcher = identifier.matcher(text);
                    if (matcher.find()) ids.add(matcher.group(1).trim());
                    else { String n = file.getName(); int dot = n.lastIndexOf('.'); ids.add(dot > 0 ? n.substring(0, dot) : n); }
                } catch (Exception ignored) { }
            }
        }
        return ids;
    }

    private static void addStrings(Set<String> out, Object raw) {
        if (raw == null) return;
        if (raw instanceof Collection<?> c) for (Object o : c) { if (o != null && !o.toString().isBlank()) out.add(o.toString()); }
        else if (raw.getClass().isArray()) for (int i=0;i<Array.getLength(raw);i++) { Object o=Array.get(raw,i); if(o!=null&&!o.toString().isBlank()) out.add(o.toString()); }
    }

    private static List<Object> asObjects(Object raw) {
        if (raw == null) return Collections.emptyList();
        List<Object> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) out.addAll(c);
        else if (raw.getClass().isArray()) for (int i=0;i<Array.getLength(raw);i++) out.add(Array.get(raw,i));
        return out;
    }

    private ItemStack extractItemStack(Object root) { return extractItemStack(root, 0, new IdentityHashMap<>()); }
    private ItemStack extractItemStack(Object obj, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (obj == null || depth > 4 || seen.put(obj, Boolean.TRUE) != null) return null;
        if (obj instanceof ItemStack stack) return stack.clone();
        if (obj instanceof Optional<?> optional) return extractItemStack(optional.orElse(null), depth+1, seen);
        Class<?> type = obj.getClass();
        for (String name : ITEMSTACK_METHOD_NAMES) try {
            Method m = type.getMethod(name);
            if (m.getParameterCount()!=0 || Modifier.isStatic(m.getModifiers())) continue;
            ItemStack found = extractItemStack(m.invoke(obj), depth+1, seen);
            if (found != null) return found;
        } catch (NoSuchMethodException ignored) { } catch (Throwable ignored) { }
        for (Method m : type.getMethods()) try {
            if (m.getParameterCount()==0 && !Modifier.isStatic(m.getModifiers()) && ItemStack.class.isAssignableFrom(m.getReturnType())) {
                Object value=m.invoke(obj); if(value instanceof ItemStack stack) return stack.clone();
            }
        } catch (Throwable ignored) { }
        for (Field f : allFields(type)) try {
            if (Modifier.isStatic(f.getModifiers()) || !f.trySetAccessible()) continue;
            Object value=f.get(obj);
            if(value instanceof ItemStack stack) return stack.clone();
            if(value!=null && isPhoenixObject(value.getClass())) { ItemStack found=extractItemStack(value,depth+1,seen); if(found!=null)return found; }
        } catch(Throwable ignored) { }
        return null;
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> out=new ArrayList<>(); for(Class<?> c=type;c!=null&&c!=Object.class;c=c.getSuperclass()) Collections.addAll(out,c.getDeclaredFields()); return out;
    }
    private static boolean isPhoenixObject(Class<?> c) { Package p=c.getPackage(); return p!=null&&p.getName().startsWith("com.phoenixplugins"); }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory top=event.getInventory(); if(!eligible(top))return;
        ensureFreshIfNeeded(); replaceInsideContainer(player, top);
        if(plugin.getConfig().getBoolean("phoenix-keys.rescan-next-tick",true)) Bukkit.getScheduler().runTask(plugin,()->{
            if(!player.isOnline())return; Inventory live=player.getOpenInventory().getTopInventory(); if(live==top&&eligible(live))replaceInsideContainer(player,live);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInventoryClick(InventoryClickEvent event) { if(event.getWhoClicked() instanceof Player p){Inventory top=event.getView().getTopInventory();if(eligible(top))replaceInsideContainer(p,top);} }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInventoryDrag(InventoryDragEvent event) { if(event.getWhoClicked() instanceof Player p){Inventory top=event.getView().getTopInventory();if(eligible(top))replaceInsideContainer(p,top);} }

    private void ensureFreshIfNeeded(){long maxAge=Math.max(10L,plugin.getConfig().getLong("phoenix-keys.auto-refresh-seconds",60L))*1000L;if(mappingsByNexoId.isEmpty()&&System.currentTimeMillis()-lastRefreshMillis>Math.min(maxAge,15000L))refreshMappingsSafe();}
    private boolean eligible(Inventory inventory){return plugin.getConfig().getBoolean("phoenix-keys.enabled",true)&&dungeonsAvailable&&nexoAvailable&&phoenixAvailable&&nexoIdFromItem!=null&&inventory!=null&&!(inventory instanceof PlayerInventory);}
    private int replaceInsideContainer(Player viewer, Inventory inventory){
        if(mappingsByNexoId.isEmpty())return 0;
        // Fail closed: Phoenix'in kendi key tag'ini okuyamiyorsak exact/ItemEdit key'i
        // legacy proxy sanip canonical item ile ezme riski alamayiz.
        if(phoenixItemBuilderOf==null||phoenixGetNbtTagString==null)return 0;
        int replaced=0; ItemStack[] c=inventory.getContents();
        for(int slot=0;slot<c.length;slot++){
            ItemStack item=c[slot];if(isEmpty(item))continue;
            String id=nexoId(item);if(id==null)continue;
            AutoKeyMapping m=mappingsByNexoId.get(id.toLowerCase(Locale.ROOT));if(m==null)continue;
            // Gercek Phoenix key tag'i mevcutsa item exact olarak korunur. ItemEdit ile
            // isim/lore/enchant/component/PDC degismis olsa bile bridge dokunmaz.
            if(hasPhoenixKeyTag(item)||item.isSimilar(m.realPhoenixKey))continue;
            ItemStack real=m.realPhoenixKey.clone();real.setAmount(item.getAmount());
            inventory.setItem(slot,real);replaced+=item.getAmount();
        }
        return replaced;
    }

    private boolean hasPhoenixKeyTag(ItemStack item){
        if(isEmpty(item)||phoenixItemBuilderOf==null||phoenixGetNbtTagString==null)return false;
        try{
            Object builder=phoenixItemBuilderOf.invoke(null,item);
            if(builder==null)return false;
            Object raw=phoenixGetNbtTagString.invoke(builder,"phoenixcrates:key");
            return raw!=null&&!raw.toString().isBlank();
        }catch(Throwable t){
            // Okuma hatasinda veri kaybi riskine girmemek icin gercek key varsay.
            plugin.getLogger().log(Level.FINE,"Phoenix key tag okunamadi; item guvenlik icin degistirilmedi.",t);
            return true;
        }
    }

    private String nexoId(ItemStack item){if(nexoIdFromItem==null||isEmpty(item))return null;try{Object r=nexoIdFromItem.invoke(null,item);return r==null?null:r.toString();}catch(Throwable t){return null;}}
    private static Object invokeNoArgs(Object target,String name){if(target==null)return null;try{return target.getClass().getMethod(name).invoke(target);}catch(Throwable ignored){return null;}}
    private static Object invokeAnyNoArgs(Object target,String...names){for(String n:names){Object v=invokeNoArgs(target,n);if(v!=null)return v;}return null;}
    private static Object invoke(Object target,String name,Class<?>[]params,Object...args){if(target==null)return null;try{return target.getClass().getMethod(name,params).invoke(target,args);}catch(Throwable ignored){return null;}}
    private static boolean booleanMethod(Object target,String name,boolean def){Object v=invokeNoArgs(target,name);return v instanceof Boolean b?b:def;}
    private static boolean isEmpty(ItemStack i){return i==null||i.getAmount()<=0||i.getType().isAir();}

    public int mappingCount(){return mappingsByNexoId.size();}
    public void sendStatus(CommandSender sender){
        sender.sendMessage("\u00a7dPhoenixKeyBridge \u00a77AUTO");
        sender.sendMessage("\u00a77Phoenix key: \u00a7f"+discoveredPhoenixKeys+" \u00a78| \u00a77Nexo fiziksel: \u00a7f"+discoveredNexoPhysicalKeys+" \u00a78| \u00a77Mapping: \u00a7f"+mappingsByNexoId.size());
        for(AutoKeyMapping m:mappingsByNexoId.values()) sender.sendMessage("\u00a78- \u00a7f"+m.nexoId+" \u00a77-> \u00a7d"+m.phoenixKeyId);
        if(!ambiguousNexoIds.isEmpty()) sender.sendMessage("\u00a7eBelirsiz Nexo ID: "+ambiguousNexoIds.size()+" (guvenlik icin atlandi)");
    }

    private record AutoKeyMapping(String nexoId,String phoenixKeyId,ItemStack realPhoenixKey){}
}