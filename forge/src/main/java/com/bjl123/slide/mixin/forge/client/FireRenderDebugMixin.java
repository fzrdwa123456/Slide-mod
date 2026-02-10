package com.bjl123.slide.mixin.forge.client;

import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.util.EntityDataHelper;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 火焰渲染修复 Mixin
 * 修复滑铲时火焰渲染消失的问题
 */
@Mixin(ScreenEffectRenderer.class)
public class FireRenderDebugMixin {

    /**
     * 重定向 isOnFire() 调用，修复滑铲时火焰渲染消失的问题
     */
    @Redirect(method = "renderScreenEffect", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isOnFire()Z"))
    private static boolean slide$fixIsOnFireWhileSliding(net.minecraft.client.player.LocalPlayer player) {
        if (player instanceof PlayerAccessor accessor && accessor.slide$isSliding()) {
            boolean fireFlag = EntityDataHelper.isOnFireFromFlags(player);
            return !player.fireImmune() && fireFlag;
        }
        return player.isOnFire();
    }
}
