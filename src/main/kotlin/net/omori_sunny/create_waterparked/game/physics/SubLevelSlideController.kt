package net.omori_sunny.create_waterparked.game.physics

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.companion.math.Pose3d
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

// Sub-levels themselves ride the slides (NOT slides inside sub-levels): a
// whole Sable sub-level captured at a tube mouth follows the precomputed
// trajectory kinematically and is released to Sable physics at the exit.
//
// Frame strategy (fully runtime-derived, no assumptions): the physics scene
// frame is opaque, so at entry we read the RAW body pose with
// PhysicsPipeline.readPose - the same native space teleport writes - and pair
// it with the world orientation from logicalPose. The constant rotation
// between the two frames is then used to convert world-space path deltas and
// orientations into scene space every tick.
object SubLevelSlideController {

    private const val ENTRY_SCAN_TICKS = 5L
    private const val ENTRY_RANGE_SQ = 2.5 * 2.5

    private class SubSession(
        val subId: UUID,
        var trajectory: SlideTrajectory,
        val access: SlideSpaceAccess,
        // world-space orientation offset: orientationWorld(t) = tangentQuat(t) * it
        val orientationOffsetWorld: Quaterniond,
        // constant rotation converting WORLD vectors into the physics scene frame
        val worldToScene: Quaterniond,
        // raw scene-space body position and world path point at entry
        val scenePos0: Vector3d,
        val worldPath0: Vec3
    ) {
        var elapsed = 0.0
        // safety: drop sessions whose sub stays unresolvable
        var skippedTicks = 0
    }

    // sessions per dimension: the tick loop visits every level (including
    // Ponder), and a global map would get wiped by levels that do not contain
    // the sub-level
    private val sessions = HashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, HashMap<UUID, SubSession>>()
    private val nextScan = HashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long>()

    @JvmStatic
    fun tick(level: ServerLevel) {
        if (!ModConfig.subLevelSlideRiding()) return
        val container = SubLevelContainer.getContainer(level) ?: return
        val time = level.gameTime
        val levelSessions = sessions.getOrPut(level.dimension()) { HashMap() }

        // drop sessions whose sub-level vanished from THIS level
        levelSessions.keys.retainAll { id -> container.getSubLevel(id) is ServerSubLevel }

        val lastScan = nextScan[level.dimension()] ?: 0L
        if (time >= lastScan) {
            nextScan[level.dimension()] = time + ENTRY_SCAN_TICKS
            scanEntries(level, container, levelSessions)
        }
        for (session in levelSessions.values.toList()) {
            val sub = container.getSubLevel(session.subId) as? ServerSubLevel
            if (sub == null) {
                if (++session.skippedTicks > 40) {
                    levelSessions.remove(session.subId)
                    CreateWaterparked.LOGGER.info("Sub-level slide abort {} (unresolvable)", session.subId)
                }
                continue
            }
            session.skippedTicks = 0
            ride(level, sub, session, levelSessions)
        }
    }

    private fun worldBoxOf(sub: ServerSubLevel): net.minecraft.world.phys.AABB {
        sub.forceUpdateGlobalBounds()
        val bb = (sub as net.omori_sunny.create_waterparked.mixin.SubLevelBoundsAccessor)
            .`waterparked$getGlobalBounds`()
        return net.minecraft.world.phys.AABB(
            bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ()
        )
    }

