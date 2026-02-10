package com.bjl123.slide.mixin.client;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 滑铲时的模型动画处理
 * 
 * 兼容性策略：
 * 1. 在 HEAD 设置 riding = true，让原版使用 += 增量方式处理手臂角度
 * 2. 禁用 limbSwingAmount 防止腿部摆动
 * 3. 在 TAIL 只覆盖腿部姿势，不覆盖手臂（让弓、弩、盾牌等动画正常工作）
 * 
 * 原版 setupAnim 处理顺序：
 * 1. 基础手臂摆动（根据 limbSwing）
 * 2. 骑乘姿势（riding=true 时 += -(PI/5F)）
 * 3. poseRightArm/poseLeftArm（弓、弩、盾牌等）
 * 4. setupAttackAnimation（攻击动画）
 * 5. 蹲下调整
 * 6. bobModelPart（轻微摆动）
 */
@Mixin(HumanoidModel.class)
public class HumanoidModelMixin<T extends LivingEntity> {

    @Shadow public ModelPart rightLeg;
    @Shadow public ModelPart leftLeg;

    /**
     * 在 HEAD 设置 riding = true，让原版自动处理骑乘姿势
     * 原版使用 += 增量方式，不会覆盖后续的弓、弩等动画
     */
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void slide$setRidingOnHead(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (entity instanceof PlayerAccessor playerAccessor && playerAccessor.slide$isSliding()) {
            HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
            model.riding = true;
        }
    }

    /**
     * 禁用腿部摆动，滑铲时腿部不应该晃动
     */
    @ModifyVariable(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 3
    )
    private float slide$disableLimbSwingWhileSliding(float limbSwingAmount, T entity) {
        if (entity instanceof PlayerAccessor playerAccessor && playerAccessor.slide$isSliding()) {
            return 0.0F;
        }
        return limbSwingAmount;
    }

    /**
     * 在方法结束后只覆盖腿部姿势
     * 不覆盖手臂，让弓、弩、盾牌、攻击等动画正常工作
     */
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void slide$fixLegPoseOnTail(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (entity instanceof PlayerAccessor playerAccessor && playerAccessor.slide$isSliding()) {
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