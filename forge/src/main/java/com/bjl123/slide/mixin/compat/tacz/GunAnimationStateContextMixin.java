package com.bjl123.slide.mixin.compat.tacz;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TACZ 兼容性 Mixin
 * 
 * 拦截 GunAnimationStateContext.shouldSlide() 方法
 * 在滑铲进入1.5格方块后强制返回 true，触发枪械斜握动画
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.animation.statemachine.GunAnimationStateContext", remap = false)
public class GunAnimationStateContextMixin {

    /**
     * 拦截 shouldSlide() 方法
     * 在滑铲期间或强制潜行期间返回 true
     */
    @Inject(method = "shouldSlide", at = @At("RETURN"), cancellable = true)
    private void slide$forceShouldSlide(CallbackInfoReturnable<Boolean> cir) {
        Entity cameraEntity = Minecraft.getInstance().cameraEntity;
        
        if (cameraEntity instanceof Player player && player instanceof PlayerAccessor accessor) {
            boolean isSliding = accessor.slide$isSliding();
            boolean isForceCrouching = accessor.slide$isForceCrouching();
            
            // 滑铲期间或强制潜行期间，强制返回 true
            if (isSliding || isForceCrouching) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * 拦截 isCrouching() 方法
     * 在滑铲期间或强制潜行期间返回 true
     */
    @Inject(method = "isCrouching", at = @At("RETURN"), cancellable = true)
    private void slide$forceIsCrouching(CallbackInfoReturnable<Boolean> cir) {
        Entity cameraEntity = Minecraft.getInstance().cameraEntity;
        
        if (cameraEntity instanceof Player player && player instanceof PlayerAccessor accessor) {
            boolean isSliding = accessor.slide$isSliding();
            boolean isForceCrouching = accessor.slide$isForceCrouching();
            
            // 滑铲期间或强制潜行期间，强制返回 true
            if (isSliding || isForceCrouching) {
                cir.setReturnValue(true);
            }
        }
    }
}
