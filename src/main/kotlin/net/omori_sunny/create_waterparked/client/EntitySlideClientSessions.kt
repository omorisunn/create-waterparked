package net.omori_sunny.create_waterparked.client

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.sublevel.ClientSubLevel
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.game.physics.SlideEndReason
import net.omori_sunny.create_waterparked.game.physics.SlideTrajectory
import net.omori_sunny.create_waterparked.network.SlideEntityTrajectoryPayload
import org.joml.Vector3d
import java.util.UUID

// Client-side playback state for non-player entity rides. The server stays
// the physics authority (per-tick setPos + move packets); these sessions add
// render-frame interpolation through the entity render dispatcher mixin.
@OnlyIn(Dist.CLIENT)
object EntitySlideClientSessions {

    // matches the server viewer range: beyond it the server pauses the ride
    // and the client falls back to the plain packet position
    const val VIEWER_RANGE_SQ = 48.0 * 48.0

    class Active(
        val sessionId: Long,
        val entityId: Int,
        var trajectory: SlideTrajectory,
        var subLevelId: UUID?,
        var contraptionEntityId: Int?,
        var startGameTime: Long,
        // applied render-frame time correction, lerped toward the server clock
        var timeOffsetTicks: Double,
        var targetOffsetTicks: Double
    )

    private val sessions = HashMap<Int, Active>()
    private var lastFrameTime = 0L

    @JvmStatic
    fun start(payload: SlideEntityTrajectoryPayload) {
        if (payload.samples.isEmpty()) return
        val level = Minecraft.getInstance().level ?: return
        val origin = plotOffset(level, payload.subLevelId)
        val active = Active(
            payload.sessionId,
            payload.entityId,
            SlideTrajectory(payload.samples.map { it.toSample(origin) }, SlideEndReason.EXITED, true),
            payload.subLevelId,
            payload.contraptionEntityId,
            payload.startGameTime,
            0.0,
            0.0
        )
        sessions[payload.entityId] = active
    }

    // trajectory replaced after a space handoff; restart the clock
    @JvmStatic
    fun segment(payload: SlideEntityTrajectoryPayload) {
        val active = sessions[payload.entityId] ?: return start(payload)
        if (active.sessionId != payload.sessionId) return start(payload)
        val level = Minecraft.getInstance().level ?: return
        val origin = plotOffset(level, payload.subLevelId)
        active.trajectory = SlideTrajectory(payload.samples.map { it.toSample(origin) }, SlideEndReason.EXITED, true)
        active.subLevelId = payload.subLevelId
        active.contraptionEntityId = payload.contraptionEntityId
        active.startGameTime = payload.startGameTime
        active.timeOffsetTicks = 0.0
        active.targetOffsetTicks = 0.0
    }

    @JvmStatic
    fun sync(sessionId: Long, elapsedTicks: Int) {
        val active = sessions.values.firstOrNull { it.sessionId == sessionId } ?: return
        val level = Minecraft.getInstance().level ?: return
        val drift = (level.gameTime - active.startGameTime) - elapsedTicks
        active.targetOffsetTicks = if (kotlin.math.abs(drift) > 5) -drift.toDouble() else 0.0
    }

    @JvmStatic
    fun end(sessionId: Long) {
        sessions.entries.removeIf { it.value.sessionId == sessionId }
    }

    @JvmStatic
    fun clear() = sessions.clear()

    @JvmStatic
    fun active(): Map<Int, Active> = sessions

    // smooth the time correction toward the server clock, per render frame
    private fun advanceClock(level: Level, active: Active) {
        if (lastFrameTime != level.gameTime) {
            lastFrameTime = level.gameTime
            sessions.values.forEach {
                val diff = it.targetOffsetTicks - it.timeOffsetTicks
                if (kotlin.math.abs(diff) > 0.01) it.timeOffsetTicks += diff * 0.2
            }
        }
    }

    // interpolated pose for the entity at the current render frame; null when
    // the entity has no active ride or is too far from the viewer to bother
    fun poseFor(entity: Entity, partialTick: Float): Pose? {
        val active = sessions[entity.id] ?: return null
        val player = Minecraft.getInstance().player
        if (player != null && player.distanceToSqr(entity) > VIEWER_RANGE_SQ) return null
        val level = entity.level()
        advanceClock(level, active)
        val elapsed = (level.gameTime - active.startGameTime + active.timeOffsetTicks +
            partialTick.toDouble()) / 20.0
        if (elapsed < 0.0) return null
        val clamped = elapsed.coerceAtMost(active.trajectory.duration)
        val at = active.trajectory.sampleAt(clamped)
        return Pose(
            toWorldPos(level, active, at.sample.position),
            toWorldNormal(level, active, at.sample.tangent).normalize()
        )
    }

    data class Pose(val position: Vec3, val tangent: Vec3)

    private fun plotOffset(level: Level?, subLevelId: UUID?): Vec3? {
        if (level == null || subLevelId == null) return null
        val container = SubLevelContainer.getContainer(level) ?: return null
        val sub = container.getSubLevel(subLevelId) as? ClientSubLevel ?: return null
        return Vec3.atLowerCornerOf(sub.getPlot().getCenterBlock())
    }

    private fun toWorldPos(level: Level, session: Active, local: Vec3): Vec3 {
        val sub = subLevel(level, session)
        if (sub != null) {
            val out = sub.logicalPose().transformPosition(JOMLConversion.toJOML(local), Vector3d())
            return JOMLConversion.toMojang(out)
        }
        val cp = contraption(level, session)
        if (cp != null) {
            return cp.toGlobalVector(local, 1.0f)
        }
        return local
    }

    private fun toWorldNormal(level: Level, session: Active, local: Vec3): Vec3 {
        val sub = subLevel(level, session)
        if (sub != null) {
            val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(local), Vector3d())
            return JOMLConversion.toMojang(out).normalize()
        }
        val cp = contraption(level, session)
        if (cp != null) {
            val p = cp.toGlobalVector(local, 1.0f)
            val q = cp.toGlobalVector(Vec3.ZERO, 1.0f)
            val n = p.subtract(q).normalize()
            return if (n.lengthSqr() < 1.0E-12) local.normalize() else n
        }
        return local.normalize()
    }

    private fun subLevel(level: Level, session: Active): ClientSubLevel? {
        if (session.subLevelId == null) return null
        val container = SubLevelContainer.getContainer(level) ?: return null
        return container.getSubLevel(session.subLevelId) as? ClientSubLevel
    }

    private fun contraption(level: Level, session: Active): com.simibubi.create.content.contraptions.AbstractContraptionEntity? {
        val id = session.contraptionEntityId ?: return null
        return level.getEntity(id) as? com.simibubi.create.content.contraptions.AbstractContraptionEntity
    }

    // sample positions are entity box centres; the render anchor needs the feet
    fun feetOffsetY(entity: Entity): Double = entity.bbHeight / 2.0

    fun yawOf(tangent: Vec3): Float =
        Math.toDegrees(kotlin.math.atan2(-tangent.x, tangent.z)).toFloat()

    fun pitchOf(tangent: Vec3): Float =
        Math.toDegrees(
            kotlin.math.atan2(-tangent.y, kotlin.math.sqrt(tangent.x * tangent.x + tangent.z * tangent.z))
        ).toFloat()
}
