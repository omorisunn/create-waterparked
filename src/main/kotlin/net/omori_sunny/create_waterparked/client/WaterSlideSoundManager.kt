package net.omori_sunny.create_waterparked.client

import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.content.registry.ModSounds
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.resources.sounds.TickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import kotlin.math.sqrt

// Looping water sound near watered tracks, volume by distance.
@OnlyIn(Dist.CLIENT)
object WaterSlideSoundManager {
    private var active: WaterSlideSoundInstance? = null

    fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return stopAll()
        val player = mc.player ?: return stopAll()
        val p = player.position()
        var best = Double.MAX_VALUE
        for (be in WaterslideCurveRenderer.clientAnchors()) {
            if (be.level !== level || be.isRemoved) continue
            for ((peer, raw) in be.anchorPeerCurvesView) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                if (WaterFlowSimulation.resultFor(level, bc) == null) continue
                val d = bc.getBounds().distanceToSqr(p)
                if (d < best) best = d
            }
        }
        if (best > 16.0 * 16.0) {
            // keep the handle, fade out instead of stop/start churn
            active?.setVolume(0f)
            return
        }
        val vol = ((1.0 - sqrt(best) / 16.0) * 0.8).toFloat().coerceIn(0f, 0.8f)
        if (active == null) {
            val inst = WaterSlideSoundInstance(ModSounds.WATER_FLOW.value())
            mc.getSoundManager().play(inst)
            active = inst
        }
        val inst = active
        if (inst != null) {
            inst.setVolume(vol)
        }
    }

    fun stopAll() {
        val mc = Minecraft.getInstance()
        active?.let { mc.getSoundManager().stop(it) }
        active = null
    }

    private class WaterSlideSoundInstance(
        sound: SoundEvent
    ) : AbstractSoundInstance(sound, SoundSource.BLOCKS, SoundInstance.createUnseededRandom()),
        TickableSoundInstance {
        init {
            relative = true
            looping = true
            attenuation = SoundInstance.Attenuation.NONE
            volume = 0.8f
        }

        override fun tick() {
        }

        override fun isStopped(): Boolean = false

        fun setVolume(v: Float) {
            volume = v
        }
    }
}
