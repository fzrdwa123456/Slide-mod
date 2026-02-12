package com.bjl123.slide.sound;

import com.bjl123.slide.SlideMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public class ModSounds {
    public static final ResourceLocation SLIDE_SOUND_ID = new ResourceLocation(SlideMod.MOD_ID, "slide");
    public static final SoundEvent SLIDE_SOUND = SoundEvent.createVariableRangeEvent(SLIDE_SOUND_ID);
}
