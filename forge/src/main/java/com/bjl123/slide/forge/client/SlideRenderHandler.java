package com.bjl123.slide.forge.client;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge客户端渲染事件处理器
 * 用于在滑铲时修改玩家模型姿势（坐下动画）
 */
@Mod.EventBusSubscriber(modid = SlideMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class SlideRenderHandler {

    /**
     * 使用RenderLivingEvent.Pre事件，在模型setupAnim之后、实际渲染之前修改姿势
     * 设置最低优先级确保在其他模组之后执行
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        
        if (entity instanceof Player player && player instanceof PlayerAccessor accessor) {
            if (accessor.slide$isSliding()) {
                EntityModel<?> model = event.getRenderer().getModel();
                
                if (model instanceof HumanoidModel<?> humanoidModel) {
                    // 设置滑铲姿势
                    setupSlidingPose(humanoidModel, player);
                }
                
                // 不偏移模型，让碰撞箱和模型位置一致
            }
        }
    }

    /**
     * 设置滑铲姿势 - 只修改腿部动画，保留手臂原有动画（如弓/弩使用动画）
     */
    private static void setupSlidingPose(HumanoidModel<?> model, Player player) {
        // 腿部姿势 - 模拟坐下/滑铲动作
        // 右腿向前伸展
        model.rightLeg.xRot = -1.4137167F;  // 向前抬起
        model.rightLeg.yRot = ((float) Math.PI / 10F);  // 轻微外展
        model.rightLeg.zRot = 0.07853982F;
        
        // 左腿向前伸展
        model.leftLeg.xRot = -1.4137167F;   // 向前抬起
        model.leftLeg.yRot = (-(float) Math.PI / 10F);  // 轻微外展
        model.leftLeg.zRot = -0.07853982F;
        
        // 只有在玩家没有使用物品时才设置手臂姿势
        // 这样弓、弩、盾牌等物品的使用动画不会被覆盖
        if (!player.isUsingItem()) {
            model.rightArm.xRot = 0.0F;
            model.rightArm.yRot = 0.0F;
            model.rightArm.zRot = 0.1F;
            
            model.leftArm.xRot = 0.0F;
            model.leftArm.yRot = 0.0F;
            model.leftArm.zRot = -0.1F;
        }
    }
}
