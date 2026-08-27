package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.AllSoundEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents

// UI edit feedback sounds, mirroring Simulated Coasters' handle sounds
object WaterslideEditSounds {

    fun playUi(sound: SoundEvent, volume: Float, pitch: Float) {
        val mc = Minecraft.getInstance() ?: return
        if (mc.level == null) return
        mc.soundManager.play(SimpleSoundInstance.forUI(sound, pitch, volume))
    }

    // commit success, soft trapdoor click with a slight pitch jitter
    fun playCommitSuccess() {
        val mc = Minecraft.getInstance() ?: return
        val pitch = 0.62f + (mc.level?.getRandom()?.nextFloat() ?: 0f) * 0.06f
        playUi(SoundEvents.IRON_TRAPDOOR_CLOSE, 0.38f, pitch)
    }

    // invalid edit, low bass thud
    fun playDeny() {
        playUi(SoundEvents.NOTE_BLOCK_BASS.value(), 1.0f, 0.5f)
    }

    // drag tick, scroll sound volume and pitch driven by the dragged amount
    fun playDragTick(amount: Float) {
        playUi(AllSoundEvents.SCROLL_VALUE.mainEvent, 0.55f, 0.9f + amount.coerceIn(0f, 1f) * 0.5f)
    }
}
