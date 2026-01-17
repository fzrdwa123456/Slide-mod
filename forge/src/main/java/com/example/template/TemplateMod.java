package com.example.template;

import net.minecraftforge.fml.common.Mod;

@Mod(TemplateMod.MOD_ID)
public class TemplateMod {
    public static final String MOD_ID = "template-mod";

    public TemplateMod() {
        System.out.println("Template Mod (Forge) initializing...");
    }
}
