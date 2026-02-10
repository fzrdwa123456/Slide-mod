package com.bjl123.slide.mixin.forge.client;

import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.util.EntityDataHelper;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复第三人称火焰渲染
 * 滑铲时 displayFireAnimation() 返回 false，导致第三人称火焰不渲染
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    /**
     * 重定向 displayFireAnimation() 调用，修复滑铲时第三人称火焰渲染消失的问题
     */
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;displayFireAnimation()Z"))
    private boolean slide$fixDisplayFireAnimationWhileSliding(Entity entity) {
        if (entity instanceof Player player && player instanceof PlayerAccessor accessor) {
            if (accessor.slide$isSliding()) {
                boolean fireFlag = EntityDataHelper.isOnFireFromFlags(player);
                return !player.fireImmune() && fireFlag && !player.isSpectator();
            }
        }
        return entity.displayFireAnimation();
    }
}
