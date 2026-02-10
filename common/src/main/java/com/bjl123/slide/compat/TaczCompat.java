package com.bjl123.slide.compat;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TACZ (Timeless and Classics Guns Zero) 兼容性补丁
 * 
 * 当玩家滑铲时，设置 TACZ 的 stopSprint 标志为 true
 * 这样 TACZ 会认为玩家没有在疾跑
 */
public class TaczCompat {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("SlideCompat");
    private static boolean taczLoaded = false;
    private static boolean initialized = false;
    
    /**
     * 初始化 TACZ 兼容性
     * 检测 TACZ 是否已加载
     */
    public static void init() {
        if (initialized) return;
        initialized = true;
        
        try {
            Class.forName("com.tacz.guns.client.gameplay.LocalPlayerSprint");
            taczLoaded = true;
            LOGGER.info("TACZ detected, enabling compatibility patch");
        } catch (ClassNotFoundException e) {
            taczLoaded = false;
            LOGGER.debug("TACZ not detected, skipping compatibility patch");
        }
    }
    
    /**
     * 检查 TACZ 是否已加载
     */
    public static boolean isTaczLoaded() {
        if (!initialized) init();
        return taczLoaded;
    }
    
    /**
     * 更新 TACZ 的疾跑状态
     * 滑铲期间设置 stopSprint = true
     */
    public static void updateSprintStatus() {
        if (!isTaczLoaded()) return;
        
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player instanceof PlayerAccessor accessor) {
                boolean isSliding = accessor.slide$isSliding();
                setTaczStopSprint(isSliding);
            }
        } catch (Exception e) {
            // 忽略异常，避免影响游戏
        }
    }
    
    /**
     * 设置 TACZ 的 stopSprint 标志
     */
    private static void setTaczStopSprint(boolean value) {
        try {
            // 使用反射设置 TACZ 的 stopSprint 静态变量
            Class<?> sprintClass = Class.forName("com.tacz.guns.client.gameplay.LocalPlayerSprint");
            java.lang.reflect.Field stopSprintField = sprintClass.getDeclaredField("stopSprint");
            stopSprintField.setAccessible(true);
            stopSprintField.setBoolean(null, value);
        } catch (Exception e) {
            // 忽略异常
        }
    }
    
    /**
     * 滑铲结束时重置 TACZ 状态
     */
    public static void onSlideEnd() {
        if (!isTaczLoaded()) return;
        setTaczStopSprint(false);
    }
}
