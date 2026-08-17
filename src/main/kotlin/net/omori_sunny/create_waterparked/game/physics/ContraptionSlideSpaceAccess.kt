package net.omori_sunny.create_waterparked.game.physics

import com.simibubi.create.content.contraptions.AbstractContraptionEntity
import com.simibubi.create.content.contraptions.Contraption
import com.simibubi.create.content.contraptions.behaviour.MovementContext
import com.simibubi.create.content.trains.track.BezierConnection
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.createmod.catnip.math.VecHelper
import net.minecraft.core.Direction.Axis
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

// Third slide space: a Create contraption carrying a waterslide.
//
// Two facts drive the design:
//  - data-location: the slide's anchors/curves/water configs no longer live in
//    the world; they are the captured BlockEntity NBT inside the contraption.
//    Each anchor is reconstructed into a temporary WaterslideAnchorBlockEntity
//    (read() re-anchors the BezierConnections into contraption-local space,
//    identical to the client tube visual), and block access routes through
//    that decode instead of the world.
//  - moved-frame: the space is a rigid transform (anchor + rotation around the
//    contraption's axis) taken from the contraption entity, so a precomputed
//    local trajectory stays valid while the contraption translates/rotates.
//    worldVelocityAt() returns the structure's linear + rotational velocity so
//    entry/exit math can subtract it exactly like Sable sub-levels.
//
// NOTE: only the anchor host BE matters (curves/frames/water live there); the
// physics intentionally reads anchors only, never track blocks.
object ContraptionSlideSpaces {

    private data class Signed(val signature: Int, val data: Map<BlockPos, WaterslideAnchorBlockEntity>)

    // entity-id -> decoded anchor data; invalidated when the captured NBT changes
    private val cache = HashMap<Int, Signed>()

    fun invalidate() { cache.clear() }

    fun decode(entity: AbstractContraptionEntity): Map<BlockPos, WaterslideAnchorBlockEntity> {
        val contraption = entity.contraption ?: return emptyMap()
        val id = entity.id
        val sig = blockDataSignature(contraption)
        val cached = cache[id]
        if (cached != null && cached.signature == sig) return cached.data
        val registries = entity.level()?.registryAccess()
        val out = HashMap<BlockPos, WaterslideAnchorBlockEntity>()
        for ((localPos, info) in contraption.blocks) {
            val state = info.state()
            if (state.block !is WaterslideAnchorBlock) continue
            val tag = info.nbt() ?: continue
            // BlockEntity's constructor validates that the BE type matches the
            // block state, so construct through the same withPendingType trick
            // the block's own newBlockEntity uses (otherwise the inherited CCS
            // coaster_anchorpoint type mismatches waterslide_anchor).
            var be: WaterslideAnchorBlockEntity? = null
            WaterslideAnchorBlockEntity.withPendingType(
                net.omori_sunny.create_waterparked.content.registry.ModBlockEntities.WATERSLIDE_ANCHOR_BE
            ) {
                be = WaterslideAnchorBlockEntity(localPos, state)
            }
            val anchor = be ?: continue
            try {
                anchor.readCaptured(tag, registries)
            } catch (t: Throwable) {
                CreateWaterparked.LOGGER.warn(
                    "[ContraptionSlide] failed to decode anchor {}: {}", localPos, t.toString()
                )
                continue
            }
            out[localPos.immutable()] = anchor
        }
        cache[id] = Signed(sig, out)
        // Feature: a mounted slide auto-uses the contraption's fluid storage.
        // When the contraption carries any water at all, every empty anchor is
        // filled to capacity and all its curves become watered - and the water
        // is NEVER consumed (the fill is read-only against captured data).
        applyAutoWater(entity, out)
        if (carriesSlides(entity)) {
            CreateWaterparked.LOGGER.debug(
                "[ContraptionSlide] decode entity={} anchors={} anchorHasWater=[{}] scanForWater={} autoWaterRan={}",
                entity.id, out.size,
                out.values.joinToString(",") { it.hasWater().toString() },
                scanForWater(entity),
                out.values.any { it.hasWater() } || scanForWater(entity)
            )
        }
        return out
    }

