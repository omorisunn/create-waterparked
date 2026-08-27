package net.omori_sunny.create_waterparked.client

import com.simibubi.create.content.contraptions.AbstractContraptionEntity
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.game.physics.SlideEndReason
import net.omori_sunny.create_waterparked.game.physics.SlideSpace
import net.omori_sunny.create_waterparked.game.physics.SlideTrajectory
import net.omori_sunny.create_waterparked.game.physics.SLIDE_WALL_THICKNESS
import net.omori_sunny.create_waterparked.client.particle.WaterslideSplashSpawner
import net.omori_sunny.create_waterparked.network.SlideCancelPayload
import net.omori_sunny.create_waterparked.network.SlideEndPayload
import net.omori_sunny.create_waterparked.network.SlideSegmentPayload
import net.omori_sunny.create_waterparked.network.SlideSyncPayload
import net.omori_sunny.create_waterparked.network.SlideTrajectoryPayload
import net.minecraft.client.Minecraft
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
        var trajectory: SlideTrajectory,
        var subLevelId: UUID?,
        var contraptionEntityId: Int?,
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
        var thrownRollLock: Float? = null
        var lastFrameYaw: Float? = null
        var lastFramePitch: Float? = null
        var lastAppliedPos: Vec3? = null

        // landing transition eases long setPos moves so ReplayMod never sees a teleport
        var landFrom: Vec3? = null
        var landTo: Vec3? = null
        var landRemaining = 0
        var landTotal = 1
        var landVel: Vec3? = null
        var landYaw = 0f
        var landPitch = 0f

        fun subLevel(level: Level): dev.ryanhcode.sable.sublevel.ClientSubLevel? {
            if (subLevelId == null) return null
            val container = SubLevelContainer.getContainer(level) ?: return null
            return container.getSubLevel(subLevelId) as? dev.ryanhcode.sable.sublevel.ClientSubLevel
        }

        fun contraption(level: Level): AbstractContraptionEntity? {
            val id = contraptionEntityId ?: return null
            return level.getEntity(id) as? AbstractContraptionEntity
        }
    }

    private var active: Active? = null
    private var waterDebugTick = 0L

    // true when in a replay view, detected without referencing ReplayMod
    @Volatile
    private var lastReplayLogTick = -1L

    @JvmStatic
    fun isReplayView(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player
        var replay = false
        var why = "?"
        val conn = mc.connection
        if (conn == null) {
            replay = player != null && mc.level != null
            why = "no-connection"
        } else if (conn.javaClass.name.startsWith("com.replaymod.replay.ReplayHandler")) {
            replay = true
            why = "fake-connection"
        } else if (player?.javaClass?.name?.startsWith("com.replaymod") == true) {
            replay = true
            why = "player-class"
        } else if (mc.level?.javaClass?.name?.startsWith("com.replaymod") == true) {
            replay = true
            why = "level-class"
        }
        val gt = mc.level?.gameTime ?: 0L
        if (replay && gt - lastReplayLogTick >= 100) {
            lastReplayLogTick = gt
            CreateWaterparked.LOGGER.info(
                "[SlideReplay] replay view detected ({}) conn={} player={} level={}",
                why, conn?.javaClass?.name, player?.javaClass?.name, mc.level?.javaClass?.name
            )
        }
        return replay
    }

    @JvmStatic
    fun isSliding(): Boolean = active != null

    // drop a dangling session so it cannot write into another context
    @JvmStatic
    fun resetActive() {
        active = null
        SlideSableOrientation.clearAll()
    }

    // current playback speed from the velocity already applied by the tick
    @JvmStatic
    fun currentSpeedBlocksPerSecond(): Float {
        if (active == null) return 0f
        val player = Minecraft.getInstance().player ?: return 0f
        return (player.deltaMovement.length() * 20.0).toFloat()
    }

    @JvmStatic
    fun currentSpace(): SlideSpace {
        val session = active ?: return SlideSpace.Main
        session.contraptionEntityId?.let { return SlideSpace.Contraption(it) }
        return session.subLevelId?.let { SlideSpace.SubLevel(it) } ?: SlideSpace.Main
    }

    // true while the real collision box intersects a rendered water band
    @JvmStatic
    fun isOnWateredSegment(level: Level): Boolean {
        val session = active ?: return false
        val playerBox = Minecraft.getInstance().player?.boundingBox ?: return false

        // the box is authoritative, the trajectory inTube flag is not part of the gate
        val space = currentSpace()
        val sub = session.subLevel(level)
        val localBox = if (sub != null) toLocalBox(level, session, playerBox) else null
        val worldTube = WaterFlowSimulation.intersectsWateredTubeBox(level, playerBox, space)
        val subTube = sub != null && localBox != null &&
            WaterFlowSimulation.intersectsWateredTubeBox(sub.getLevel(), localBox, space)
        val inStream = WaterFlowSimulation.intersectsStreamBox(level, playerBox, 0.45)
        val hit = worldTube || subTube || inStream

        if (!hit && level.gameTime - waterDebugTick >= 20) {
            waterDebugTick = level.gameTime
            val elapsed = (level.gameTime - session.startTick + session.timeOffsetTicks) / 20.0
            val at = session.trajectory.sampleAt(elapsed)
            CreateWaterparked.LOGGER.info(
                "[SplashWater] inTube={} watered={} worldTube={} subTube={} stream={} box={}",
                at.sample.inTube, at.sample.watered, worldTube, subTube, inStream, playerBox
            )
        }
        return hit
    }

    private fun toLocalBox(
        level: Level,
        session: Active,
        worldBox: net.minecraft.world.phys.AABB
    ): net.minecraft.world.phys.AABB? {
        val sub = session.subLevel(level) ?: return null
        val pose = sub.logicalPose()
        val corners = listOf(
            Vec3(worldBox.minX, worldBox.minY, worldBox.minZ),
            Vec3(worldBox.minX, worldBox.minY, worldBox.maxZ),
            Vec3(worldBox.minX, worldBox.maxY, worldBox.minZ),
            Vec3(worldBox.minX, worldBox.maxY, worldBox.maxZ),
            Vec3(worldBox.maxX, worldBox.minY, worldBox.minZ),
            Vec3(worldBox.maxX, worldBox.minY, worldBox.maxZ),
            Vec3(worldBox.maxX, worldBox.maxY, worldBox.minZ),
            Vec3(worldBox.maxX, worldBox.maxY, worldBox.maxZ)
        )
        val local = corners.map { corner ->
            val out = pose.transformPositionInverse(JOMLConversion.toJOML(corner), Vector3d())
            JOMLConversion.toMojang(out)
        }
        return net.minecraft.world.phys.AABB(
            local.minOf { it.x }, local.minOf { it.y }, local.minOf { it.z },
            local.maxOf { it.x }, local.maxOf { it.y }, local.maxOf { it.z }
        )
    }

    // Per-frame camera state, lerped by partialTick like Sable.
    @JvmStatic
    fun cameraState(partialTick: Float): CameraState? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        val session = active ?: return null
        // In replay view the camera entity is ReForgePlay's own (it copies the
        // recorded player's pos/rot into its camera every frame); do not drive a
        // slide camera there.
        if (isReplayView()) return null
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
        // roll is locked while flying outside a tube, released on the next entry
        val thrownNow = !atNow.sample.inTube
        val preThrownRoll = session.lastSmoothedRoll
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
            val prevRoll = session.lastRoll ?: 0f
            val unwrapped = prevRoll + Mth.wrapDegrees(rawRoll - prevRoll)
            session.lastRoll = unwrapped
            val targetRoll = unwrapped.coerceIn(-30f, 30f)
            val prevSmoothedRoll = session.lastSmoothedRoll ?: 0f
            val smoothedRoll = prevSmoothedRoll + Mth.wrapDegrees(targetRoll - prevSmoothedRoll)
                .coerceIn(-MAX_CAMERA_ROLL_STEP, MAX_CAMERA_ROLL_STEP)
            session.lastSmoothedRoll = smoothedRoll
            roll = smoothedRoll.coerceIn(-30f, 30f)
        }
        if (thrownNow) {
            // First airborne sample: freeze at the last in-tube smoothed roll.
            if (session.thrownRollLock == null) {
                session.thrownRollLock = preThrownRoll ?: roll
            }
            val locked = session.thrownRollLock!!
            session.lastRoll = locked
            session.lastSmoothedRoll = locked
            roll = locked
        } else {
            // Back inside a tube (possibly a later tube that caught the throw).
            session.thrownRollLock = null
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
        // replay view: never create a session that writes the (virtual) player
        if (isReplayView()) return
        SlideSableOrientation.clearAll()
        val level = Minecraft.getInstance().level
        val origin = plotOffset(level, payload.subLevelId) ?: Vec3.ZERO
        val session = Active(
            payload.sessionId,
            SlideTrajectory(payload.samples.map { it.toSample(origin) }, SlideEndReason.EXITED, true),
            payload.subLevelId,
            payload.contraptionEntityId,
            payload.swimmingPose,
            payload.startTick
        )
        active = session
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (level != null && payload.samples.isNotEmpty()) {
            val first = payload.samples.first().toSample(origin)
            val tan = toWorldNormal(level, session, first.tangent)
            session.startTrackYaw = yawOf(tan)
            session.startTrackPitch = pitchOf(tan)
            if (player != null) {
                // start the camera at the pre-entry view, then the track delta adds on
                session.freeLookYaw = player.getYRot()
                session.freeLookPitch = player.getXRot()
                session.lastEntityYaw = player.getYRot()
                session.lastEntityPitch = player.getXRot()
            } else {
                session.lastEntityYaw = yawOf(tan)
                session.lastEntityPitch = pitchOf(tan)
            }
            // snap the smoothed camera to the entry view so the FIRST frame is
            // immediate (no easing from zero); smoothing only applies afterwards
            session.lastCameraYaw = session.freeLookYaw
            session.lastCameraPitch = session.freeLookPitch

            // pre-spawn the entry splash and entry sound right now, instead of
            // waiting for the next client tick (removes the visible delay)
            val worldPos = toWorldPos(level, session, first.position)
            val bodyCenter = Vec3(
                worldPos.x,
                if (payload.swimmingPose) worldPos.y + 0.3 else worldPos.y - SIT_HEIGHT + 0.3,
                worldPos.z
            )
            val velPerTick = tan.scale(first.speed / 20.0)
            WaterslideSplashSpawner.onSlideStart(
                mc, bodyCenter, velPerTick, first.speed, first.watered
            )
        }
    }

    @JvmStatic
    fun appendSegment(payload: SlideSegmentPayload) {
        val session = active ?: return
        if (session.sessionId != payload.sessionId) return
        if (isReplayView()) return
        val level = Minecraft.getInstance().level ?: return
        if (payload.samples.isEmpty()) return
        val origin = plotOffset(level, payload.subLevelId)
        session.trajectory = SlideTrajectory(
            payload.samples.map { it.toSample(origin) }, SlideEndReason.EXITED, true
        )
        session.subLevelId = payload.subLevelId
        session.contraptionEntityId = payload.contraptionEntityId
        session.startTick = payload.startTick
        session.timeOffsetTicks = 0.0
        session.targetOffsetTicks = 0.0
    }

    @JvmStatic
    fun end(payload: SlideEndPayload) {
        val session = active ?: return
        if (session.sessionId != payload.sessionId) return
        CreateWaterparked.LOGGER.debug(
            "Slide end {} reason {}{}", payload.sessionId, payload.reason,
            if (isReplayView()) " (replay view -> not applying to player)" else ""
        )
        // in replay view drop the session without touching the camera player
        if (isReplayView()) {
            active = null
            SlideSableOrientation.clearAll()
            return
        }
        val player = Minecraft.getInstance().player
        val landPos = Vec3(payload.x.toDouble(), payload.y.toDouble(), payload.z.toDouble())
        if (player != null) {
            val dist = player.position().distanceTo(landPos)
            CreateWaterparked.LOGGER.info(
                "[SlideLand] session={} dist={} from={} to={}",
                payload.sessionId, dist, player.position(), landPos
            )
            if (dist > 8.0) {
                // ease the drop over a few ticks, avoids a teleport packet in ReplayMod
                val n = kotlin.math.ceil(dist / 8.0).toInt().coerceIn(2, 30)
                session.landFrom = player.position()
                session.landTo = landPos
                session.landTotal = n
                session.landRemaining = n
                session.landVel = Vec3(
                    payload.vx.toDouble(), payload.vy.toDouble(), payload.vz.toDouble()
                )
                session.landYaw = session.lastCameraYaw
                session.landPitch = session.lastCameraPitch
                return // eased in onClientTickPost; active stays until done
            }
        }
        applyLanding(session, payload)
    }

    // finish the eased landing at the final step
    private fun finishLand(session: Active) {
        active = null
        val player = Minecraft.getInstance().player ?: return
        val to = session.landTo ?: return
        val vel = session.landVel ?: Vec3.ZERO
        SlideSableOrientation.clear(player)
        player.setPos(to)
        player.setDeltaMovement(vel)
        player.setYRot(session.landYaw)
        player.setXRot(session.landPitch)
        player.setYHeadRot(session.landYaw)
        player.yRotO = session.landYaw
        player.xRotO = session.landPitch
        player.yHeadRotO = session.landYaw
        player.setNoGravity(false)
        player.setPose(Pose.STANDING)
        player.refreshDimensions()
    }

    // final landing, restores gravity and pose and ends the session
    private fun applyLanding(session: Active, payload: SlideEndPayload) {
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

        // in replay view the recorded player owns the camera, skip the playback
        if (isReplayView()) {
            return
        }

        // ease the landing over a few ticks, no ReplayMod teleport packet
        if (session.landRemaining > 0) {
            session.landRemaining--
            val f = session.landFrom ?: return
            val t = session.landTo ?: return
            val k = if (session.landRemaining == 0) 1.0
            else 1.0 - session.landRemaining.toDouble() / session.landTotal.toDouble()
            val p = f.scale(1.0 - k).add(t.scale(k))
            player.setPos(p.x, p.y, p.z)
            if (session.landRemaining == 0) finishLand(session)
            return
        }

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

        // splash particles run after the playback velocity is written
        WaterslideSplashSpawner.tickSliding(mc)
    }

    private fun plotOffset(level: Level?, subLevelId: UUID?): Vec3? {
        if (level == null || subLevelId == null) return null
        val container = SubLevelContainer.getContainer(level) ?: return null
        val sub = container.getSubLevel(subLevelId) as? dev.ryanhcode.sable.sublevel.ClientSubLevel ?: return null
        return Vec3.atLowerCornerOf(sub.getPlot().getCenterBlock())
    }

    private fun toWorldPos(level: Level, session: Active, local: Vec3): Vec3 {
        val sub = session.subLevel(level)
        if (sub != null) {
            val out = sub.logicalPose().transformPosition(JOMLConversion.toJOML(local), Vector3d())
            return JOMLConversion.toMojang(out)
        }
        val cp = session.contraption(level)
        if (cp != null) {
            return cp.toGlobalVector(local, 1.0f)
        }
        return local
    }

    private fun toWorldNormal(level: Level, session: Active, local: Vec3): Vec3 {
        val sub = session.subLevel(level)
        if (sub != null) {
            val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(local), Vector3d())
            return JOMLConversion.toMojang(out).normalize()
        }
        val cp = session.contraption(level)
        if (cp != null) {
            val p = cp.toGlobalVector(local, 1.0f)
            val q = cp.toGlobalVector(Vec3.ZERO, 1.0f)
            val n = p.subtract(q).normalize()
            return if (n.lengthSqr() < 1.0E-12) local.normalize() else n
        }
        return local.normalize()
    }

    private fun yawOf(tangent: Vec3): Float =
        Math.toDegrees(atan2(-tangent.x, tangent.z)).toFloat()

    private fun pitchOf(tangent: Vec3): Float {
        val horiz = sqrt(tangent.x * tangent.x + tangent.z * tangent.z)
        return Math.toDegrees(atan2(-tangent.y, horiz)).toFloat()
    }
}
