package com.bjl123.slide.mixin;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity Mixin - 伪装滑铲时的状态
 * 
 * 滑铲时伪装成走路状态：
 * 1. isSprinting() 返回 false - 让其他 mod 检测不到疾跑
 * 2. canSpawnSprintParticle() 返回 false - 不生成疾跑粒子
 * 
 * 注意：isCrouching() 拦截在 LocalPlayerMixin 中实现，因为 LocalPlayer 覆盖了该方法
 * 
 * TACZ 兼容性通过 TaczCompat 类设置 stopSprint 变量实现
 * 
 * 骑乘时自动结束滑铲并恢复碰撞箱
 * 
 * 火焰渲染修复：滑铲时确保 isOnFire() 正确返回火焰状态
 */
@Mixin(value = Entity.class, priority = 500)
public abstract class EntityMixin {

    @Shadow
    protected SynchedEntityData entityData;
    
    @Shadow
    public abstract boolean fireImmune();
    
    @Shadow
    protected abstract boolean getSharedFlag(int flag);

    /**
     * 伪装疾跑状态：滑铲期间返回 false
     * 优先级设置为 500（低于默认的 1000），确保在其他 mod 之前执行
     */
    @Inject(method = {"isSprinting", "m_6060_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void slide$disguiseSprintingState(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        
        if (entity instanceof Player player && player instanceof PlayerAccessor accessor) {
            if (accessor.slide$isSliding()) {
                cir.setReturnValue(false);
            }
        }
    }
    
    /**
     * 修复滑铲时火焰渲染消失的问题
     * 
     * 原版 isOnFire() 实现：
     * return !this.fireImmune() && (this.remainingFireTicks > 0 || flag && this.getSharedFlag(0));
     * 
     * 问题：滑铲时 isOnFire() 返回 false，即使 getSharedFlag(0) 为 true
     * 解决：直接读取 entityData 中的火焰标志位，绕过可能被干扰的 getSharedFlag 调用
     */
    @Inject(method = {"isOnFire", "m_6063_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void slide$fixFireRenderingWhileSliding(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        
        if (entity instanceof Player player && player instanceof PlayerAccessor accessor) {
            if (accessor.slide$isSliding()) {
                // 直接检查火焰标志位，绕过可能的干扰
                boolean isClientSide = player.level() != null && player.level().isClientSide;
                if (isClientSide) {
                    // 客户端：使用 getSharedFlag(0) 检查火焰状态
                    boolean fireFlag = this.getSharedFlag(0);
                    cir.setReturnValue(!this.fireImmune() && fireFlag);
                }
            }
        }
    }

    /**
     * 拦截 canSpawnSprintParticle 方法，滑铲期间不生成疾跑粒子
     */
    @Inject(method = {"canSpawnSprintParticle", "m_20000_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void slide$disableSprintParticle(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        
        if (entity instanceof Player player && player instanceof PlayerAccessor accessor) {
            if (accessor.slide$isSliding()) {
                cir.setReturnValue(false);
            }
        }
    }

    /**
     * 拦截 startRiding 方法，在骑乘开始前结束滑铲状态
     */
    @Inject(method = {"startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", "m_7998_"}, at = @At("HEAD"), remap = false)
    private void slide$endSlideOnStartRiding(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        
        if (entity instanceof Player player && player instanceof PlayerAccessor accessor) {
            if (accessor.slide$isSliding()) {
                accessor.slide$setSliding(false);
                accessor.slide$resetSlideState();
                player.setPose(net.minecraft.world.entity.Pose.STANDING);
                player.refreshDimensions();
            }
        }
    }
    
    /**
     * 在骑乘成功后再次刷新碰撞箱
     */
    @Inject(method = {"startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", "m_7998_"}, at = @At("RETURN"), remap = false)
    private void slide$refreshAfterStartRiding(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        
        if (entity instanceof Player player && cir.getReturnValue()) {
            player.refreshDimensions();
        }
    }

    /**
     * 拦截 stopRiding 方法，在玩家下车时确保碰撞箱正确
     */
    @Inject(method = {"stopRiding", "m_8127_"}, at = @At("TAIL"), remap = false)
    private void slide$refreshDimensionsOnStopRiding(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        
        if (entity instanceof Player player) {
            player.refreshDimensions();
        }
    }

}
