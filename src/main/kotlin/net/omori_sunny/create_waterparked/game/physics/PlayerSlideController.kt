package net.omori_sunny.create_waterparked.game.physics

import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.registry.ModEntityTypes
import net.omori_sunny.create_waterparked.content.sit.SlideSitEntity
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.water.SlideWaterManager
import net.omori_sunny.create_waterparked.network.SlideEndPayload
import net.omori_sunny.create_waterparked.network.SlidePackets
import net.omori_sunny.create_waterparked.network.SlideSampleWire
import net.omori_sunny.create_waterparked.network.SlideSyncPayload
import net.omori_sunny.create_waterparked.network.SlideTrajectoryPayload
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

// Server-side slide sessions.
object PlayerSlideController {

    private const val SIT_HEIGHT = 0.7

    private class Session(
        val id: Long,
        val entity: Entity,
        val player: ServerPlayer?,
        var trajectory: SlideTrajectory,
        var subLevelId: UUID?,
        val swimmingPose: Boolean,
        val sit: SlideSitEntity?,
        var startTick: Long
    ) {
        var elapsed = 0.0
        var lastSyncTick = 0L

        fun subLevel(level: ServerLevel): ServerSubLevel? {
            if (subLevelId == null) return null
            val container = SubLevelContainer.getContainer(level) ?: return null
            return container.getSubLevel(subLevelId) as? ServerSubLevel
        }
    }

    private data class SlideEntry(
        val curve: BezierConnection,
        val towardSecond: Boolean,
        val startT: Float?,
        val anchorPos: BlockPos
    )

    private data class SegmentHit(val entry: SlideEntry, val distSq: Double)

