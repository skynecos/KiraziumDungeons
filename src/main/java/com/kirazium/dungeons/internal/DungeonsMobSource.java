package com.kirazium.dungeons.internal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Exact Dungeons 4.1.10 mob source.
 *
 * Dungeons' own EntityController keeps every spawned Dungeons entity in its
 * private `entities` map. Dungeons itself associates those entities with a
 * session by checking the entity location against session.getRegion().contains(...).
 * This helper intentionally mirrors that exact ownership rule instead of
 * guessing from nearby mobs or relying on a SpawnerStructure queue.
 */
public final class DungeonsMobSource {
    private static final Map<Class<?>, Optional<Field>> ENTITY_MAP_FIELD = new HashMap<>();
    private static volatile Method blockVectorAt;
    private static volatile Class<?> blockVectorClass;

    private DungeonsMobSource() {}

    public static List<Entity> collect(Plugin dungeonsPlugin, Object session) {
        if (dungeonsPlugin == null || session == null) return List.of();
        LinkedHashMap<UUID, Entity> unique = new LinkedHashMap<>();
        try {
            Object entityController = dungeonsPlugin.getClass().getMethod("getEntityController").invoke(dungeonsPlugin);
            if (entityController == null) return List.of();

            Field mapField = resolveEntitiesField(entityController.getClass());
            if (mapField == null) return List.of();
            Object raw = mapField.get(entityController);
            if (!(raw instanceof Map<?, ?> map)) return List.of();

            for (Object activeEntity : map.values()) {
                Object rawEntity = invokeNoArgs(activeEntity, "getSpawnedEntity");
                if (!(rawEntity instanceof Entity entity)) continue;
                if (!alive(entity)) continue;
                if (!belongsToSession(dungeonsPlugin, session, entity.getLocation())) continue;
                unique.put(entity.getUniqueId(), entity);
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        return new ArrayList<>(unique.values());
    }

    public static boolean verify(Plugin dungeonsPlugin) {
        if (dungeonsPlugin == null) return false;
        try {
            Object controller = dungeonsPlugin.getClass().getMethod("getEntityController").invoke(dungeonsPlugin);
            return controller != null && resolveEntitiesField(controller.getClass()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field resolveEntitiesField(Class<?> type) {
        Optional<Field> cached = ENTITY_MAP_FIELD.get(type);
        if (cached != null) return cached.orElse(null);
        Field found = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("entities");
                f.setAccessible(true);
                found = f;
                break;
            } catch (NoSuchFieldException ignored) {
            }
        }
        ENTITY_MAP_FIELD.put(type, Optional.ofNullable(found));
        return found;
    }

    private static boolean belongsToSession(Plugin dungeonsPlugin, Object session, Location location) {
        if (location == null) return false;
        World bukkitWorld = location.getWorld();
        if (bukkitWorld == null) return false;
        try {
            Object region = invokeNoArgs(session, "getRegion");
            if (region == null) return false;

            Object regionWorld = invokeNoArgs(region, "getWorld");
            Object regionWorldName = invokeNoArgs(regionWorld, "getName");
            if (regionWorldName == null || !regionWorldName.toString().equalsIgnoreCase(bukkitWorld.getName())) return false;

            Class<?> vectorType = blockVectorClass;
            Method at = blockVectorAt;
            if (vectorType == null || at == null) {
                ClassLoader cl = dungeonsPlugin.getClass().getClassLoader();
                vectorType = Class.forName("com.sk89q.worldedit.math.BlockVector3", true, cl);
                at = vectorType.getMethod("at", double.class, double.class, double.class);
                blockVectorClass = vectorType;
                blockVectorAt = at;
            }
            Object vector = at.invoke(null, location.getX(), location.getY(), location.getZ());
            Method contains = region.getClass().getMethod("contains", vectorType);
            return Boolean.TRUE.equals(contains.invoke(region, vector));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean alive(Entity entity) {
        return entity != null && entity.isValid() && !entity.isDead();
    }

    private static Object invokeNoArgs(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}