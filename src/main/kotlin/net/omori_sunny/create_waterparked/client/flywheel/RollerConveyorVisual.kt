package net.omori_sunny.create_waterparked.client.flywheel

import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual
import com.simibubi.create.content.kinetics.base.RotatingInstance
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack
import com.simibubi.create.foundation.render.AllInstanceTypes
import com.simibubi.create.foundation.utility.ServerSpeedProvider
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.companion.math.Pose3dc
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlock
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlockEntity
import net.omori_sunny.create_waterparked.content.roller.RollerDeckFace
import net.omori_sunny.create_waterparked.content.roller.RollerDeckGravity
import net.omori_sunny.create_waterparked.content.roller.RollerMomentum
import net.omori_sunny.create_waterparked.content.roller.RollerHinge
import net.omori_sunny.create_waterparked.content.roller.RollerMomentumAccess
import java.util.function.Consumer
import kotlin.math.abs

// six rollers bridging the two rails, laid out along the rails' length and spun about their own long axis in the block's local frame
class RollerConveyorVisual(
    ctx: VisualizationContext,
    be: RollerConveyorBlockEntity,
    partialTick: Float
) : KineticBlockEntityVisual<RollerConveyorBlockEntity>(ctx, be, partialTick), SimpleDynamicVisual {

    private val rollerModel = Models.partial(ModPartialModels.ROLLER)
    private val rollers = ArrayList<RotatingInstance>(ROLLER_COUNT)
    private val hingeShaft = instancerProvider()
        .instancer(AllInstanceTypes.ROTATING, Models.partial(ModPartialModels.ROLLER_SHAFT))
        .createInstance()
    private val appliedSpeed = FloatArray(ROLLER_COUNT) { Float.NaN }
    private val appliedOffset = FloatArray(ROLLER_COUNT) { Float.NaN }
    private val riders = ArrayList<Rider>()
    private var riderTick = -1
    private var shaftVisible = false
    private var lastHingeAngle = Float.NaN
    private var lastStubHidden = false
    private var stubDiag = ""

    init {
        try {
            hingeShaft.setVisible(false)
            hingeShaft.setChanged()
            for (slot in 0 until ROLLER_COUNT) {
                rollers.add(
                    instancerProvider().instancer(AllInstanceTypes.ROTATING, rollerModel)
                        .createInstance()
                )
            }
            readSpin(partialTick)
            spin()
        } catch (e: Throwable) {
            CreateWaterparked.LOGGER.error("[RollerConveyor] visual init failed", e)
        }
    }

    override fun update(partialTick: Float) {
        // a plot copy is never ticked by the world, so the run is stepped from here as the fallback; the client
        // tick steps it too, and both entries step it at most once per game tick
        blockEntity.mirrorTick()
        spin()
    }

    override fun beginFrame(ctx: DynamicVisual.Context) {
        val key = blockEntity.blockPos.asLong()
        val seconds = renderSeconds(ctx.partialTick())
        var changed = false
        for (slot in 0 until ROLLER_COUNT) {
            val rollerSpin = RollerConveyorSpin.advance(key, slot, targetSpeed(slot), seconds)
            if (rollerSpin.speed == appliedSpeed[slot] && rollerSpin.offset == appliedOffset[slot]) continue
            appliedSpeed[slot] = rollerSpin.speed
            appliedOffset[slot] = rollerSpin.offset
            changed = true
        }
        val angle = hingeAngle()
        if (angle != lastHingeAngle) {
            lastHingeAngle = angle
            changed = true
        }
        val hidden = stubHidden()
        if (hidden != lastStubHidden) {
            lastStubHidden = hidden
            changed = true
        }
        reportStub(hidden)
        if (changed) spin()
    }

    override fun updateLight(partialTick: Float) {
        relight(rollers + hingeShaft)
    }

    override fun _delete() {
        for (roller in rollers) roller.delete()
        hingeShaft.delete()
    }

    override fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
        for (roller in rollers) consumer.accept(roller)
        consumer.accept(hingeShaft)
    }

    // the block rolls with the loads of both lanes. The two faces of one roller move opposite ways, so a load
    // under the deck turns it the other way round - the load part is shared with the plain renderer fallback
    private fun targetSpeed(slot: Int): Float {
        val slice = RollerConveyorSpin.rollerSlice(slot)
        var speed = RollerConveyorSpin.loadSpeed(blockEntity, slot)
        if (blockEntity.kineticSpeed() == 0f) for (rider in riders()) {
            val gap = abs(rider.slice - slice)
            if (gap >= REACH) continue
            val riderSpeed = rider.speed * falloff(gap)
            if (abs(riderSpeed) > abs(speed)) speed = riderSpeed
        }
        return speed * RollerConveyorSpin.ROLL_SPEED_PER_BLOCK
    }

    // a load keeps turning rollers a whole block away, at a quarter of its speed
    private fun falloff(gap: Float): Float = 1f - FALLOFF * gap

    // riders move at the tick rate, so their slices are scanned once a tick and reused for the frames in between
    private fun riders(): List<Rider> {
        val tick = Minecraft.getInstance().levelRenderer.ticks
        if (tick == riderTick) return riders
        riderTick = tick
        riders.clear()
        scanRiders()
        return riders
    }

    private fun scanRiders() {
        val world = blockEntity.level ?: return
        if (world !== Minecraft.getInstance().level) return
        val deck = blockEntity.blockPos
        val facing = blockState.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
        val pose = Sable.HELPER.getContaining(world, deck)?.lastPose()
        // both faces are scanned: the band reaches from the lower lane under the run to the upper one over it,
        // and the box is folded from every corner of that band, so a run its hinge has turned still covers the
        // space it really sits in instead of the one corner its block base happens to land on
        val box = bandBox(deck, pose, RollerDeckFace.LOWER.surface - RIDER_REACH, RollerDeckFace.UPPER.surface + RIDER_REACH)
        for (entity in world.getEntitiesOfClass(Entity::class.java, box)) {
            val local = localBox(entity, pose, deck)
            if (local.maxX <= 0.0 || local.minX >= 1.0 || local.maxZ <= 0.0 || local.minZ >= 1.0) continue
            // a rider either stands on the top face with their feet or hangs on the underside with the far edge
            // of their own box, so both faces read off the one surface that is nearest, inside the same band
            val upper = abs(local.minY - RollerDeckFace.UPPER.surface)
            val lower = abs(local.maxY - RollerDeckFace.LOWER.surface)
            if (minOf(upper, lower) > RollerDeckFace.BAND) continue
            val face = if (upper <= lower) RollerDeckFace.UPPER else RollerDeckFace.LOWER
            val velocity = if (pose == null) entity.deltaMovement else pose.transformNormalInverse(entity.deltaMovement)
            // the one speed this face sees: the rider walks the surface they ride, and the two faces of one roller
            // move opposite ways, which is exactly the lift the face already carries
            val along = (velocity.x * facing.stepX + velocity.z * facing.stepZ).toFloat() * face.lift.toFloat()
            val centre = if (pose == null) entity.position() else pose.transformPositionInverse(entity.position())
            val slice = 0.5 + (centre.x - deck.x - 0.5) * facing.stepX + (centre.z - deck.z - 0.5) * facing.stepZ
            riders.add(Rider(slice.toFloat(), along))
        }
    }

    // the world box one deck riding band occupies: every corner of the band is carried through the pose, so a
    // run turned any way by its hinge is covered by the box it really fills
    private fun bandBox(deck: BlockPos, pose: Pose3dc?, low: Double, high: Double): AABB {
        if (pose == null) {
            return AABB(deck.x.toDouble(), deck.y + low, deck.z.toDouble(), deck.x + 1.0, deck.y + high, deck.z + 1.0)
        }
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (dx in doubleArrayOf(0.0, 1.0)) {
            for (dy in doubleArrayOf(low, high)) {
                for (dz in doubleArrayOf(0.0, 1.0)) {
                    val corner = pose.transformPosition(Vec3(deck.x + dx, deck.y + dy, deck.z + dz))
                    if (corner.x < minX) minX = corner.x
                    if (corner.y < minY) minY = corner.y
                    if (corner.z < minZ) minZ = corner.z
                    if (corner.x > maxX) maxX = corner.x
                    if (corner.y > maxY) maxY = corner.y
                    if (corner.z > maxZ) maxZ = corner.z
                }
            }
        }
        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }

    // an entity own box read in a deck frame, in that deck own block units, corners and all
    private fun localBox(entity: Entity, pose: Pose3dc?, deck: BlockPos): AABB {
        val bounds = entity.boundingBox
        if (pose == null) {
            return AABB(
                bounds.minX - deck.x, bounds.minY - deck.y, bounds.minZ - deck.z,
                bounds.maxX - deck.x, bounds.maxY - deck.y, bounds.maxZ - deck.z
            )
        }
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (x in doubleArrayOf(bounds.minX, bounds.maxX)) {
            for (y in doubleArrayOf(bounds.minY, bounds.maxY)) {
                for (z in doubleArrayOf(bounds.minZ, bounds.maxZ)) {
                    val corner = pose.transformPositionInverse(Vec3(x, y, z))
                    if (corner.x < minX) minX = corner.x
                    if (corner.y < minY) minY = corner.y
                    if (corner.z < minZ) minZ = corner.z
                    if (corner.x > maxX) maxX = corner.x
                    if (corner.y > maxY) maxY = corner.y
                    if (corner.z > maxZ) maxZ = corner.z
                }
            }
        }
        return AABB(minX - deck.x, minY - deck.y, minZ - deck.z, maxX - deck.x, maxY - deck.y, maxZ - deck.z)
    }

    private fun readSpin(partialTick: Float) {
        val key = blockEntity.blockPos.asLong()
        val seconds = renderSeconds(partialTick)
        for (slot in 0 until ROLLER_COUNT) {
            val rollerSpin = RollerConveyorSpin.advance(key, slot, targetSpeed(slot), seconds)
            appliedSpeed[slot] = rollerSpin.speed
            appliedOffset[slot] = rollerSpin.offset
        }
    }

    private fun renderSeconds(partialTick: Float): Float =
        (Minecraft.getInstance().levelRenderer.ticks + partialTick) / TICKS_PER_SECOND

    // the shader spins about the mesh centre while vanilla turns the block model about its corner, so offsets are compensated here
    private fun spin() {
        val yRad = -blockState.getValue(RollerConveyorBlock.HORIZONTAL_FACING).toYRot() * Mth.DEG_TO_RAD
        val rodAxis = Vec3(1.0, 0.0, 0.0).yRot(yRad)
        val corner = Vec3.atLowerCornerOf(visualPosition)
        val middle = Vec3(HALF_BLOCK.toDouble(), HALF_BLOCK.toDouble(), HALF_BLOCK.toDouble())
        for ((slot, roller) in rollers.withIndex()) {
            val localZ = (RollerConveyorSpin.MIN_ROLLER_OFFSET + slot * RollerConveyorSpin.ROLLER_PITCH) / RollerConveyorSpin.BLOCK_LENGTH
            val local = Vec3(HALF_BLOCK.toDouble(), ROLLER_CENTRE_Y.toDouble(), localZ)
            val centre = corner.add(middle).add(local.subtract(middle).yRot(yRad))
            val world = centre.subtract(middle)
            roller.rotation.rotationY(yRad)
            roller.setPosition(world.x.toFloat(), world.y.toFloat(), world.z.toFloat())
                .setRotationAxis(rodAxis.x.toFloat(), rodAxis.y.toFloat(), rodAxis.z.toFloat())
                .setRotationalSpeed(appliedSpeed[slot])
                .setRotationOffset(appliedOffset[slot])
                .setChanged()
        }
        spinHingeShaft(yRad, corner)
    }

    // the shaft rides the run's swing, so like Create's shaft visual it takes an axis and an offset, with no speed to integrate
    private fun spinHingeShaft(yRad: Float, corner: Vec3) {
        val angle = hingeAngle()
        lastHingeAngle = angle
        if (blockState.getValue(RollerConveyorBlock.HINGE) == RollerHinge.Side.NONE || stubHidden()) {
            if (shaftVisible) {
                hingeShaft.setVisible(false)
                hingeShaft.setChanged()
                shaftVisible = false
            }
            return
        }
        val facing = blockState.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
        val hingeAxis = if (facing.axis == Direction.Axis.X) Vec3(0.0, 0.0, 1.0) else Vec3(1.0, 0.0, 0.0)
        hingeShaft.rotation.rotationY(yRad)
        hingeShaft.setPosition(corner.x.toFloat(), corner.y.toFloat(), corner.z.toFloat())
            .setRotationAxis(hingeAxis.x.toFloat(), hingeAxis.y.toFloat(), hingeAxis.z.toFloat())
            .setRotationalSpeed(0f)
            .setRotationOffset(angle)
            .setChanged()
        hingeShaft.setVisible(true)
        hingeShaft.setChanged()
        shaftVisible = true
    }

    // a deck inside a sub-level never draws its own shaft stub, and the client reads that off its own copy
    private fun stubHidden(): Boolean {
        val world = blockEntity.level ?: return true
        if (RollerConveyorBlock.insideSubLevel(world, blockEntity.blockPos)) return true
        if (inPlotGrid(blockEntity.blockPos)) return true
        return Sable.HELPER.getContaining(world, blockEntity.blockPos) != null
    }

    // a plot copy sits millions of blocks out, so its own position alone tells the client it is one
    private fun inPlotGrid(pos: BlockPos): Boolean = abs(pos.x) > PLOT_COORD_LIMIT || abs(pos.z) > PLOT_COORD_LIMIT

    // temporary: names what hides the stub, so a run that still shows two shafts can be read from the log
    private fun reportStub(hidden: Boolean) {
        if (!stubDiagEnabled) return
        val world = blockEntity.level
        val line = "hinge=${blockState.getValue(RollerConveyorBlock.HINGE)} assembled=${blockEntity.hingeAssembled()} " +
            "plot=${RollerConveyorBlock.insideSubLevel(world, blockEntity.blockPos)} grid=${inPlotGrid(blockEntity.blockPos)} " +
            "sub=${world != null && Sable.HELPER.getContaining(world, blockEntity.blockPos) != null} hidden=$hidden"
        if (line == stubDiag) return
        stubDiag = line
        CreateWaterparked.LOGGER.debug("[roller stub] {} {}", blockEntity.blockPos, line)
    }

    // the controller owns the swing angle and syncs it to clients, so a segment reads it there and falls back to its own angle when that controller is not loaded
    private fun hingeAngle(): Float {
        if (blockEntity.isController) return blockEntity.hingeAngle
        val head = blockEntity.controllerPosition() ?: return blockEntity.hingeAngle
        val controller = blockEntity.level?.getBlockEntity(head) as? RollerConveyorBlockEntity ?: return blockEntity.hingeAngle
        return controller.hingeAngle
    }

    // a roller sits at its own place along the deck, counted from the deck's back end
    private fun rollerSlice(slot: Int): Float = RollerConveyorSpin.rollerSlice(slot)

    private class Rider(val slice: Float, val speed: Float)

    companion object {
        // off unless a run asks for it: the stub line costs two sub level lookups a frame per segment to build
        private val stubDiagEnabled = System.getProperty("createwaterparked.stubdiag", "false") == "true"
        private const val ROLLER_COUNT = 6
        private const val HALF_BLOCK = 0.5f
        private const val ROLLER_CENTRE_Y = 2f / 16f
        private const val RIDER_REACH = 1.0
        // how far along the deck a load still turns rollers, and how much of its speed the far end keeps
        private const val REACH = 1f
        private const val FALLOFF = 0.75f
        private const val TICKS_PER_SECOND = 20f
        // a plot copy of a deck sits millions of blocks out, unlike anything the world itself holds
        private const val PLOT_COORD_LIMIT = 1 shl 20
    }
}