    private val sessions = mutableMapOf<UUID, Session>()
    private val lastPos = mutableMapOf<UUID, Vec3>()
    private val entryCooldown = mutableMapOf<UUID, Long>()
    private var nextSessionId = 1L

    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        val levels = event.server.allLevels.toSet()
        for (session in sessions.values.toList()) {
            if (session.entity.isRemoved || session.entity.level() !in levels) {
                sessions.remove(session.entity.uuid)
                session.sit?.discard()
            }
        }
        for (level in event.server.allLevels) {
            for (anchorPos in SlideAnchorIndex.all(level).toList()) {
                val be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity
                if (be != null) SlideWaterManager.tickServer(level, be)
                else SlideAnchorIndex.unregister(level, anchorPos)
            }
            for (session in sessions.values.toList()) {
                if (session.entity.level() != level) continue
                tickSession(level, session)
            }
            val bounds = slideBounds(level)
            if (bounds == null) continue
            for (entity in level.getEntities(null, bounds)) {
                if (entity.isRemoved) continue
                if (entity is SlideSitEntity) continue
                val prev = lastPos.put(entity.uuid, entity.position())
                if (!sessions.containsKey(entity.uuid) && prev != null) {
                    tryStartSlide(level, entity, prev)
                }
            }
        }
    }

    @JvmStatic
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        sessions.remove(event.entity.uuid)?.sit?.discard()
        lastPos.remove(event.entity.uuid)
        entryCooldown.remove(event.entity.uuid)
    }

    @JvmStatic
    fun onCancel(player: ServerPlayer, sessionId: Long) {
        val session = sessions[player.uuid]
        if (session == null) {
            CreateWaterparked.LOGGER.debug(
                "Slide cancel ignored: no session for {}", player.uuid
            )
            return
        }
        if (session.id != sessionId) {
            CreateWaterparked.LOGGER.debug(
                "Slide cancel ignored: id {} != {}", sessionId, session.id
            )
            return
        }
        val level = player.serverLevel()
        val at = session.trajectory.sampleAt(session.elapsed)
        val first = session.trajectory.samples.first()
        val lastSample = session.trajectory.samples.last()
        val useEntry = at.sample.position.distanceToSqr(first.position) <=
            at.sample.position.distanceToSqr(lastSample.position)
        val target = if (useEntry) session.trajectory.samples.first()
        else session.trajectory.samples.last()
        val outward = if (useEntry) target.tangent.scale(-1.0) else target.tangent
        val worldPos = toWorldPos(level, session, target.position)
            .add(toWorldNormal(level, session, outward).scale(1.5))
        val worldVel = toWorldVel(level, session, target.position, outward.scale(at.sample.speed))
        CreateWaterparked.LOGGER.info(
            "Slide cancel {} pos {} vel {}",
            session.id, worldPos, worldVel
        )

        cleanupSit(session, player)
        player.setPos(worldPos)
        player.setDeltaMovement(worldVel)
        restoreEntity(player)
        sessions.remove(player.uuid)
        SlidePackets.sendTo(player, SlideEndPayload(
            session.id, SlideEndReason.EXITED.ordinal.toByte(),
            worldPos.x.toFloat(), worldPos.y.toFloat(), worldPos.z.toFloat(),
            worldVel.x.toFloat(), worldVel.y.toFloat(), worldVel.z.toFloat()
        ))
    }

    private fun tryStartSlide(level: ServerLevel, entity: Entity, prevPos: Vec3) {
        val player = entity as? ServerPlayer
        if (player != null && player.isShiftKeyDown) return
        val cd = entryCooldown[entity.uuid]
        if (cd != null) {
            if (level.gameTime < cd) return
            entryCooldown.remove(entity.uuid)
        }
        val entry = findSlideEntry(level, entity) ?: return

        val swimming = (entity as? LivingEntity)?.getPose() == Pose.SWIMMING
        if (entity is LivingEntity) {
            entity.setPose(if (swimming) Pose.SWIMMING else Pose.SITTING)
            entity.refreshDimensions()
        }

        val dims = entityDimensions(entity)
        val moved = entity.position().subtract(prevPos)
        val velWorld = if (moved.lengthSqr() <= 2.25) moved.scale(20.0)
        else entity.deltaMovement.scale(20.0)
        val entryTanWorld = entryTangentWorld(level, entry)
        val along = velWorld.dot(entryTanWorld)
        if (along < -0.5) return
        val axial = entryTanWorld.scale(along.coerceAtLeast(0.0))
        var startVelWorld = axial.add(entryTanWorld.scale(ModConfig.entranceBoost() * 20.0))
        val maxSpeed = ModConfig.slideMaxEntrySpeed()
        if (startVelWorld.lengthSqr() > maxSpeed * maxSpeed) {
            startVelWorld = startVelWorld.normalize().scale(maxSpeed)
        }
        val subLevel = Sable.HELPER.getContaining(level, entry.anchorPos) as? ServerSubLevel
        val startPos = if (subLevel != null) toLocalPos(subLevel, entity.position()) else entity.position()
        val startVel = if (subLevel != null) toLocalVel(subLevel, startVelWorld) else startVelWorld
        val trajectory = PhysicsSlideTrajectoryBuilder.build(
            level, entry.curve, entry.towardSecond, entry.startT,
            startPos, startVel, dims.width.toDouble(), dims.height.toDouble()
        )
        if (trajectory == null) {
            restoreEntity(entity)
            return
        }

        val sit = if (player != null && !swimming) spawnSit(level, player) else null
        if (sit != null && player != null) player.startRiding(sit, true)
        val session = Session(
            nextSessionId++, entity, player, trajectory,
            subLevel?.uniqueId, swimming, sit, level.gameTime
        )
        CreateWaterparked.LOGGER.info(
            "Slide start {} entity {} dir {} pos {} vel {} samples={} last={} endOpen={} reason={}",
            session.id, entity.uuid, entry.towardSecond, startPos, startVel,
            trajectory.samples.size, trajectory.exitPosition, trajectory.endIsOpenEnd,
            trajectory.endReason
        )
        sessions[entity.uuid] = session
        if (entity is LivingEntity) entity.setNoGravity(true)
        entity.fallDistance = 0f
        if (player != null) {
            SlidePackets.sendTo(player, SlideTrajectoryPayload(
                session.id, session.startTick, swimming, session.subLevelId,
                trajectory.samples.map { SlideSampleWire.from(it) }
            ))
        }
    }

    private fun spawnSit(level: ServerLevel, player: ServerPlayer): SlideSitEntity {
        val sit = SlideSitEntity(ModEntityTypes.SLIDE_SIT, level)
        sit.setPos(player.position())
        sit.setDeltaMovement(Vec3.ZERO)
        level.addFreshEntity(sit)
        return sit
    }

    private fun cleanupSit(session: Session, entity: Entity) {
        val sit = session.sit ?: return
        if (entity.vehicle === sit) entity.stopRiding()
        sit.discard()
    }

    private fun entryTangentWorld(level: ServerLevel, entry: SlideEntry): Vec3 {
        val bc = entry.curve
        val t = entry.startT ?: if (entry.towardSecond) 0f else 1f
        var tan = CoasterBezierRailFrames.unitTangentAt(bc, t)
        if (tan.lengthSqr() < 1.0E-12 || !tan.x.isFinite() || !tan.y.isFinite() || !tan.z.isFinite()) {
            val a = bc.bePositions.getFirst()
            val b = bc.bePositions.getSecond()
            tan = Vec3.atCenterOf(b).subtract(Vec3.atCenterOf(a))
            if (tan.lengthSqr() < 1.0E-12) tan = Vec3(0.0, 1.0, 0.0)
        }
        tan = tan.normalize()
        if (!entry.towardSecond) tan = tan.scale(-1.0)
        val sub = Sable.HELPER.getContaining(level, entry.anchorPos) as? ServerSubLevel
        if (sub != null) {
            val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(tan), Vector3d())
            return JOMLConversion.toMojang(out).normalize()
        }
        return tan
    }

    private fun findSlideEntry(
        level: ServerLevel,
        entity: Entity,
        requireSolid: Boolean = true
    ): SlideEntry? {
        val p = entity.position()
        var best: SegmentHit? = null
        for (anchorPos in SlideAnchorIndex.all(level).toList()) {
            val be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity
            if (be == null) {
                SlideAnchorIndex.unregister(level, anchorPos)
                continue
            }
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                if (!bc.getBounds().inflate(2.0).contains(p)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(level, a)
                val r1 = SlideCurveGeometry.radiusAt(level, b)
                val frames = SlideCurveGeometry.sampleFrames(level, bc, r0, r1)
                if (frames.size < 2) continue
                val config = SlideCurveGeometry.sectorConfig(level, a, b)
                    ?: WaterslideSectorConfig.defaultConfig()
                for (i in 0 until frames.size - 1) {
                    val hit = testSegment(p, bc, frames[i], frames[i + 1], config, requireSolid) ?: continue
                    if (best == null || hit.distSq < best.distSq) best = hit
                }
            }
        }
        return best?.entry
    }

    private fun testSegment(
        p: Vec3,
        bc: BezierConnection,
        fa: SlideCurveGeometry.Frame,
        fb: SlideCurveGeometry.Frame,
        config: WaterslideSectorConfig,
        requireSolid: Boolean
    ): SegmentHit? {
        val ab = fb.center.subtract(fa.center)
        val lenSq = ab.lengthSqr()
        val f = if (lenSq < 1.0E-9) 0.0
        else ((p.subtract(fa.center)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
        val closest = fa.center.add(ab.scale(f))
        val radial = p.subtract(closest)
        val axisDist = radial.length()
        val radius = (fa.radius + (fb.radius - fa.radius) * f.toFloat()).toDouble()
        if (axisDist > radius - 0.25) return null

        val tan = fa.tangent.lerp(fb.tangent, f).normalize()
        val lat = fa.lateral.lerp(fb.lateral, f).normalize()
        val up = fa.up.lerp(fb.up, f).normalize()
        val angle = Math.toDegrees(atan2(radial.dot(up), radial.dot(lat))).toFloat()
        val sector = WaterslideSectorLayout.sectorAt(WaterslideSectorLayout.place(config), angle)
            ?: return null
        if (requireSolid && sector.sector.material == SectorMaterial.OPEN) return null

        val startT = fa.t + (fb.t - fa.t) * f.toFloat()
        val towardSecond = if (startT < 0.02f) true
        else if (startT > 0.98f) false
        else {
            val tanAt = CoasterBezierRailFrames.unitTangentAt(bc, startT).normalize()
            when {
                tanAt.y < -0.02 -> true
                tanAt.y > 0.02 -> false
                else -> bc.bePositions.getSecond().y <= bc.bePositions.getFirst().y
            }
        }
        val anchorPos = if (startT <= 0.5f) bc.bePositions.getFirst() else bc.bePositions.getSecond()
        val entryStartT = if (startT < 0.02f || startT > 0.98f) null else startT
        return SegmentHit(
            SlideEntry(bc, towardSecond, entryStartT, anchorPos),
            p.distanceToSqr(closest)
        )
    }

    private fun tickSession(level: ServerLevel, session: Session) {
        val entity = session.entity
        val player = session.player
        if (player != null && player.isShiftKeyDown) {
            onCancel(player, session.id)
            return
        }
        val sit = session.sit
        if (sit != null && (sit.isRemoved || entity.vehicle !== sit)) {
            endSession(level, session, SlideEndReason.CANCELLED)
            return
        }
        session.elapsed += 1.0 / 20.0
        if (session.elapsed >= session.trajectory.duration) {
            if (session.trajectory.landedOnSlide) {
                reenterSlide(level, session)
            } else {
                endSession(level, session, session.trajectory.endReason)
            }
            return
        }

        val at = session.trajectory.sampleAt(session.elapsed)
        val worldPos = toWorldPos(level, session, at.sample.position)
        val sitPos = if (sit != null) worldPos.subtract(0.0, SIT_HEIGHT, 0.0) else worldPos
        val worldTan = toWorldNormal(level, session, at.sample.tangent)
        val worldVel = toWorldVel(level, session, at.sample.position, at.sample.tangent.scale(at.sample.speed))

        entity.setPos(sitPos)
        entity.setDeltaMovement(worldVel)
        sit?.setPos(sitPos)
        applyRotation(entity, worldTan)
        if (entity is LivingEntity) {
            entity.setPose(if (session.swimmingPose) Pose.SWIMMING else Pose.SITTING)
            entity.setNoGravity(true)
            entity.setSprinting(false)
        }
        entity.fallDistance = 0f

        if (player != null && level.gameTime - session.lastSyncTick >= 20) {
            session.lastSyncTick = level.gameTime
            SlidePackets.sendTo(player, SlideSyncPayload(
                session.id, (session.elapsed * 20.0).toInt()
            ))
        }
    }

    private fun reenterSlide(level: ServerLevel, session: Session) {
        val entity = session.entity
        val player = session.player
        val at = session.trajectory.sampleAt(session.trajectory.duration)
        val worldPos = toWorldPos(level, session, at.sample.position)
        val worldVel = toWorldVel(level, session, at.sample.position, session.trajectory.exitVelocity)
        entity.setPos(worldPos)
        entity.setDeltaMovement(worldVel)
        val entry = findSlideEntry(level, entity, requireSolid = false)
        if (entry == null) {
            endSession(level, session, session.trajectory.endReason)
            return
        }
        var startVelWorld = worldVel.scale(20.0)
        val maxSpeed = ModConfig.slideMaxEntrySpeed()
        if (startVelWorld.lengthSqr() > maxSpeed * maxSpeed) {
            startVelWorld = startVelWorld.normalize().scale(maxSpeed)
        }
        val subLevel = Sable.HELPER.getContaining(level, entry.anchorPos) as? ServerSubLevel
        val startPos = if (subLevel != null) toLocalPos(subLevel, entity.position()) else entity.position()
        val startVel = if (subLevel != null) toLocalVel(subLevel, startVelWorld) else startVelWorld
        val dims = entityDimensions(entity)
        val newTrajectory = PhysicsSlideTrajectoryBuilder.build(
            level, entry.curve, entry.towardSecond, entry.startT,
            startPos, startVel, dims.width.toDouble(), dims.height.toDouble()
        )
        if (newTrajectory == null) {
            endSession(level, session, session.trajectory.endReason)
            return
        }
        session.trajectory = newTrajectory
        session.subLevelId = subLevel?.uniqueId
        session.startTick = level.gameTime
        session.elapsed = 0.0
        session.lastSyncTick = 0L
        CreateWaterparked.LOGGER.info(
            "Slide reenter {} pos {} vel {}",
            session.id, worldPos, worldVel
        )
        if (player != null) {
            SlidePackets.sendTo(player, SlideTrajectoryPayload(
                session.id, session.startTick, session.swimmingPose, session.subLevelId,
                newTrajectory.samples.map { SlideSampleWire.from(it) }
            ))
        }
    }

    private fun endSession(level: ServerLevel, session: Session, reason: SlideEndReason) {
        val entity = session.entity
        val player = session.player
        val at = session.trajectory.sampleAt(session.trajectory.duration)
        val worldPos = toWorldPos(level, session, at.sample.position)
        val worldVel = if (reason == SlideEndReason.STOPPED) Vec3.ZERO
        else toWorldVel(level, session, at.sample.position, session.trajectory.exitVelocity)
        CreateWaterparked.LOGGER.info(
            "Slide end {} reason {} pos {} vel {}",
            session.id, reason, worldPos, worldVel
        )

        cleanupSit(session, entity)
        entity.setPos(worldPos)
        entity.setDeltaMovement(worldVel)
        restoreEntity(entity)
        sessions.remove(entity.uuid)
        if (reason == SlideEndReason.EXITED) {
            entryCooldown[entity.uuid] = level.gameTime + 10
        }
        if (player != null) {
            SlidePackets.sendTo(player, SlideEndPayload(
                session.id, reason.ordinal.toByte(),
                worldPos.x.toFloat(), worldPos.y.toFloat(), worldPos.z.toFloat(),
                worldVel.x.toFloat(), worldVel.y.toFloat(), worldVel.z.toFloat()
            ))
        }
    }

    private fun restoreEntity(entity: Entity) {
        if (entity is LivingEntity) {
            entity.setNoGravity(false)
            entity.setPose(Pose.STANDING)
            entity.refreshDimensions()
        }
        entity.fallDistance = 0f
    }

    private fun toWorldPos(level: ServerLevel, session: Session, local: Vec3): Vec3 {
        val sub = session.subLevel(level) ?: return local
        val out = sub.logicalPose().transformPosition(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun toWorldNormal(level: ServerLevel, session: Session, local: Vec3): Vec3 {
        val sub = session.subLevel(level) ?: return local.normalize()
        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out).normalize()
    }

    private fun toWorldVel(
        level: ServerLevel,
        session: Session,
        localPos: Vec3,
        localVel: Vec3
    ): Vec3 {
        val sub = session.subLevel(level)
        if (sub == null) return localVel.scale(1.0 / 20.0)

        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(localVel), Vector3d())
        val structure = Sable.HELPER.getVelocity(
            level, sub, JOMLConversion.toJOML(localPos), Vector3d()
        )
        out.add(structure)
        return JOMLConversion.toMojang(out).scale(1.0 / 20.0)
    }

    private fun slideBounds(level: ServerLevel): AABB? {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        var found = false
        for (pos in SlideAnchorIndex.all(level)) {
            found = true
            minX = minOf(minX, pos.x - 8.0)
            minY = minOf(minY, pos.y - 8.0)
            minZ = minOf(minZ, pos.z - 8.0)
            maxX = maxOf(maxX, pos.x + 8.0)
            maxY = maxOf(maxY, pos.y + 8.0)
            maxZ = maxOf(maxZ, pos.z + 8.0)
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                val bounds = bc.getBounds()
                minX = minOf(minX, bounds.minX)
                minY = minOf(minY, bounds.minY)
                minZ = minOf(minZ, bounds.minZ)
                maxX = maxOf(maxX, bounds.maxX)
                maxY = maxOf(maxY, bounds.maxY)
                maxZ = maxOf(maxZ, bounds.maxZ)
            }
        }
        if (!found) return null
        return AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(24.0)
    }

    private fun entityDimensions(entity: Entity): EntityDimensions =
        if (entity is LivingEntity) entity.getDimensions(entity.getPose())
        else entity.getDimensions(Pose.STANDING)

    private fun toLocalPos(sub: ServerSubLevel, world: Vec3): Vec3 {
        val out = sub.logicalPose().transformPositionInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun toLocalVel(sub: ServerSubLevel, world: Vec3): Vec3 {
        val out = sub.logicalPose().transformNormalInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun applyRotation(entity: Entity, tangent: Vec3) {
        val horiz = sqrt(tangent.x * tangent.x + tangent.z * tangent.z)
        val yaw = Math.toDegrees(atan2(-tangent.x, tangent.z)).toFloat()
        val pitch = Math.toDegrees(atan2(-tangent.y, horiz)).toFloat()
        entity.setYRot(yaw)
        entity.setXRot(pitch)
        entity.setYHeadRot(yaw)
        if (entity is LivingEntity) entity.setYBodyRot(yaw)
    }
}
