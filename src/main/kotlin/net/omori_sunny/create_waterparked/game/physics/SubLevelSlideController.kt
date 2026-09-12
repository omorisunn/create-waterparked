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

// Sable sub-levels ride the slides; the physics scene pose is derived at runtime
object SubLevelSlideController {

    private const val ENTRY_SCAN_TICKS = 5L
    private const val ENTRY_RANGE_SQ = 2.5 * 2.5

    private class SubSession(
        val subId: UUID,
        var trajectory: SlideTrajectory,
        val access: SlideSpaceAccess,
        val orientationOffsetWorld: Quaterniond,
        val worldToScene: Quaterniond,
        val scenePos0: Vector3d,
        val worldPath0: Vec3
    ) {
        var elapsed = 0.0
        var skippedTicks = 0
    }

    private val sessions = HashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, HashMap<UUID, SubSession>>()
    private val nextScan = HashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long>()

    @JvmStatic
    fun tick(level: ServerLevel) {
        if (!ModConfig.subLevelSlideRiding()) return
        val container = SubLevelContainer.getContainer(level) ?: return
        val time = level.gameTime
        val levelSessions = sessions.getOrPut(level.dimension()) { HashMap() }

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
                    CreateWaterparked.LOGGER.debug("Sub-level slide abort {} (unresolvable)", session.subId)
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
            if (isRivetSub(sub)) continue
            val box = worldBoxOf(sub)
            val center = box.center
            val vel = Vec3(
                sub.latestLinearVelocity.x(), sub.latestLinearVelocity.y(), sub.latestLinearVelocity.z()
            )
            var best: PlayerSlideController.SlideMouth? = null
            var bestD = Double.MAX_VALUE
            for (mouth in mouths) {
                val mouthSub = (mouth.access as? SubSlideSpaceAccess)?.sub
                if (mouthSub?.uniqueId == sub.uniqueId) continue
                val px = mouth.worldPos.x.coerceIn(box.minX, box.maxX)
                val py = mouth.worldPos.y.coerceIn(box.minY, box.maxY)
                val pz = mouth.worldPos.z.coerceIn(box.minZ, box.maxZ)
                val dx = px - mouth.worldPos.x
                val dy = py - mouth.worldPos.y
                val dz = pz - mouth.worldPos.z
                val insideBox = dx * dx + dy * dy + dz * dz < 1.0E-8
                val along = dx * mouth.worldTangent.x + dy * mouth.worldTangent.y + dz * mouth.worldTangent.z
                val rx = dx - along * mouth.worldTangent.x
                val ry = dy - along * mouth.worldTangent.y
                val rz = dz - along * mouth.worldTangent.z
                val radialSq = rx * rx + ry * ry + rz * rz
                val rr = mouth.radius.toDouble()
                if (insideBox) {
                    if (!hasBlocksAtMouth(sub, mouth)) continue
                } else if (!(along > 0.1 && radialSq < rr * rr)) {
                    continue
                }
                val d = box.distanceToSqr(mouth.worldPos)
                if (d >= bestD) continue
                best = mouth
                bestD = d
            }
            val mouth = best ?: continue
            tryStart(level, levelSessions, sub, mouth, center, vel)
        }
    }

    // blocks live at plot coordinates, so the mouth is sampled in the local frame
    private fun hasBlocksAtMouth(sub: ServerSubLevel, mouth: PlayerSlideController.SlideMouth): Boolean {
        val access = mouth.access as? SubSlideSpaceAccess ?: return true
        val local = access.worldToLocal(mouth.worldPos)
        val r = (mouth.radius.toDouble() + 0.6).coerceAtLeast(1.0)
        val cx = kotlin.math.floor(local.x).toInt()
        val cy = kotlin.math.floor(local.y).toInt()
        val cz = kotlin.math.floor(local.z).toInt()
        val span = kotlin.math.ceil(r).toInt().coerceAtMost(3)
        for (dx in -span..span) {
            for (dy in -span..span) {
                for (dz in -span..span) {
                    val state = access.getBlockState(net.minecraft.core.BlockPos(cx + dx, cy + dy, cz + dz))
                    if (!state.isAir) return true
                }
            }
        }
        return false
    }

    // rivet sub-levels are wall decorations and never ride the slide
    private fun isRivetSub(sub: ServerSubLevel): Boolean =
        net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetSpawner
            .rivetSubIds.contains(sub.uniqueId)

    private fun tryStart(
        level: ServerLevel,
        levelSessions: HashMap<UUID, SubSession>,
        sub: ServerSubLevel,
        mouth: PlayerSlideController.SlideMouth,
        centerWorld: Vec3,
        velWorld: Vec3
    ) {
        if (isRivetSub(sub)) return
        val access = mouth.access
        val startLocal = access.worldToLocal(centerWorld)
        val startVel = access.worldNormalToLocal(velWorld)
        val trajectory = PhysicsSlideTrajectoryBuilder.build(
            access, mouth.curve, mouth.towardSecond, null,
            startLocal, startVel, 0.9, 0.9, poseRad = 0.45
        ) ?: return

        val pipeline = SubLevelPhysicsSystem.get(level)?.pipeline ?: return
        val scenePose = pipeline.readPose(sub, Pose3d())
        val scenePos0 = Vector3d(scenePose.position())
        val qScene0 = Quaterniond(scenePose.orientation())
        val qWorld0 = Quaterniond(sub.logicalPose().orientation())
        val worldToScene = Quaterniond(qWorld0).mul(Quaterniond(qScene0).invert())
        val orientationOffsetWorld = Quaterniond(tangentQuat(mouth.worldTangent)).invert().mul(qWorld0)

        val first = trajectory.samples.first()
        levelSessions[sub.uniqueId] = SubSession(
            sub.uniqueId, trajectory, access,
            orientationOffsetWorld, worldToScene, scenePos0,
            access.toWorld(first.position)
        )
        CreateWaterparked.LOGGER.debug(
            "Sub-level slide start {} scenePos0={} worldPath0={} samples={}",
            sub.uniqueId, scenePos0, access.toWorld(first.position), trajectory.samples.size
        )
    }

    private fun ride(level: ServerLevel, sub: ServerSubLevel, session: SubSession, levelSessions: HashMap<UUID, SubSession>) {
        session.elapsed += 1.0 / 20.0
        if (session.elapsed >= session.trajectory.duration) {
            levelSessions.remove(session.subId)
            CreateWaterparked.LOGGER.debug("Sub-level slide end {}", session.subId)
            return
        }
        val at = session.trajectory.sampleAt(session.elapsed)
        val pathWorld = session.access.toWorld(at.sample.position)
        val tangent = session.access.toWorldNormal(at.sample.tangent).normalize()

        val orientationWorld = tangentQuat(tangent).mul(session.orientationOffsetWorld)
        val worldDelta = pathWorld.subtract(session.worldPath0)
        val sceneDelta = Quaterniond(session.worldToScene)
            .transform(JOMLConversion.toJOML(worldDelta), Vector3d())
        val scenePos = Vector3d(session.scenePos0).add(sceneDelta)
        val sceneRot = Quaterniond(session.worldToScene).mul(orientationWorld)

        val handle = RigidBodyHandle.of(sub) ?: return
        if (!handle.isValid()) return
        handle.teleport(scenePos, sceneRot)

        val target = Quaterniond(session.worldToScene)
            .transform(JOMLConversion.toJOML(tangent.scale(at.sample.speed)), Vector3d())
        val current = handle.getLinearVelocity(Vector3d())
        val dLinear = target.sub(current, Vector3d())
        val dAngular = handle.getAngularVelocity(Vector3d()).mul(-1.0, Vector3d())
        handle.addLinearAndAngularVelocity(dLinear, dAngular)
    }

    private fun tangentQuat(tangent: Vec3): Quaterniond {
        val horiz = sqrt(tangent.x * tangent.x + tangent.z * tangent.z)
        val yaw = atan2(-tangent.x, tangent.z).toDouble()
        val pitch = atan2(-tangent.y, horiz).toDouble()
        return Quaterniond().rotationY(yaw).mul(Quaterniond().rotationX(pitch))
    }
}
