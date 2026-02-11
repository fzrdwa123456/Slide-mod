package com.bjl123.slide.mixin.forge;

import com.bjl123.slide.compat.TaczCompat;
import com.bjl123.slide.duck.PlayerAccessor;
import com.bjl123.slide.network.SlideNetworking;
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
 * Forge版本的PlayerMixin，使用aiStep方法以提高兼容性
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
        // 检查 forceCrouchTicks 或者头顶有障碍物无法站立
        if (this.slide$isSliding()) {
            return false;
        }
        if (this.slide$forceCrouchTicks > 0) {
            return true;
        }
        // 额外检查：如果头顶有障碍物且姿势是 CROUCHING，也认为是强制潜行
        Player player = (Player) (Object) this;
        if (player.getPose() == Pose.CROUCHING && !this.slide$canStandUp(player)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean slide$canJumpCancel() {
        // 滑铲后期（noSlowTicks <= 5）才允许跳跃打断
        return this.slide$isSliding() && this.slide$noSlowTicks <= 5;
    }

    /**
     * 拦截跳跃方法，滑铲开始阶段禁止跳跃
     */
    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void slide$preventJumpWhileSliding(CallbackInfo ci) {
        // 滑铲期间且不允许跳跃打断时，取消跳跃
        if (this.slide$isSliding() && !this.slide$canJumpCancel()) {
            ci.cancel();
        }
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void slide$forgeAiStep(CallbackInfo ci) {
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
        
        // 在强制潜行期间（头顶有障碍物），阻止 updatePlayerPose 将姿势改为 STANDING
        if (this.slide$forceCrouchTicks > 0) {
            player.setPose(Pose.CROUCHING);
            ci.cancel();
            return;
        }
        
        // 其他情况让原版逻辑处理（包括正常的潜行键按下）
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
                boolean canStand = this.slide$canStandUp(player);
                boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                
                if (!canStand) {
                    // 头顶有障碍物，保持潜行，重置计时器确保持续生效
                    this.slide$forceCrouchTicks = 5;
                    player.setPose(Pose.CROUCHING);
                    player.setShiftKeyDown(true);
                } else if (holdingSneak) {
                    // 按着潜行键，保持潜行，递减计时器
                    this.slide$forceCrouchTicks--;
                    player.setPose(Pose.CROUCHING);
                    player.setShiftKeyDown(true);
                } else {
                    // 可以站立且没按潜行键，递减计时器
                    this.slide$forceCrouchTicks--;
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
                // 使用固定速度而不是叠加，防止速度累积
                if (isClient) {
                    Vec3 impulse = new Vec3(this.slide$slideDirX, 0.0D, this.slide$slideDirZ);
                    if (impulse.lengthSqr() > 1.0E-6D) {
                        impulse = impulse.normalize().scale(0.75D);
                        Vec3 currentMovement = player.getDeltaMovement();
                        // 直接设置水平速度为固定值，不叠加
                        player.setDeltaMovement(new Vec3(impulse.x, currentMovement.y, impulse.z));
                    }
                }
            }

            if (sliding && player.getPose() != Pose.CROUCHING) {
                player.setPose(Pose.CROUCHING);
                player.refreshDimensions();
            }
            
            // 滑铲期间伪装为非疾跑状态，让其他 mod 无法检测到疾跑
            // 通过 EntityMixin 拦截 isSprinting() 和 getSharedFlag(3) 实现伪装
            // 不再强制清除疾跑标志位，这样玩家可以在滑铲期间按疾跑键

            // 服务器端滑铲逻辑：空中检测、撞墙检测、自然结束
            // 服务器端独立执行这些检测，确保即使客户端断开也能正确结束滑铲
            if (sliding && !isClient) {
                // 空中检测
                if (!player.onGround()) {
                    this.slide$airTicks++;
                    if (this.slide$airTicks > 10) {
                        this.slide$setSliding(false);
                        sliding = false;
                        this.slide$resetSlideState();
                        player.refreshDimensions();
                    }
                } else {
                    this.slide$airTicks = 0;
                }
                
                // 撞墙检测
                if (player.horizontalCollision && this.slide$wasSliding) {
                    this.slide$sprintSlideCooldown = 10;
                    this.slide$setSliding(false);
                    sliding = false;
                    this.slide$resetSlideState();
                    player.refreshDimensions();
                }
                
                // 骑乘检测
                if (player.isPassenger()) {
                    this.slide$sprintSlideCooldown = 10;
                    this.slide$setSliding(false);
                    sliding = false;
                    this.slide$forceCrouchTicks = 0;
                    this.slide$resetSlideState();
                    player.refreshDimensions();
                }
                
                // 自然结束检测（速度过低）
                if (this.slide$sprintSlide && this.slide$noSlowTicks <= 0) {
                    Vec3 currentMovement = player.getDeltaMovement();
                    double currentSpeed = Math.sqrt(currentMovement.x * currentMovement.x + currentMovement.z * currentMovement.z);
                    double naturalEndThreshold = 0.05D;
                    if (currentSpeed < naturalEndThreshold) {
                        this.slide$setSliding(false);
                        sliding = false;
                        this.slide$resetSlideState();
                        player.refreshDimensions();
                    }
                }
            }

            // 客户端滑铲结束检测逻辑
            if (sliding && isClient) {
                // 滑铲需要持续至少10个tick后才允许跳跃打断（noSlowTicks从15开始递减）
                // 这样可以防止滑铲刚开始就被跳跃打断导致速度叠加
                boolean canJumpCancel = this.slide$noSlowTicks <= 5;
                
                if (this.jumping && this.slide$wasSliding && canJumpCancel) {
                    // 滑铲跳：给玩家额外的向前冲力（暂时禁用）
                    // Vec3 dm = player.getDeltaMovement();
                    // double forwardBoost = 0.08D;
                    // player.setDeltaMovement(new Vec3(
                    //     dm.x + this.slide$slideDirX * forwardBoost,
                    //     dm.y,
                    //     dm.z + this.slide$slideDirZ * forwardBoost
                    // ));
                    this.slide$jumpCancelled = true;
                    this.slide$sprintSlideCooldown = 10;
                    boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                    SlideNetworking.sendSlidePacket(false, holdingSneak);
                    this.slide$setSliding(false);
                    sliding = false;
                    // 必须在 setSliding(false) 之后调用，否则 getDimensions 仍返回滑铲尺寸
                    this.slide$endSlideWithPoseCheck(player);
                } else {
                    Vec3 currentMovement = player.getDeltaMovement();
                    double currentSpeed = Math.sqrt(currentMovement.x * currentMovement.x + currentMovement.z * currentMovement.z);
                    
                    if (this.slide$sprintSlide && this.slide$noSlowTicks <= 0) {
                        double naturalEndThreshold = 0.05D;
                        if (currentSpeed < naturalEndThreshold) {
                            boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                            SlideNetworking.sendSlidePacket(false, holdingSneak);
                            this.slide$setSliding(false);
                            sliding = false;
                            // 必须在 setSliding(false) 之后调用
                            this.slide$endSlideWithPoseCheck(player);
                        }
                    }
                    
                    // 空中检测
                    if (!player.onGround()) {
                        this.slide$airTicks++;
                        if (this.slide$airTicks > 10) {
                            boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                            SlideNetworking.sendSlidePacket(false, holdingSneak);
                            this.slide$setSliding(false);
                            sliding = false;
                            // 必须在 setSliding(false) 之后调用
                            this.slide$endSlideWithPoseCheck(player);
                        }
                    } else {
                        this.slide$airTicks = 0;
                    }
                    
                    // 撞墙检测
                    if (player.horizontalCollision && this.slide$wasSliding) {
                        this.slide$sprintSlideCooldown = 10;
                        boolean holdingSneak = this.slide$isHoldingSneakKey(player);
                        SlideNetworking.sendSlidePacket(false, holdingSneak);
                        this.slide$setSliding(false);
                        sliding = false;
                        // 必须在 setSliding(false) 之后调用
                        this.slide$endSlideWithPoseCheck(player);
                    }
                    
                    // 骑乘检测
                    // 注意：骑乘时的滑铲结束由 EntityMixin.startRiding 处理
                    // 这里只需要结束滑铲状态，不需要调用 endSlideWithPoseCheck
                    // 因为骑乘时姿势由原版 startRiding 设置为 STANDING
                    if (player.isPassenger()) {
                        this.slide$sprintSlideCooldown = 10;
                        SlideNetworking.sendSlidePacket(false, false);
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
                    // 无论是正常结束还是跳跃打断，都应用摩擦力，防止速度叠加
                    Vec3 dm = player.getDeltaMovement();
                    double horizontalSpeed = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
                    if (horizontalSpeed > 0.05D) {
                        // 跳跃打断时使用较小的摩擦力，保留一些冲力但不会叠加
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
            boolean forceCrouch = this.slide$forceCrouchTicks > 0;
            
            // 滑铲期间或强制潜行期间（头顶有障碍物），返回潜行视线高度
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
            boolean forceCrouch = this.slide$forceCrouchTicks > 0;
            
            // 骑乘时不返回滑铲尺寸，确保碰撞箱正确
            // 使用 scalable 而不是 fixed，与潜行碰撞箱类型一致，避免切换时跳变
            if (sliding && !passenger) {
                cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.5F));
                return;
            }
            
            // 强制潜行期间（头顶有障碍物），强制返回潜行尺寸
            if (forceCrouch && !passenger) {
                cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.5F));
            }
        } catch (Exception e) {
        }
    }

    @Unique
    private void slide$endSlideWithPoseCheck(Player player) {
        boolean canStand = this.slide$canStandUp(player);
        boolean isHoldingSneak = this.slide$isHoldingSneakKey(player);
        
        // 情况1：头顶有障碍物无法站起来 -> 强制潜行
        if (!canStand) {
            this.slide$forceCrouchTicks = 5;
            player.setPose(Pose.CROUCHING);
            player.setShiftKeyDown(true);
            player.refreshDimensions();
        }
        // 情况2：按着潜行键且不是跳跃结束 -> 直接衔接潜行状态
        // 设置 forceCrouchTicks=3 让 updatePlayerPose 保持潜行姿势三帧，
        // 等待 KeyboardInput.tick() 正确设置 input.shiftKeyDown 后原版逻辑接管
        // 使用3帧确保碰撞箱和视线高度不会在过渡期间跳变
        else if (isHoldingSneak && !this.jumping) {
            this.slide$forceCrouchTicks = 3;
            player.setPose(Pose.CROUCHING);
            player.setShiftKeyDown(true);
            player.refreshDimensions();
        }
        // 情况3：其他情况（跳跃结束或没按潜行键）-> 站立
        else {
            this.slide$forceCrouchTicks = 0;
            player.setPose(Pose.STANDING);
            player.refreshDimensions();
        }
    }
    
    /**
     * 检查玩家是否正在按着潜行键
     * 直接检查 Minecraft 的按键绑定状态，而不是被修改过的 input.shiftKeyDown
     */
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
     * 修复滑铲时火焰渲染消失的问题
     * 
     * 原版 isOnFire() 在客户端依赖 getSharedFlag(0) 来判断火焰状态
     * 但滑铲时这个值可能被错误地返回 false
     * 
     * 解决方案：滑铲时直接调用父类的 getSharedFlag(0) 方法
     */
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
