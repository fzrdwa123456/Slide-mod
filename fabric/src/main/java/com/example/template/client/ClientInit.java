package com.example.template.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientInit implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("template");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Template Mod client (Fabric)...");
    }
}
