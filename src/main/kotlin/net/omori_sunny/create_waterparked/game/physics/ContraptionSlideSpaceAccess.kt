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

// slide space over a contraption, anchors decode from captured NBT and the frame moves with it
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
            // build through the same pending type trick the block uses
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
        // empty anchors fill from contraption fluid, water is never consumed
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

    // auto water from the contraption fluid storage, no consumption
    private fun applyAutoWater(entity: AbstractContraptionEntity, anchors: Map<BlockPos, WaterslideAnchorBlockEntity>) {
        if (anchors.isEmpty()) return
        // anchor tanks are the water source, other containers are best effort
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

    // true when any non anchor container carries water
    private fun scanForWater(entity: AbstractContraptionEntity): Boolean {
        val contraption = entity.contraption ?: return false
        for (info in contraption.blocks.values) {
            val nbt = info.nbt() ?: continue
            if (nbtHasWater(nbt)) return true
        }
        return false
    }

    // recursive scan for fluid stacks that name water
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
    // structure velocity from the previous pose, tracked once per server tick
    // entity id to previous pose position and angle
    private val prevPose = HashMap<Int, Pair<Vec3, Float>>()

    // slide carrying contraption ids per dimension, kept by join leave events
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
                    // one time water computation at assembly, then push the field to players
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

// access over a contraption, local coords are contraption block positions
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

    // direction rotation for normals, returns a unit direction
    override fun toWorldNormal(local: Vec3): Vec3 {
        val axis = rotationAxis()
        if (axis == null) return local.normalize()
        return VecHelper.rotate(local, currentAngle().toDouble(), axis).normalize()
    }

    // inverse rotation that preserves magnitude, same as sub level access
    override fun worldNormalToLocal(world: Vec3): Vec3 {
        val axis = rotationAxis()
        if (axis == null) return world
        return VecHelper.rotate(world, -currentAngle().toDouble(), axis)
    }

    override fun toWorld(local: Vec3): Vec3 = entity.toGlobalVector(local, 1.0f)

    override fun worldToLocal(world: Vec3): Vec3 = entity.toLocalVector(world, 1.0f)

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = anchors[pos.immutable()]

    // local positions of every mounted slide anchor
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

    // structure velocity at a local point, linear plus rotational terms
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

        // rotation arm from the contraption pivot at center of zero
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
