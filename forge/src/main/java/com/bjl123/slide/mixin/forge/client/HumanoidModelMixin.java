package com.bjl123.slide.mixin.forge.client;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge版本的HumanoidModel Mixin
 * 用于在滑铲时修改玩家模型姿势
 * 
 * 兼容性策略：
 * 1. 在 HEAD 设置 riding = true，让原版使用 += 增量方式处理手臂角度
 * 2. 在 TAIL 只覆盖腿部姿势，不覆盖手臂（让弓、弩、盾牌等动画正常工作）
 */
@Mixin(HumanoidModel.class)
public class HumanoidModelMixin<T extends LivingEntity> {

    @Shadow public ModelPart rightLeg;
    @Shadow public ModelPart leftLeg;

    /**
     * 在setupAnim方法开头注入，设置滑铲时使用原版骑乘姿势
     * 原版使用 += 增量方式，不会覆盖后续的弓、弩等动画
     */
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void slide$onSetupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (entity instanceof PlayerAccessor accessor && accessor.slide$isSliding()) {
            HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
            model.riding = true;
        }
    }

    /**
     * 在方法结束后只覆盖腿部姿势
     * 不覆盖手臂，让弓、弩、盾牌、攻击等动画正常工作
     */
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void slide$fixLegPoseOnTail(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (entity instanceof PlayerAccessor accessor && accessor.slide$isSliding()) {
            // 只覆盖腿部姿势，使用原版骑乘的腿部角度
            this.rightLeg.xRot = -1.4137167F;
            this.rightLeg.yRot = ((float)Math.PI / 10F);
            this.rightLeg.zRot = 0.07853982F;
            this.leftLeg.xRot = -1.4137167F;
            this.leftLeg.yRot = (-(float)Math.PI / 10F);
            this.leftLeg.zRot = -0.07853982F;
        }
    }
}
