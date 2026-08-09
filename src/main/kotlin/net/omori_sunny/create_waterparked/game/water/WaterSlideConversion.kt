package net.omori_sunny.create_waterparked.game.water

import net.omori_sunny.create_waterparked.content.entrance.WaterslideEntranceBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids

// Water slide activation.
object WaterSlideConversion {

    fun refreshEntrance(level: Level, pos: BlockPos, state: BlockState) {
        val block = state.block
        if (block is WaterslideEntranceBlock) {
            block.refreshWater(level, pos, state)
        }
    }

    fun isEntranceWet(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.block !is WaterslideEntranceBlock) return false
        return state.getValue(WaterslideEntranceBlock.WATER_ACTIVE)
    }

    // Propagate water state to the adjacent anchor.
    fun propagateToAdjacentAnchors(level: Level, entrancePos: BlockPos, wet: Boolean) {
        for (dir in Direction.values()) {
            val be = level.getBlockEntity(entrancePos.relative(dir)) as? WaterslideAnchorBlockEntity ?: continue
            be.setWaterActive(wet)
        }
    }

    fun isWaterSourceAt(level: Level, pos: BlockPos): Boolean {
        val fluid = level.getFluidState(pos)
        return fluid.`is`(Fluids.WATER) && fluid.isSource
    }
}
