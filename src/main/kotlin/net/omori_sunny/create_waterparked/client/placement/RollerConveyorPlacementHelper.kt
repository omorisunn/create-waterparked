package net.omori_sunny.create_waterparked.client.placement

import net.createmod.catnip.placement.IPlacementHelper
import net.createmod.catnip.placement.PlacementHelpers
import net.createmod.catnip.placement.PlacementOffset
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlock
import net.omori_sunny.create_waterparked.content.roller.RollerHinge
import java.util.function.Predicate

// shows where a run would continue when the roller item is held over a segment already placed
object RollerConveyorPlacementHelper : IPlacementHelper {

    // the block and the client setup both ask for the helper, so one lazy registration serves the whole game
    private val registeredId: Int by lazy { PlacementHelpers.register(this) }

    private var lastChoice = ""

    fun register(): Int = registeredId

    override fun getItemPredicate(): Predicate<ItemStack> = Predicate { it.`is`(ModItems.ROLLER_CONVEYOR) }

    override fun getStatePredicate(): Predicate<BlockState> = Predicate { it.block is RollerConveyorBlock }

    override fun getOffset(
        player: Player,
        level: Level,
        state: BlockState,
        pos: BlockPos,
        ray: BlockHitResult
    ): PlacementOffset = offsetAt(level, state, pos)

    // the client draws through this overload, whose default would swap our oriented ghost for a bare default state
    override fun getOffset(
        player: Player,
        level: Level,
        state: BlockState,
        pos: BlockPos,
        ray: BlockHitResult,
        stack: ItemStack
    ): PlacementOffset = offsetAt(level, state, pos)

    // a run only ever extends along its own axis, so the cell in front comes first and the one behind the tail second
    private fun offsetAt(level: Level, state: BlockState, pos: BlockPos): PlacementOffset {
        val facing = state.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
        val ghost = ModBlocks.ROLLER_CONVEYOR.defaultBlockState()
            .setValue(RollerConveyorBlock.HORIZONTAL_FACING, facing)
            .setValue(RollerConveyorBlock.HINGE, RollerHinge.Side.NONE)
            .setValue(BlockStateProperties.WATERLOGGED, false)
        val ahead = pos.relative(facing)
        val aheadFree = level.getBlockState(ahead).canBeReplaced()
        val behind = pos.relative(facing.opposite)
        val behindFree = !aheadFree && level.getBlockState(behind).canBeReplaced()
        reportChoice(pos, facing, aheadFree, behindFree, ahead, behind)
        if (aheadFree) return oriented(ahead, ghost)
        if (behindFree) return oriented(behind, ghost)
        return PlacementOffset.fail()
    }

    // one debug line per change proves in game that the item and state predicates hit, and where the ghost went
    private fun reportChoice(pos: BlockPos, facing: Direction, aheadFree: Boolean, behindFree: Boolean, ahead: BlockPos, behind: BlockPos) {
        val choice = "$aheadFree/$behindFree"
        if (choice == lastChoice) return
        lastChoice = choice
        CreateWaterparked.LOGGER.debug(
            "[roller ghost] target={} facing={} frontFree={} backFree={} at={}",
            pos, facing, aheadFree, behindFree, if (aheadFree) ahead else if (behindFree) behind else "none"
        )
    }

    // the same state drives the preview and the placement, so a click always lands what the ghost showed
    private fun oriented(target: BlockPos, ghost: BlockState): PlacementOffset =
        PlacementOffset.success(target).withGhostState(ghost).withTransform { ghost }
}
