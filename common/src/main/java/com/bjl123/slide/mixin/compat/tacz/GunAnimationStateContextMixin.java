package com.bjl123.slide.mixin.compat.tacz;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TACZ 兼容 Mixin - 直接拦截 shouldSlide() 方法
 * 
 * 滑铲时让 TACZ 触发枪械斜握动画，而不需要拦截 isCrouching()
 * 这样可以保持原版的第一人称和第三人称动画不受影响
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.animation.statemachine.GunAnimationStateContext", remap = false)
public class GunAnimationStateContextMixin {

    /**
     * 拦截 shouldSlide() 方法
     * 滑铲时返回 true，让 TACZ 触发枪械斜握动画
     */
    @Inject(method = "shouldSlide", at = @At("HEAD"), cancellable = true)
    private void slide$fakeShouldSlideWhileSliding(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player instanceof PlayerAccessor accessor) {
            if (accessor.slide$isSliding()) {
                cir.setReturnValue(true);
            }
        }
    }
}