    // ---------------------------------------------------------------
    // Auto-water from the contraption's own fluid storage (no consumption).
    // ---------------------------------------------------------------

    private fun applyAutoWater(entity: AbstractContraptionEntity, anchors: Map<BlockPos, WaterslideAnchorBlockEntity>) {
        if (anchors.isEmpty()) return
        // water source: any decoded anchor tank with water is authoritative (the
        // captured tank NBT is already decoded), plus a best-effort scan of any
        // other on-board fluid container.
        val hasWater = anchors.values.any { it.hasWater() } || scanForWater(entity)
        if (!hasWater) return
        for ((pos, be) in anchors) {
            if (!be.hasWater()) be.refillWater()
            try {
                for (peer in be.anchorPeerCurvesView.keys) be.setCurveWatered(peer, true)
            } catch (_: Throwable) {
            }
        }
        CreateWaterparked.LOGGER.debug("[ContraptionSlide] auto-watered {} anchor(s) on contraption {}", anchors.size, entity.id)
    }

    // True when the captured contraption data contains any water fluid stack
    // in a non-anchor container (best effort; the anchor tanks are authoritative).
    private fun scanForWater(entity: AbstractContraptionEntity): Boolean {
        val contraption = entity.contraption ?: return false
        for (info in contraption.blocks.values) {
            val nbt = info.nbt() ?: continue
            if (nbtHasWater(nbt)) return true
        }
        return false
    }

    // Recursive scan: any compound that looks like a FluidStack carrying water
    // (an "Amount" > 0 together with an "Id"/"FluidName" naming water).
    private fun nbtHasWater(nbt: Tag): Boolean {
        if (nbt !is CompoundTag) return false
        if (nbt.contains("Amount") && nbt.getInt("Amount") > 0) {
            val id = when {
                nbt.contains("Id") -> nbt.getString("Id")
                nbt.contains("FluidName") -> nbt.getString("FluidName")
                else -> null
            }
            if (id?.contains("water", ignoreCase = true) == true) return true
        }
        for (key in nbt.allKeys) {
            val child = nbt.get(key)
            if (child is CompoundTag && nbtHasWater(child)) return true
        }
        return false
    }

    private fun blockDataSignature(contraption: Contraption): Int {
        var key = 0
        for (info in contraption.blocks.values) {
            val state = info.state()
            if (state.block !is WaterslideAnchorBlock) continue
            val nbt = info.nbt()
            key = key * 31 + (nbt?.hashCode() ?: 0)
        }
        return key
    }

    // Whether the contraption carries any waterslide anchor (cheap block-state scan).
    fun carriesSlides(entity: AbstractContraptionEntity): Boolean {
        val contraption = entity.contraption ?: return false
        for (info in contraption.blocks.values) {
            if (info.state().block is WaterslideAnchorBlock) return true
        }
        return false
    }

    // Anchor positions for ANY slide space: the world index for main/sub-level
    // spaces, the decoded contraption map for contraption spaces.
    fun anchorPositions(access: SlideSpaceAccess): Set<BlockPos> =
        if (access is ContraptionSlideSpaceAccess) access.anchorPositions()
        else SlideAnchorIndex.all(access.level, access.space)

    // ---------------------------------------------------------------
    // Structure-velocity tracking. A ContraptionSlideSpaceAccess is created
    // fresh in many contexts (entry probe, session, water code), so per-instance
    // "previous tick pose" would always be the current pose and velocity would
    // be zero. Instead the previous pose of every slide-carrying contraption is
    // stored here, updated once per server tick, and any access reads it.
    // ---------------------------------------------------------------

