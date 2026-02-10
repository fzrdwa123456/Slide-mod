package com.bjl123.slide.fabric;

import com.bjl123.slide.SlideMod;
import net.fabricmc.api.ModInitializer;

public class SlideModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        SlideMod.init();
    }
}
