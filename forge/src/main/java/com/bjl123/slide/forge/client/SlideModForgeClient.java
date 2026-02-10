package com.bjl123.slide.forge.client;

import com.bjl123.slide.SlideMod;
import com.bjl123.slide.compat.FirstPersonModelsCompat;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = SlideMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class SlideModForgeClient {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 客户端初始化
        SlideMod.LOGGER.info("Slide Mod client initializing (Forge)...");
        com.bjl123.slide.client.SlideKeyBindings.init();
        
        // 注册 First Person Models 兼容层
        FirstPersonModelsCompat.registerSlideOffsetHandler();
    }
}