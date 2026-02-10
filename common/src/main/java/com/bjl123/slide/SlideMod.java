package com.bjl123.slide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlideMod {
    public static final String MOD_ID = "slide";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Slide Mod common initializing...");
        com.bjl123.slide.network.SlideNetworking.init();
    }
}