    private fun scanEntries(
        level: ServerLevel,
        container: dev.ryanhcode.sable.api.sublevel.SubLevelContainer,
        levelSessions: HashMap<UUID, SubSession>
    ) {
        val mouths = PlayerSlideController.allSlideMouths(level)
        if (mouths.isEmpty()) return
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            if (levelSessions.containsKey(sub.uniqueId)) continue
            // shape-aware world bounds of the whole structure
            val box = worldBoxOf(sub)
            val center = box.center
            val vel = Vec3(
                sub.latestLinearVelocity.x(), sub.latestLinearVelocity.y(), sub.latestLinearVelocity.z()
            )
            var best: PlayerSlideController.SlideMouth? = null
            var bestD = ENTRY_RANGE_SQ
            for (mouth in mouths) {
                // never capture a sub-level through a mouth of its own slide
                val mouthSub = (mouth.access as? SubSlideSpaceAccess)?.sub
                if (mouthSub?.uniqueId == sub.uniqueId) continue
                // EVERY sub-level whose structure reaches the mouth enters,
                // regardless of motion - gravity and the entry boost start it
                val d = box.distanceToSqr(mouth.worldPos)
                if (d >= bestD) continue
                best = mouth
                bestD = d
            }
            val mouth = best ?: continue
            tryStart(level, levelSessions, sub, mouth, center, vel)
        }
    }

    private fun tryStart(
        level: ServerLevel,
        levelSessions: HashMap<UUID, SubSession>,
        sub: ServerSubLevel,
        mouth: PlayerSlideController.SlideMouth,
        centerWorld: Vec3,
        velWorld: Vec3
    ) {
        val access = mouth.access
        val startLocal = access.worldToLocal(centerWorld)
        val startVel = access.worldNormalToLocal(velWorld)
        val trajectory = PhysicsSlideTrajectoryBuilder.build(
            access, mouth.curve, mouth.towardSecond, null,
            startLocal, startVel, 0.9, 0.9, poseRad = 0.45
        ) ?: return

        // read the RAW scene pose (same native frame teleport writes)
        val pipeline = SubLevelPhysicsSystem.get(level)?.pipeline ?: return
        val scenePose = pipeline.readPose(sub, Pose3d())
        val scenePos0 = Vector3d(scenePose.position())
        val qScene0 = Quaterniond(scenePose.orientation())
        val qWorld0 = Quaterniond(sub.logicalPose().orientation())
        // world vector -> scene vector rotation: R = qWorld0 * qScene0^-1
        val worldToScene = Quaterniond(qWorld0).mul(Quaterniond(qScene0).invert())
        // world orientation over the ride, anchored so that q(0) = qWorld0
        val orientationOffsetWorld = Quaterniond(tangentQuat(mouth.worldTangent)).invert().mul(qWorld0)

        val first = trajectory.samples.first()
        levelSessions[sub.uniqueId] = SubSession(
            sub.uniqueId, trajectory, access,
            orientationOffsetWorld, worldToScene, scenePos0,
            access.toWorld(first.position)
        )
        CreateWaterparked.LOGGER.info(
            "Sub-level slide start {} scenePos0={} worldPath0={} samples={}",
            sub.uniqueId, scenePos0, access.toWorld(first.position), trajectory.samples.size
        )
    }

    private fun ride(level: ServerLevel, sub: ServerSubLevel, session: SubSession, levelSessions: HashMap<UUID, SubSession>) {
        session.elapsed += 1.0 / 20.0
        if (session.elapsed >= session.trajectory.duration) {
            levelSessions.remove(session.subId)
            CreateWaterparked.LOGGER.info("Sub-level slide end {}", session.subId)
            // physics keeps the last set velocity, the structure flies off
            return
        }
        val at = session.trajectory.sampleAt(session.elapsed)
        val pathWorld = session.access.toWorld(at.sample.position)
        val tangent = session.access.toWorldNormal(at.sample.tangent).normalize()

        // world -> scene by constant rotation, position by entry-anchored delta
        val orientationWorld = tangentQuat(tangent).mul(session.orientationOffsetWorld)
        val worldDelta = pathWorld.subtract(session.worldPath0)
        val sceneDelta = Quaterniond(session.worldToScene)
            .transform(JOMLConversion.toJOML(worldDelta), Vector3d())
        val scenePos = Vector3d(session.scenePos0).add(sceneDelta)
        val sceneRot = Quaterniond(session.worldToScene).mul(orientationWorld)

        val handle = RigidBodyHandle.of(sub) ?: return
        if (!handle.isValid()) return
        handle.teleport(scenePos, sceneRot)

        // keep the physics body coherent with the kinematic ride: velocity on
        // the tangent at the sample speed, no spin (scene frame)
        val target = Quaterniond(session.worldToScene)
            .transform(JOMLConversion.toJOML(tangent.scale(at.sample.speed)), Vector3d())
        val current = handle.getLinearVelocity(Vector3d())
        val dLinear = target.sub(current, Vector3d())
        val dAngular = handle.getAngularVelocity(Vector3d()).mul(-1.0, Vector3d())
        handle.addLinearAndAngularVelocity(dLinear, dAngular)
    }

    // entity-convention yaw/pitch frame of the travel direction
    private fun tangentQuat(tangent: Vec3): Quaterniond {
        val horiz = sqrt(tangent.x * tangent.x + tangent.z * tangent.z)
        val yaw = atan2(-tangent.x, tangent.z).toDouble()
        val pitch = atan2(-tangent.y, horiz).toDouble()
        return Quaterniond().rotationY(yaw).mul(Quaterniond().rotationX(pitch))
    }
}
