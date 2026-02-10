package com.bjl123.slide.mixin.forge.client;

import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.util.EntityDataHelper;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge 版本的 LocalPlayer Mixin - 滑铲期间伪装成走路状态
 * 
 * 1. isShiftKeyDown() - 阻止潜行
 * 2. isMoving() - 返回 true（伪装成在走路）
 * 3. aiStep() - 在疾跑检测前清除 input.shiftKeyDown
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends Player {

    @Shadow public Input input;

    public LocalPlayerMixin() {
        super(null, null, 0, null);
    }
    
    /**
     * 在 aiStep() 开始时，滑铲期间清除 input.shiftKeyDown
     */
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void slide$clearShiftKeyDownBeforeAiStep(CallbackInfo ci) {
        PlayerAccessor accessor = (PlayerAccessor) this;
        if (accessor.slide$isSliding() && this.input != null) {
            this.input.shiftKeyDown = false;
        }
    }

    /**
     * 拦截 isShiftKeyDown() 方法
     * 滑铲期间返回 false，阻止潜行
     */
    @Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void slide$preventShiftWhileSliding(CallbackInfoReturnable<Boolean> cir) {
        PlayerAccessor accessor = (PlayerAccessor) this;
        if (accessor.slide$isSliding()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 拦截 isMoving() 方法
     * 滑铲时返回 true（伪装成在走路）
     */
    @Inject(method = "isMoving*", at = @At("HEAD"), cancellable = true)
    private void slide$fakeWalking(CallbackInfoReturnable<Boolean> cir) {
        PlayerAccessor accessor = (PlayerAccessor) this;
        if (accessor.slide$isSliding()) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 拦截 isMovingSlowly() 方法
     * 滑铲时返回 true，让 TACZ 认为玩家在缓慢移动而不是疾跑
     * 强制潜行期间也返回 true，确保移速正确
     */
    @Inject(method = "isMovingSlowly", at = @At("HEAD"), cancellable = true)
    private void slide$fakeMovingSlowly(CallbackInfoReturnable<Boolean> cir) {
        PlayerAccessor accessor = (PlayerAccessor) this;
        // 滑铲期间或强制潜行期间，返回 true
        if (accessor.slide$isSliding() || accessor.slide$isForceCrouching()) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 修复滑铲时火焰渲染消失的问题
     */
    @Inject(method = {"isOnFire", "m_6063_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void slide$fixFireRenderingWhileSliding(CallbackInfoReturnable<Boolean> cir) {
        PlayerAccessor accessor = (PlayerAccessor) this;
        if (accessor.slide$isSliding()) {
            LocalPlayer player = (LocalPlayer) (Object) this;
            boolean fireFlag = EntityDataHelper.isOnFireFromFlags(player);
            cir.setReturnValue(!player.fireImmune() && fireFlag);
        }
    }
}
