package net.omori_sunny.create_waterparked.game.physics

import com.simibubi.create.AllItems
import com.simibubi.create.content.contraptions.AbstractContraptionEntity
import com.simibubi.create.content.equipment.armor.DivingBootsItem
import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension
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
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
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
    private const val RIDER_SIG_CACHE_TICKS = 5L
    // How long a rider stays locked to the exit point of a sub-level slide
    // after the ride ends. The lock gives Sable one or two ticks to register
    // the landing, then it MUST be released: leaving sable$plotPosition set
    // makes EntityMixin keep teleporting the player back to that point every
    // tick, which blocks normal movement and item pickup.
    private const val POST_RIDE_STICK_TICKS = 10L
    private val CREATIVE_PHYSICS_STAFF =
        ResourceLocation.fromNamespaceAndPath("simulated", "creative_physics_staff")

    private class Session(
        val id: Long,
        val entity: Entity,
        val player: ServerPlayer?,
        var trajectory: SlideTrajectory,
        var subLevelId: UUID?,
        var contraption: AbstractContraptionEntity?,
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

        fun access(level: ServerLevel): SlideSpaceAccess {
            val sub = subLevel(level)
            if (sub != null) return SubSlideSpaceAccess(level, sub)
            val cp = contraption
            if (cp != null) return ContraptionSlideSpaceAccess(level, cp)
            return MainSlideSpaceAccess(level)
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
    private val postRideRelease = mutableMapOf<UUID, Pair<ResourceKey<Level>, Long>>()
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
            val releaseIt = postRideRelease.entries.iterator()
            while (releaseIt.hasNext()) {
                val (uuid, release) = releaseIt.next()
                if (release.first != level.dimension()) continue
                val entity = level.getEntity(uuid)
                if (entity == null) {
                    releaseIt.remove()
                } else if (level.gameTime >= release.second) {
                    clearStick(entity)
                    releaseIt.remove()
                }
            }
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
            ServerWaterSimulation.tickAll(level)
            // track contraption poses so fresh ContraptionSlideSpaceAccess
            // instances still report correct structure velocity
            ContraptionSlideSpaces.updatePrev(level)
            for (session in sessions.values.toList()) {
                if (session.entity.level() != level) continue
                tickSession(level, session)
            }
            val bounds = slideBounds(level)
            if (bounds != null) {
                for (entity in level.getEntities(null, bounds)) {
                    if (entity.isRemoved) continue
                    if (entity is SlideSitEntity) continue
                    if (entity is net.minecraft.world.entity.player.Player) continue
                    val prev = lastPos.put(entity.uuid, entity.position())
                    if (!sessions.containsKey(entity.uuid) && prev != null) {
                        val t0 = System.nanoTime()
                        tryStartSlide(level, entity)
                        perfEntryNs += System.nanoTime() - t0
                        perfSamples++
                    }
                }
            }
            // players are checked directly so entry detection also works while
            // the player is inside a Sable sub-level (world AABB queries above
            // use main-world slide geometry bounds only)
            for (player in level.players()) {
                if (player.isRemoved || sessions.containsKey(player.uuid)) continue
                val prev = lastPos.put(player.uuid, player.position())
                if (prev == null) continue
                val t0 = System.nanoTime()
                tryStartSlide(level, player)
                perfEntryNs += System.nanoTime() - t0
                perfSamples++
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
                // The Simulated physics staff drags a sub-level to the holder's
                // eye position. Pushing the holder here would make the dragged
                // sub-level chase that movement every tick and fly around.
                if (isHoldingCreativePhysicsStaff(player)) continue
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
        postRideRelease.remove(event.entity.uuid)
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
        releaseStickAfterRide(level, player, session.subLevel(level))
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
        val found = findSlideEntry(level, entity) ?: return
        val entry = found.second
        val entryAccess = found.first

        // Never auto-capture a player holding the Simulated creative physics
        // staff into a sub-level slide. The staff makes the dragged sub-level
        // follow the holder's eye, so moving the player along the slide would
        // feed back into the staff target and send the sub-level flying.
        if (entity is ServerPlayer && isHoldingCreativePhysicsStaff(entity) &&
            entryAccess is SubSlideSpaceAccess
        ) {
            if (level.gameTime % 200 == 0L) {
                CreateWaterparked.LOGGER.info(
                    "[StaffGuard] skipped sub-level slide entry for staff holder {}", entity.uuid
                )
            }
            return
        }

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
        // Sable moves tracked living entities by a separate `inheritedVelocity`
        // (the sub-level's motion, blocks/tick) applied to position during
        // travel(). entity.deltaMovement alone does NOT contain the structure
        // motion, so include both before subtracting the structure velocity
        // below. The result is the player's own velocity relative to the slide.
        val inherited = (entity as? LivingEntityMovementExtension)?.`sable$getInheritedVelocity`()
        val playerVelPerTick = if (inherited == null) entity.deltaMovement
        else entity.deltaMovement.add(inherited.x, inherited.y, inherited.z)
        // real per-tick velocity, blocks/tick -> blocks/sec
        val rawVelWorld = playerVelPerTick.scale(20.0)
        val entryTanWorld = entryTangentWorld(level, entryAccess, entry)
        val subLevel = (entryAccess as? SubSlideSpaceAccess)?.sub
        val contraptionEntity = (entryAccess as? ContraptionSlideSpaceAccess)?.entity
        val access = entryAccess
        val startPos = access.worldToLocal(entity.position())
        // Sanity guard: never teleport the rider to an absurd position. A
        // space mismatch (e.g. a contraption entry detected through the wrong
        // access) would otherwise move the player to an enormous y. However a
        // contraption hosted INSIDE a Sable sub-level legitimately sits at
        // plot-global coordinates (~2e7) with the player also at plot scale, so
        // only abort when the target is absurd while the player is NOT plot-
        // scaled (a genuine world/main-scale mismatch).
        val wouldPos = access.toWorld(startPos)
        val spaceLabel = access.space.cacheKey(level)
        val playerIsPlotScaled = kotlin.math.abs(entity.position().x) > 1.0E5 ||
            kotlin.math.abs(entity.position().z) > 1.0E5
        val targetAbsurd = !(wouldPos.x.isFinite() && wouldPos.y.isFinite() && wouldPos.z.isFinite()) ||
            kotlin.math.abs(wouldPos.y) > 100_000.0 || kotlin.math.abs(wouldPos.x) > 100_000.0 ||
            kotlin.math.abs(wouldPos.z) > 100_000.0
        if (targetAbsurd && !playerIsPlotScaled) {
            CreateWaterparked.LOGGER.warn(
                "[SlideEntry] aborted absurd teleport target={} space={} startLocal={} playerWorld={} contraptionPos={}",
                wouldPos, spaceLabel, startPos, entity.position(),
                (access as? ContraptionSlideSpaceAccess)?.entity?.position()
            )
            restoreEntity(entity)
            return
        }
        // On a Sable sub-level the entity can follow the structure in two ways:
        // 1. plot tracking: Sable repositions the entity from sable$plotPosition
        //    through the logical pose each tick. The structure velocity is then
        //    OUTSIDE deltaMovement/inherited, so subtracting it again gave a
        //    standing player a huge backwards velocity and bounced them out of
        //    the entrance.
        // 2. collision inheritance: Sable adds collisionInfo.inheritedMotion on
        //    top of deltaMovement; then the structure velocity must be removed.
        val structureVel = access.worldVelocityAt(startPos)
        val plotTracked = (entity as? EntityStickExtension)?.`sable$getPlotPosition`() != null
        val velWorld = if (plotTracked) rawVelWorld
        else rawVelWorld.subtract(structureVel)
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
        val startVel = access.worldNormalToLocal(startVelWorld)
        CreateWaterparked.LOGGER.info(
            "[EntryVel] sub={} delta={} inherited={} rawWorld={} structure={} relative={} along={} tangentWorld={} startWorld={} startLocal={} gravity={} plotTracked={}",
            subLevel?.uniqueId, entity.deltaMovement, inherited, rawVelWorld, structureVel, velWorld, along,
            entryTanWorld, startVelWorld, startVel, access.localGravity(), plotTracked
        )
        val trajectory = PhysicsSlideTrajectoryBuilder.build(
            access, entry.curve, entry.towardSecond, entry.startT,
            startPos, startVel, dims.width.toDouble(), dims.height.toDouble()
        )
        if (trajectory == null) {
            restoreEntity(entity)
            return
        }

        // No realtime state: main-branch style precomputed trajectory playback.
        val sit = if (player != null && !swimming) spawnSit(level, player) else null
        if (sit != null && player != null) player.startRiding(sit, true)
        postRideRelease.remove(entity.uuid)
        // A sub-level rider must be tracked into the sub-level BEFORE the next
        // vanilla movement pass, otherwise Sable's inclusive entity getter
        // observes AABBs that span main-world and plot-global coordinates.
        bindToSpace(entity, sit, subLevel, startPos)
        entity.setPos(access.toWorld(startPos))
        sit?.setPos(access.toWorld(startPos))
        val session = Session(
            nextSessionId++, entity, player, trajectory,
            subLevel?.uniqueId, contraptionEntity, swimming, sit, level.gameTime
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
                session.id, session.startTick, swimming, session.subLevelId, session.contraption?.id,
                trajectory.samples.map { SlideSampleWire.from(it, wireOffset(level, session)) }
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

    private fun entryTangentWorld(level: ServerLevel, access: SlideSpaceAccess, entry: SlideEntry): Vec3 {
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
        return access.toWorldNormal(tan)
    }

    private fun findSlideEntry(
        level: ServerLevel,
        entity: Entity,
        requireSolid: Boolean = true
    ): Pair<SlideSpaceAccess, SlideEntry>? {
        val mainHit = findSlideEntryInSpace(level, MainSlideSpaceAccess(level), entity, requireSolid)
        if (mainHit != null) return mainHit

        val container = SubLevelContainer.getContainer(level)
        if (container != null) {
            for (raw in container.allSubLevels) {
                val sub = raw as? ServerSubLevel ?: continue
                val access = SubSlideSpaceAccess(level, sub)
                val hit = findSlideEntryInSpace(level, access, entity, requireSolid) ?: continue
                return hit
            }
        }

        // Create contraptions carrying a waterslide are their own slide space.
        for (cp in contraptionCandidates(level, entity)) {
            val access = ContraptionSlideSpaceAccess(level, cp)
            val hit = findSlideEntryInSpace(level, access, entity, requireSolid) ?: continue
            return hit
        }
        return null
    }

    // Contraptions carrying slides whose bounding box touches the entity's
    // neighbourhood; entry probing uses the contraption-local inverse transform.
    private fun contraptionCandidates(level: ServerLevel, entity: Entity): List<AbstractContraptionEntity> {
        val box = entity.boundingBox.inflate(6.0)
        val out = ArrayList<AbstractContraptionEntity>()
        for (cp in level.getEntitiesOfClass(AbstractContraptionEntity::class.java, box)) {
            if (!ContraptionSlideSpaces.carriesSlides(cp)) continue
            out += cp
        }
        return out
    }

    private fun findSlideEntryInSpace(
        level: ServerLevel,
        access: SlideSpaceAccess,
        entity: Entity,
        requireSolid: Boolean
    ): Pair<SlideSpaceAccess, SlideEntry>? {
        val localPos = access.worldToLocal(entity.position())
        val margin = entityDimensions(entity).width / 2.0
        var best: SegmentHit? = null
        for (anchorPos in ContraptionSlideSpaces.anchorPositions(access)) {
            val be = access.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity
            if (be == null) {
                if (access.space !is SlideSpace.Contraption) {
                    SlideAnchorIndex.unregister(level, anchorPos)
                }
                continue
            }
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(access, a)
                val r1 = SlideCurveGeometry.radiusAt(access, b)
                val cf = curveFrames(access, bc, r0, r1) ?: continue
                if (!cf.bounds.contains(localPos)) continue
                val config = SlideCurveGeometry.sectorConfig(access, a, b)
                    ?: WaterslideSectorConfig.defaultConfig()
                for (i in 0 until cf.frames.size - 1) {
                    val hit = testSegment(
                        localPos, bc, cf.frames[i], cf.frames[i + 1], config, requireSolid, margin
                    ) ?: continue
                    if (best == null || hit.distSq < best.distSq) best = hit
                }
            }
        }
        return best?.let { access to it.entry }
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
        val frames = SlideCurveGeometry.sampleFrames(access, bc, r0, r1, includeExtensions = false)
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
        // Taking out the physics staff mid-ride on a sub-level slide would
        // otherwise let the staff chase the player along the trajectory.
        if (player != null && session.subLevelId != null && isHoldingCreativePhysicsStaff(player)) {
            CreateWaterparked.LOGGER.info(
                "[StaffGuard] cancelling sub-level slide session {} for staff holder {}",
                session.id, player.uuid
            )
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
            // Main-branch behavior: one precomputed trajectory per space. At
            // the end of a trajectory, try a single cross-space handoff into
            // another space before declaring the ride finished.
            val end = session.trajectory.sampleAt(session.trajectory.duration)
            val endWorldPos = toWorldPos(level, session, end.sample.position)
            val endWorldVel = toWorldVel(level, session, end.sample.position, session.trajectory.exitVelocity)
            val t0 = System.nanoTime()
            val switched = trySwitchSpace(level, session, endWorldPos, endWorldVel)
            perfSwitchNs += System.nanoTime() - t0
            if (switched) {
                startPlaybackSegment(level, session)
                return
            }
            endSession(level, session, SlideEndReason.EXITED)
            return
        }

        val at = session.trajectory.sampleAt(session.elapsed)
        val worldPos = toWorldPos(level, session, at.sample.position)
        val sitPos = if (sit != null) worldPos.subtract(0.0, SIT_HEIGHT, 0.0) else worldPos
        val worldTan = toWorldNormal(level, session, at.sample.tangent)
        val worldVel = toWorldVel(level, session, at.sample.position, at.sample.tangent.scale(at.sample.speed))

        // keep Sable's tracking/plot state in sync so the rider collides with
        // the sub-level's own blocks instead of the main-world void below
        bindToSpace(entity, sit, session.subLevel(level), at.sample.position)
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

    // Applies the first sample of a freshly started segment (including after a
    // cross-space handoff) and tells the client to swap to the new trajectory.
    private fun startPlaybackSegment(level: ServerLevel, session: Session) {
        val entity = session.entity
        val player = session.player
        val sit = session.sit
        val first = session.trajectory.samples.first()
        val newWorld = toWorldPos(level, session, first.position)
        val newSitPos = if (sit != null) newWorld.subtract(0.0, SIT_HEIGHT, 0.0) else newWorld
        val newWorldVel = toWorldVel(level, session, first.position, first.tangent.scale(first.speed))
        bindToSpace(entity, sit, session.subLevel(level), first.position)
        entity.setPos(newSitPos)
        entity.setDeltaMovement(newWorldVel)
        sit?.setPos(newSitPos)
        applyRotation(entity, toWorldNormal(level, session, first.tangent))
        if (player != null) {
            SlidePackets.sendTo(player, SlideSegmentPayload(
                session.id, level.gameTime, session.subLevelId, session.contraption?.id,
                session.trajectory.samples.map { SlideSampleWire.from(it, wireOffset(level, session)) }
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
        val currentCp = session.contraption
        val currentSpace = currentCp?.let { SlideSpace.Contraption(it.id) }
            ?: currentSub?.let { SlideSpace.SubLevel(it.uniqueId) }
            ?: SlideSpace.Main
        val worldVelPerSecond = worldVel.scale(20.0)

        fun tryTarget(access: SlideSpaceAccess, cp: AbstractContraptionEntity?): Boolean {
            // Skip only the EXACT same space (the same sub-level id or the same
            // contraption id); switching to a DIFFERENT space of the same kind
            // (sub->sub or contraption->contraption) is allowed.
            if (access.space == currentSpace) return false
            val localNow = access.worldToLocal(worldPos)
            val found = findSlideEntryInSpace(level, access, session.entity, requireSolid = false) ?: return false
            val entry = found.second
            val structure = access.worldVelocityAt(localNow)
            val localVel = access.worldNormalToLocal(worldVelPerSecond.subtract(structure))
            val dims = entityDimensions(session.entity)
            val next = PhysicsSlideTrajectoryBuilder.build(
                access, entry.curve, entry.towardSecond, entry.startT,
                localNow, localVel, dims.width.toDouble(), dims.height.toDouble()
            ) ?: return false
            session.trajectory = next
            session.subLevelId = (access as? SubSlideSpaceAccess)?.sub?.uniqueId
            session.contraption = cp
            session.elapsed = 0.0
            return true
        }

        if ((currentSub != null || currentCp != null) && tryTarget(MainSlideSpaceAccess(level), null)) return true

        // Create contraptions carrying slides
        val box = AABB(worldPos, worldPos).inflate(8.0)
        for (cp in level.getEntitiesOfClass(AbstractContraptionEntity::class.java, box)) {
            if (currentCp?.id == cp.id) continue
            if (!ContraptionSlideSpaces.carriesSlides(cp)) continue
            if (tryTarget(ContraptionSlideSpaceAccess(level, cp), cp)) return true
        }

        val container = SubLevelContainer.getContainer(level) ?: return false
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            if (currentSub?.uniqueId == sub.uniqueId) continue
            if (tryTarget(SubSlideSpaceAccess(level, sub), null)) return true
        }
        return false
    }

    private fun endSession(level: ServerLevel, session: Session, reason: SlideEndReason) {
        val entity = session.entity
        val player = session.player
        val at = session.trajectory.sampleAt(session.trajectory.duration)
        val localPos = at.sample.position
        val localVel = session.trajectory.exitVelocity
        val worldPos = toWorldPos(level, session, localPos)
        val worldVel = if (reason == SlideEndReason.STOPPED) Vec3.ZERO
        else {
            val cp = session.contraption
            if (cp != null) {
                // Landing after a contraption ride: do NOT inherit the
                // contraption's structure velocity. The rotational tangential
                // term (omega x r) can be large at the far end of a slide and
                // carries an upward component, launching the player on landing.
                // Keep only the slide-relative exit velocity in world
                // orientation, converted to blocks/tick.
                val cAccess = ContraptionSlideSpaceAccess(level, cp)
                cAccess.toWorldNormal(localVel).scale(localVel.length()).scale(1.0 / 20.0)
            } else {
                toWorldVel(level, session, localPos, localVel)
            }
        }
        CreateWaterparked.LOGGER.info(
            "Slide end {} reason {} pos {} vel {}",
            session.id, reason, worldPos, worldVel
        )

        cleanupSit(session, entity)
        // The rider lands with the exit velocity, then the plot lock is
        // released after a short grace period (see releaseStickAfterRide).
        entity.setPos(worldPos)
        entity.setDeltaMovement(worldVel)
        restoreEntity(entity)
        releaseStickAfterRide(level, entity, session.subLevel(level))
        sessions.remove(entity.uuid)
        if (player != null) {
            // The end position is a WORLD-space respawn point (setPos on both
            // sides). Do not send the local/plot-global trajectory position:
            // on sub-levels it is ~2e7 magnitude (float32 ulp ~ 2 blocks) and
            // would require the client to re-derive world coords via the pose.
            // World coords are small enough for float32. This matches the
            // cancel path (onCancel), which already sends worldPos.
            SlidePackets.sendTo(player, SlideEndPayload(
                session.id, reason.ordinal.toByte(),
                worldPos.x.toFloat(), worldPos.y.toFloat(), worldPos.z.toFloat(),
                worldVel.x.toFloat(), worldVel.y.toFloat(), worldVel.z.toFloat()
            ))
        }
    }

    private fun wireOffset(level: ServerLevel, session: Session): Vec3? =
        session.subLevel(level)?.let { Vec3.atLowerCornerOf(it.getPlot().getCenterBlock()) }

    private fun bindToSpace(
        entity: Entity,
        sit: SlideSitEntity?,
        sub: ServerSubLevel?,
        localPos: Vec3
    ) {
        if (sub == null) return
        (entity as? EntityStickExtension)?.`sable$setPlotPosition`(localPos)
        (entity as? EntityMovementExtension)?.`sable$setTrackingSubLevel`(sub)
        if (sit != null) {
            (sit as? EntityStickExtension)?.`sable$setPlotPosition`(localPos)
            (sit as? EntityMovementExtension)?.`sable$setTrackingSubLevel`(sub)
        }
    }

    private fun clearStick(entity: Entity) {
        (entity as? EntityStickExtension)?.`sable$setPlotPosition`(null)
        (entity as? EntityMovementExtension)?.`sable$setTrackingSubLevel`(null)
    }

    // After a sub-level ride, release the plot lock either immediately (the
    // rider is already inside a sub-level and Sable collision can take over)
    // or after a short grace period that lets the rider land on sub-level
    // terrain instead of falling through into the main-world void.
    private fun releaseStickAfterRide(level: ServerLevel, entity: Entity, sub: ServerSubLevel?) {
        if (sub == null) {
            clearStick(entity)
            return
        }
        val contained = Sable.HELPER.getContaining(level, entity.position()) != null
        if (contained) {
            clearStick(entity)
        } else {
            postRideRelease[entity.uuid] =
                level.dimension() to (level.gameTime + POST_RIDE_STICK_TICKS)
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

    private fun toWorldPos(level: ServerLevel, session: Session, local: Vec3): Vec3 =
        session.access(level).toWorld(local)

    private fun toWorldNormal(level: ServerLevel, session: Session, local: Vec3): Vec3 =
        session.access(level).toWorldNormal(local)

    private fun toWorldVel(
        level: ServerLevel,
        session: Session,
        localPos: Vec3,
        localVel: Vec3
    ): Vec3 {
        // Contraption spaces rotate/translate as a unit; the conventional
        // helper keeps Sable sub-level scale semantics untouched.
        val cp = session.contraption
        if (cp != null) {
            val access = ContraptionSlideSpaceAccess(level, cp)
            val out = access.toWorldNormal(localVel).scale(localVel.length())
            val structure = access.worldVelocityAt(localPos)
            return out.add(structure).scale(1.0 / 20.0)
        }
        val sub = session.subLevel(level)
        if (sub == null) return localVel.scale(1.0 / 20.0)
        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(localVel), Vector3d())
        val structure = Sable.HELPER.getVelocity(
            level, sub, JOMLConversion.toJOML(localPos), Vector3d()
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
        for (pos in SlideAnchorIndex.all(level, SlideSpace.Main)) {
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
        boundsCache[level.dimension()] = key to result
        return result
    }

    // structural fingerprint: only rebuild the box when the slide graph changes
    private fun boundsKey(level: ServerLevel): Int {
        var key = 0
        for (pos in SlideAnchorIndex.all(level, SlideSpace.Main)) {
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

    // Detected by registry id so the mod works identically with Aeronautics
    // (and therefore the whole Simulated library) absent from the mod list.
    private fun isHoldingCreativePhysicsStaff(entity: Entity): Boolean {
        if (entity !is LivingEntity) return false
        return isCreativePhysicsStaff(entity.mainHandItem) ||
            isCreativePhysicsStaff(entity.offhandItem)
    }

    private fun isCreativePhysicsStaff(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return BuiltInRegistries.ITEM.getKey(stack.item) == CREATIVE_PHYSICS_STAFF
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
