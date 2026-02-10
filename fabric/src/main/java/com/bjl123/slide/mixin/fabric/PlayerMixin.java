package com.bjl123.slide.mixin.fabric;

import com.bjl123.slide.compat.TaczCompat;
import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.network.SlideNetworking;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Fabric版本的PlayerMixin，使用tick方法注入
 */
@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity implements PlayerAccessor {

    @Shadow public float bob;
    @Shadow public float oBob;

    @Unique
    private static final EntityDataAccessor<Boolean> IS_SLIDING = SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);

    @Unique
    private static final UUID SLIDE_SLOWDOWN_ID = UUID.fromString("d723d3f9-8a7d-4a7a-bc7b-9c2d2e20d0a6");

    @Unique
    private boolean slide$wasSliding;

    @Unique
    private boolean slide$sprintSlide;

    @Unique
    private int slide$noSlowTicks;

    @Unique
    private double slide$slideDirX;

    @Unique
    private double slide$slideDirZ;

    @Unique
    private int slide$sprintSlideCooldown;

    @Unique
    private int slide$airTicks = 0;

    @Unique
    private boolean slide$jumpCancelled = false;

    @Unique
    private boolean slide$isProcessing = false;

    @Unique
    private int slide$forceCrouchTicks = 0;

    protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(Level level, BlockPos pos, float yRot, GameProfile gameProfile, CallbackInfo ci) {
        this.entityData.define(IS_SLIDING, false);
    }

    @Override
    public boolean slide$isSliding() {
        try {
            return this.entityData.get(IS_SLIDING);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void slide$setSliding(boolean sliding) {
        this.entityData.set(IS_SLIDING, sliding);
    }

    @Override
    public boolean slide$canSlide() {
        return this.slide$sprintSlideCooldown <= 0;
    }

    @Override
    public void slide$resetSlideState() {
        this.slide$sprintSlide = false;
        this.slide$noSlowTicks = 0;
        this.slide$slideDirX = 0.0D;
        this.slide$slideDirZ = 0.0D;
        this.slide$airTicks = 0;
        this.slide$sprintSlideCooldown = 10;
        this.slide$jumpCancelled = false;
    }

    @Override
    public boolean slide$shouldShowCrouchPose() {
        // 滑铲中或强制潜行中都应该显示潜行姿势
        return this.slide$isSliding() || this.slide$forceCrouchTicks > 0;
    }

    @Override
    public boolean slide$isForceCrouching() {
        // 只有在强制潜行期间（滑铲已结束）才返回 true
        return !this.slide$isSliding() && this.slide$forceCrouchTicks > 0;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void slide$fabricTick(CallbackInfo ci) {
        this.slide$handleSlideLogic();
    }

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void slide$onUpdatePlayerPose(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        
        // 骑乘时：结束滑铲状态，设置 STANDING 姿势，然后让原版逻辑继续
        if (this.isPassenger()) {
            // 如果还在滑铲，强制结束
            if (this.slide$isSliding()) {
                this.slide$setSliding(false);
                this.slide$resetSlideState();
            }
            this.slide$forceCrouchTicks = 0;
            // 骑乘时设置 STANDING 姿势并刷新碰撞箱
            if (player.getPose() != Pose.STANDING) {
                player.setPose(Pose.STANDING);
                player.refreshDimensions();
            }
            // 不 cancel，让原版逻辑继续处理
            return;
        }
        
        // 滑铲期间，强制保持 CROUCHING 姿势
        if (this.slide$isSliding()) {
            player.setPose(Pose.CROUCHING);
            ci.cancel();
            return;
        }
        
        // 在强制潜行期间，阻止 updatePlayerPose 将姿势改为 STANDING
        if (this.slide$forceCrouchTicks > 0) {
            if (!this.slide$canStandUp(player)) {
                player.setPose(Pose.CROUCHING);
                ci.cancel();
            }
        }
    }

    @Unique
    private void slide$handleSlideLogic() {
        if (this.slide$isProcessing) return;
        this.slide$isProcessing = true;
        
        try {
            boolean sliding = this.slide$isSliding();
            Player player = (Player) (Object) this;

            boolean isClient = player.level().isClientSide;
            
            // TACZ 兼容性：更新 TACZ 的疾跑状态
            if (isClient) {
                TaczCompat.updateSprintStatus();
            }
            
            if (isClient && !player.isLocalPlayer()) {
                this.slide$wasSliding = sliding;
                return;
            }

            // 强制潜行计时器：在滑铲结束后的几帧内强制保持潜行状态
            if (this.slide$forceCrouchTicks > 0) {
                this.slide$forceCrouchTicks--;
                if (!this.slide$canStandUp(player)) {
                    player.setPose(Pose.CROUCHING);
                    player.setShiftKeyDown(true);
                } else {
                    this.slide$forceCrouchTicks = 0;
                }
            }

            // 滑铲初始化：当 sliding=true 且 sprintSlide=false 时初始化（兼容客户端和服务器端）
            if (sliding && !this.slide$sprintSlide) {
                this.slide$sprintSlide = true;
                this.slide$noSlowTicks = 15;
                this.slide$sprintSlideCooldown = 0;
                this.slide$airTicks = 0;

                Vec3 slideDirection = this.slide$calculateSlideDirection(player);
                this.slide$slideDirX = slideDirection.x;
                this.slide$slideDirZ = slideDirection.z;

                // 只在客户端应用初始冲力，避免重复
                if (isClient) {
                    Vec3 impulse = new Vec3(this.slide$slideDirX, 0.0D, this.slide$slideDirZ);
                    if (impulse.lengthSqr() > 1.0E-6D) {
                        impulse = impulse.normalize().scale(0.75D);
                        player.setDeltaMovement(player.getDeltaMovement().add(impulse));
                    }
                }
            }

            if (sliding && player.getPose() != Pose.CROUCHING) {
                player.setPose(Pose.CROUCHING);
                player.refreshDimensions();
            }
            
            // 滑铲期间强制保持疾跑状态，显示疾跑粒子效果
            if (sliding && !player.isSprinting()) {
                player.setSprinting(true);
            }

            // 滑铲结束检测逻辑只在客户端执行，避免客户端和服务器端状态不同步
            if (sliding && isClient) {
                // 只有在滑铲已经持续至少一个tick后才允许跳跃打断，避免刚开始滑铲就被打断
                if (this.jumping && this.slide$wasSliding) {
                    // 滑铲跳：给玩家额外的向前冲力
                    Vec3 dm = player.getDeltaMovement();
                    double forwardBoost = 0.08D;
                    player.setDeltaMovement(new Vec3(
                        dm.x + this.slide$slideDirX * forwardBoost,
                        dm.y,
                        dm.z + this.slide$slideDirZ * forwardBoost
                    ));
                    this.slide$jumpCancelled = true;
                    this.slide$sprintSlideCooldown = 10;
                    this.slide$endSlideWithPoseCheck(player);
                    SlideNetworking.sendSlidePacket(false);
                    this.slide$setSliding(false);
                    sliding = false;
                } else {
                    Vec3 currentMovement = player.getDeltaMovement();
                    double currentSpeed = Math.sqrt(currentMovement.x * currentMovement.x + currentMovement.z * currentMovement.z);
                    
                    if (this.slide$sprintSlide && this.slide$noSlowTicks <= 0) {
                        double naturalEndThreshold = 0.05D;
                        if (currentSpeed < naturalEndThreshold) {
                            this.slide$endSlideWithPoseCheck(player);
                            SlideNetworking.sendSlidePacket(false);
                            this.slide$setSliding(false);
                            sliding = false;
                        }
                    }
                    
                    // 空中检测
                    if (!player.onGround()) {
                        this.slide$airTicks++;
                        if (this.slide$airTicks > 10) {
                            this.slide$endSlideWithPoseCheck(player);
                            SlideNetworking.sendSlidePacket(false);
                            this.slide$setSliding(false);
                            sliding = false;
                        }
                    } else {
                        this.slide$airTicks = 0;
                    }
                    
                    // 撞墙检测
                    if (player.horizontalCollision && this.slide$wasSliding) {
                        this.slide$sprintSlideCooldown = 10;
                        this.slide$endSlideWithPoseCheck(player);
                        SlideNetworking.sendSlidePacket(false);
                        this.slide$setSliding(false);
                        sliding = false;
                    }
                    
                    // 骑乘检测
                    // 注意：骑乘时的滑铲结束由 EntityMixin.startRiding 处理
                    // 这里只需要结束滑铲状态，不需要调用 endSlideWithPoseCheck
                    // 因为骑乘时姿势由原版 startRiding 设置为 STANDING
                    if (player.isPassenger()) {
                        this.slide$sprintSlideCooldown = 10;
                        SlideNetworking.sendSlidePacket(false);
                        this.slide$setSliding(false);
                        sliding = false;
                        this.slide$forceCrouchTicks = 0;
                        // 骑乘时不调用 endSlideWithPoseCheck，避免错误的碰撞检测
                        // 直接刷新碰撞箱
                        player.refreshDimensions();
                    }
                }
            }

            if (this.slide$sprintSlideCooldown > 0) {
                this.slide$sprintSlideCooldown--;
            }

            if (isClient && sliding) {
                this.bob = 0.0F;
            }

            if (sliding && this.slide$sprintSlide && (this.slide$slideDirX != 0.0D || this.slide$slideDirZ != 0.0D)) {
                if (this.slide$noSlowTicks > 5) {
                    Vec3 dm = player.getDeltaMovement();
                    double horizontalSpeed = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
                    double minSpeed = 0.40D;
                    if (horizontalSpeed < minSpeed) {
                        horizontalSpeed = minSpeed;
                    }
                    player.setDeltaMovement(new Vec3(this.slide$slideDirX * horizontalSpeed, dm.y, this.slide$slideDirZ * horizontalSpeed));
                }
            }

            if (!sliding) {
                if (this.slide$wasSliding) {
                    // 跳跃打断时不应用摩擦力，保留滑铲跳冲力
                    if (!this.slide$jumpCancelled) {
                        Vec3 dm = player.getDeltaMovement();
                        double horizontalSpeed = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
                        if (horizontalSpeed > 0.05D) {
                            double friction = 0.3D;
                            player.setDeltaMovement(new Vec3(dm.x * friction, dm.y, dm.z * friction));
                        } else {
                            player.setDeltaMovement(new Vec3(0.0D, dm.y, 0.0D));
                        }
                    }
                    
                    if (this.slide$sprintSlide) {
                        this.slide$sprintSlideCooldown = 10;
                    }
                    
                    // 姿势设置已在 slide$endSlideWithPoseCheck 中处理
                    this.slide$jumpCancelled = false;
                }
                this.slide$sprintSlide = false;
                this.slide$noSlowTicks = 0;
                this.slide$slideDirX = 0.0D;
                this.slide$slideDirZ = 0.0D;
                this.slide$airTicks = 0;
            } else if (this.slide$noSlowTicks > 0) {
                this.slide$noSlowTicks--;
            }

            AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                boolean shouldSlow = sliding && this.slide$noSlowTicks == 0;
                if (shouldSlow) {
                    if (speed.getModifier(SLIDE_SLOWDOWN_ID) == null) {
                        // 降低减速惩罚，让操作更流畅
                        AttributeModifier slowdown = new AttributeModifier(SLIDE_SLOWDOWN_ID, "slide_slowdown", -0.05D, AttributeModifier.Operation.MULTIPLY_TOTAL);
                        speed.addTransientModifier(slowdown);
                    }
                } else {
                    if (speed.getModifier(SLIDE_SLOWDOWN_ID) != null) {
                        speed.removeModifier(SLIDE_SLOWDOWN_ID);
                    }
                }
            }

            this.slide$wasSliding = sliding;
        } finally {
            this.slide$isProcessing = false;
        }
    }

    @ModifyVariable(
            method = "travel(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1
    )
    private Vec3 slide$lockInputWhileSprintSliding(Vec3 travelVector) {
        if (this.slide$isSliding() && this.slide$sprintSlide && this.slide$noSlowTicks > 10) {
            return Vec3.ZERO;
        } else if (this.slide$isSliding() && this.slide$sprintSlide) {
            return travelVector.scale(0.2D);
        }
        return travelVector;
    }

    @Inject(method = "getStandingEyeHeight", at = @At("HEAD"), cancellable = true)
    private void onGetStandingEyeHeight(Pose pose, EntityDimensions dimensions, CallbackInfoReturnable<Float> cir) {
        try {
            if (this.slide$isSliding()) {
                cir.setReturnValue(1.27F);
            }
        } catch (Exception e) {
        }
    }

    /**
     * 覆盖getDimensions方法，滑铲时返回潜行尺寸
     * 使用 fixed() 而不是 scalable()，与原版潜行一致
     */
    @Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
    private void onGetDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        try {
            // 骑乘时不返回滑铲尺寸，确保碰撞箱正确
            if (this.slide$isSliding() && !this.isPassenger()) {
                // 返回潜行尺寸：宽度0.6F，高度1.5F
                // 使用 fixed() 确保碰撞箱从脚底开始向上
                cir.setReturnValue(EntityDimensions.fixed(0.6F, 1.5F));
            }
        } catch (Exception e) {
            // 忽略初始化期间的错误
        }
    }

    @Unique
    private void slide$endSlideWithPoseCheck(Player player) {
        // 在滑铲结束时立即检测头顶空间并设置正确的姿势
        boolean canStand = this.slide$canStandUp(player);
        
        if (!canStand) {
            // 空间不足，设置强制潜行计时器（持续5帧确保不会站立）
            this.slide$forceCrouchTicks = 5;
            player.setPose(Pose.CROUCHING);
            player.setShiftKeyDown(true);
            player.refreshDimensions();
        } else {
            // 空间足够，设置为站立
            this.slide$forceCrouchTicks = 0;
            player.setPose(Pose.STANDING);
            player.refreshDimensions();
        }
    }

    @Unique
    private boolean slide$canStandUp(Player player) {
        // 检测玩家头顶是否有足够空间站立（站立高度1.8格，潜行高度1.5格）
        double standingHeight = 1.8D;
        double crouchingHeight = 1.5D;
        
        // 使用AABB检测头顶空间
        net.minecraft.world.phys.AABB standingBox = player.getBoundingBox().expandTowards(0, standingHeight - crouchingHeight, 0);
        return player.level().noCollision(player, standingBox);
    }

    @Unique
    private Vec3 slide$calculateSlideDirection(Player player) {
        // 优先使用玩家的输入方向（基于视角和按键）
        // 这样 W+A 会向左前方滑，W+D 会向右前方滑
        float forwardInput = 0.0F;
        float leftInput = 0.0F;
        
        // 获取玩家输入
        if (player.level().isClientSide && player instanceof net.minecraft.client.player.LocalPlayer localPlayer) {
            forwardInput = localPlayer.input.forwardImpulse;
            leftInput = localPlayer.input.leftImpulse;
        }
        
        // 如果有输入，基于视角和输入计算方向
        if (forwardInput != 0.0F || leftInput != 0.0F) {
            float yaw = player.getYRot() * ((float)Math.PI / 180F);
            float sinYaw = (float)Math.sin(yaw);
            float cosYaw = (float)Math.cos(yaw);
            
            // 计算基于视角的移动方向
            // leftImpulse: 正值=左(A键), 负值=右(D键)
            double dirX = -sinYaw * forwardInput + cosYaw * leftInput;
            double dirZ = cosYaw * forwardInput + sinYaw * leftInput;
            
            Vec3 inputDir = new Vec3(dirX, 0.0D, dirZ);
            if (inputDir.lengthSqr() > 1.0E-6D) {
                return inputDir.normalize();
            }
        }
        
        // 如果没有输入，使用当前移动方向
        Vec3 currentMovement = player.getDeltaMovement();
        double movementSpeed = Math.sqrt(currentMovement.x * currentMovement.x + currentMovement.z * currentMovement.z);
        
        if (movementSpeed > 0.1D) {
            Vec3 movementDir = new Vec3(currentMovement.x, 0.0D, currentMovement.z).normalize();
            return movementDir;
        }
        
        // 最后使用视角方向
        Vec3 lookDirection = player.getLookAngle();
        Vec3 horizLookDir = new Vec3(lookDirection.x, 0.0D, lookDirection.z);
        if (horizLookDir.lengthSqr() > 1.0E-6D) {
            return horizLookDir.normalize();
        }
        
        return Vec3.ZERO;
    }
    
    /**
     * 检查玩家是否在任何流体中（水、岩浆、其他 mod 的流体）
     * 使用 Minecraft 原版的流体检测方法，兼容所有 mod 的流体
     * 
     * @param player 要检查的玩家
     * @return 如果玩家在任何流体中返回 true
     */
    @Unique
    private boolean slide$isInAnyFluid(Player player) {
        // 检查是否在水中（包括气泡柱）
        if (player.isInWater()) {
            return true;
        }
        
        // 检查是否在岩浆中
        if (player.isInLava()) {
            return true;
        }
        
        // 检查是否在游泳状态（可能在其他 mod 的流体中）
        if (player.isSwimming()) {
            return true;
        }
        
        // 检查玩家脚下的方块是否是流体
        // 这可以检测到其他 mod 添加的流体
        if (!player.level().getFluidState(player.blockPosition()).isEmpty()) {
            return true;
        }
        
        return false;
    }
}
