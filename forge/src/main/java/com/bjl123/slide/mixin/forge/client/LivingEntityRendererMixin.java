package com.bjl123.slide.mixin.forge.client;

import com.bjl123.slide.duck.PlayerAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LivingEntityRenderer Mixin (Forge)
 * 
 * 在渲染时拦截 walkAnimation.speed() 调用
 * 滑铲时返回 0，这样手臂就不会摆动
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin<T extends LivingEntity> {

    @Unique
    private static ThreadLocal<LivingEntity> slide$currentEntity = new ThreadLocal<>();

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", 
            at = @At(value = "HEAD"))
    private void slide$setCurrentEntity(LivingEntity entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        slide$currentEntity.set(entity);
    }

    /**
     * 拦截 walkAnimation.speed(partialTicks) 调用
     * 滑铲时返回 0，禁用手臂摆动
     */
    @Redirect(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/WalkAnimationState;speed(F)F")
    )
    private float slide$disableWalkAnimationSpeed(WalkAnimationState walkAnimation, float partialTicks) {
        LivingEntity entity = slide$currentEntity.get();
        if (entity instanceof Player && entity instanceof PlayerAccessor accessor) {
            if (accessor.slide$isSliding()) {
                return 0.0F;
            }
        }
        return walkAnimation.speed(partialTicks);
    }
}
