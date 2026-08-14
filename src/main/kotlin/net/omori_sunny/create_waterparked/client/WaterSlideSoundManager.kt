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
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import kotlin.math.abs

// Looping water sound near watered tracks. The volume follows the local flow
// speed of the nearest watered curve and is attenuated by distance (the sound
// instance is positioned at the closest curve point and uses LINEAR falloff).
@OnlyIn(Dist.CLIENT)
object WaterSlideSoundManager {
    private const val RANGE = 16.0
    private const val MAX_VOLUME = 0.8f
    private const val SPEED_FULL_VOLUME = 16.0
    private const val CURVE_SAMPLES = 20

    private var active: WaterSlideSoundInstance? = null

    fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return stopAll()
        val player = mc.player ?: return stopAll()
        val p = player.position()
        var best = Double.MAX_VALUE
        var bestPos = Vec3.ZERO
        var bestSpeed = 0f
        for (be in WaterslideCurveRenderer.clientAnchors()) {
            if (be.level !== level || be.isRemoved) continue
            for ((peer, raw) in be.anchorPeerCurvesView) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val water = WaterFlowSimulation.resultFor(level, bc) ?: continue
                val segs = water.segments
                if (segs.isEmpty()) continue
                // nearest sampled point on this curve + its local segment speed
                var nearestSq = Double.MAX_VALUE
                var nearestPos = bc.getPosition(0.0)
                var nearestFrac = 0.0
                for (i in 0..CURVE_SAMPLES) {
                    val f = i.toDouble() / CURVE_SAMPLES
                    val pt = bc.getPosition(f)
                    val d = pt.distanceToSqr(p)
                    if (d < nearestSq) {
                        nearestSq = d
                        nearestPos = pt
                        nearestFrac = f
                    }
                }
                if (nearestSq >= best) continue
                val idx = (nearestFrac * segs.size).toInt().coerceIn(0, segs.size - 1)
                best = nearestSq
                bestPos = nearestPos
                bestSpeed = abs(segs[idx].speed)
            }
        }
        if (best > RANGE * RANGE) {
            // keep the handle, fade out instead of stop/start churn
            active?.setVolume(0f)
            return
        }
        if (active == null) {
            val inst = WaterSlideSoundInstance(ModSounds.WATER_FLOW.value())
            mc.getSoundManager().play(inst)
            active = inst
        }
        val inst = active ?: return
        val speedFactor = (bestSpeed / SPEED_FULL_VOLUME).toDouble().coerceIn(0.0, 1.0)
        inst.setVolume((MAX_VOLUME * speedFactor).toFloat())
        inst.setPosition(bestPos)
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
            relative = false
            looping = true
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0.8f
        }

        override fun tick() {
        }

        override fun isStopped(): Boolean = false

        fun setVolume(v: Float) {
            volume = v
        }

        fun setPosition(v: Vec3) {
            x = v.x
            y = v.y
            z = v.z
        }
    }
}
