package com.bjl123.slide.network;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.sound.ModSounds;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

public class SlideNetworking {
    public static final ResourceLocation SLIDE_PACKET_ID = new ResourceLocation(SlideMod.MOD_ID, "slide_toggle");
    public static final ResourceLocation SLIDE_STATE_SYNC_ID = new ResourceLocation(SlideMod.MOD_ID, "slide_state_sync");
    public static final ResourceLocation SLIDE_BROADCAST_ID = new ResourceLocation(SlideMod.MOD_ID, "slide_broadcast");

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
                        
                        // 服务器端广播滑铲音效给其他玩家（排除自己，因为客户端已经播放）
                        // 音量0.15F与原版脚步声一致，传播范围16格
                        player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
                            ModSounds.SLIDE_SOUND, SoundSource.PLAYERS, 0.15F, 1.0F);
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
                    
                    // 广播滑铲状态给附近的其他玩家
                    broadcastSlideState(player, isSliding);
                }
            });
        });
        
        // 注册S2C广播包接收器（客户端接收其他玩家的滑铲状态）
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SLIDE_BROADCAST_ID, (buf, context) -> {
            int entityId = buf.readInt();
            boolean isSliding = buf.readBoolean();
            
            context.queue(() -> {
                // 在客户端找到对应的玩家实体并更新其滑铲状态
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level != null) {
                    net.minecraft.world.entity.Entity entity = mc.level.getEntity(entityId);
                    if (entity instanceof Player otherPlayer && otherPlayer != mc.player) {
                        PlayerAccessor accessor = (PlayerAccessor) otherPlayer;
                        accessor.slide$setSliding(isSliding);
                        if (isSliding) {
                            otherPlayer.setPose(Pose.CROUCHING);
                        }
                        otherPlayer.refreshDimensions();
                    }
                }
            });
        });
        
        // 注册状态同步包接收器
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SLIDE_STATE_SYNC_ID, (buf, context) -> {
            boolean shouldStand = buf.readBoolean();
            boolean holdingSneak = buf.readBoolean(); // 客户端实时潜行键状态
            
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) {
                    PlayerAccessor playerAccessor = (PlayerAccessor) player;
                    
                    // 更新服务器端存储的客户端潜行键状态
                    playerAccessor.slide$setClientHoldingSneak(holdingSneak);
                    
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
     * 发送状态同步包，同步客户端潜行键状态到服务器
     * @param shouldStand 是否应该站立
     * @param holdingSneak 客户端是否按着潜行键
     */
    public static void sendStateSyncPacket(boolean shouldStand, boolean holdingSneak) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(shouldStand);
        buf.writeBoolean(holdingSneak);
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
    
    /**
     * 广播滑铲状态给附近的其他玩家
     */
    public static void broadcastSlideState(ServerPlayer player, boolean isSliding) {
        // 发送给所有跟踪该玩家的客户端（排除自己）
        for (ServerPlayer otherPlayer : player.serverLevel().players()) {
            if (otherPlayer != player && otherPlayer.distanceTo(player) < 64) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeInt(player.getId());
                buf.writeBoolean(isSliding);
                NetworkManager.sendToPlayer(otherPlayer, SLIDE_BROADCAST_ID, buf);
            }
        }
    }
}