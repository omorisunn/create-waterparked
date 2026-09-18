package net.omori_sunny.create_waterparked.content.attachment.grab_bar

import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock
import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Pose
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.attachment.IHaveSlideAttachmentEditor
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachment
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlock
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentEntry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentKinetics
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentManager
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType
import net.omori_sunny.create_waterparked.content.attachment.accelerator.AcceleratorAttachment
import net.omori_sunny.create_waterparked.content.attachment.detector.DetectorAttachment
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorAttachment
import net.omori_sunny.create_waterparked.content.registry.ModEntityTypes
import net.omori_sunny.create_waterparked.content.sit.SlideSitEntity
import net.omori_sunny.create_waterparked.game.physics.GrabBarTrajectoryBuilder
import net.omori_sunny.create_waterparked.game.physics.MainSlideSpaceAccess
import net.omori_sunny.create_waterparked.game.physics.PhysicsSlideTrajectoryBuilder
import net.omori_sunny.create_waterparked.game.physics.PlayerSlideController
import net.omori_sunny.create_waterparked.game.physics.SlideSpace
import net.omori_sunny.create_waterparked.game.physics.SlideSpaceAccess
import net.omori_sunny.create_waterparked.game.physics.SlideTrajectory
import net.omori_sunny.create_waterparked.game.physics.SubSlideSpaceAccess
import net.omori_sunny.create_waterparked.network.GrabBarHoldPayload
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

