package com.bjl123.slide.fabric.client;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.compat.FirstPersonModelsCompat;
import net.fabricmc.api.ClientModInitializer;

public class SlideModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 客户端初始化
        SlideMod.LOGGER.info("Slide Mod client initializing (Fabric)...");
        com.bjl123.slide.client.SlideKeyBindings.init();
        
        // 注册 First Person Models 兼容层
        FirstPersonModelsCompat.registerSlideOffsetHandler();
    }
}