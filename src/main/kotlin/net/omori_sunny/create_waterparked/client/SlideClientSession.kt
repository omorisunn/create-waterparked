package net.omori_sunny.create_waterparked.client

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.game.physics.SlideEndReason
import net.omori_sunny.create_waterparked.game.physics.SlideTrajectory
import net.omori_sunny.create_waterparked.game.physics.SLIDE_WALL_THICKNESS
import net.omori_sunny.create_waterparked.network.SlideCancelPayload
import net.omori_sunny.create_waterparked.network.SlideEndPayload
import net.omori_sunny.create_waterparked.network.SlideSyncPayload
import net.omori_sunny.create_waterparked.network.SlideTrajectoryPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

// Client-side slide playback.
@OnlyIn(Dist.CLIENT)
object SlideClientSession {

    private const val SIT_HEIGHT = 0.7
    private const val MAX_CAMERA_YAW_STEP = 6f
    private const val MAX_CAMERA_PITCH_STEP = 4f
    private const val MAX_CAMERA_ROLL_STEP = 4f

    data class CameraState(val pos: Vec3, val yaw: Float, val pitch: Float, val roll: Float)

    private class Active(
        val sessionId: Long,
        val trajectory: SlideTrajectory,
        val subLevelId: UUID?,
        val swimmingPose: Boolean,
        var startTick: Long
    ) {
        var hintTick = 0L
        var wasShiftDown = false
        var lastCancelSentTick = 0L
        var targetOffsetTicks = 0.0
        var timeOffsetTicks = 0.0
        var startTrackYaw = 0f
        var startTrackPitch = 0f
        var freeLookYaw = 0f
        var freeLookPitch = 0f
        var lastEntityYaw = 0f
        var lastEntityPitch = 0f
        var lastCameraYaw = 0f
        var lastCameraPitch = 0f
        var lastTrackYaw: Float? = null
        var lastRoll: Float? = null
        var lastOffset: Vec3? = null
        var lastSmoothedTrackDelta: Float? = null
        var lastSmoothedTrackPitch: Float? = null
        var lastSmoothedRoll: Float? = null
        var lastFrameYaw: Float? = null
        var lastFramePitch: Float? = null
        var lastAppliedPos: Vec3? = null

        fun subLevel(level: Level): dev.ryanhcode.sable.sublevel.ClientSubLevel? {
            if (subLevelId == null) return null
            val container = SubLevelContainer.getContainer(level) ?: return null
            return container.getSubLevel(subLevelId) as? dev.ryanhcode.sable.sublevel.ClientSubLevel
        }
    }

    private var active: Active? = null

    // Per-frame camera state, lerped by partialTick like Sable.
    @JvmStatic
    fun cameraState(partialTick: Float): CameraState? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        val session = active ?: return null
        val mouseDyaw = Mth.wrapDegrees(player.getYRot() - session.lastEntityYaw)
        val mouseDpitch = player.getXRot() - session.lastEntityPitch
        session.freeLookYaw += mouseDyaw
        session.freeLookPitch += mouseDpitch
        session.lastEntityYaw = player.getYRot()
        session.lastEntityPitch = player.getXRot()
        val nowTime = (level.gameTime - session.startTick + session.timeOffsetTicks + partialTick) / 20.0
        val prevTime = max(0.0, nowTime - 1.0 / 20.0)
        val atNow = session.trajectory.sampleAt(nowTime)
        val atPrev = session.trajectory.sampleAt(prevTime)
        val worldPos = toWorldPos(level, session, atNow.sample.position)
        val basePos = if (session.swimmingPose) worldPos
        else worldPos.subtract(0.0, SIT_HEIGHT, 0.0)
        val worldTanNow = toWorldNormal(level, session, atNow.sample.tangent)
        val worldTanPrev = toWorldNormal(level, session, atPrev.sample.tangent)
        val rawOffset = worldTanNow.subtract(worldTanPrev).scale(0.8)
        val offset = if (session.lastOffset == null) rawOffset
        else session.lastOffset!!.lerp(rawOffset, 0.25)
        session.lastOffset = offset
        val rawPos = basePos.add(0.0, player.getEyeHeight().toDouble(), 0.0).add(offset)
        val tubeCenter = toWorldPos(level, session, atNow.sample.tubeCenter)
        val inner = max(0.1, (atNow.sample.radius - SLIDE_WALL_THICKNESS).toDouble())
        val maxDist = inner - 0.04
        val rel = rawPos.subtract(tubeCenter)
        val axial = rel.dot(worldTanNow)
        val radial = rel.subtract(worldTanNow.scale(axial))
        val radialDist = radial.length()
        val pos = if (atNow.sample.inTube && radialDist > maxDist && radialDist > 1.0E-9) {
            val clamped = tubeCenter.add(radial.scale(maxDist / radialDist))
                .add(worldTanNow.scale(axial))
            val prevPos = session.lastAppliedPos
            if (prevPos == null) clamped else prevPos.lerp(clamped, 0.25)
        } else rawPos
        val rawYaw = yawOf(worldTanNow)
        val trackYaw = if (session.lastTrackYaw == null) rawYaw
        else session.lastTrackYaw!! + Mth.wrapDegrees(rawYaw - session.lastTrackYaw!!)
        session.lastTrackYaw = trackYaw
        val targetTrackDelta = trackYaw - session.startTrackYaw
        val prevTrackDelta = session.lastSmoothedTrackDelta
        val trackDelta = if (prevTrackDelta == null) targetTrackDelta
        else prevTrackDelta + Mth.wrapDegrees(targetTrackDelta - prevTrackDelta)
            .coerceIn(-MAX_CAMERA_YAW_STEP, MAX_CAMERA_YAW_STEP)
        session.lastSmoothedTrackDelta = trackDelta
        val yaw = session.freeLookYaw + trackDelta

