package com.bjl123.slide.mixin.forge.client;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KeyboardInput Mixin
 * 
 * 在 KeyboardInput.tick() 结束后清除 shiftKeyDown 标志
 * 让滑铲期间可以触发疾跑（不会因为潜行键被阻止）
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends Input {

    /**
     * 在 tick() 方法结束后，滑铲期间清除 shiftKeyDown 标志
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void slide$clearShiftKeyDownAfterTick(boolean p_234118_, float p_234119_, CallbackInfo ci) {
        if (isLocalPlayerSliding()) {
            this.shiftKeyDown = false;
        }
    }

    @Unique
    private static boolean isLocalPlayerSliding() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player instanceof PlayerAccessor accessor) {
            return accessor.slide$isSliding();
        }
        return false;
    }
}
