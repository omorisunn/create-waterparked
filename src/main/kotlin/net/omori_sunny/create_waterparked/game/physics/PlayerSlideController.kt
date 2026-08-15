package net.omori_sunny.create_waterparked.game.physics

import com.simibubi.create.AllItems
import com.simibubi.create.content.equipment.armor.DivingBootsItem
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
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import net.omori_sunny.create_waterparked.game.water.SlideWaterManager
import net.omori_sunny.create_waterparked.network.SlideEndPayload
import net.omori_sunny.create_waterparked.network.SlidePackets
import net.omori_sunny.create_waterparked.network.SlideSampleWire
import net.omori_sunny.create_waterparked.network.SlideSegmentPayload
import net.omori_sunny.create_waterparked.network.SlideSyncPayload
import net.omori_sunny.create_waterparked.network.SlideTrajectoryPayload
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.atan2
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

    private data class CurveFrames(
        val sig: String,
        val frames: List<SlideCurveGeometry.Frame>,
        val bounds: AABB
    )

    private val sessions = mutableMapOf<UUID, Session>()
    private val lastPos = mutableMapOf<UUID, Vec3>()
    private val curveFramesCache = HashMap<Pair<Long, Long>, CurveFrames>()
    private val entryCooldown = mutableMapOf<UUID, Long>()
    private var nextSessionId = 1L
    private var perfEntryNs = 0L
    private var perfSwitchNs = 0L
    private var perfSamples = 0

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
            for (anchorPos in SlideAnchorIndex.all(level, SlideSpace.Main).toList()) {
                val be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity
                if (be != null) SlideWaterManager.tickServer(level, be)
                else SlideAnchorIndex.unregister(level, anchorPos)
            }
            SubLevelContainer.getContainer(level)?.allSubLevels?.forEach { raw ->
                val sub = raw as? ServerSubLevel ?: return@forEach
                val access = SubSlideSpaceAccess(level, sub)
                for (anchorPos in SlideAnchorIndex.all(level, access.space).toList()) {
                    val be = access.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity
                    if (be != null) SlideWaterManager.tickServer(level, be)
                    else SlideAnchorIndex.unregister(level, anchorPos)
                }
            }
            SableCoordProbe.dump(level)
            ServerWaterSimulation.tickServer(level)
            SubLevelContainer.getContainer(level)?.allSubLevels?.forEach { raw ->
                val sub = raw as? ServerSubLevel ?: return@forEach
                ServerWaterSimulation.tickServer(SubSlideSpaceAccess(level, sub))
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
                    val t0 = System.nanoTime()
                    tryStartSlide(level, entity)
                    perfEntryNs += System.nanoTime() - t0
                    perfSamples++
                }
            }
            if (level.gameTime % 200 == 0L && perfSamples > 0) {
                CreateWaterparked.LOGGER.info(
                    "[SlidePerf] entryUs={} switchUs={} samples={}",
                    perfEntryNs / perfSamples / 1000, perfSwitchNs / 200 / 1000, perfSamples
                )
                perfEntryNs = 0L
                perfSwitchNs = 0L
                perfSamples = 0
            }
            // water flow pushes players standing inside the tube (not while sliding)
            for (player in level.players()) {
                if (player.isRemoved || sessions.containsKey(player.uuid)) continue
                val center = player.position().add(0.0, player.bbHeight / 2.0, 0.0)
                val waterVel = ServerWaterSimulation.waterVelocityAt(level, center) ?: continue
                // push scales with the player's velocity relative to the water
                val relVel = waterVel.subtract(player.deltaMovement.scale(20.0))
                player.addDeltaMovement(relVel.scale(0.2 / 20.0))
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
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        // resync the water field for late joiners
        val level = event.entity.level()
        ServerWaterSimulation.resync(level)
        if (level is ServerLevel) ServerWaterSimulation.resendTo(level)
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
        val worldPos = toWorldPos(level, session, at.sample.position)
        val worldVel = toWorldVel(level, session, at.sample.position, at.sample.tangent.scale(at.sample.speed))
        CreateWaterparked.LOGGER.info(
            "Slide cancel {} pos {} vel {}",
            session.id, worldPos, worldVel
        )

        cleanupSit(session, player)
        player.setPos(worldPos)
        player.setDeltaMovement(worldVel)
        restoreEntity(player)
        sessions.remove(player.uuid)
        entryCooldown[player.uuid] = level.gameTime + ModConfig.slideCancelCooldownTicks()
        SlidePackets.sendTo(player, SlideEndPayload(
            session.id, SlideEndReason.EXITED.ordinal.toByte(),
            worldPos.x.toFloat(), worldPos.y.toFloat(), worldPos.z.toFloat(),
            worldVel.x.toFloat(), worldVel.y.toFloat(), worldVel.z.toFloat()
        ))
    }

    private fun tryStartSlide(level: ServerLevel, entity: Entity) {
        if (isWearingCopperDivingBoots(entity)) return
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

        // keep the standing body height when switching to the sitting pose
        if (!swimming && entity is LivingEntity) {
            val lift = (entity.getDimensions(Pose.STANDING).height -
                entity.getDimensions(Pose.SITTING).height).coerceAtLeast(0f)
            if (lift > 0.001f) {
                entity.setPos(entity.position().add(0.0, lift.toDouble(), 0.0))
            }
        }

        val dims = entityDimensions(entity)
        // real per-tick velocity, blocks/tick -> blocks/sec
        val velWorld = entity.deltaMovement.scale(20.0)
        val entryTanWorld = entryTangentWorld(level, entry)
        val along = velWorld.dot(entryTanWorld)
        if (along < -0.5) return
        // preserve the player's actual 3D velocity (direction AND magnitude)
        // and add the configured entrance boost; the trajectory builder already
        // starts from the real position/velocity and resolves any wall contact
        var startVelWorld = velWorld
            .add(entryTanWorld.scale(ModConfig.entranceBoost() * 20.0))
        val maxSpeed = ModConfig.slideMaxEntrySpeed()
        if (startVelWorld.lengthSqr() > maxSpeed * maxSpeed) {
            startVelWorld = startVelWorld.normalize().scale(maxSpeed)
        }
        val subLevel = Sable.HELPER.getContaining(level, entry.anchorPos) as? ServerSubLevel
        val access = if (subLevel != null) SubSlideSpaceAccess(level, subLevel) else MainSlideSpaceAccess(level)
        val startPos = if (subLevel != null) toLocalPos(subLevel, entity.position()) else entity.position()
        val startVel = if (subLevel != null) toLocalVel(subLevel, startVelWorld) else startVelWorld
        val trajectory = PhysicsSlideTrajectoryBuilder.build(
            access, entry.curve, entry.towardSecond, entry.startT,
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
            "Slide start {} entity {} dir {} pos {} vel {} samples={} last={} reason={}",
            session.id, entity.uuid, entry.towardSecond, startPos, startVel,
            trajectory.samples.size, trajectory.exitPosition, trajectory.endReason
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
        val mainHit = findSlideEntryInSpace(level, MainSlideSpaceAccess(level), entity, requireSolid)
        if (mainHit != null) return mainHit

        val container = SubLevelContainer.getContainer(level) ?: return null
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            val access = SubSlideSpaceAccess(level, sub)
            val hit = findSlideEntryInSpace(level, access, entity, requireSolid) ?: continue
            return hit
        }
        return null
    }

    private fun findSlideEntryInSpace(
        level: ServerLevel,
        access: SlideSpaceAccess,
        entity: Entity,
        requireSolid: Boolean
    ): SlideEntry? {
        val localPos = access.worldToLocal(entity.position())
        val margin = entityDimensions(entity).width / 2.0
        var best: SegmentHit? = null
        for (anchorPos in SlideAnchorIndex.all(level, access.space)) {
            val be = access.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity
            if (be == null) {
                SlideAnchorIndex.unregister(level, anchorPos)
                continue
            }
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(access.level, a)
                val r1 = SlideCurveGeometry.radiusAt(access.level, b)
                val cf = curveFrames(access, bc, r0, r1) ?: continue
                if (!cf.bounds.contains(localPos)) continue
                val config = SlideCurveGeometry.sectorConfig(access.level, a, b)
                    ?: WaterslideSectorConfig.defaultConfig()
                for (i in 0 until cf.frames.size - 1) {
                    val hit = testSegment(
                        localPos, bc, cf.frames[i], cf.frames[i + 1], config, requireSolid, margin
                    ) ?: continue
                    if (best == null || hit.distSq < best.distSq) best = hit
                }
            }
        }
        return best?.entry
    }

    // cached per-curve tube envelope; no max-radius guess
    private fun curveFrames(
        access: SlideSpaceAccess,
        bc: BezierConnection,
        r0: Float,
        r1: Float
    ): CurveFrames? {
        val a = bc.bePositions.getFirst()
        val b = bc.bePositions.getSecond()
        val key = if (a.asLong() <= b.asLong()) a.asLong() to b.asLong()
        else b.asLong() to a.asLong()
        val h0 = bc.starts.getFirst()
        val h1 = bc.starts.getSecond()
        val sig = "${access.space.cacheKey(access.level)}|$r0,$r1,${h0.x},${h0.y},${h0.z},${h1.x},${h1.y},${h1.z},${bc.getSegmentCount()}"
        curveFramesCache[key]?.let { if (it.sig == sig) return it }

        // entry only inside the real tube, never on the open-end extension
        val frames = SlideCurveGeometry.sampleFrames(access.level, bc, r0, r1, includeExtensions = false)
        if (frames.size < 2) return null
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (f in frames) {
            val r = f.radius.toDouble() + 1.0
            minX = minOf(minX, f.center.x - r)
            minY = minOf(minY, f.center.y - r)
            minZ = minOf(minZ, f.center.z - r)
            maxX = maxOf(maxX, f.center.x + r)
            maxY = maxOf(maxY, f.center.y + r)
            maxZ = maxOf(maxZ, f.center.z + r)
        }
        val cf = CurveFrames(sig, frames, AABB(minX, minY, minZ, maxX, maxY, maxZ))
        if (curveFramesCache.size > 1024) curveFramesCache.clear()
        curveFramesCache[key] = cf
        return cf
    }

    private fun testSegment(
        p: Vec3,
        bc: BezierConnection,
        fa: SlideCurveGeometry.Frame,
        fb: SlideCurveGeometry.Frame,
        config: WaterslideSectorConfig,
        requireSolid: Boolean,
        margin: Double
    ): SegmentHit? {
        val ab = fb.center.subtract(fa.center)
        val lenSq = ab.lengthSqr()
        val f = if (lenSq < 1.0E-9) 0.0
        else ((p.subtract(fa.center)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
        val closest = fa.center.add(ab.scale(f))
        val radial = p.subtract(closest)
        val axisDist = radial.length()
        val radius = (fa.radius + (fb.radius - fa.radius) * f.toFloat()).toDouble()
        if (axisDist > radius - SLIDE_WALL_THICKNESS - margin) return null

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
            endSession(level, session, SlideEndReason.EXITED)
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

        val t0 = System.nanoTime()
        val switched = trySwitchSpace(level, session, worldPos, worldVel)
        perfSwitchNs += System.nanoTime() - t0
        if (switched) {
            // restart playback in the new space immediately, same tick
            val first = session.trajectory.samples.first()
            val newWorld = toWorldPos(level, session, first.position)
            val newSitPos = if (sit != null) newWorld.subtract(0.0, SIT_HEIGHT, 0.0) else newWorld
            val newWorldVel = toWorldVel(level, session, first.position, first.tangent.scale(first.speed))
            entity.setPos(newSitPos)
            entity.setDeltaMovement(newWorldVel)
            sit?.setPos(newSitPos)
            applyRotation(entity, toWorldNormal(level, session, first.tangent))
            if (player != null) {
                SlidePackets.sendTo(player, SlideSegmentPayload(
                    session.id, level.gameTime, session.subLevelId,
                    session.trajectory.samples.map { SlideSampleWire.from(it) }
                ))
            }
            return
        }

        if (player != null && level.gameTime - session.lastSyncTick >= 20) {
            session.lastSyncTick = level.gameTime
            SlidePackets.sendTo(player, SlideSyncPayload(
                session.id, (session.elapsed * 20.0).toInt()
            ))
        }
    }

    private fun trySwitchSpace(
        level: ServerLevel,
        session: Session,
        worldPos: Vec3,
        worldVel: Vec3
    ): Boolean {
        val currentSub = session.subLevel(level)
        val worldVelPerSecond = worldVel.scale(20.0)

        fun tryTarget(access: SlideSpaceAccess): Boolean {
            if (access.space == (currentSub?.let { SlideSpace.SubLevel(it.uniqueId) } ?: SlideSpace.Main)) return false
            val localNow = access.worldToLocal(worldPos)
            val entry = findSlideEntryInSpace(level, access, session.entity, requireSolid = false) ?: return false
            val structure = access.worldVelocityAt(localNow)
            val localVel = access.worldNormalToLocal(worldVelPerSecond.subtract(structure))
            val dims = entityDimensions(session.entity)
            val next = PhysicsSlideTrajectoryBuilder.build(
                access, entry.curve, entry.towardSecond, entry.startT,
                localNow, localVel, dims.width.toDouble(), dims.height.toDouble()
            ) ?: return false
            session.trajectory = next
            session.subLevelId = (access.space as? SlideSpace.SubLevel)?.id
            session.elapsed = 0.0
            return true
        }

        if (currentSub != null && tryTarget(MainSlideSpaceAccess(level))) return true
        val container = SubLevelContainer.getContainer(level) ?: return false
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            if (currentSub?.uniqueId == sub.uniqueId) continue
            if (tryTarget(SubSlideSpaceAccess(level, sub))) return true
        }
        return false
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
        val plotCenter = Vec3.atLowerCornerOf(sub.getPlot().getCenterBlock())
        val out = sub.logicalPose().transformPosition(
            JOMLConversion.toJOML(local.subtract(plotCenter)), Vector3d()
        )
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

        val plotCenter = Vec3.atLowerCornerOf(sub.getPlot().getCenterBlock())
        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(localVel), Vector3d())
        val structure = Sable.HELPER.getVelocity(
            level, sub, JOMLConversion.toJOML(localPos.subtract(plotCenter)), Vector3d()
        )
        out.add(structure)
        return JOMLConversion.toMojang(out).scale(1.0 / 20.0)
    }

    private val boundsCache = mutableMapOf<ResourceKey<Level>, Pair<Int, AABB?>>()

    private fun slideBounds(level: ServerLevel): AABB? {
        val key = boundsKey(level)
        val cached = boundsCache[level.dimension()]
        if (cached != null && cached.first == key) return cached.second
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        var found = false
        val seen = mutableSetOf<Pair<Long, Long>>()
        for (pos in SlideAnchorIndex.all(level)) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            found = true
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val key = if (a.asLong() <= b.asLong()) a.asLong() to b.asLong()
                else b.asLong() to a.asLong()
                if (!seen.add(key)) continue
                val bounds = bc.getBounds()
                minX = minOf(minX, bounds.minX)
                minY = minOf(minY, bounds.minY)
                minZ = minOf(minZ, bounds.minZ)
                maxX = maxOf(maxX, bounds.maxX)
                maxY = maxOf(maxY, bounds.maxY)
                maxZ = maxOf(maxZ, bounds.maxZ)
                val r0 = SlideCurveGeometry.radiusAt(level, a)
                val r1 = SlideCurveGeometry.radiusAt(level, b)
                for (f in SlideCurveGeometry.sampleFrames(level, bc, r0, r1)) {
                    val r = f.radius.toDouble() + 1.0
                    minX = minOf(minX, f.center.x - r)
                    minY = minOf(minY, f.center.y - r)
                    minZ = minOf(minZ, f.center.z - r)
                    maxX = maxOf(maxX, f.center.x + r)
                    maxY = maxOf(maxY, f.center.y + r)
                    maxZ = maxOf(maxZ, f.center.z + r)
                }
            }
        }
        val result = if (found) AABB(minX, minY, minZ, maxX, maxY, maxZ) else null
        // also scan entities inside sub-level world bounds, not just main-world
        // slide geometry bounds
        SubLevelContainer.getContainer(level)?.allSubLevels?.forEach { raw ->
            val sub = raw as? ServerSubLevel ?: return@forEach
            val b = sub.boundingBox()
            if (result == null) {
                minX = b.minX(); minY = b.minY(); minZ = b.minZ()
                maxX = b.maxX(); maxY = b.maxY(); maxZ = b.maxZ()
            } else {
                minX = minOf(minX, b.minX()); minY = minOf(minY, b.minY()); minZ = minOf(minZ, b.minZ())
                maxX = maxOf(maxX, b.maxX()); maxY = maxOf(maxY, b.maxY()); maxZ = maxOf(maxZ, b.maxZ())
            }
        }
        val finalBounds = if (result != null || found) AABB(minX, minY, minZ, maxX, maxY, maxZ) else null
        boundsCache[level.dimension()] = key to finalBounds
        return finalBounds
    }

    // structural fingerprint: only rebuild the box when the slide graph changes
    private fun boundsKey(level: ServerLevel): Int {
        var key = 0
        for (pos in SlideAnchorIndex.all(level)) {
            key = key * 31 + pos.hashCode()
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val bounds = bc.getBounds()
                key = key * 31 + Mth.floor(bounds.minX) + Mth.floor(bounds.minY) * 7 +
                    Mth.floor(bounds.minZ) * 13 + Mth.floor(bounds.maxX) * 17 +
                    Mth.floor(bounds.maxY) * 23 + Mth.floor(bounds.maxZ) * 29
                key = key * 31 + a.hashCode() + b.hashCode() * 37
                key = key * 31 + (SlideCurveGeometry.radiusAt(level, a) * 4f).toInt() +
                    (SlideCurveGeometry.radiusAt(level, b) * 4f).toInt() * 41
            }
        }
        return key
    }

    // Create copper diving boots are heavy enough to keep the player from
    // being swept into a slide (netherite diving boots are intentionally NOT
    // affected by this rule).
    private fun isWearingCopperDivingBoots(entity: Entity): Boolean {
        if (entity !is LivingEntity) return false
        val worn = DivingBootsItem.getWornItem(entity)
        return !worn.isEmpty && worn.item === AllItems.COPPER_DIVING_BOOTS.get()
    }

    private fun entityDimensions(entity: Entity): EntityDimensions =
        if (entity is LivingEntity) entity.getDimensions(entity.getPose())
        else entity.getDimensions(Pose.STANDING)

    private fun toLocalPos(sub: ServerSubLevel, world: Vec3): Vec3 {
        val out = sub.logicalPose().transformPositionInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out).add(Vec3.atLowerCornerOf(sub.getPlot().getCenterBlock()))
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
