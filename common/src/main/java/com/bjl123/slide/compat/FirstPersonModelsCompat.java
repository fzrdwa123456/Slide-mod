package com.bjl123.slide.compat;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.duck.PlayerAccessor;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * First Person Models 模组兼容层
 * 通过反射调用 First Person Models 的 API，避免硬依赖
 */
public class FirstPersonModelsCompat {

    private static boolean initialized = false;
    private static boolean isFirstPersonModelsPresent = false;
    private static boolean registrationAttempted = false;

    /**
     * 检测 First Person Models 是否存在
     */
    public static boolean isFirstPersonModelsPresent() {
        if (!initialized) {
            initialized = true;
            try {
                Class.forName("dev.tr7zw.firstperson.FirstPersonModelCore");
                isFirstPersonModelsPresent = true;
                SlideMod.LOGGER.info("First Person Models detected, enabling compatibility layer");
            } catch (ClassNotFoundException e) {
                isFirstPersonModelsPresent = false;
                SlideMod.LOGGER.info("First Person Models not found, skipping compatibility");
            }
        }
        return isFirstPersonModelsPresent;
    }

    /**
     * 检测当前是否正在进行 First Person Models 的第一人称渲染
     */
    public static boolean isRenderingFirstPerson() {
        if (!isFirstPersonModelsPresent()) {
            return false;
        }
        try {
            Class<?> coreClass = Class.forName("dev.tr7zw.firstperson.FirstPersonModelCore");
            return (boolean) coreClass.getField("isRenderingPlayer").get(null);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 注册滑铲偏移处理器到 First Person Models API
     * 只在客户端初始化时调用一次
     */
    public static void registerSlideOffsetHandler() {
        if (!isFirstPersonModelsPresent() || registrationAttempted) {
            return;
        }
        registrationAttempted = true;

        try {
            Class<?> apiClass = Class.forName("dev.tr7zw.firstperson.api.FirstPersonAPI");
            Class<?> handlerInterface = Class.forName("dev.tr7zw.firstperson.api.PlayerOffsetHandler");
            
            // 创建一个代理对象实现 PlayerOffsetHandler 接口
            Object handler = java.lang.reflect.Proxy.newProxyInstance(
                handlerInterface.getClassLoader(),
                new Class<?>[] { handlerInterface },
                (proxy, method, args) -> {
                    if ("applyOffset".equals(method.getName())) {
                        AbstractClientPlayer entity = (AbstractClientPlayer) args[0];
                        float delta = (float) args[1];
                        Vec3 original = (Vec3) args[2];
                        Vec3 current = (Vec3) args[3];
                        
                        return applySlideOffset(entity, delta, original, current);
                    }
                    return null;
                }
            );

            // 直接将 handler 添加到 playerOffsetHandlers 列表中
            // 因为 registerPlayerHandler 使用 instanceof 检查，动态代理可能无法通过
            java.lang.reflect.Method getHandlersMethod = apiClass.getMethod("getPlayerOffsetHandlers");
            @SuppressWarnings("unchecked")
            java.util.List<Object> handlers = (java.util.List<Object>) getHandlersMethod.invoke(null);
            handlers.add(handler);
            
            SlideMod.LOGGER.info("Successfully registered slide offset handler with First Person Models");
        } catch (Exception e) {
            SlideMod.LOGGER.warn("Failed to register slide offset handler with First Person Models", e);
        }
    }

    /**
     * 计算滑铲时的偏移调整
     * 滑铲时需要调整 First Person Models 的偏移以正确显示模型
     * 
     * First Person Models 的偏移机制：
     * - PlayerRendererMixin.getRenderOffset 流程:
     *   1. 调用 updatePositionOffset() 计算 FPM 偏移
     *   2. offset = original + FPM偏移
     *   3. 遍历 PlayerOffsetHandler，传入 original 和 current(累加值)
     * 
     * - 水平偏移公式：
     *   - 正常站立: bodyOffset = 0.25f + (config.xOffset / 100f)
     *   - 潜行: bodyOffset = 0.27f + (config.sneakXOffset / 100f)
     *   - 坐姿: bodyOffset = 0.20f + (config.sitXOffset / 100f)
     * 
     * 滑铲时使用骑乘姿势 (riding=true)，所以动态同步 FPM 的坐姿偏移 (sitXOffset)
     * 这样用户调整 FPM 配置时，滑铲偏移也会自动同步
     */
    private static Vec3 applySlideOffset(AbstractClientPlayer entity, float delta, Vec3 original, Vec3 current) {
        if (!(entity instanceof PlayerAccessor accessor)) {
            return current;
        }

        if (!accessor.slide$isSliding()) {
            return current;
        }

        // 方案：直接使用 FPM 当前计算的偏移值
        // current 已经包含了 original + FPM偏移
        // FPM偏移 = current - original
        // 滑铲时 FPM 使用正常站立偏移 (因为不是 isPassenger)
        // 我们需要将其替换为坐姿偏移
        
        // 获取 FPM 的站立偏移配置值
        // 滑铲时直接使用站立偏移，与 FPM 正常站立时保持一致
        int xOffset = getFirstPersonModelXOffset();
        
        // 使用站立偏移公式: bodyOffset = 0.25f + (config.xOffset / 100f)
        float bodyOffset = 0.25f + (xOffset / 100.0f);
        
        // 调试输出
        SlideMod.LOGGER.info("Slide FPM compat: xOffset={}, bodyOffset={}", xOffset, bodyOffset);
        
        // 计算玩家朝向的水平偏移
        double realYaw = net.minecraft.util.Mth.rotLerp(delta, entity.yBodyRotO, entity.yBodyRot);
        double x = bodyOffset * Math.sin(Math.toRadians(realYaw));
        double z = -bodyOffset * Math.cos(Math.toRadians(realYaw));
        
        // Y 轴偏移：模拟坐姿效果
        // 滑铲时玩家使用骑乘姿势 (riding=true)，但没有载具来调整 Y 位置
        // 添加一个向下的偏移，使模型看起来像坐下一样
        // 这个值可以根据实际效果调整
        double y = -0.35;
        
        // 返回计算的偏移，完全替换 FPM 原本的偏移
        return original.add(x, y, z);
    }

    // 缓存反射字段，避免每帧都查找
    private static java.lang.reflect.Field configField = null;
    private static boolean reflectionInitialized = false;

    /**
     * 初始化反射字段缓存
     */
    private static void initReflectionFields() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        
        try {
            // 使用当前线程的类加载器来加载 FPM 类
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Class<?> coreClass = Class.forName("dev.tr7zw.firstperson.FirstPersonModelCore", true, classLoader);
            configField = coreClass.getField("config");
            
            // 输出调试信息
            Object config = configField.get(null);
            SlideMod.LOGGER.info("FPM reflection initialized: coreClass={}, configField={}, config={}", 
                coreClass.getName(), configField, config);
            if (config != null) {
                SlideMod.LOGGER.info("FPM config object class: {}, classLoader: {}", 
                    config.getClass().getName(), config.getClass().getClassLoader());
            }
            
            SlideMod.LOGGER.info("FPM reflection fields initialized successfully");
        } catch (Exception e) {
            SlideMod.LOGGER.warn("Failed to initialize FPM reflection fields", e);
        }
    }

    // 缓存从文件读取的配置值
    private static int cachedXOffset = 0;
    private static long lastConfigReadTime = 0;
    private static final long CONFIG_READ_INTERVAL = 1000; // 每秒最多读取一次配置文件

    /**
     * 从 FPM 的配置文件读取配置值
     * 这是一个备选方案，当反射读取失败时使用
     */
    private static void readConfigFromFile() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastConfigReadTime < CONFIG_READ_INTERVAL) {
            return; // 避免频繁读取文件
        }
        lastConfigReadTime = currentTime;
        
