package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.base.IRotate
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.util.StringRepresentable
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.omori_sunny.create_waterparked.config.ModConfig

// the shaft a run turns about once it leaves the world: pushed into a side rail, taken back out by sneaking
object RollerHinge {

    // which of the deck's two side rails holds the shaft
    enum class Side(private val serializedName: String) : StringRepresentable {
        NONE("none"), LEFT("left"), RIGHT("right");

        override fun getSerializedName(): String = serializedName

        fun face(facing: Direction): Direction? = when (this) {
            NONE -> null
            LEFT -> facing.counterClockWise
            RIGHT -> facing.clockWise
        }

        companion object {
            fun at(facing: Direction, face: Direction): Side = when (face) {
                facing.counterClockWise -> LEFT
                facing.clockWise -> RIGHT
                else -> NONE
            }
        }
    }

    // a shaft is what fits a side rail, anything else in hand is not our business
    @JvmStatic
    fun isShaft(stack: ItemStack): Boolean = stack.item === AllBlocks.SHAFT.get().asItem()

    // a hinge only ever comes from a real shaft: other kinetic blocks are neighbours, not pivots
    @JvmStatic
    fun isShaftBlock(state: BlockState): Boolean = state.block.asItem() === AllBlocks.SHAFT.get().asItem()

    // a shaft offered to a side rail only marks where the hinge will be, the run still waits for a real shaft
    @JvmStatic
    fun install(level: Level, pos: BlockPos, state: BlockState, player: Player, stack: ItemStack, face: Direction): Boolean {
        if (!isShaft(stack)) return false
        val facing = state.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
        val side = Side.at(facing, face)
        if (side == Side.NONE) return false
        if (level.isClientSide) return true
        val controller = controllerAt(level, pos) ?: return false
        if (controller.hingeSide != Side.NONE) return false
        val limit = ModConfig.rollerDeckMaxLength()
        if (controller.deckLength > limit) {
            player.displayClientMessage(
                Component.translatable("create_waterparked.roller.hinge_too_long", controller.deckLength, limit)
                    .withStyle(ChatFormatting.RED),
                true
            )
            return false
        }
        val shaftPos = pos.relative(side.face(facing) ?: return false)
        val there = level.getBlockState(shaftPos)
        val drive = there.block
        if (drive is IRotate && drive.getRotationAxis(there) != RollerConveyorBlock.rotationAxisOf(state)) {
            player.displayClientMessage(
                Component.translatable("create_waterparked.roller.hinge_wrong_axis").withStyle(ChatFormatting.RED),
                true
            )
            return false
        }
        controller.setHinge(side, pos, true)
        level.setBlockAndUpdate(pos, state.setValue(RollerConveyorBlock.HINGE, side))
        if (!player.isCreative) stack.shrink(1)
        return true
    }

    // a shaft placed by hand on a width face becomes the hinge as well, it just is not ours to refund
    @JvmStatic
    fun adoptShaft(level: Level, pos: BlockPos, state: BlockState): Boolean {
        val facing = state.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
        if (state.getValue(RollerConveyorBlock.HINGE) != Side.NONE) return false
        val axis = RollerConveyorBlock.rotationAxisOf(state)
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            val face = side.face(facing) ?: continue
            val there = level.getBlockState(pos.relative(face))
            if (!isShaftBlock(there)) continue
            val drive = there.block as? IRotate ?: continue
            if (drive.getRotationAxis(there) != axis) continue
            if (level.isClientSide) return true
            val controller = controllerAt(level, pos) ?: return false
            if (controller.hingeSide != Side.NONE) return false
            if (controller.deckLength > ModConfig.rollerDeckMaxLength()) return false
            controller.setHinge(side, pos, false)
            level.setBlockAndUpdate(pos, state.setValue(RollerConveyorBlock.HINGE, side))
            return true
        }
        return false
    }

    // sneaking pulls the shaft back out and hands it over, leaving the run flat in the world
    @JvmStatic
    fun uninstall(level: Level, pos: BlockPos, player: Player): Boolean {
        val controller = controllerAt(level, pos) ?: return false
        val side = controller.hingeSide
        if (side == Side.NONE) return false
        if (level.isClientSide) return true
        val pivot = controller.hingePivot ?: pos
        val pivotState = level.getBlockState(pivot)
        val facing = if (pivotState.block is RollerConveyorBlock) pivotState.getValue(RollerConveyorBlock.HORIZONTAL_FACING) else null
        val owned = controller.hingeOwned
        controller.setHinge(Side.NONE, null, false)
        if (facing == null) return true
        level.setBlockAndUpdate(pivot, pivotState.setValue(RollerConveyorBlock.HINGE, Side.NONE))
        if (!owned) return true
        // the fee comes back whether or not a drive stands there, and a block the player placed keeps standing
        if (!player.isCreative) {
            player.inventory.placeItemBackInInventory(ItemStack(AllBlocks.SHAFT.get().asItem()))
        }
        return true
    }

    private fun controllerAt(level: Level, pos: BlockPos): RollerConveyorBlockEntity? {
        val segment = level.getBlockEntity(pos) as? RollerConveyorBlockEntity ?: return null
        val controller = segment.controllerPosition() ?: return segment
        return level.getBlockEntity(controller) as? RollerConveyorBlockEntity
    }
}
