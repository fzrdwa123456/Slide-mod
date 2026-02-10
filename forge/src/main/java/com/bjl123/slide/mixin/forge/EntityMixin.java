package com.bjl123.slide.mixin.forge;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity Mixin - TACZ 兼容性补丁
 * 
 * 修复滑铲进入1.5格方块后强制潜行时，TACZ 无法检测到潜行状态的问题
 * TACZ 使用 Entity::isCrouching() 来检测潜行，触发枪械斜握动画
 */
@Mixin(Entity.class)
public class EntityMixin {

    /**
     * 拦截 isCrouching() 方法
     * 在强制潜行期间返回 true，让其他 mod 能够检测到潜行状态
     */
    @Inject(method = "isCrouching", at = @At("HEAD"), cancellable = true)
    private void slide$forceCrouchingForTacz(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        
        // 只对玩家生效
        if (self instanceof Player && self instanceof PlayerAccessor accessor) {
            boolean isSliding = accessor.slide$isSliding();
            boolean isForceCrouching = accessor.slide$isForceCrouching();
            
            // 滑铲期间或强制潜行期间，返回 true
            if (isSliding || isForceCrouching) {
                cir.setReturnValue(true);
            }
        }
    }
}
