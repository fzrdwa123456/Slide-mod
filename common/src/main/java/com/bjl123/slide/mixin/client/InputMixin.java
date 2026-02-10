package com.bjl123.slide.mixin.client;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Input Mixin
 * 
 * 滑铲时伪装成走路状态：
 * - getMoveVector() 返回 (0, 1) 表示向前走
 * - 不拦截 hasForwardImpulse()，让疾跑检测正常工作
 */
@Mixin(Input.class)
public class InputMixin {

    @Shadow public float forwardImpulse;
    @Shadow public float leftImpulse;

    /**
     * 拦截 getMoveVector() 方法
     * 滑铲时返回向前走的向量 (0, 1)，而不是零向量
     * 这样其他 mod 会认为玩家在向前走，疾跑检测也能正常工作
     */
    @Inject(method = "getMoveVector", at = @At("HEAD"), cancellable = true)
    private void slide$fakeWalkingMoveVector(CallbackInfoReturnable<Vec2> cir) {
        if (isLocalPlayerSliding()) {
            // 返回向前走的向量 (leftImpulse=0, forwardImpulse=1)
            cir.setReturnValue(new Vec2(0.0F, 1.0F));
        }
    }

    /**
     * 检查本地玩家是否在滑铲
     */
    private static boolean isLocalPlayerSliding() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player instanceof PlayerAccessor accessor) {
            return accessor.slide$isSliding();
        }
        return false;
    }
}