        try {
            File configFile = new File("config", "firstperson.json");
            if (configFile.exists()) {
                String content = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                JsonObject json = new Gson().fromJson(content, JsonObject.class);
                
                if (json.has("xOffset")) {
                    cachedXOffset = json.get("xOffset").getAsInt();
                }
                
                SlideMod.LOGGER.info("FPM config read from file: xOffset={}", cachedXOffset);
            }
        } catch (Exception e) {
            SlideMod.LOGGER.warn("Failed to read FPM config from file", e);
        }
    }

    /**
     * 动态读取 FirstPersonModel 的 config.xOffset 值 (站立偏移)
     */
    private static int getFirstPersonModelXOffset() {
        // 首先尝试反射读取
        initReflectionFields();
        
        if (configField != null) {
            try {
                Object config = configField.get(null);
                if (config != null) {
                    Class<?> configClass = config.getClass();
                    java.lang.reflect.Field xOffsetField = configClass.getField("xOffset");
                    Object value = xOffsetField.get(config);
                    
                    if (value instanceof Integer && (Integer) value != 0) {
                        return (Integer) value;
                    }
                }
            } catch (Exception e) {
                // 反射失败，使用文件读取
            }
        }
        
        // 反射读取失败或值为0，尝试从文件读取
        readConfigFromFile();
        return cachedXOffset;
    }

}
