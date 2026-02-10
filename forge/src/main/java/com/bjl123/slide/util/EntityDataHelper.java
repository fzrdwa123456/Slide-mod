package com.bjl123.slide.util;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;

/**
 * 实体数据访问工具类
 * 提供缓存的反射访问，避免每帧都进行反射查找
 */
public final class EntityDataHelper {

    private static EntityDataAccessor<Byte> cachedSharedFlagsAccessor = null;
    private static boolean initialized = false;

    private EntityDataHelper() {
    }

    /**
     * 获取实体的共享标志位（包含火焰状态等）
     * 
     * @param entity 目标实体
     * @return 共享标志位字节值，失败返回 0
     */
    public static byte getSharedFlags(Entity entity) {
        try {
            EntityDataAccessor<Byte> accessor = getSharedFlagsAccessor();
            if (accessor != null) {
                return entity.getEntityData().get(accessor);
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    /**
     * 检查实体是否着火（基于共享标志位）
     * 
     * @param entity 目标实体
     * @return 是否着火
     */
    public static boolean isOnFireFromFlags(Entity entity) {
        byte flags = getSharedFlags(entity);
        return (flags & 1) != 0;
    }

    /**
     * 获取缓存的共享标志位访问器
     */
    public static EntityDataAccessor<Byte> getSharedFlagsAccessor() {
        if (!initialized) {
            initialized = true;
            cachedSharedFlagsAccessor = findSharedFlagsAccessor();
        }
        return cachedSharedFlagsAccessor;
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Byte> findSharedFlagsAccessor() {
        try {
            for (Field field : Entity.class.getDeclaredFields()) {
                if (field.getType() == EntityDataAccessor.class) {
                    field.setAccessible(true);
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                    if (accessor != null 
                            && accessor.getSerializer() == EntityDataSerializers.BYTE 
                            && accessor.getId() == 0) {
                        return (EntityDataAccessor<Byte>) accessor;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