    // entityId -> (position(), angle) from the previous server tick
    private val prevPose = HashMap<Int, Pair<Vec3, Float>>()

    // slide-carrying contraption entity ids, per dimension, maintained by
    // entity join/leave events so the per-tick velocity tracking never has to
    // scan for entities with an unbounded AABB (Sable rejects those).
    private val tracked = HashMap<ResourceKey<Level>, MutableSet<Int>>()

    @Volatile
    private var eventsRegistered = false

    fun registerEvents() {
        if (eventsRegistered) return
        eventsRegistered = true
        NeoForge.EVENT_BUS.addListener(
            Consumer { event: EntityJoinLevelEvent ->
                val e = event.entity as? AbstractContraptionEntity ?: return@Consumer
                if (e.contraption == null) return@Consumer
                val lvl = e.level()
                if (lvl !is ServerLevel) return@Consumer
                if (carriesSlides(e)) {
                    tracked.getOrPut(lvl.dimension()) { HashSet() }.add(e.id)
                    // One-time contraption-internal water computation at assembly,
                    // then push the field to every player for rendering.
                    net.omori_sunny.create_waterparked.game.water.ContraptionWaterSimulation.fieldsFor(lvl, e)
                    net.omori_sunny.create_waterparked.game.water.ContraptionWaterSimulation.syncToPlayers(lvl, e)
                }
            }
        )
        NeoForge.EVENT_BUS.addListener(
            Consumer { event: EntityLeaveLevelEvent ->
                val e = event.entity
                val id = if (e is AbstractContraptionEntity) e.id else return@Consumer
                for (set in tracked.values) set.remove(id)
            }
        )
    }

    fun angleOf(entity: AbstractContraptionEntity): Float =
        (entity as? com.simibubi.create.content.contraptions.ControlledContraptionEntity)
            ?.getAngle(1.0f) ?: 0.0f

    fun prevOf(entityId: Int): Pair<Vec3, Float>? = prevPose[entityId]

    // entity ids of slide-carrying contraptions in a level (from the live tracker)
    fun carriersIn(level: ServerLevel): Set<Int> =
        tracked[level.dimension()]?.toSet() ?: emptySet()

    // Called once per server tick per level (before slide sessions tick).
    fun updatePrev(level: ServerLevel) {
        val ids = tracked[level.dimension()] ?: return
        val it = ids.iterator()
        while (it.hasNext()) {
            val cp = level.getEntity(it.next()) as? AbstractContraptionEntity
            // drop dead or disassembled contraptions; keep live ones' poses
            if (cp == null || !carriesSlides(cp)) it.remove()
            else prevPose[cp.id] = cp.position() to angleOf(cp)
        }
        if (ids.isEmpty()) tracked.remove(level.dimension())
    }

    fun invalidatePose() { prevPose.clear() ; tracked.clear() }
}

