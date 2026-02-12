package com.bjl123.slide.mixin.forge;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.compat.TaczCompat;
import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.network.SlideNetworking;
import com.bjl123.slide.state.SlideState;
import com.bjl123.slide.util.EntityDataHelper;
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
 * Forge版本的PlayerMixin，使用状态机管理滑铲逻辑
 */
@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity implements PlayerAccessor {

    @Shadow public float bob;
    @Shadow public float oBob;

    @Unique
    private static final EntityDataAccessor<Boolean> IS_SLIDING = SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);

    @Unique
    private static final UUID SLIDE_SLOWDOWN_ID = UUID.fromString("d723d3f9-8a7d-4a7a-bc7b-9c2d2e20d0a6");

    // ==================== 状态机核心 ====================
    @Unique
    private SlideState slide$state = SlideState.IDLE;
    
    @Unique
    private int slide$stateTimer = 0;

    // ==================== 滑铲参数 ====================
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

    protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(Level level, BlockPos pos, float yRot, GameProfile gameProfile, CallbackInfo ci) {
        this.entityData.define(IS_SLIDING, false);
    }

    // ==================== PlayerAccessor 接口实现 ====================
    
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
        return this.slide$sprintSlideCooldown <= 0 && this.slide$state == SlideState.IDLE;
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
        return this.slide$isSliding() || this.slide$state == SlideState.ENDING_TO_CROUCH;
    }

    @Override
    public boolean slide$isForceCrouching() {
        if (this.slide$isSliding()) {
            return false;
        }
        if (this.slide$state == SlideState.ENDING_TO_CROUCH) {
            return true;
        }
        Player player = (Player) (Object) this;
        if (player.getPose() == Pose.CROUCHING && !this.slide$canStandUp(player)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean slide$canJumpCancel() {
        return this.slide$isSliding() && this.slide$noSlowTicks <= 5;
    }

    @Override
    public void slide$setSafetyCheckDelay(int ticks) {
        // 状态机模式下不再需要此方法，保留接口兼容
    }

    // ==================== 状态机核心方法 ====================

    /**
     * 状态机转换：切换到新状态
     */
    @Unique
    private void slide$transitionTo(SlideState newState, int timer) {
        if (this.slide$state != newState) {
            this.slide$state = newState;
            this.slide$stateTimer = timer;
        }
    }

    /**
     * 状态机更新：每tick调用
     */
    @Unique
    private void slide$updateStateMachine(Player player, boolean isClient) {
        boolean canStand = this.slide$canStandUp(player);
        boolean holdingSneak = isClient ? this.slide$isHoldingSneakKey(player) : player.isShiftKeyDown();
        
        switch (this.slide$state) {
            case IDLE:
                // 空闲状态：检查是否开始滑铲
                if (this.slide$isSliding() && !this.slide$sprintSlide) {
                    this.slide$transitionTo(SlideState.SLIDING, 0);
                }
                // 修复原版延迟：IDLE 状态下检测并修复碰撞箱异常
                // 客户端：当不按潜行键、可以站立、但姿势是蹲伏时，强制设置为站立
                // 服务端：因为 isShiftKeyDown 有网络延迟，直接检查碰撞箱高度
                else if (canStand && player.getPose() == Pose.CROUCHING && 
                         !player.isSwimming() && !player.isFallFlying() && !player.isSleeping()) {
                    if (isClient) {
                        // 客户端：使用按键检测
                        if (!holdingSneak) {
                            player.setPose(Pose.STANDING);
                            player.refreshDimensions();
                        }
                    } else {
                        // 服务端：直接检查碰撞箱高度，如果小于1.6且可以站立，强制站立
                        // 这样可以绕过 isShiftKeyDown 的网络延迟
                        if (player.getBbHeight() < 1.6F && !player.isShiftKeyDown()) {
                            player.setPose(Pose.STANDING);
                            player.refreshDimensions();
                        }
                    }
                }
                break;
                
            case SLIDING:
                // 滑铲状态：由 handleSlideLogic 处理滑铲结束
                // 这里只处理状态同步
                if (!this.slide$isSliding()) {
                    // 滑铲已结束，决定下一个状态
                    if (!canStand) {
                        // 头顶有障碍物，必须保持蹲伏
                        this.slide$transitionTo(SlideState.ENDING_TO_CROUCH, 10);
                        player.setPose(Pose.CROUCHING);
                        player.setShiftKeyDown(true);
                    } else if (holdingSneak && !this.slide$jumpCancelled) {
                        // 按着潜行键，过渡到蹲伏
                        this.slide$transitionTo(SlideState.ENDING_TO_CROUCH, 5);
                        player.setPose(Pose.CROUCHING);
                        player.setShiftKeyDown(true);
                    } else {
                        // 其他情况，过渡到站立
                        this.slide$transitionTo(SlideState.ENDING_TO_STAND, 5);
                        player.setPose(Pose.STANDING);
                        player.setShiftKeyDown(false);
                    }
                    player.refreshDimensions();
                }
                break;
                
            case ENDING_TO_CROUCH:
                // 过渡到蹲伏状态（衔接潜行）
                if (!canStand) {
                    // 头顶有障碍物，保持蹲伏，重置计时器
                    this.slide$stateTimer = 10;
                    player.setPose(Pose.CROUCHING);
                    player.setShiftKeyDown(true);
                } else if (!holdingSneak) {
                    // 玩家松开潜行键且可以站立，切换到站立
                    if (isClient) {
                        // 发送同步包通知服务器端玩家应该站立
                        SlideNetworking.sendStateSyncPacket(true);
                    }
                    this.slide$transitionTo(SlideState.ENDING_TO_STAND, 3);
                    player.setPose(Pose.STANDING);
                    player.setShiftKeyDown(false);
                    player.refreshDimensions();
                } else {
                    // 按着潜行键，递减计时器
                    this.slide$stateTimer--;
                    player.setPose(Pose.CROUCHING);
                    player.setShiftKeyDown(true);
                    
                    if (this.slide$stateTimer <= 0) {
                        // 过渡完成，计时器到期时再次检查潜行键状态
                        if (!holdingSneak && canStand) {
                            player.setPose(Pose.STANDING);
                            player.setShiftKeyDown(false);
                            player.refreshDimensions();
                        }
                        this.slide$transitionTo(SlideState.IDLE, 0);
                    }
                }
                break;
                
            case ENDING_TO_STAND:
                // 过渡到站立状态
                if (holdingSneak) {
                    // 玩家按下潜行键，切换到蹲伏
                    this.slide$transitionTo(SlideState.IDLE, 0);
                } else {
                    this.slide$stateTimer--;
                    player.setPose(Pose.STANDING);
                    
                    if (this.slide$stateTimer <= 0) {
                        // 过渡完成
                        this.slide$transitionTo(SlideState.IDLE, 0);
                    }
                }
                break;
        }
    }

    // ==================== 注入点 ====================

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void slide$preventJumpWhileSliding(CallbackInfo ci) {
        if (this.slide$isSliding() && !this.slide$canJumpCancel()) {
            ci.cancel();
        }
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void slide$forgeAiStep(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        boolean isClient = player.level().isClientSide;
        
        this.slide$handleSlideLogic();
        this.slide$updateStateMachine(player, isClient);
    }

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void slide$onUpdatePlayerPose(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        
        // 骑乘时：结束滑铲状态，让原版逻辑处理
        if (this.isPassenger()) {
            if (this.slide$isSliding()) {
                this.slide$setSliding(false);
                this.slide$resetSlideState();
            }
            this.slide$transitionTo(SlideState.IDLE, 0);
            if (player.getPose() != Pose.STANDING) {
                player.setPose(Pose.STANDING);
                player.refreshDimensions();
            }
            return;
        }
        
        // 滑铲期间，强制保持 CROUCHING 姿势
        if (this.slide$isSliding()) {
            player.setPose(Pose.CROUCHING);
            ci.cancel();
            return;
        }
        
        // 状态机控制姿势
        switch (this.slide$state) {
            case ENDING_TO_CROUCH:
                player.setPose(Pose.CROUCHING);
                ci.cancel();
                return;
            case ENDING_TO_STAND:
                player.setPose(Pose.STANDING);
                ci.cancel();
                return;
            default:
                // IDLE 和 SLIDING 状态让原版逻辑处理
                break;
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
            
            // TACZ 兼容性
            if (isClient) {
                TaczCompat.updateSprintStatus();
            }
            
            if (isClient && !player.isLocalPlayer()) {
                this.slide$wasSliding = sliding;
                return;
            }

            // 滑铲初始化
            if (sliding && !this.slide$sprintSlide) {
                this.slide$sprintSlide = true;
                this.slide$noSlowTicks = 15;
                this.slide$sprintSlideCooldown = 0;
                this.slide$airTicks = 0;
                this.slide$state = SlideState.SLIDING;

                Vec3 slideDirection = this.slide$calculateSlideDirection(player);
                this.slide$slideDirX = slideDirection.x;
                this.slide$slideDirZ = slideDirection.z;

                if (isClient) {
                    Vec3 impulse = new Vec3(this.slide$slideDirX, 0.0D, this.slide$slideDirZ);
                    if (impulse.lengthSqr() > 1.0E-6D) {
                        impulse = impulse.normalize().scale(0.75D);
                        Vec3 currentMovement = player.getDeltaMovement();
                        player.setDeltaMovement(new Vec3(impulse.x, currentMovement.y, impulse.z));
                    }
                }
            }

            if (sliding && player.getPose() != Pose.CROUCHING) {
                player.setPose(Pose.CROUCHING);
                player.refreshDimensions();
            }

            // 服务器端滑铲结束检测
            if (sliding && !isClient) {
                if (!player.onGround()) {
                    this.slide$airTicks++;
                    if (this.slide$airTicks > 10) {
                        this.slide$setSliding(false);
                        sliding = false;
                        this.slide$resetSlideState();
                    }
                } else {
                    this.slide$airTicks = 0;
                }
                
                if (player.horizontalCollision && this.slide$wasSliding) {
                    this.slide$sprintSlideCooldown = 10;
                    this.slide$setSliding(false);
                    sliding = false;
                    this.slide$resetSlideState();
                }
                
                if (player.isPassenger()) {
                    this.slide$sprintSlideCooldown = 10;
                    this.slide$setSliding(false);
                    sliding = false;
                    this.slide$transitionTo(SlideState.IDLE, 0);
                    this.slide$resetSlideState();
                    player.setPose(Pose.STANDING);
                    player.refreshDimensions();
                }
                
                if (this.slide$sprintSlide && this.slide$noSlowTicks <= 0) {
                    Vec3 currentMovement = player.getDeltaMovement();
                    double currentSpeed = Math.sqrt(currentMovement.x * currentMovement.x + currentMovement.z * currentMovement.z);
                    if (currentSpeed < 0.05D) {
                        this.slide$setSliding(false);
                        sliding = false;
                        this.slide$resetSlideState();
                    }
                }
            }

            // 客户端滑铲结束检测
            if (sliding && isClient) {
                boolean canJumpCancel = this.slide$noSlowTicks <= 5;
                
                if (this.jumping && this.slide$wasSliding && canJumpCancel) {
                    this.slide$jumpCancelled = true;
                    this.slide$sprintSlideCooldown = 10;
                    boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                    SlideNetworking.sendSlidePacket(false, holdingSneak);
                    this.slide$setSliding(false);
                    sliding = false;
                } else {
                    Vec3 currentMovement = player.getDeltaMovement();
                    double currentSpeed = Math.sqrt(currentMovement.x * currentMovement.x + currentMovement.z * currentMovement.z);
                    
                    if (this.slide$sprintSlide && this.slide$noSlowTicks <= 0) {
                        boolean shouldEnd = currentSpeed < 0.05D || player.onGround();
                        if (shouldEnd) {
                            boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                            SlideNetworking.sendSlidePacket(false, holdingSneak);
                            this.slide$setSliding(false);
                            sliding = false;
                        }
                    }
                    
                    if (!player.onGround()) {
                        this.slide$airTicks++;
                        if (this.slide$airTicks > 10) {
                            boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                            SlideNetworking.sendSlidePacket(false, holdingSneak);
                            this.slide$setSliding(false);
                            sliding = false;
                        }
                    } else {
                        this.slide$airTicks = 0;
                    }
                    
                    if (player.horizontalCollision && this.slide$wasSliding) {
                        this.slide$sprintSlideCooldown = 10;
                        boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                        SlideNetworking.sendSlidePacket(false, holdingSneak);
                        this.slide$setSliding(false);
                        sliding = false;
                    }
                    
                    if (player.isPassenger()) {
                        this.slide$sprintSlideCooldown = 10;
                        SlideNetworking.sendSlidePacket(false, false);
                        this.slide$setSliding(false);
                        sliding = false;
                        this.slide$transitionTo(SlideState.IDLE, 0);
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

            // 滑铲速度维持
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

            // 滑铲结束时的处理
            if (!sliding) {
                if (this.slide$wasSliding) {
                    Vec3 dm = player.getDeltaMovement();
                    double horizontalSpeed = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
                    if (horizontalSpeed > 0.05D) {
                        double friction = this.slide$jumpCancelled ? 0.5D : 0.3D;
                        player.setDeltaMovement(new Vec3(dm.x * friction, dm.y, dm.z * friction));
                    } else {
                        player.setDeltaMovement(new Vec3(0.0D, dm.y, 0.0D));
                    }
                    
                    if (this.slide$sprintSlide) {
                        this.slide$sprintSlideCooldown = 10;
                    }
                    
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

            // 速度修改器
            AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                boolean shouldSlow = sliding && this.slide$noSlowTicks == 0;
                if (shouldSlow) {
                    if (speed.getModifier(SLIDE_SLOWDOWN_ID) == null) {
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
            boolean sliding = this.slide$isSliding();
            boolean forceCrouch = this.slide$state == SlideState.ENDING_TO_CROUCH;
            
            if (sliding || forceCrouch) {
                cir.setReturnValue(1.27F);
            }
        } catch (Exception e) {
        }
    }

    @Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
    private void onGetDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        try {
            boolean sliding = this.slide$isSliding();
            boolean passenger = this.isPassenger();
            boolean forceCrouch = this.slide$state == SlideState.ENDING_TO_CROUCH;
            
            if (sliding && !passenger) {
                cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.5F));
                return;
            }
            
            if (forceCrouch && !passenger) {
                cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.5F));
            }
        } catch (Exception e) {
        }
    }

    // ==================== 辅助方法 ====================
    
    @Unique
    private boolean slide$isHoldingSneakKey(Player player) {
        if (player.level().isClientSide && player instanceof net.minecraft.client.player.LocalPlayer) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return mc.options.keyShift.isDown();
        }
        return false;
    }

    @Unique
    private boolean slide$canStandUp(Player player) {
        double standingHeight = 1.8D;
        double crouchingHeight = 1.5D;
        
        net.minecraft.world.phys.AABB standingBox = player.getBoundingBox().expandTowards(0, standingHeight - crouchingHeight, 0);
        return player.level().noCollision(player, standingBox);
    }

    @Unique
    private Vec3 slide$calculateSlideDirection(Player player) {
        float forwardInput = 0.0F;
        float leftInput = 0.0F;
        
        if (player.level().isClientSide && player instanceof net.minecraft.client.player.LocalPlayer localPlayer) {
            forwardInput = localPlayer.input.forwardImpulse;
            leftInput = localPlayer.input.leftImpulse;
        }
        
        if (forwardInput != 0.0F || leftInput != 0.0F) {
            float yaw = player.getYRot() * ((float)Math.PI / 180F);
            float sinYaw = (float)Math.sin(yaw);
            float cosYaw = (float)Math.cos(yaw);
            
            double dirX = -sinYaw * forwardInput + cosYaw * leftInput;
            double dirZ = cosYaw * forwardInput + sinYaw * leftInput;
            
            Vec3 inputDir = new Vec3(dirX, 0.0D, dirZ);
            if (inputDir.lengthSqr() > 1.0E-6D) {
                return inputDir.normalize();
            }
        }
        
        Vec3 currentMovement = player.getDeltaMovement();
        double movementSpeed = Math.sqrt(currentMovement.x * currentMovement.x + currentMovement.z * currentMovement.z);
        
        if (movementSpeed > 0.1D) {
            Vec3 movementDir = new Vec3(currentMovement.x, 0.0D, currentMovement.z).normalize();
            return movementDir;
        }
        
        Vec3 lookDirection = player.getLookAngle();
        Vec3 horizLookDir = new Vec3(lookDirection.x, 0.0D, lookDirection.z);
        if (horizLookDir.lengthSqr() > 1.0E-6D) {
            return horizLookDir.normalize();
        }
        
        return Vec3.ZERO;
    }
    
    @Inject(method = {"isOnFire", "m_6063_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void slide$fixFireRenderingWhileSliding(CallbackInfoReturnable<Boolean> cir) {
        if (this.slide$isSliding()) {
            Player player = (Player) (Object) this;
            if (player.level() != null && player.level().isClientSide) {
                boolean fireFlag = EntityDataHelper.isOnFireFromFlags(player);
                cir.setReturnValue(!this.fireImmune() && fireFlag);
            }
        }
    }
}
