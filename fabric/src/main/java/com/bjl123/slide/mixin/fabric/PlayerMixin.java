package com.bjl123.slide.mixin.fabric;

import com.bjl123.slide.compat.TaczCompat;
import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.network.SlideNetworking;
import com.bjl123.slide.sound.ModSounds;
import com.bjl123.slide.state.SlideState;
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
import net.minecraft.sounds.SoundSource;
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
 * Fabric版本的PlayerMixin，使用状态机管理滑铲逻辑
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

    @Unique
    private boolean slide$clientHoldingSneak = false;

    protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void slide$onDefineSynchedData(CallbackInfo ci) {
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
    public void slide$setSafetyCheckDelay(int ticks) {
        // 状态机模式下不再需要此方法，保留接口兼容
    }

    @Override
    public boolean slide$getClientHoldingSneak() {
        return this.slide$clientHoldingSneak;
    }

    @Override
    public void slide$setClientHoldingSneak(boolean holding) {
        this.slide$clientHoldingSneak = holding;
    }

    // ==================== 状态机核心方法 ====================

    @Unique
    private void slide$transitionTo(SlideState newState, int timer) {
        if (this.slide$state != newState) {
            this.slide$state = newState;
            this.slide$stateTimer = timer;
        }
    }

    @Unique
    private void slide$updateStateMachine(Player player, boolean isClient) {
        // 客户端只为本地玩家运行状态机，其他玩家的状态由服务器同步
        boolean isLocalPlayer = isClient && player == net.minecraft.client.Minecraft.getInstance().player;
        
        // 对于客户端的其他玩家，不运行状态机逻辑
        if (isClient && !isLocalPlayer) {
            return;
        }
        
        boolean canStand = this.slide$canStandUp(player);
        boolean canCrouchCheck = this.slide$canEnterPose(player, Pose.CROUCHING);
        // 本地客户端使用实时按键检测，服务器端使用客户端同步的状态
        boolean holdingSneak = isLocalPlayer ? this.slide$isHoldingSneakKey(player) : this.slide$clientHoldingSneak;
        
        
        switch (this.slide$state) {
            case IDLE:
                if (this.slide$isSliding() && !this.slide$sprintSlide) {
                    this.slide$transitionTo(SlideState.SLIDING, 0);
                }
                // 修复原版延迟：IDLE 状态下检测并修复碰撞箱异常
                else if (canStand && player.getPose() == Pose.CROUCHING && 
                         !player.isSwimming() && !player.isFallFlying() && !player.isSleeping()) {
                    if (isClient) {
                        if (!holdingSneak) {
                            player.setPose(Pose.STANDING);
                            player.refreshDimensions();
                        }
                    } else {
                        if (player.getBbHeight() < 1.6F && !player.isShiftKeyDown()) {
                            player.setPose(Pose.STANDING);
                            player.refreshDimensions();
                        }
                    }
                }
                break;
                
            case SLIDING:
                if (!this.slide$isSliding()) {
                    // 滑铲已结束，决定下一个状态（参考原版活板门逻辑）
                    boolean canCrouch = this.slide$canEnterPose(player, Pose.CROUCHING);
                    
                    if (!canStand && !canCrouch) {
                        // 空间极小，无法站立也无法蹲伏，强制游泳姿势（0.6格高）
                        this.slide$transitionTo(SlideState.ENDING_TO_CROUCH, 20);
                        player.setPose(Pose.SWIMMING);
                        player.setShiftKeyDown(false);
                    } else if (!canStand) {
                        // 头顶有障碍物，但可以蹲伏
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
                boolean canCrouchNow = this.slide$canEnterPose(player, Pose.CROUCHING);
                
                if (!canStand && !canCrouchNow) {
                    // 空间极小，保持游泳姿势
                    this.slide$stateTimer = 20;
                    player.setPose(Pose.SWIMMING);
                    player.setShiftKeyDown(false);
                } else if (!canStand) {
                    // 头顶有障碍物，保持蹲伏，重置计时器
                    this.slide$stateTimer = 10;
                    player.setPose(Pose.CROUCHING);
                    player.setShiftKeyDown(true);
                } else if (!holdingSneak) {
                    // 发送同步包通知服务器端玩家应该站立
                    if (isClient) {
                        SlideNetworking.sendStateSyncPacket(true, holdingSneak);
                    }
                    this.slide$transitionTo(SlideState.ENDING_TO_STAND, 3);
                    player.setPose(Pose.STANDING);
                    player.setShiftKeyDown(false);
                    player.refreshDimensions();
                } else {
                    this.slide$stateTimer--;
                    player.setPose(Pose.CROUCHING);
                    player.setShiftKeyDown(true);
                    
                    if (this.slide$stateTimer <= 0) {
                        // 计时器到期时再次检查潜行键状态，确保正确设置姿势
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
                // 过渡到站立状态（添加空间检查，参考原版活板门逻辑）
                if (!canStand && !canCrouchCheck) {
                    // 空间极小，强制游泳姿势
                    player.setPose(Pose.SWIMMING);
                    player.setShiftKeyDown(false);
                    player.refreshDimensions();
                    // 保持在此状态直到有空间
                } else if (!canStand) {
                    // 无法站立但可以蹲伏，切换到蹲伏
                    this.slide$transitionTo(SlideState.ENDING_TO_CROUCH, 10);
                    player.setPose(Pose.CROUCHING);
                    player.setShiftKeyDown(true);
                    player.refreshDimensions();
                } else if (holdingSneak) {
                    this.slide$transitionTo(SlideState.IDLE, 0);
                } else {
                    this.slide$stateTimer--;
                    player.setPose(Pose.STANDING);
                    
                    if (this.slide$stateTimer <= 0) {
                        this.slide$transitionTo(SlideState.IDLE, 0);
                    }
                }
                break;
        }
    }

    // ==================== 注入点 ====================

    @Inject(method = "tick", at = @At("TAIL"))
    private void slide$fabricTick(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        boolean isClient = player.level().isClientSide;
        
        this.slide$handleSlideLogic();
        this.slide$updateStateMachine(player, isClient);
        
        // 客户端定期同步潜行键状态到服务器（仅本地玩家，滑铲中或状态转换时）
        if (isClient && player == net.minecraft.client.Minecraft.getInstance().player && 
            (this.slide$isSliding() || this.slide$state != SlideState.IDLE)) {
            boolean holdingSneak = this.slide$isHoldingSneakKey(player);
            SlideNetworking.sendStateSyncPacket(false, holdingSneak);
        }
    }

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void slide$onUpdatePlayerPose(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        
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
        
        if (this.slide$isSliding()) {
            player.setPose(Pose.CROUCHING);
            ci.cancel();
            return;
        }
        
        switch (this.slide$state) {
            case ENDING_TO_CROUCH:
                // 检查空间，决定使用蹲伏还是游泳姿势
                boolean canCrouch = this.slide$canEnterPose(player, Pose.CROUCHING);
                if (canCrouch) {
                    player.setPose(Pose.CROUCHING);
                } else {
                    // 空间不足，使用游泳姿势
                    player.setPose(Pose.SWIMMING);
                    player.refreshDimensions();
                }
                ci.cancel();
                return;
            case ENDING_TO_STAND:
                // 检查空间，决定使用站立、蹲伏还是游泳姿势
                boolean canStandNow = this.slide$canStandUp(player);
                boolean canCrouchNow = this.slide$canEnterPose(player, Pose.CROUCHING);
                if (canStandNow) {
                    player.setPose(Pose.STANDING);
                } else if (canCrouchNow) {
                    player.setPose(Pose.CROUCHING);
                } else {
                    player.setPose(Pose.SWIMMING);
                    player.refreshDimensions();
                }
                ci.cancel();
                return;
            default:
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
                
                // 播放滑铲音效（仅本地玩家在客户端播放，其他玩家由服务器广播）
                if (isClient && player == net.minecraft.client.Minecraft.getInstance().player) {
                    player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
                        ModSounds.SLIDE_SOUND, SoundSource.PLAYERS, 1.0F, 1.0F);
                }

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
            // 如果姿势是SWIMMING，返回正确的游泳视线高度
            if (pose == Pose.SWIMMING) {
                cir.setReturnValue(0.4F);
                return;
            }
            
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
        // 如果传入的姿势是SWIMMING，返回正确的游泳尺寸（0.6格高）
        if (pose == Pose.SWIMMING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.6F));
            return;
        }
        
        // 滑铲中且非乘客，返回滑铲碰撞箱（宽0.6，高1.5）
        if (this.slide$isSliding() && !this.isPassenger()) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.5F));
        }
        // 其他状态让原版逻辑处理
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
        // 使用绝对边界框，而不是相对扩展当前边界框
        // 这确保无论当前姿势如何，都能正确检查站立空间
        return this.slide$canEnterPose(player, Pose.STANDING);
    }

    /**
     * 检查玩家是否可以进入指定姿势（参考原版 Entity.canEnterPose）
     */
    @Unique
    private boolean slide$canEnterPose(Player player, Pose pose) {
        EntityDimensions dimensions = player.getDimensions(pose);
        float width = dimensions.width / 2.0F;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            player.getX() - width, player.getY(), player.getZ() - width,
            player.getX() + width, player.getY() + dimensions.height, player.getZ() + width
        );
        return player.level().noCollision(player, box.deflate(1.0E-7D));
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
}
