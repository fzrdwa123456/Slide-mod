package com.bjl123.slide.duck;

public interface PlayerAccessor {
    boolean slide$isSliding();
    void slide$setSliding(boolean sliding);
    boolean slide$canSlide(); // 检查是否可以疾跑滑铲（冷却时间）
    void slide$resetSlideState(); // 重置滑铲状态（用于服务器端同步）
    boolean slide$shouldShowCrouchPose(); // 检查是否应该显示潜行姿势（滑铲中或强制潜行中）
    boolean slide$isForceCrouching(); // 检查是否处于强制潜行状态（滑铲结束后）
    boolean slide$canJumpCancel(); // 检查是否可以跳跃打断滑铲（滑铲后期）
}