// bar across a slide mouth: holds a player, blocks automatic entry and launches on a full charge
class GrabBarAttachment(
    type: SlideAttachmentType,
    entry: SlideAttachmentEntry
) : SlideAttachment(type, entry), IHaveSlideAttachmentEditor {

    override fun slideEditorKey(): String? = GrabBarHeightEditor.EDITOR_KEY

    companion object {
        const val TAG_HEIGHT = "GrabBarHeight"

        const val TAG_GRABS = "GrabBarGrabs"

        const val MIN_HEIGHT = -5.0f

        const val MAX_HEIGHT = 5.0f

        private const val START_SPEED = 3.0
        private const val RELEASE_PUSH = 0.3
        private const val RELEASE_LIFT = 0.08
        private const val ENDPOINT_EPS = 0.02
        private const val PROJECT_STEPS = 32
        private const val PROJECT_REFINE = 8
        private const val SCAN_INTERVAL_TICKS = 5L
        private const val HOLD_COOLDOWN_TICKS = 10L
        private const val INPUT_TIMEOUT_TICKS = 5L
        private const val INPUT_DIAG_TICKS = 10L
        private const val FRONT_TOLERANCE = 0.5
        private const val SEAT_HEIGHT_RATIO = 0.5f
        private const val PROGRESS_NONE = -1f
        private const val PROGRESS_LAUNCH = -2f

        // vanilla player arm: the hand sits 10 px from the shoulder pivot, the model is drawn at
        // 15/16 scale, so the reach is 0.9375 * 10/16 blocks
        private const val ARM_REACH = 0.586
        // shoulder pivot 2 px below the model top: 0.9375 * (1.501 - 2/16) blocks above the feet
        private const val SHOULDER_HEIGHT = 1.29
        private const val LEAN_PRESS = 0.15
        private const val MIN_ARM_SPAN = 0.05

        private val UP = Vec3(0.0, 1.0, 0.0)

        private val refusalReasons = HashMap<UUID, String>()
        private val holdsByBar = HashMap<String, Hold>()
        private val holdsByEntity = HashMap<UUID, Hold>()
        private val releaseCooldown = HashMap<UUID, Long>()

        // data keys that move on their own and never describe the tube
        private val RUNTIME_DATA_KEYS = setOf(
            TAG_GRABS,
            DetectorAttachment.TAG_COUNT,
            AcceleratorAttachment.TAG_RATE,
            MechanicalDoorAttachment.TAG_OPEN
        )

        private val grabBarTypeId: String by lazy { ModSlideAttachments.GRAB_BAR.id.toString() }

        private val acceleratorTypeId: String by lazy { ModSlideAttachments.SLIDE_ACCELERATOR.id.toString() }

        fun height(data: CompoundTag): Float =
            data.getFloat(TAG_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT)

        fun grabs(data: CompoundTag): Int = data.getInt(TAG_GRABS)

        // automatic entry is blocked at this bar's own mouth only
        fun blocksEntry(
            level: ServerLevel,
            access: SlideSpaceAccess,
            curve: BezierConnection,
            towardSecond: Boolean,
            startT: Float?
        ): Boolean {
            if (startT != null) return false
            for (be in SlideAttachmentManager.allAttachments()) {
                if (be.isRemoved || be.level !== level) continue
                val data = be.entry ?: continue
                if (data.typeId != grabBarTypeId) continue
                val barSpace = SlideSpace.ofLevelAndSub(level, data.curveA)
                if (barSpace is SlideSpace.Contraption) continue
                if (barSpace != access.space) continue
                if (!sameEdge(data.curveA, data.curveB, curve)) continue
                if ((data.t < 0.5f) == towardSecond) {
                    CreateWaterparked.LOGGER.debug(
                        "[GrabBar] blocked slide entry at {} towardSecond={}", be.blockPos, towardSecond
                    )
                    return true
                }
            }
            return false
        }

        // a bar that is gone would leave its player riding an unpositioned seat forever
        fun releaseStale(level: ServerLevel) {
            val cooldowns = releaseCooldown.entries.iterator()
            while (cooldowns.hasNext()) {
                if (level.gameTime >= cooldowns.next().value) cooldowns.remove()
            }
            for (hold in holdsByBar.values.toList()) {
                if (hold.level !== level) continue
                if (!hold.valid(level) || !level.isLoaded(hold.barPos)) {
                    CreateWaterparked.LOGGER.info(
                        "[GrabBar] sweep released {} valid={} loaded={}",
                        hold.player.uuid, hold.valid(level), level.isLoaded(hold.barPos)
                    )
                    hold.release(false)
                    notifyRelease(hold.player)
                    continue
                }
                val be = level.getBlockEntity(hold.barPos) as? SlideAttachmentBlockEntity
                if (be != null && !be.isRemoved) continue
                hold.release(false)
                notifyRelease(hold.player)
            }
        }

        // the hanging client owns its key state, the mounted vanilla input path only ever sees a snapshot
        fun onClientInput(player: ServerPlayer, forward: Boolean, backward: Boolean) {
            val hold = holdsByEntity[player.uuid] ?: return
            hold.forwardFlag = forward
            hold.backwardFlag = backward
            hold.flagTick = hold.level.gameTime
            hold.flagSeen = true
        }

        // edge triggered so a player standing in range cannot flood the log
        private fun logRefusal(player: ServerPlayer, reason: String) {
            if (refusalReasons.put(player.uuid, reason) == reason) return
            CreateWaterparked.LOGGER.info("[GrabBar] {} not grabbed: {}", player.uuid, reason)
        }

        private fun clearRefusal(player: ServerPlayer) {
            refusalReasons.remove(player.uuid)
        }

        private fun holdKey(level: ServerLevel, pos: BlockPos): String =
            level.dimension().location().toString() + "|" + pos.asLong()

        // the launch keeps the client pose alive so it can ease into the seated ride
        private fun notifyLaunch(entity: Entity) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                GrabBarHoldPayload(entity.id, 0.0, 0.0, 0.0, 0f, PROGRESS_LAUNCH, false, 0f, 0.0, 0.0, 0.0)
            )
        }

        private fun notifyRelease(entity: Entity) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                entity,
                GrabBarHoldPayload(entity.id, 0.0, 0.0, 0.0, 0f, PROGRESS_NONE, false, 0f, 0.0, 0.0, 0.0)
            )
        }

        private fun sameEdge(a: BlockPos, b: BlockPos, curve: BezierConnection): Boolean {
            val first = curve.bePositions.getFirst()
            val second = curve.bePositions.getSecond()
            return (a == first && b == second) || (a == second && b == first)
        }
    }

    private class Hold(
        val level: ServerLevel,
        val barPos: BlockPos,
        val player: ServerPlayer,
        val sit: SlideSitEntity
    ) {
        var progress = 0.0
        var forwardFlag = false
        var backwardFlag = false
        var flagTick = 0L
        var flagSeen = false
        var diagTick = 0L
        var releaseDirection: Vec3? = null

        fun valid(level: ServerLevel): Boolean =
            level === this.level && !player.isRemoved && player.isAlive &&
                !sit.isRemoved && player.vehicle === sit

        // never touches a mount that is not this hold's own seat
        fun release(push: Boolean) {
            holdsByBar.remove(holdKey(level, barPos))
            holdsByEntity.remove(player.uuid)
            val riding = player.vehicle === sit
            if (riding) player.stopRiding()
            sit.discard()
            player.fallDistance = 0f
            if (player.vehicle != null) return
            if (!push) {
                player.deltaMovement = Vec3.ZERO
                return
            }
            val out = releaseDirection ?: return
            player.deltaMovement = out.scale(RELEASE_PUSH).add(0.0, RELEASE_LIFT, 0.0)
            player.hurtMarked = true
        }
    }

    private class Frame(
        val access: SlideSpaceAccess,
        val curve: BezierConnection,
        val towardSecond: Boolean,
        val startT: Float?,
        val startPos: Vec3,
        val startVel: Vec3,
        val outWorld: Vec3,
        val yaw: Float,
        val seated: Boolean,
        val grip: Vec3,
        val barA: Vec3,
        val barB: Vec3,
        val floorY: Double
    )

    private class BodyPose(val width: Double, val height: Double, val radius: Double)

    private class Reach(val feet: Vec3, val pitch: Double)

    private var cachedSignature: String? = null
    private var cachedTrajectory: SlideTrajectory? = null
    private var buildingSignature: String? = null
    private var frameTick = -SCAN_INTERVAL_TICKS

    // the worker only warms the shared caches, its own result is never ridden
    fun workerFinished(signature: String) {
        if (buildingSignature == signature) buildingSignature = null
    }

    override fun serverTick(level: ServerLevel, sab: SlideAttachmentBlockEntity) {
        val held = holdsByBar[holdKey(level, sab.blockPos)]
        if (held != null) {
            val frame = frameOf(level, sab)
            if (frame == null || !held.valid(level)) {
                held.release(false)
                setPowered(level, sab, false)
                notifyRelease(held.player)
                return
            }
            tickHold(level, sab, frame, held)
            return
        }
        // a bar destroyed or unloaded mid hold must not keep its redstone on
        if (sab.blockState.getValue(SlideAttachmentBlock.POWERED)) setPowered(level, sab, false)
        if (level.gameTime - frameTick < SCAN_INTERVAL_TICKS) return
        frameTick = level.gameTime
        val frame = frameOf(level, sab) ?: return
        val candidate = findCandidate(level, frame) ?: return
        takeHold(level, sab, frame, candidate)
    }

    private fun frameOf(level: ServerLevel, sab: SlideAttachmentBlockEntity): Frame? {
        val resolved = SlideAttachmentGeometry.resolve(
            level, entry.curveA, entry.curveB, entry.t, entry.angle, sab.blockPos, entry.data
        ) ?: return null
        val access = accessFor(level, SlideSpace.ofLevelAndSub(level, entry.curveA)) ?: return null
        val ctx = resolved.context
        val (lateral, up, _) = SlideAttachmentGeometry.basis(ctx)
        val inner = (ctx.radius - 0.1f).coerceAtLeast(0.05f).toDouble()
        val outer = (ctx.radius + ctx.wallThickness - 0.1).toDouble()
        val spine = ctx.position.subtract(ctx.radialOut.scale(outer))
        val barCentre = spine.add(up.scale(height(entry.data).toDouble()))
        // the bar sits on its own mouth, so the projection must stay in that half
        val towardSecond = entry.t < 0.5f
        val projected = projectOntoCurve(resolved.curve, barCentre)
        val t = if (towardSecond) projected.coerceIn(0.0, 0.5) else projected.coerceIn(0.5, 1.0)
        val startPos = resolved.curve.getPosition(t)
        var tangent = CoasterBezierRailFrames.unitTangentAt(resolved.curve, t.toFloat())
        if (tangent.lengthSqr() < 1.0E-12 || !tangent.x.isFinite()) {
            tangent = Vec3.atCenterOf(resolved.curve.bePositions.getSecond())
                .subtract(Vec3.atCenterOf(resolved.curve.bePositions.getFirst()))
        }
        if (tangent.lengthSqr() < 1.0E-12) return null
        tangent = tangent.normalize()
        val inward = if (towardSecond) tangent else tangent.scale(-1.0)
        val outDir = inward.scale(-1.0)
        val startT = if (t < ENDPOINT_EPS || t > 1.0 - ENDPOINT_EPS) null else t.toFloat()
        // the grip is the bar the provider draws: the axis point lifted to the control height
        val grip = startPos.add(up.scale(height(entry.data).toDouble()))
        val seated = height(entry.data) < -SEAT_HEIGHT_RATIO * inner
        return Frame(
            access, resolved.curve, towardSecond, startT,
            startPos, inward.scale(START_SPEED), access.toWorldNormal(outDir),
            Mth.wrapDegrees(Math.toDegrees(atan2(-inward.x, inward.z)).toFloat()),
            seated, grip,
            grip.subtract(lateral.scale(inner)), grip.add(lateral.scale(inner)),
            startPos.y - inner
        )
    }

    private fun accessFor(level: ServerLevel, space: SlideSpace): SlideSpaceAccess? = when (space) {
        SlideSpace.Main -> MainSlideSpaceAccess(level)
        is SlideSpace.SubLevel -> {
            val sub = SubLevelContainer.getContainer(level)?.getSubLevel(space.id) as? ServerSubLevel
            if (sub == null) null else SubSlideSpaceAccess(level, sub)
        }
        is SlideSpace.Contraption -> null
    }

    // the hands stay on the grip, so the body rides the arm reach sphere around it
    private fun reach(frame: Frame, progress: Double): Reach {
        val horizontalOut = Vec3(frame.outWorld.x, 0.0, frame.outWorld.z)
        val out = if (horizontalOut.lengthSqr() < 1.0E-6) frame.outWorld else horizontalOut.normalize()
        val shoulderY = frame.floorY + SHOULDER_HEIGHT
        val vertical0 = (frame.grip.y - shoulderY).coerceIn(-ARM_REACH, ARM_REACH)
        val span0 = sqrt((ARM_REACH * ARM_REACH - vertical0 * vertical0).coerceAtLeast(0.0))
        val span = (span0 - LEAN_PRESS * progress).coerceAtLeast(MIN_ARM_SPAN)
        val vertical = sqrt((ARM_REACH * ARM_REACH - span * span).coerceAtLeast(0.0))
        val signed = if (vertical0 >= 0.0) vertical else -vertical
        val shoulder = frame.grip.add(out.scale(span)).subtract(UP.scale(signed))
        val feet = shoulder.subtract(UP.scale(SHOULDER_HEIGHT))
        return Reach(feet, atan2(signed, span))
    }

    private fun findCandidate(level: ServerLevel, frame: Frame): ServerPlayer? {
        val barA = frame.access.toWorld(frame.barA)
        val barB = frame.access.toWorld(frame.barB)
        val middle = barA.add(barB).scale(0.5)
        val reach = ModConfig.grabDistance()
        val reachSq = reach * reach
        val box = AABB(middle, middle).inflate(reach + barA.distanceTo(barB) / 2.0)
        var best: ServerPlayer? = null
        var bestDist = Double.MAX_VALUE
        for (player in level.getEntitiesOfClass(ServerPlayer::class.java, box)) {
            if (player.isRemoved || !player.isAlive || player.isSpectator) continue
            if (player.isPassenger) {
                logRefusal(player, "riding something")
                continue
            }
            if (holdsByEntity.containsKey(player.uuid)) {
                logRefusal(player, "already in a hold")
                continue
            }
            val cooldown = releaseCooldown[player.uuid]
            if (cooldown != null) {
                if (level.gameTime < cooldown) {
                    logRefusal(player, "release cooldown")
                    continue
                }
                releaseCooldown.remove(player.uuid)
            }
            if (PlayerSlideController.isSliding(player)) {
                logRefusal(player, "sliding")
                continue
            }
            val closest = closestOnSegment(barA, barB, player.boundingBox.center)
            if (player.boundingBox.center.subtract(closest).dot(frame.outWorld) < -FRONT_TOLERANCE) {
                logRefusal(player, "behind the bar")
                continue
            }
            val distance = player.boundingBox.distanceToSqr(closest)
            if (distance > reachSq) {
                logRefusal(player, "out of grab range")
                continue
            }
            if (distance < bestDist) {
                bestDist = distance
                best = player
            }
        }
        return best
    }

    private fun closestOnSegment(a: Vec3, b: Vec3, point: Vec3): Vec3 {
        val ab = b.subtract(a)
        val lenSq = ab.lengthSqr()
        if (lenSq < 1.0E-9) return a
        return a.add(ab.scale((point.subtract(a).dot(ab) / lenSq).coerceIn(0.0, 1.0)))
    }

    private fun takeHold(
        level: ServerLevel,
        sab: SlideAttachmentBlockEntity,
        frame: Frame,
        player: ServerPlayer
    ) {
        if (PlayerSlideController.isSliding(player)) return
        val seat = frame.access.toWorld(reach(frame, 0.0).feet)
        val sit = SlideSitEntity(ModEntityTypes.SLIDE_SIT, level)
        sit.setPos(seat)
        sit.deltaMovement = Vec3.ZERO
        level.addFreshEntity(sit)
        if (!player.startRiding(sit, true)) {
            sit.discard()
            return
        }
        val stale = player.zza
        player.zza = 0f
        player.xxa = 0f
        val hold = Hold(level, sab.blockPos, player, sit)
        holdsByBar[holdKey(level, sab.blockPos)] = hold
        holdsByEntity[player.uuid] = hold
        releaseCooldown.remove(player.uuid)
        clearRefusal(player)
        CreateWaterparked.LOGGER.info("[GrabBar] {} grabbed the bar, staleInput reset from {}", player.uuid, stale)
        data.putInt(TAG_GRABS, grabs(data) + 1)
        sync(sab)
        DisplayLinkBlock.notifyGatherers(level, sab.blockPos)
        setPowered(level, sab, true)
        sendHold(frame, hold)
        requestTrajectory(level, sab, frame, hold)
    }

    private fun tickHold(
        level: ServerLevel,
        sab: SlideAttachmentBlockEntity,
        frame: Frame,
        hold: Hold
    ) {
        val player = hold.player
        // the client flags win while they keep arriving, zza is only the fallback for a silent client
        val live = hold.flagSeen && level.gameTime - hold.flagTick <= INPUT_TIMEOUT_TICKS
        val forward = if (live) hold.forwardFlag else player.zza > 0f
        val backward = if (live) hold.backwardFlag else player.zza < 0f
        if (level.gameTime - hold.diagTick >= INPUT_DIAG_TICKS) {
            hold.diagTick = level.gameTime
            CreateWaterparked.LOGGER.info(
                "[GrabBarInput] zza={} xxa={} progress={} passenger={} forward={} backward={} live={}",
                player.zza, player.xxa, hold.progress, player.isPassenger,
                hold.forwardFlag, hold.backwardFlag, live
            )
        }
        if (backward) {
            CreateWaterparked.LOGGER.info(
                "[GrabBar] {} released by backward input, live flags {} at progress {}",
                player.uuid, live, hold.progress
            )
            hold.releaseDirection = frame.outWorld
            hold.release(true)
            setPowered(level, sab, false)
            notifyRelease(player)
            releaseCooldown[player.uuid] = level.gameTime + HOLD_COOLDOWN_TICKS
            frameTick = -SCAN_INTERVAL_TICKS
            return
        }
        hold.progress = if (forward) {
            (hold.progress + 1.0 / ModConfig.grabChargeTicks()).coerceAtMost(1.0)
        } else {
            0.0
        }
        pin(frame, hold)
        if (hold.progress >= 1.0 && launch(level, sab, frame, hold)) return
        sendHold(frame, hold)
    }

    private fun pin(frame: Frame, hold: Hold) {
        val solved = reach(frame, hold.progress)
        val world = frame.access.toWorld(solved.feet)
        hold.sit.setPos(world)
        hold.player.setPos(world)
        hold.player.deltaMovement = Vec3.ZERO
        hold.player.fallDistance = 0f
        hold.player.setYRot(frame.yaw)
        hold.player.setYHeadRot(frame.yaw)
        hold.player.setYBodyRot(frame.yaw)
    }

    private fun launch(
        level: ServerLevel,
        sab: SlideAttachmentBlockEntity,
        frame: Frame,
        hold: Hold
    ): Boolean {
        val pose = poseOf(hold.player, frame.seated)
        val signature = signatureOf(level, frame, pose)
        var trajectory = if (cachedSignature == signature) cachedTrajectory else null
        if (trajectory == null) {
            trajectory = PhysicsSlideTrajectoryBuilder.build(
                frame.access, frame.curve, frame.towardSecond, frame.startT,
                frame.startPos, frame.startVel, pose.width, pose.height, pose.radius
            )
            if (trajectory == null) {
                CreateWaterparked.LOGGER.debug("Grab bar found no trajectory at {}", sab.blockPos)
                hold.progress = 0.0
                return false
            }
            cachedSignature = signature
            cachedTrajectory = trajectory
        }
        val startWorld = frame.access.toWorld(trajectory.samples.first().position)
        if (PhysicsSlideTrajectoryBuilder.worldBlocksCollide(level, startWorld, pose.width, pose.height)) {
            CreateWaterparked.LOGGER.debug("Grab bar start is blocked at {}", sab.blockPos)
            hold.progress = 0.0
            return false
        }
        if (!PlayerSlideController.startSlideAt(level, hold.player, frame.access, frame.startPos, trajectory)) {
            hold.progress = 0.0
            return false
        }
        holdsByBar.remove(holdKey(level, sab.blockPos))
        holdsByEntity.remove(hold.player.uuid)
        hold.sit.discard()
        releaseCooldown[hold.player.uuid] = level.gameTime + HOLD_COOLDOWN_TICKS
        setPowered(level, sab, false)
        notifyLaunch(hold.player)
        return true
    }

    private fun poseOf(player: ServerPlayer, seated: Boolean): BodyPose {
        val dims = player.getDimensions(if (seated) Pose.SITTING else Pose.STANDING)
        val width = dims.width.toDouble()
        return BodyPose(width, dims.height.toDouble(), width / 2.0)
    }

    // the client mirrors the pin every tick, so its target can never drift off the bar
    private fun sendHold(frame: Frame, hold: Hold) {
        val solved = reach(frame, hold.progress)
        val world = frame.access.toWorld(solved.feet)
        val grip = frame.access.toWorld(frame.grip)
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
            hold.player,
            GrabBarHoldPayload(
                hold.player.id, world.x, world.y, world.z, frame.yaw,
                hold.progress.toFloat(), frame.seated, solved.pitch.toFloat(),
                grip.x, grip.y, grip.z
            )
        )
    }

    private fun setPowered(level: ServerLevel, sab: SlideAttachmentBlockEntity, powered: Boolean) {
        if (sab.blockState.getValue(SlideAttachmentBlock.POWERED) == powered) return
        level.setBlockAndUpdate(sab.blockPos, sab.blockState.setValue(SlideAttachmentBlock.POWERED, powered))
    }

    // optional off thread warm up: the launch never waits for it
    private fun requestTrajectory(
        level: ServerLevel,
        sab: SlideAttachmentBlockEntity,
        frame: Frame,
        hold: Hold
    ) {
        val pose = poseOf(hold.player, frame.seated)
        val signature = signatureOf(level, frame, pose)
        if (cachedSignature == signature) return
        if (buildingSignature == signature) return
        val step = try {
            PhysicsSlideTrajectoryBuilder.prepareBuild(
                frame.access, frame.curve, frame.towardSecond, frame.startT,
                frame.startPos, frame.startVel, pose.width, pose.height, pose.radius,
                worldChecks = false
            )
        } catch (e: Exception) {
            CreateWaterparked.LOGGER.error("Grab bar prepare failed at {}", sab.blockPos, e)
            null
        }
        if (step == null) {
            CreateWaterparked.LOGGER.debug("Grab bar has no tube to ride at {}", sab.blockPos)
            return
        }
        buildingSignature = signature
        if (!GrabBarTrajectoryBuilder.request(level, sab.blockPos, signature, step)) {
            buildingSignature = null
        }
    }

    // everything a rebuild depends on: the walked tube, the sectors and the attachments on it
    private fun signatureOf(level: ServerLevel, frame: Frame, pose: BodyPose): String {
        val tube = PhysicsSlideTrajectoryBuilder.tubeDigest(
            frame.access, frame.curve, frame.towardSecond, frame.startT
        )
        val sb = StringBuilder(512)
        sb.append(tube?.digest ?: "-").append('|')
        sb.append(pose.width).append(',').append(pose.height).append('|')
        sb.append(height(entry.data)).append('|').append(frame.startT).append('|')
        sb.append(ModConfig.slideMaxTrajectorySamples()).append('|')
        sb.append(ModConfig.slideMaxTrajectoryBlocks()).append('|')
        sb.append(ModConfig.slideWaterFriction()).append('|')
        if (tube != null) {
            for (be in SlideAttachmentManager.allAttachments().sortedBy { it.blockPos.asLong() }) {
                if (be.isRemoved || be.level !== level) continue
                val other = be.entry ?: continue
                if (!tube.endpoints.contains(other.curveA.asLong())) continue
                if (!tube.endpoints.contains(other.curveB.asLong())) continue
                sb.append(other.typeId).append('@').append(other.t).append('@').append(other.angle).append('@')
                for (key in other.data.allKeys.sorted()) {
                    if (key in RUNTIME_DATA_KEYS) continue
                    sb.append(key).append('=').append(other.data.get(key)).append(';')
                }
                if (other.typeId == acceleratorTypeId) {
                    sb.append("rpm=").append(SlideAttachmentKinetics.drivenSpeed(level, be)).append(';')
                }
                sb.append('|')
            }
        }
        return sb.toString()
    }

    private fun projectOntoCurve(curve: BezierConnection, point: Vec3): Double {
        var best = 0.0
        var bestDist = Double.MAX_VALUE
        for (i in 0..PROJECT_STEPS) {
            val t = i.toDouble() / PROJECT_STEPS
            val d = curve.getPosition(t).distanceToSqr(point)
            if (d < bestDist) {
                bestDist = d
                best = t
            }
        }
        val span = 1.0 / PROJECT_STEPS
        var lo = (best - span).coerceAtLeast(0.0)
        var hi = (best + span).coerceAtMost(1.0)
        repeat(PROJECT_REFINE) {
            val third = (hi - lo) / 3.0
            if (curve.getPosition(lo + third).distanceToSqr(point) <
                curve.getPosition(hi - third).distanceToSqr(point)
            ) {
                hi -= third
            } else {
                lo += third
            }
        }
        return (lo + hi) / 2.0
    }
}
