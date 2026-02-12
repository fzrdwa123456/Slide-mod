package com.bjl123.slide.network;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.duck.PlayerAccessor;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

public class SlideNetworking {
    public static final ResourceLocation SLIDE_PACKET_ID = new ResourceLocation(SlideMod.MOD_ID, "slide_toggle");
    public static final ResourceLocation SLIDE_STATE_SYNC_ID = new ResourceLocation(SlideMod.MOD_ID, "slide_state_sync");

    public static void init() {
        // 注册 C2S 包接收器
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SLIDE_PACKET_ID, (buf, context) -> {
            boolean isSliding = buf.readBoolean();
            boolean isHoldingSneak = buf.readBoolean(); // 客户端是否按着潜行键
            
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
                        
                        // 设置安全检查延迟，等待客户端-服务器状态同步
                        playerAccessor.slide$setSafetyCheckDelay(5);
                        
                        // 根据客户端传来的潜行键状态决定姿势
                        if (isHoldingSneak) {
                            // 按着潜行键，保持潜行姿势
                            player.setPose(Pose.CROUCHING);
                            player.setShiftKeyDown(true);
                        } else {
                            // 没按潜行键，检查头顶空间并设置正确姿势
                            boolean canStand = canStandUp(player);
                            if (canStand) {
                                player.setPose(Pose.STANDING);
                            } else {
                                // 头顶有障碍物，保持潜行
                                player.setPose(Pose.CROUCHING);
                                player.setShiftKeyDown(true);
                            }
                        }
                    }
                    player.refreshDimensions(); // 刷新碰撞箱
                }
            });
        });
        
        // 注册状态同步包接收器
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SLIDE_STATE_SYNC_ID, (buf, context) -> {
            boolean shouldStand = buf.readBoolean();
            
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    if (shouldStand) {
                        // 客户端通知服务器端玩家应该站立
                        boolean canStand = canStandUp(player);
                        if (canStand) {
                            player.setPose(Pose.STANDING);
                            player.setShiftKeyDown(false);
                            player.refreshDimensions();
                        }
                    }
                }
            });
        });
    }

    // 发送包的方法
    public static void sendSlidePacket(boolean isSliding, boolean isHoldingSneak) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(isSliding);
        buf.writeBoolean(isHoldingSneak);
        NetworkManager.sendToServer(SLIDE_PACKET_ID, buf);
    }
    
    /**
     * 发送状态同步包，通知服务器端玩家应该站立
     */
    public static void sendStateSyncPacket(boolean shouldStand) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(shouldStand);
        NetworkManager.sendToServer(SLIDE_STATE_SYNC_ID, buf);
    }

    /**
     * 检查玩家头顶是否有足够空间站立
     */
    private static boolean canStandUp(Player player) {
        double standingHeight = 1.8D;
        double crouchingHeight = 1.5D;
        
        AABB standingBox = player.getBoundingBox().expandTowards(0, standingHeight - crouchingHeight, 0);
        return player.level().noCollision(player, standingBox);
    }
}