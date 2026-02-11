package com.bjl123.slide.client;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.network.SlideNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

public class SlideKeyBindings {
    
    // 定义按键绑定：默认为 C 键
    public static final KeyMapping SLIDE_KEY = new KeyMapping(
            "key.slide.slide", // 翻译键名
            InputConstants.Type.KEYSYM, // 输入类型
            GLFW.GLFW_KEY_C, // 默认按键
            "key.categories.slide" // 按键分类
    );

    // 客户端按键冷却时间，防止按住时反复触发
    private static int keyPressCooldown = 0;
    
    // 追踪按键是否已经释放，只有释放后再按下才能触发下一次滑铲
    private static boolean keyWasReleased = true;

    public static void init() {
        // 注册按键
        KeyMappingRegistry.register(SLIDE_KEY);
        SlideMod.LOGGER.info("Registered slide key binding: Slide (C)");
        
        // 注册客户端 tick 事件监听按键点击
        ClientTickEvent.CLIENT_PRE.register(client -> {
            if (client.player == null) return;
            if (client.player.isPassenger()) {
                return;
            }

            // 减少按键冷却时间
            if (keyPressCooldown > 0) {
                keyPressCooldown--;
            }

            PlayerAccessor playerAccessor = (PlayerAccessor) client.player;
            
            // 检查按键是否被释放
            if (!SLIDE_KEY.isDown()) {
                keyWasReleased = true;
            }
            
            // 检查是否有按键点击事件（按一下触发）
            // 必须满足：按键被按下、按键之前已经释放过、冷却时间已结束
            if (SLIDE_KEY.isDown() && keyWasReleased && keyPressCooldown <= 0) {
                // 标记按键已被使用，需要释放后才能再次触发
                keyWasReleased = false;
                // 设置按键冷却时间（10 ticks = 0.5秒），防止快速连按
                keyPressCooldown = 10;
                
                // 如果已经在滑铲，点击C键停止滑铲
                if (playerAccessor.slide$isSliding()) {
                    boolean holdingSneak = client.options.keyShift.isDown();
                    SlideNetworking.sendSlidePacket(false, holdingSneak);
                    playerAccessor.slide$setSliding(false);
                    return;
                }
                
                // 如果没有在滑铲，检查各种限制条件后开始滑铲
                // 检查是否在地面上
                if (!client.player.onGround()) {
                    // 空中不允许滑铲
                    return;
                }
                
                // 检查是否正在跳跃 - 防止同时按滑铲和跳跃导致以滑铲姿势跳起
                if (client.options.keyJump.isDown()) {
                    // 跳跃键按下时不允许滑铲
                    return;
                }
                
                // 检查是否在流体中（水、岩浆、其他 mod 的流体）
                if (isInAnyFluid(client.player)) {
                    // 在流体中不允许滑铲
                    return;
                }
                
                // 检查是否为疾跑状态 - 只有疾跑时才能触发滑铲
                if (!client.player.isSprinting()) {
                    // 非疾跑状态不允许滑铲
                    return;
                }
                
                // 检查是否正在潜行 - 潜行时不能滑铲
                // 包括：正常潜行、按着潜行键、强制潜行（在1.5格空间内）
                if (client.player.isCrouching() || client.player.isShiftKeyDown() || playerAccessor.slide$isForceCrouching()) {
                    // 潜行状态不允许滑铲
                    return;
                }
                
                // 检查疾跑滑铲冷却时间
                if (!playerAccessor.slide$canSlide()) {
                    // 疾跑滑铲冷却时间未结束，不允许滑铲
                    return;
                }
                
                // 开始疾跑滑铲
                SlideNetworking.sendSlidePacket(true, false);
                playerAccessor.slide$setSliding(true);
            }
        });
    }
    
    /**
     * 检查滑铲键是否被按下并消费点击事件
     * @return 如果滑铲键被点击则返回true
     */
    public static boolean consumeSlideClick() {
        return SLIDE_KEY.consumeClick();
    }
    
    /**
     * 检查玩家是否在任何流体中（水、岩浆、其他 mod 的流体）
     * 使用 Minecraft 原版的流体检测方法，兼容所有 mod 的流体
     * 
     * @param player 要检查的玩家
     * @return 如果玩家在任何流体中返回 true
     */
    private static boolean isInAnyFluid(Player player) {
        // 检查是否在水中（包括气泡柱）
        if (player.isInWater()) {
            return true;
        }
        
        // 检查是否在岩浆中
        if (player.isInLava()) {
            return true;
        }
        
        // 检查是否在游泳状态（可能在其他 mod 的流体中）
        if (player.isSwimming()) {
            return true;
        }
        
        // 检查玩家脚下的方块是否是流体
        // 这可以检测到其他 mod 添加的流体
        if (!player.level().getFluidState(player.blockPosition()).isEmpty()) {
            return true;
        }
        
        return false;
    }
}