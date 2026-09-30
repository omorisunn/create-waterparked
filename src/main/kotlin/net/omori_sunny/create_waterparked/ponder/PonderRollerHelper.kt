package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlock
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlockEntity
import net.omori_sunny.create_waterparked.content.roller.RollerDeckInventory
import net.omori_sunny.create_waterparked.content.roller.RollerMomentumAccess
import net.omori_sunny.create_waterparked.content.roller.RollerWorldShaftBlock

// a ponder level never ticks, so a roller run never forms on its own in a scene: the helper writes the deck data
// the world's own onPlace would have written (controller, index, length), seeds the speed the rollers read, and
// puts loads straight onto the deck. The walk of the loads is not done here - the scene drives it with
// PonderRollerLoadInstruction, the same way the door scene drives its sweep
object PonderRollerHelper {

    // one contiguous run of decks as the scene found it in its schematic
    data class Run(val controller: BlockPos, val facing: Direction, val length: Int) {
        val end: BlockPos get() = controller.relative(facing, length - 1)
    }

    // the longest run of same facing decks in the schematic: the segment with no deck behind it is its controller
    @JvmStatic
    fun findRun(level: Level): Run? {
        var best: Run? = null
        for (x in 0..14) for (y in 1..12) for (z in 0..14) {
            val pos = BlockPos(x, y, z)
            val state = level.getBlockState(pos)
            if (state.block !is RollerConveyorBlock) continue
            val facing = state.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
            val behind = pos.relative(facing.opposite)
            val behindState = level.getBlockState(behind)
            if (behindState.block is RollerConveyorBlock &&
                behindState.getValue(RollerConveyorBlock.HORIZONTAL_FACING) == facing
            ) continue
            var length = 1
            var walk = pos.relative(facing)
            while (level.getBlockState(walk).let {
                    it.block is RollerConveyorBlock &&
                        it.getValue(RollerConveyorBlock.HORIZONTAL_FACING) == facing
                }
            ) {
                length++
                walk = walk.relative(facing)
            }
            if (best == null || length > best.length) best = Run(pos.immutable(), facing, length)
        }
        return best
    }

    // the hinge shaft block the schematic holds, if it holds one
    @JvmStatic
    fun findShaft(level: Level): BlockPos? {
        for (x in 0..14) for (y in 1..12) for (z in 0..14) {
            val pos = BlockPos(x, y, z)
            if (level.getBlockState(pos).block is RollerWorldShaftBlock) return pos
        }
        return null
    }

    // writes the run shape the world's own chain build would have worked out, on every segment of it
    @JvmStatic
    fun formRun(level: Level, run: Run) {
        for (index in 0 until run.length) {
            val be = level.getBlockEntity(run.controller.relative(run.facing, index)) as? RollerConveyorBlockEntity
                ?: continue
            be.setDeck(run.controller, index, run.length)
        }
    }

    // the speed the rollers and the loads both read; a positive value travels the way the run faces
    @JvmStatic
    fun seedSpeed(level: Level, controller: BlockPos, rpm: Float) {
        (level.getBlockEntity(controller) as? RollerConveyorBlockEntity)?.setSpeed(rpm)
    }

    // a load starts on the deck at once, in the lane it is given, without waiting for a pick up
    @JvmStatic
    fun dropLoad(
        level: Level,
        controller: BlockPos,
        stack: ItemStack,
        position: Float,
        lane: Int = RollerDeckInventory.UPPER_LANE
    ) {
        val be = level.getBlockEntity(controller) as? RollerConveyorBlockEntity ?: return
        val transported = TransportedItemStack(stack)
        (transported as RollerMomentumAccess).`waterparked$setDeckLane`(lane)
        transported.beltPosition = position
        transported.prevBeltPosition = position
        be.inventory?.transportedItems?.add(transported)
    }
}