        val targetTrackPitch = pitchOf(worldTanNow) - session.startTrackPitch
        val prevTrackPitch = session.lastSmoothedTrackPitch
        val trackPitch = if (prevTrackPitch == null) targetTrackPitch
        else prevTrackPitch + (targetTrackPitch - prevTrackPitch)
            .coerceIn(-MAX_CAMERA_PITCH_STEP, MAX_CAMERA_PITCH_STEP)
        session.lastSmoothedTrackPitch = trackPitch
        val pitch = (session.freeLookPitch + trackPitch).coerceIn(-90f, 90f)

        val atNext = session.trajectory.sampleAt(nowTime + 1.0 / 20.0)
        val vPrev = worldTanPrev.scale(atPrev.sample.speed)
        val vNext = toWorldNormal(level, session, atNext.sample.tangent).scale(atNext.sample.speed)
        val felt = vNext.subtract(vPrev).scale(10.0).add(0.0, 32.0, 0.0)
        val right = worldTanNow.cross(Vec3(0.0, 1.0, 0.0))
        var roll: Float
        if (right.lengthSqr() < 1.0E-9) {
            val prev = session.lastRoll ?: 0f
            val eased = prev * 0.8f
            session.lastRoll = eased
            val targetRoll = eased.coerceIn(-30f, 30f)
            val prevSmoothedRoll = session.lastSmoothedRoll
            val smoothedRoll = if (prevSmoothedRoll == null) targetRoll
            else prevSmoothedRoll + Mth.wrapDegrees(targetRoll - prevSmoothedRoll)
                .coerceIn(-MAX_CAMERA_ROLL_STEP, MAX_CAMERA_ROLL_STEP)
            session.lastSmoothedRoll = smoothedRoll
            roll = smoothedRoll.coerceIn(-30f, 30f)
        } else {
            val rightN = right.normalize()
            val upCam = rightN.cross(worldTanNow).normalize()
            val perp = felt.subtract(worldTanNow.scale(felt.dot(worldTanNow)))
            val rawRoll = Math.toDegrees(Math.atan2(perp.dot(rightN), perp.dot(upCam))).toFloat()
            val prevRoll = session.lastRoll
            val unwrapped = if (prevRoll == null) rawRoll
            else prevRoll + Mth.wrapDegrees(rawRoll - prevRoll)
            session.lastRoll = unwrapped
            val targetRoll = unwrapped.coerceIn(-30f, 30f)
            val prevSmoothedRoll = session.lastSmoothedRoll
            val smoothedRoll = if (prevSmoothedRoll == null) targetRoll
            else prevSmoothedRoll + Mth.wrapDegrees(targetRoll - prevSmoothedRoll)
                .coerceIn(-MAX_CAMERA_ROLL_STEP, MAX_CAMERA_ROLL_STEP)
            session.lastSmoothedRoll = smoothedRoll
            roll = smoothedRoll.coerceIn(-30f, 30f)
        }
        val smoothing = ModClientConfig.cameraSmoothing()
        val k = 1f - smoothing
        val smoothYaw: Float
        val smoothPitch: Float
        val smoothRoll: Float
        val smoothPos: Vec3
        if (smoothing <= 0f) {
            smoothYaw = yaw
            smoothPitch = pitch
            smoothRoll = roll
            smoothPos = pos
        } else {
            smoothYaw = session.lastCameraYaw + Mth.wrapDegrees(yaw - session.lastCameraYaw) * k
            smoothPitch = session.lastCameraPitch + (pitch - session.lastCameraPitch) * k
            smoothRoll = session.lastSmoothedRoll?.let { it + Mth.wrapDegrees(roll - it) * k } ?: roll
            smoothPos = session.lastAppliedPos?.lerp(pos, k.toDouble()) ?: pos
        }
        session.lastCameraYaw = smoothYaw
        session.lastCameraPitch = smoothPitch
        session.lastSmoothedRoll = smoothRoll
        session.lastAppliedPos = smoothPos
        val lastYaw = session.lastFrameYaw
        val lastPitch = session.lastFramePitch
        if (lastYaw != null && lastPitch != null) {
            val dy = Mth.wrapDegrees(smoothYaw - lastYaw)
            val dp = smoothPitch - lastPitch
            if (abs(dy) > 8f || abs(dp) > 6f) {
                CreateWaterparked.LOGGER.debug(
                    "CamJump dy={} dp={} y={} p={} r={} t={} pt={}",
                    dy, dp, smoothYaw, smoothPitch, smoothRoll, level.gameTime, partialTick
                )
            }
        }
        session.lastFrameYaw = smoothYaw
        session.lastFramePitch = smoothPitch
        SlideSableOrientation.update(player, smoothRoll, worldTanNow)
        return CameraState(smoothPos, smoothYaw, smoothPitch, 0f)
    }

    @JvmStatic
    fun start(payload: SlideTrajectoryPayload) {
        SlideSableOrientation.clearAll()
        val session = Active(
            payload.sessionId,
            SlideTrajectory(payload.samples.map { it.toSample() }, SlideEndReason.EXITED, true),
            payload.subLevelId,
            payload.swimmingPose,
            payload.startTick
        )
        active = session
        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player
        if (level != null && payload.samples.isNotEmpty()) {
            val first = payload.samples.first().toSample()
            val tan = toWorldNormal(level, session, first.tangent)
            session.startTrackYaw = yawOf(tan)
            session.startTrackPitch = pitchOf(tan)
            if (player != null) {
                session.freeLookYaw = player.getYRot()
                session.freeLookPitch = player.getXRot()
                session.lastEntityYaw = player.getYRot()
                session.lastEntityPitch = player.getXRot()
            } else {
                session.lastEntityYaw = yawOf(tan)
                session.lastEntityPitch = pitchOf(tan)
            }
        }
    }

    @JvmStatic
    fun end(payload: SlideEndPayload) {
        val session = active ?: return
        if (session.sessionId != payload.sessionId) return
        CreateWaterparked.LOGGER.debug(
            "Slide end {} reason {}", payload.sessionId, payload.reason
        )
        active = null
        val player = Minecraft.getInstance().player ?: return
        SlideSableOrientation.clear(player)
        player.setPos(Vec3(payload.x.toDouble(), payload.y.toDouble(), payload.z.toDouble()))
        player.setDeltaMovement(Vec3(payload.vx.toDouble(), payload.vy.toDouble(), payload.vz.toDouble()))
        player.setYRot(session.lastCameraYaw)
        player.setXRot(session.lastCameraPitch)
        player.setYHeadRot(session.lastCameraYaw)
        player.yRotO = session.lastCameraYaw
        player.xRotO = session.lastCameraPitch
        player.yHeadRotO = session.lastCameraYaw
        player.setNoGravity(false)
        player.setPose(Pose.STANDING)
        player.refreshDimensions()
    }

    @JvmStatic
    fun sync(sessionId: Long, elapsedTicks: Int) {
        val session = active ?: return
        if (session.sessionId != sessionId) return
        val level = Minecraft.getInstance().level ?: return
        val drift = (level.gameTime - session.startTick) - elapsedTicks
        session.targetOffsetTicks = if (kotlin.math.abs(drift) > 5) -drift.toDouble() else 0.0
    }

    // Lock input before vanilla movement.
    @JvmStatic
    fun onClientTickPre(event: ClientTickEvent.Pre) {
        val player = Minecraft.getInstance().player ?: return
        val session = active ?: return
        player.input.forwardImpulse = 0f
        player.input.leftImpulse = 0f
        player.input.up = false
        player.input.down = false
        player.input.left = false
        player.input.right = false
        player.input.jumping = false
        player.setNoGravity(true)
        player.setDeltaMovement(Vec3.ZERO)
        player.fallDistance = 0f

        val shift = Minecraft.getInstance().options.keyShift.isDown
        if (shift && (!session.wasShiftDown || player.level().gameTime - session.lastCancelSentTick >= 10)) {
            session.lastCancelSentTick = player.level().gameTime
            CreateWaterparked.LOGGER.debug(
                "Sending slide cancel {}", session.sessionId
            )
            PacketDistributor.sendToServer(SlideCancelPayload(session.sessionId))
        }
        session.wasShiftDown = shift
    }

    // Play the trajectory after vanilla tick.
    @JvmStatic
    fun onClientTickPost(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val session = active ?: return
        if (player !is LocalPlayer) return

        session.timeOffsetTicks += (session.targetOffsetTicks - session.timeOffsetTicks).coerceIn(-1.0, 1.0)
        val elapsed = (level.gameTime - session.startTick + session.timeOffsetTicks) / 20.0
        val at = session.trajectory.sampleAt(elapsed)
        val worldPos = toWorldPos(level, session, at.sample.position)
        val sitPos = if (session.swimmingPose) worldPos
        else worldPos.subtract(0.0, SIT_HEIGHT, 0.0)
        val worldTan = toWorldNormal(level, session, at.sample.tangent)

        val entityYaw = yawOf(worldTan)
        val entityPitch = pitchOf(worldTan)
        val prev = player.position()
        player.yRotO = player.yRot
        player.xRotO = player.xRot
        player.yHeadRotO = player.yHeadRot
        player.yBodyRotO = player.yBodyRot
        player.setYRot(entityYaw)
        player.setXRot(entityPitch)
        player.setYHeadRot(entityYaw)
        player.setYBodyRot(entityYaw)
        session.lastEntityYaw = entityYaw
        session.lastEntityPitch = entityPitch

        player.xo = prev.x
        player.yo = prev.y
        player.zo = prev.z
        player.xOld = prev.x
        player.yOld = prev.y
        player.zOld = prev.z
        player.setPos(sitPos)
        player.setDeltaMovement(worldTan.scale(at.sample.speed / 20.0))
        player.setPose(if (session.swimmingPose) Pose.SWIMMING else Pose.SITTING)
        player.setNoGravity(true)
        player.fallDistance = 0f
        player.setSprinting(false)

        if (ModClientConfig.showSlideExitHint() && level.gameTime - session.hintTick >= 100) {
            session.hintTick = level.gameTime
            player.displayClientMessage(
                Component.translatable(
                    "create_waterparked.slide.exit_hint",
                    mc.options.keyShift.getTranslatedKeyMessage()
                ),
                true
            )
        }
    }

    private fun toWorldPos(level: Level, session: Active, local: Vec3): Vec3 {
        val sub = session.subLevel(level) ?: return local
        val out = sub.logicalPose().transformPosition(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun toWorldNormal(level: Level, session: Active, local: Vec3): Vec3 {
        val sub = session.subLevel(level) ?: return local.normalize()
        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out).normalize()
    }

    private fun yawOf(tangent: Vec3): Float =
        Math.toDegrees(atan2(-tangent.x, tangent.z)).toFloat()

    private fun pitchOf(tangent: Vec3): Float {
        val horiz = sqrt(tangent.x * tangent.x + tangent.z * tangent.z)
        return Math.toDegrees(atan2(-tangent.y, horiz)).toFloat()
    }
}
