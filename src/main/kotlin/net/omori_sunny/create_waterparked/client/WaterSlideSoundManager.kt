package net.omori_sunny.create_waterparked.client

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.content.registry.ModSounds
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.physics.SlideSpace
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
import kotlin.math.sqrt

// One looping water sound per watered slide curve. Every instance computes its
// own distance falloff and local-flow-speed volume, so several slides can be
// heard at once.
@OnlyIn(Dist.CLIENT)
object WaterSlideSoundManager {
    private const val RANGE = 16.0
    private const val MAX_VOLUME = 0.64f
    private const val SPEED_FULL_VOLUME = 16.0
    private const val CURVE_SAMPLES = 20

    private val sounds = LinkedHashMap<Pair<Long, Long>, WaterSlideSoundInstance>()
    private var debugTick = 0L

    fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return stopAll()
        val player = mc.player ?: return stopAll()
        val p = player.position()
        val seen = HashSet<Pair<Long, Long>>()
        var playableCurves = 0
        var curvesInRange = 0

        for (be in WaterslideCurveRenderer.clientAnchors()) {
            if (be.level !== level || be.isRemoved) continue
            for ((peer, raw) in be.anchorPeerCurvesView) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val space = SlideSpace.ofLevelAndSub(level, be.blockPos)
                val water = WaterFlowSimulation.resultFor(level, space, bc) ?: continue
                val segs = water.segments
                if (segs.isEmpty()) continue
                playableCurves++

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

                if (nearestSq > RANGE * RANGE) continue

                val a = bc.bePositions.getFirst().asLong()
                val b = bc.bePositions.getSecond().asLong()
                val key = if (a <= b) a to b else b to a
                seen.add(key)
                curvesInRange++
                val idx = (nearestFrac * segs.size).toInt().coerceIn(0, segs.size - 1)
                val speed = abs(segs[idx].speed)
                val speedFactor = (speed / SPEED_FULL_VOLUME).toDouble().coerceIn(0.0, 1.0)
                val distFactor = (1.0 - sqrt(nearestSq) / RANGE).coerceIn(0.0, 1.0)
                val volume = (MAX_VOLUME * speedFactor * distFactor).toFloat()
                val inst = sounds.getOrPut(key) {
                    WaterSlideSoundInstance(ModSounds.WATER_FLOW.value()).also {
                        // volume and position must be valid BEFORE play():
                        // SoundEngine drops volume-zero sounds entirely.
                        it.setVolume(volume)
                        it.setPosition(nearestPos)
                        CreateWaterparked.LOGGER.info(
                            "[WaterSound] play key={} pos={} distSq={} volume={}",
                            key, nearestPos, nearestSq, volume
                        )
                        mc.soundManager.play(it)
                    }
                }
                inst.setVolume(volume)
                inst.setPosition(nearestPos)
            }
        }

        val it = sounds.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in seen) {
                Minecraft.getInstance().soundManager.stop(e.value)
                it.remove()
            }
        }

        if (level.gameTime - debugTick >= 40) {
            debugTick = level.gameTime
            CreateWaterparked.LOGGER.info(
                "[WaterSound] playableCurves={} inRange={} sounds={} player={}",
                playableCurves, curvesInRange, sounds.size, p
            )
        }
    }

    fun stopAll() {
        val mc = Minecraft.getInstance()
        sounds.values.forEach { mc.soundManager.stop(it) }
        sounds.clear()
    }

    private class WaterSlideSoundInstance(
        sound: SoundEvent
    ) : AbstractSoundInstance(sound, SoundSource.BLOCKS, SoundInstance.createUnseededRandom()),
        TickableSoundInstance {
        init {
            relative = false
            looping = true
            attenuation = SoundInstance.Attenuation.NONE
            volume = 0f
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
