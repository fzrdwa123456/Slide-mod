package com.bjl123.slide.network;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.duck.PlayerAccessor;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;

public class SlideNetworking {
    public static final ResourceLocation SLIDE_PACKET_ID = new ResourceLocation(SlideMod.MOD_ID, "slide_toggle");

    public static void init() {
        // 注册 C2S 包接收器
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SLIDE_PACKET_ID, (buf, context) -> {
            boolean isSliding = buf.readBoolean();
            
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    PlayerAccessor playerAccessor = (PlayerAccessor) player;
                    
                    // 如果要开始滑铲，检查各种限制条件
                    if (isSliding) {
                        // 检查是否在地面上
                        if (!player.onGround()) {
                            // 空中不允许滑铲，拒绝请求
                            return;
                        }
                        
                        // 检查是否在潜行状态
                        if (player.isCrouching()) {
                            // 潜行时不允许滑铲，拒绝请求
                            return;
                        }
                        
                        // 检查是否为疾跑状态
                        boolean isSprinting = player.isSprinting();
                        
                        // 只有疾跑滑铲才检查冷却时间
                        if (isSprinting && !playerAccessor.slide$canSlide()) {
                            // 疾跑滑铲冷却时间未结束，拒绝滑铲请求
                            return;
                        }
                    }
                    
                    // 通过 Mixin 接口设置滑铲状态
                    playerAccessor.slide$setSliding(isSliding);
                    
                    // 强制更新 Pose - 使用CROUCHING获得潜行高度(1.5格)
                    if (isSliding) {
                        player.setPose(Pose.CROUCHING);
                    } else {
                        // 滑铲结束时，重置服务器端的滑铲状态变量
                        playerAccessor.slide$resetSlideState();
                        // 不直接设置为 STANDING，让客户端的 PlayerMixin 检测头顶空间后决定姿势
                        // 服务器端保持 CROUCHING，避免在低矮空间中"顶一下"
                    }
                    player.refreshDimensions(); // 刷新碰撞箱
                }
            });
        });
    }

    // 发送包的方法
    public static void sendSlidePacket(boolean isSliding) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(isSliding);
        NetworkManager.sendToServer(SLIDE_PACKET_ID, buf);
    }
}