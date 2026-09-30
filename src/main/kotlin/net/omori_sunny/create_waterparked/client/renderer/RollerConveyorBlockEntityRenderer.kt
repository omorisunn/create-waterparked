package net.omori_sunny.create_waterparked.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.simibubi.create.content.kinetics.belt.BeltHelper
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack
import com.simibubi.create.content.logistics.box.PackageItem
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer
import com.simibubi.create.foundation.render.ShadowRenderHelper
import dev.engine_room.flywheel.api.visualization.VisualizationManager
import dev.engine_room.flywheel.lib.transform.TransformStack
import net.createmod.catnip.levelWrappers.WrappedLevel
import net.createmod.catnip.render.CachedBuffers
import net.createmod.ponder.api.level.PonderLevel
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.client.RollerPayloadTrace
import net.omori_sunny.create_waterparked.client.flywheel.ModPartialModels
import net.omori_sunny.create_waterparked.client.flywheel.RollerConveyorSpin
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Item
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlock
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlockEntity
import net.omori_sunny.create_waterparked.content.roller.RollerDeckFace
import net.omori_sunny.create_waterparked.content.roller.RollerHinge
import net.omori_sunny.create_waterparked.content.roller.RollerMomentumAccess
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2

// Create binds BeltRenderer to its own belt type only, so the items riding our rollers need this port of its item pass
class RollerConveyorBlockEntityRenderer(
    context: BlockEntityRendererProvider.Context
) : SafeBlockEntityRenderer<RollerConveyorBlockEntity>() {

    override fun shouldRenderOffScreen(be: RollerConveyorBlockEntity): Boolean = be.isController

    override fun renderSafe(
        be: RollerConveyorBlockEntity,
        partialTicks: Float,
        ms: PoseStack,
        buffer: MultiBufferSource,
        light: Int,
        overlay: Int
    ) {
        if (!be.isController) return
        if (be.deckLength == 0) return
        // a plot copy is never ticked by the world, so the run is stepped from here as the fallback; the client
        // tick steps it too, and both entries step it at most once per game tick
        be.mirrorTick()
        val inventory = be.inventory ?: return

        ms.pushPose()

        val beltFacing = be.blockState.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
        val directionVec: Vec3i = beltFacing.normal
        val beltStartOffset = Vec3.atLowerCornerOf(directionVec)
            .scale(-.5)
            .add(.5, ITEM_RIDE_Y, .5)
        ms.translate(beltStartOffset.x, beltStartOffset.y, beltStartOffset.z)
        val onContraption = be.level is WrappedLevel

        for (transported in inventory.transportedItems) {
            renderItem(be, partialTicks, ms, buffer, light, overlay, beltFacing, directionVec, onContraption,
                transported, beltStartOffset)
        }
        val lazy = inventory.lazyClientItem
        if (lazy != null) {
            renderItem(be, partialTicks, ms, buffer, light, overlay, beltFacing, directionVec, onContraption, lazy,
                beltStartOffset)
        }

        ms.popPose()

        // the flywheel visual owns the rollers wherever it can run; this stands in when it cannot, the same way
        // Create's own kinetic renderers fall back, and draws every segment's rollers from the controller
        val world = be.level
        if (world == null || !VisualizationManager.supportsVisualization(world)) {
            renderRollers(be, partialTicks, ms, buffer, light)
        }
    }

    // the plain renderer fallback: the same six rollers per segment and the hinge stub, from the same partials,
    // the same spin easing state and the same load driven speed the visual reads
    private fun renderRollers(
        be: RollerConveyorBlockEntity,
        partialTicks: Float,
        ms: PoseStack,
        buffer: MultiBufferSource,
        light: Int
    ) {
        val world = be.level ?: return
        val seconds = (Minecraft.getInstance().levelRenderer.ticks + partialTicks) / 20f
        val vb = buffer.getBuffer(RenderType.cutoutMipped())

        for (segment in 0 until be.deckLength) {
            val segPos = be.positionForOffset(segment)
            val state = world.getBlockState(segPos)
            if (state.block !is RollerConveyorBlock) continue
            val facing = state.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
            val yRad = -facing.toYRot() * Mth.DEG_TO_RAD
            val segBe = (world.getBlockEntity(segPos) as? RollerConveyorBlockEntity) ?: be
            val key = segPos.asLong()

            ms.pushPose()
            val step = Vec3.atLowerCornerOf(facing.normal).scale(segment.toDouble())
            ms.translate(step.x, step.y, step.z)
            ms.translate(0.5, 0.5, 0.5)
            ms.mulPose(Axis.YP.rotation(yRad))
            for (slot in 0 until RollerConveyorSpin.ROLLER_COUNT) {
                val speed = RollerConveyorSpin.loadSpeed(segBe, slot) * RollerConveyorSpin.ROLL_SPEED_PER_BLOCK
                val spin = RollerConveyorSpin.advance(key, slot, speed, seconds)
                val localZ = (RollerConveyorSpin.MIN_ROLLER_OFFSET + slot * RollerConveyorSpin.ROLLER_PITCH) /
                    RollerConveyorSpin.BLOCK_LENGTH
                // flywheel places a baked mesh by translating its own origin, so the mesh centre lands at
                // middle + facing(local - middle); the chain below is that same map, with the spin about the
                // centred mesh before the offset and the facing yaw
                ms.pushPose()
                ms.translate(0.0, RollerConveyorSpin.ROLLER_CENTRE_Y - 0.5, localZ - 0.5)
                ms.mulPose(Axis.XP.rotation(spin.offset * Mth.DEG_TO_RAD))
                ms.translate(-0.5, -0.5, -0.5)
                val roller: net.createmod.catnip.render.SuperByteBuffer =
                    CachedBuffers.partial(ModPartialModels.ROLLER, state)
                net.omori_sunny.create_waterparked.client.RollerBufferDraw.draw(roller, ms, vb, light)
                ms.popPose()
            }
            ms.popPose()

            if (state.getValue(RollerConveyorBlock.HINGE) == RollerHinge.Side.NONE || stubHidden(world, segPos)) continue
            ms.pushPose()
            ms.translate(step.x, step.y, step.z)
            ms.translate(0.5, 0.5, 0.5)
            ms.mulPose(Axis.YP.rotation(yRad))
            val angle = if (segBe.isController) segBe.hingeAngle
            else segBe.controllerPosition()
                ?.let { world.getBlockEntity(it) as? RollerConveyorBlockEntity }?.hingeAngle ?: segBe.hingeAngle
            // the stub swings about the deck's own width axis, which the yaw already maps the local X onto
            ms.mulPose(Axis.XP.rotation(angle * Mth.DEG_TO_RAD))
            ms.translate(-0.5, -0.5, -0.5)
            val stub: net.createmod.catnip.render.SuperByteBuffer =
                CachedBuffers.partial(ModPartialModels.ROLLER_SHAFT, state)
            net.omori_sunny.create_waterparked.client.RollerBufferDraw.draw(stub, ms, vb, light)
            ms.popPose()
        }
    }

    // the same world side read the visual's own stub does: a deck inside a plot never draws its own stub
    private fun stubHidden(world: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
        if (RollerConveyorBlock.insideSubLevel(world, pos)) return true
        return abs(pos.x) > PLOT_COORD_LIMIT || abs(pos.z) > PLOT_COORD_LIMIT
    }

    // the boat hull lies along the travel direction instead of the random per-item angle
    private fun renderItem(
        be: RollerConveyorBlockEntity,
        partialTicks: Float,
        ms: PoseStack,
        buffer: MultiBufferSource,
        light: Int,
        overlay: Int,
        beltFacing: Direction,
        directionVec: Vec3i,
        onContraption: Boolean,
        transported: TransportedItemStack,
        beltStartOffset: Vec3
    ) {
        if (transported.stack.item is InflatableBoat1x2Item) {
            transported.angle = Math.round(
                Math.toDegrees(atan2(beltFacing.stepX.toDouble(), beltFacing.stepZ.toDouble())).toFloat()
            )
        }
        val mc = Minecraft.getInstance()
        val itemRenderer = mc.itemRenderer
        val mutablePos = BlockPos.MutableBlockPos()

        // Create's own belt rendering: the two endpoints the walk wrote this tick, mixed by the frame's part
        var offset = Mth.lerp(partialTicks, transported.prevBeltPosition, transported.beltPosition)
        var sideOffset = Mth.lerp(partialTicks, transported.prevSideOffset, transported.sideOffset)

        if (be.travelSpeed == 0f) {
            offset = transported.beltPosition
            sideOffset = transported.sideOffset
        }

        // temporary: the log reads the very offset this frame draws the load at
        RollerPayloadTrace.sample(be, transported, partialTicks, offset)
        val offsetVec = Vec3.atLowerCornerOf(directionVec).scale(offset.toDouble())
        val itemPos = beltStartOffset
            .add(be.blockPos.x.toDouble(), be.blockPos.y.toDouble(), be.blockPos.z.toDouble())
            .add(offsetVec)
        if (shouldCullItem(itemPos, be.level)) return

        ms.pushPose()
        TransformStack.of(ms).nudge(transported.angle)
        ms.translate(offsetVec.x, offsetVec.y, offsetVec.z)

        val alongX = beltFacing.clockWise.axis == Direction.Axis.X
        if (!alongX) sideOffset *= -1f
        ms.translate(if (alongX) sideOffset.toDouble() else 0.0, 0.0, if (alongX) 0.0 else sideOffset.toDouble())
        // a load riding the lower lane is the upper one mirrored through the deck, so both read one face
        val face = RollerDeckFace.ofLane((transported as RollerMomentumAccess).`waterparked$deckLane`())
        ms.translate(0.0, -face.drop, 0.0)
        ms.mulPose((if (beltFacing.axis == Direction.Axis.X) Axis.XP else Axis.ZP).rotationDegrees(face.flip))

        val stackLight: Int
        if (onContraption) {
            stackLight = light
        } else {
            val segment = Math.floor(offset.toDouble()).toInt()
            mutablePos.set(be.blockPos).move(directionVec.x * segment, 0, directionVec.z * segment)
            stackLight = LevelRenderer.getLightColor(be.level, mutablePos)
        }

        val renderUpright = BeltHelper.isItemUpright(transported.stack)
        val bakedModel = itemRenderer.getModel(transported.stack, be.level, null, 0)
        val blockItem = bakedModel.isGui3d

        var count = 0
        if (be.level is PonderLevel || mc.player!!.eyePosition.distanceTo(itemPos) < 16) {
            count = Mth.log2(transported.stack.count) / 2
        }

        val r = Random(transported.angle.toLong())
        ms.pushPose()
        ms.translate(0.0, (PLATE_TOP_Y - ITEM_RIDE_Y + SHADOW_LIFT).toDouble(), 0.0)
        ShadowRenderHelper.renderShadow(ms, buffer, .75f, .2f)
        ms.popPose()

        if (renderUpright) {
            val cameraPosition = mc.gameRenderer.mainCamera.position
            val diff = be.vectorForOffset(offset).subtract(cameraPosition)
            ms.mulPose(Axis.YP.rotation((Mth.atan2(diff.x, diff.z) + Math.PI).toFloat()))
            ms.translate(0.0, 3 / 32.0, 1 / 16.0)
        }

        for (i in 0..count) {
            ms.pushPose()

            val box = PackageItem.isPackage(transported.stack) || transported.stack.item is InflatableBoat1x2Item
            ms.mulPose(Axis.YP.rotationDegrees(transported.angle.toFloat()))
            if (!blockItem && !renderUpright) {
                ms.translate(0.0, -.09375, 0.0)
                ms.mulPose(Axis.XP.rotationDegrees(90f))
            }

            if (blockItem && !box) {
                ms.translate((r.nextFloat() * .0625f * i).toDouble(), 0.0, (r.nextFloat() * .0625f * i).toDouble())
            }

            if (box) {
                // a dyed boat is a flat hull, not a package box: half the package lift lands its hull
                // bottom on the plate top (7/16 + 2/16 - 1.5 * 1/4 = 3/16), in block space below the scale
                val lift = if (transported.stack.item is InflatableBoat1x2Item) 2 / 16.0 else 4 / 16.0
                ms.translate(0.0, lift, 0.0)
                ms.scale(1.5f, 1.5f, 1.5f)
            } else {
                ms.scale(.5f, .5f, .5f)
            }

            itemRenderer.render(transported.stack, ItemDisplayContext.FIXED, false, ms, buffer, stackLight, overlay, bakedModel)
            ms.popPose()

            if (!renderUpright) {
                if (!blockItem) ms.mulPose(Axis.YP.rotationDegrees(10f))
                ms.translate(0.0, if (blockItem) 1 / 64.0 else 1 / 16.0, 0.0)
            } else {
                ms.translate(0.0, 0.0, -1 / 16.0)
            }
        }

        ms.popPose()
    }

    companion object {
        // item origin: the plate top plus the 4/16 a 0.5-scaled block item hangs below its own origin
        private const val ITEM_RIDE_Y = 7.0 / 16.0
        private const val PLATE_TOP_Y = 3.0 / 16.0
        private const val SHADOW_LIFT = 0.005f
        // a plot copy of a deck sits millions of blocks out, unlike anything the world itself holds
        private const val PLOT_COORD_LIMIT = 1 shl 20
    }
}
