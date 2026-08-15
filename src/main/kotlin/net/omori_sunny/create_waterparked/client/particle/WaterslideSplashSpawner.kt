package net.omori_sunny.create_waterparked.client.particle

import net.omori_sunny.create_waterparked.client.SlideClientSession
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Spawns vanilla splash particles while sliding through water (left/right of
// the player, 1% of player velocity) and while standing/walking inside thrown
// stream water (left/right of the player, using the water's own velocity).
// The splash sound fires once when a slide enters a watered segment.
@OnlyIn(Dist.CLIENT)
object WaterslideSplashSpawner {

    private const val SIDE_OFFSET = 0.35
    private const val STREAM_CONTACT_RADIUS = 0.55

    // slide-state accumulators
    private var leftAccum = 0.0
    private var rightAccum = 0.0
    private var wasContact = false
    private var slideEntryPrimed = false
    // non-slide stream accumulators
    private var standLeftAccum = 0.0
    private var standRightAccum = 0.0
    private var standWasContact = false

    private var debugLogTick = 0L
    private var debugSpawnTick = 0L
    private var debugSpawnedTotal = 0
    private var debugFirstSpawnLogged = false

    // Called every client tick from CreateWaterparkedClient; handles players
    // who are NOT sliding but whose box touches a thrown stream polyline.
    fun tickStanding(mc: Minecraft) {
        if (SlideClientSession.isSliding()) return
        val level = mc.level ?: return
        val player = mc.player ?: return

        if (!WaterFlowSimulation.intersectsStreamBox(level, player.boundingBox, 0.45)) {
            standLeftAccum = 0.0
            standRightAccum = 0.0
            standWasContact = false
            return
        }

        // sides follow the player's horizontal view direction
        val view = player.getViewVector(1f)
        val forward = Vec3(view.x, 0.0, view.z).let {
            if (it.lengthSqr() < 1.0E-9) Vec3(0.0, 0.0, 1.0) else it.normalize()
        }
        val side = forward.cross(Vec3(0.0, 1.0, 0.0))
        val bodyCenter = Vec3(
            player.boundingBox.center.x,
            player.boundingBox.minY + 0.3,
            player.boundingBox.center.z
        )
        val box = player.boundingBox

        // find the nearest point on the thrown-water sheet for each side of
        // the player, then move it onto the collision-box surface along the
        // sheet -> probe line. Particles are born exactly on the intersection
        // between the box and the water surface.
        val leftProbe = bodyCenter.subtract(side.scale(SIDE_OFFSET))
        val rightProbe = bodyCenter.add(side.scale(SIDE_OFFSET))
        val leftContact = WaterFlowSimulation.streamContactAt(level, leftProbe, STREAM_CONTACT_RADIUS)
            ?: WaterFlowSimulation.streamContactAt(level, bodyCenter, STREAM_CONTACT_RADIUS)
            ?: return
        val rightContact = WaterFlowSimulation.streamContactAt(level, rightProbe, STREAM_CONTACT_RADIUS)
            ?: WaterFlowSimulation.streamContactAt(level, bodyCenter, STREAM_CONTACT_RADIUS)
            ?: return
        val leftPos = boxSurfacePoint(box, leftContact.pos, leftProbe)
        val rightPos = boxSurfacePoint(box, rightContact.pos, rightProbe)
        val leftVel = leftContact.velocity
        val rightVel = rightContact.velocity

        val waterSpeed = max(leftVel.length(), rightVel.length())
        if (waterSpeed <= 1.0E-6) return

        if (!standWasContact) {
            standWasContact = true
            standLeftAccum = 1.0
            standRightAccum = 1.0
        }
        val ratePerSecond = max(
            8.0,
            min(waterSpeed * 4.0 * ModClientConfig.splashDensity(), ModClientConfig.splashMaxRate())
        )
        val perTick = ratePerSecond / 20.0
        standLeftAccum += perTick
        standRightAccum += perTick

        while (standLeftAccum >= 1.0) {
            level.addParticle(
                ParticleTypes.SPLASH,
                leftPos.x, leftPos.y, leftPos.z,
                leftVel.x, leftVel.y, leftVel.z
            )
            standLeftAccum -= 1.0
        }
        while (standRightAccum >= 1.0) {
            level.addParticle(
                ParticleTypes.SPLASH,
                rightPos.x, rightPos.y, rightPos.z,
                rightVel.x, rightVel.y, rightVel.z
            )
            standRightAccum -= 1.0
        }
    }

    // The point where the segment from the water-sheet contact to the probe
    // crosses the player's AABB surface (or the contact itself when it is
    // already inside the box).
    private fun boxSurfacePoint(box: AABB, from: Vec3, toward: Vec3): Vec3 {
        if (box.contains(from)) return from
        val d = toward.subtract(from)
        val len = d.length()
        if (len < 1.0E-9) return clampToBox(box, from)
        val nx = d.x / len
        val ny = d.y / len
        val nz = d.z / len
        var tMin = 0.0
        var tMax = len
        val origin = doubleArrayOf(from.x, from.y, from.z)
        val dir = doubleArrayOf(nx, ny, nz)
        val mins = doubleArrayOf(box.minX, box.minY, box.minZ)
        val maxs = doubleArrayOf(box.maxX, box.maxY, box.maxZ)
        for (axis in 0..2) {
            if (Math.abs(dir[axis]) < 1.0E-9) {
                if (origin[axis] < mins[axis] || origin[axis] > maxs[axis]) return clampToBox(box, from)
            } else {
                val inv = 1.0 / dir[axis]
                var t1 = (mins[axis] - origin[axis]) * inv
                var t2 = (maxs[axis] - origin[axis]) * inv
                if (t1 > t2) {
                    val tmp = t1
                    t1 = t2
                    t2 = tmp
                }
                tMin = max(tMin, t1)
                tMax = min(tMax, t2)
                if (tMin > tMax) return clampToBox(box, from)
            }
        }
        val t = if (tMin >= 0.0 && tMin <= len) tMin else len
        return from.add(d.scale(t / len))
    }

    private fun clampToBox(box: AABB, p: Vec3): Vec3 = Vec3(
        p.x.coerceIn(box.minX, box.maxX),
        p.y.coerceIn(box.minY, box.maxY),
        p.z.coerceIn(box.minZ, box.maxZ)
    )

    // Called from SlideClientSession.start the instant the trajectory payload
    // arrives, before the next client tick. Pre-spawning the entry burst here
    // removes the one-tick delay between entering the water and the first
    // visible splash.
    fun onSlideStart(
        mc: Minecraft,
        bodyCenter: Vec3,
        vel: Vec3,
        speedBlocksPerSecond: Double,
        watered: Boolean
    ) {
        val level = mc.level ?: return
        val player = mc.player ?: return
        leftAccum = 0.0
        rightAccum = 0.0
        if (!watered) {
            wasContact = false
            slideEntryPrimed = false
            return
        }
        slideEntryPrimed = true
        wasContact = true

        val forward = vel.normalize()
        val right = forward.cross(Vec3(0.0, 1.0, 0.0))
        val side = if (right.lengthSqr() < 1.0E-9) Vec3(1.0, 0.0, 0.0) else right.normalize()
        val leftPos = bodyCenter.subtract(side.scale(SIDE_OFFSET))
        val rightPos = bodyCenter.add(side.scale(SIDE_OFFSET))
        val particleVel = vel.scale(0.01)
        level.addParticle(
            ParticleTypes.SPLASH,
            leftPos.x, leftPos.y, leftPos.z,
            particleVel.x, particleVel.y, particleVel.z
        )
        level.addParticle(
            ParticleTypes.SPLASH,
            rightPos.x, rightPos.y, rightPos.z,
            particleVel.x, particleVel.y, particleVel.z
        )
        debugSpawnedTotal += 2

        val volume = min(
            1.0f,
            1.3f * (0.5f + 0.4f * (speedBlocksPerSecond / 40.0).coerceIn(0.0, 1.0)).toFloat()
        )
        val pitch = 0.85f + level.random.nextFloat() * 0.25f
        player.playSound(SoundEvents.PLAYER_SPLASH, volume, pitch)
    }

    // Called from SlideClientSession.onClientTickPost AFTER the playback
    // velocity has been written for this tick.
    fun tickSliding(mc: Minecraft) {
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (!SlideClientSession.isSliding()) {
            leftAccum = 0.0
            rightAccum = 0.0
            wasContact = false
            slideEntryPrimed = false
            debugSpawnedTotal = 0
            debugFirstSpawnLogged = false
            return
        }

        val inWater = SlideClientSession.isOnWateredSegment(level)
        if (!inWater) {
            leftAccum = 0.0
            rightAccum = 0.0
            wasContact = false
            if (level.gameTime - debugLogTick >= 20) {
                debugLogTick = level.gameTime
                net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                    "[SplashSpawn] sliding but box not in water pos={}",
                    player.position()
                )
            }
            return
        }

        val vel = player.deltaMovement
        val speed = vel.length() * 20.0
        if (speed <= 0.0) {
            leftAccum = 0.0
            rightAccum = 0.0
            wasContact = false
            if (level.gameTime - debugLogTick >= 20) {
                debugLogTick = level.gameTime
                net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                    "[SplashSpawn] in water but speed=0 pos={}",
                    player.position()
                )
            }
            return
        }

        // First moving tick of a contact: one pair immediately, plus the
        // Minecraft player-splash sound for crashing into the water segment.
        if (!wasContact) {
            wasContact = true
            leftAccum = 1.0
            rightAccum = 1.0
            val volume = min(
                1.0f,
                1.3f * (0.5f + 0.4f * (speed / 40.0).coerceIn(0.0, 1.0)).toFloat()
            )
            val pitch = 0.85f + level.random.nextFloat() * 0.25f
            player.playSound(SoundEvents.PLAYER_SPLASH, volume, pitch)
        }

        // particle positions track the player's LOWER body continuously (the
        // part that actually slices the water), offset to the left/right of
        // the movement direction
        val bodyCenter = Vec3(
            player.boundingBox.center.x,
            player.boundingBox.minY + 0.3,
            player.boundingBox.center.z
        )
        val forward = vel.normalize()
        val right = forward.cross(Vec3(0.0, 1.0, 0.0))
        val side = if (right.lengthSqr() < 1.0E-9) Vec3(1.0, 0.0, 0.0) else right.normalize()
        val leftPos = bodyCenter.subtract(side.scale(SIDE_OFFSET))
        val rightPos = bodyCenter.add(side.scale(SIDE_OFFSET))

        // speed-scaled rate, multiplied by the splash density config and
        // capped by splashMaxRate (per side per second)
        val ratePerSecond = max(
            8.0,
            min(speed * 4.0 * ModClientConfig.splashDensity(), ModClientConfig.splashMaxRate())
        )
        val perTick = ratePerSecond / 20.0
        leftAccum += perTick
        rightAccum += perTick

        // 1% of the player's current velocity, exact direction
        val particleVel = vel.scale(0.01)
        while (leftAccum >= 1.0) {
            level.addParticle(
                ParticleTypes.SPLASH,
                leftPos.x, leftPos.y, leftPos.z,
                particleVel.x, particleVel.y, particleVel.z
            )
            leftAccum -= 1.0
            debugSpawnedTotal++
        }
        while (rightAccum >= 1.0) {
            level.addParticle(
                ParticleTypes.SPLASH,
                rightPos.x, rightPos.y, rightPos.z,
                particleVel.x, particleVel.y, particleVel.z
            )
            rightAccum -= 1.0
            debugSpawnedTotal++
        }

        if (!debugFirstSpawnLogged && debugSpawnedTotal > 0) {
            debugFirstSpawnLogged = true
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "[SplashSpawn] first actual spawn total={} speed={} pos={}",
                debugSpawnedTotal, speed, leftPos
            )
        }
        if (level.gameTime - debugSpawnTick >= 20) {
            debugSpawnTick = level.gameTime
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "[SplashSpawn] spawning total={} speed={} rate={} left={} right={} vel={}",
                debugSpawnedTotal, speed, ratePerSecond, leftPos, rightPos, particleVel
            )
        }
    }
}