// A SlideSpaceAccess over a contraption. Local coordinates are contraption
// block positions (BlockPos.ZERO-relative); world mapping comes from the
// entity's own toGlobalVector/toLocalVector so translation+rotation match the
// actual rendered contraption every tick.
class ContraptionSlideSpaceAccess(
    override val level: ServerLevel,
    val entity: AbstractContraptionEntity
) : SlideSpaceAccess {

    override val space: SlideSpace = SlideSpace.Contraption(entity.id)

    // decoded anchor BEs in contraption-local coordinates
    private val anchors: Map<BlockPos, WaterslideAnchorBlockEntity> by lazy {
        ContraptionSlideSpaces.decode(entity)
    }

    private var gravity: Vec3? = null

    private fun currentAngle(): Float = ContraptionSlideSpaces.angleOf(entity)

    private fun rotationAxis(): Axis? =
        (entity as? com.simibubi.create.content.contraptions.ControlledContraptionEntity)
            ?.getRotationAxis()

    // direction rotation for normals; translation is irrelevant. Matches the
    // Main/Sub convention: this returns a UNIT direction.
    override fun toWorldNormal(local: Vec3): Vec3 {
        val axis = rotationAxis()
        if (axis == null) return local.normalize()
        return VecHelper.rotate(local, currentAngle().toDouble(), axis).normalize()
    }

    // inverse rotation that PRESERVES MAGNITUDE - the same contract as
    // SubSlideSpaceAccess.worldNormalToLocal (transformNormalInverse). Callers
    // convert whole velocity vectors through this, so a simple normalize was a
    // bug that dropped the entry/transition speed to 1.
    override fun worldNormalToLocal(world: Vec3): Vec3 {
        val axis = rotationAxis()
        if (axis == null) return world
        return VecHelper.rotate(world, -currentAngle().toDouble(), axis)
    }

    override fun toWorld(local: Vec3): Vec3 = entity.toGlobalVector(local, 1.0f)

    override fun worldToLocal(world: Vec3): Vec3 = entity.toLocalVector(world, 1.0f)

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = anchors[pos.immutable()]

    // local positions of every mounted slide anchor (replaces the world
    // SlideAnchorIndex lookup for contraption spaces)
    fun anchorPositions(): Set<BlockPos> = anchors.keys

    override fun getBlockState(pos: BlockPos): BlockState =
        entity.contraption?.blocks?.get(pos)?.state()
            ?: if (pos in anchors) {
                // anchor host block state not in the captured map (shouldn't happen)
                Blocks.AIR.defaultBlockState()
            } else {
                Blocks.AIR.defaultBlockState()
            }

    override fun localGravity(): Vec3 {
        gravity?.let { return it }
        // rotate the world gravity (down) into contraption-local frame
        val axis = rotationAxis()
        val angle = currentAngle()
        if (axis == null || (angle.toInt() % 360).toFloat() == 0.0f) {
            gravity = Vec3(0.0, -32.0, 0.0)
            return gravity!!
        }
        val down = Vec3(0.0, -1.0, 0.0)
        gravity = VecHelper.rotate(down, -angle.toDouble(), axis).scale(32.0)
        return gravity!!
    }

    // structure velocity (blocks/s) at a contraption-local point: linear anchor
    // motion plus the tangential contribution of rotation around the axis
    override fun worldVelocityAt(localPos: Vec3): Vec3 {
        val prev = ContraptionSlideSpaces.prevOf(entity.id)
        val anchorVel = if (prev == null) Vec3.ZERO
        else Vec3(entity.x - prev.first.x, entity.y - prev.first.y, entity.z - prev.first.z).scale(20.0)
        val axis = rotationAxis()
        if (axis == null) return anchorVel
        val angle = currentAngle()
        val angleDelta = angle - (prev?.second ?: angle) // degrees since last tick
        if (kotlin.math.abs(angleDelta) < 1.0E-5) return anchorVel
        val omega = Math.toRadians(angleDelta.toDouble()) * 20.0 // rad/s

        // Rotational arm measured from the rotation pivot. Create rotates
        // contraption-local vectors about the axis through (0.5,0.5,0.5) (it
        // subtracts centerOf(BlockPos.ZERO) before rotating in toGlobalVector),
        // so the arm is localPos - (0.5,0.5,0.5), then brought to the current
        // world orientation.
        val pivot = Vec3(0.5, 0.5, 0.5)
        val r = localPos.subtract(pivot)
        val rWorld = VecHelper.rotate(r, angle.toDouble(), axis)
        val axisUnit = when (axis) {
            Axis.X -> Vec3(1.0, 0.0, 0.0)
            Axis.Y -> Vec3(0.0, 1.0, 0.0)
            else -> Vec3(0.0, 0.0, 1.0)
        }
        // tangential = omega * (axis x rWorld)
        val tangent = axisUnit.cross(rWorld).scale(omega)
        return anchorVel.add(tangent)
    }
}
