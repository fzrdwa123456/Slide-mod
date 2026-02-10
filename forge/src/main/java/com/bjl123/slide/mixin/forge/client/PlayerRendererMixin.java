package com.bjl123.slide.mixin.forge.client;

import com.bjl123.slide.compat.FirstPersonModelsCompat;
import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge 版本的 PlayerRenderer Mixin - 滑铲期间强制显示潜行姿势
 */
@Mixin(PlayerRenderer.class)
public class PlayerRendererMixin {

    @Inject(method = "getRenderOffset(Lnet/minecraft/client/player/AbstractClientPlayer;F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
    private void slide$adjustRenderOffset(AbstractClientPlayer player, float partialTicks, CallbackInfoReturnable<Vec3> cir) {
        if (!(player instanceof PlayerAccessor accessor)) {
            return;
        }
        
        // 如果 First Person Models 正在渲染第一人称玩家，不修改偏移
        if (FirstPersonModelsCompat.isRenderingFirstPerson()) {
            return;
        }
        
        // 滑铲期间将模型向下移动，让玩家贴近地面
        if (accessor.slide$isSliding()) {
            // 第三人称时，向下移动约 0.4 格让模型贴地
            Vec3 original = cir.getReturnValue();
            cir.setReturnValue(original.add(0.0D, -0.4D, 0.0D));
            return;
        }
        
        // 滑铲刚结束时，如果玩家按着潜行键或强制潜行中，但 isCrouching() 还没返回 true
        // 需要手动应用潜行偏移 -0.125，避免模型跳动
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == player) {
            boolean holdingSneak = mc.options.keyShift.isDown();
            boolean forceCrouch = accessor.slide$isForceCrouching();
            
            // 如果按着潜行键或强制潜行，但原版还没应用潜行偏移（isCrouching() 为 false）
            // 则手动应用潜行偏移
            if ((holdingSneak || forceCrouch) && !player.isCrouching()) {
                Vec3 original = cir.getReturnValue();
                cir.setReturnValue(original.add(0.0D, -0.125D, 0.0D));
            }
        }
    }

    @Inject(method = "setModelProperties*", at = @At("TAIL"))
    private void slide$forceCrouchingWhileSliding(AbstractClientPlayer player, CallbackInfo ci) {
        if (!(player instanceof PlayerAccessor accessor)) {
            return;
        }
        
        PlayerRenderer renderer = (PlayerRenderer) (Object) this;
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        
        // 强制潜行期间（滑铲结束后头顶有方块）设置 crouching = true
        if (accessor.slide$isForceCrouching()) {
            model.crouching = true;
            return;
        }
        
        // 滑铲刚结束时，如果玩家按着潜行键，也强制显示潜行姿势
        // 这样可以避免一帧站立动作
        if (!accessor.slide$isSliding()) {
            // 检查是否是本地玩家且按着潜行键
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == player) {
                // 直接检查按键绑定状态，而不是 input.shiftKeyDown（因为它在滑铲期间被清除）
                if (mc.options.keyShift.isDown()) {
                    model.crouching = true;
                }
            }
        }
    }
}
