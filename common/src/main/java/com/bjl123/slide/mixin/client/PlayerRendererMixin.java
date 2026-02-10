package com.bjl123.slide.mixin.client;

import com.bjl123.slide.duck.PlayerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerRenderer Mixin - 滑铲期间强制显示潜行姿势
 */
@Mixin(PlayerRenderer.class)
public class PlayerRendererMixin {

    @Inject(method = "setModelProperties", at = @At("TAIL"))
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
                LocalPlayer localPlayer = mc.player;
                // 直接读取 input.shiftKeyDown，绕过 isShiftKeyDown() 的拦截
                if (localPlayer.input != null && localPlayer.input.shiftKeyDown) {
                    model.crouching = true;
                }
            }
        }
    }
}
