package com.bjl123.slide.forge;

import com.bjl123.slide.SlideMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(SlideMod.MOD_ID)
public class SlideModForge {
    public SlideModForge() {
        SlideMod.init();
        SlideMod.LOGGER.info("Slide Mod initializing... (Forge)");
        
        // 初始化客户端（仅在客户端环境）
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            // 客户端初始化在SlideModForgeClient中处理
        });
    }
}
