package net.omori_sunny.create_waterparked.game.water

import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.entrance.WaterslideEntranceBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

// Water source tracking and drain.
object SlideWaterManager {

    @JvmStatic
    fun tickServer(level: ServerLevel, be: WaterslideAnchorBlockEntity) {
        if (hasAdjacentWetEntrance(level, be.blockPos)) be.refillWater()

        // diagnostic: periodic water amount report
        if (level.gameTime % 200 == 0L && be.hasWater()) {
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "Water anchor {} amount={} mb", be.blockPos, be.waterAmount()
            )
        }

        // source anchors drain continuously
        if (!be.hasWater()) {
            be.resetDrainAccum()
            return
        }
        val rate = ModConfig.waterDrainRateMbPerSecond()
        be.addDrainAccum(rate / 20.0)
        val want = be.waterDrainAccum().toInt()
        if (want <= 0) return
        val drained = be.drainWater(want)
        be.addDrainAccum(-drained.toDouble())
        if (drained < want) be.resetDrainAccum()
    }

    private fun hasAdjacentWetEntrance(level: ServerLevel, pos: BlockPos): Boolean {
        for (dir in Direction.entries) {
            val state = level.getBlockState(pos.relative(dir))
            if (state.block is WaterslideEntranceBlock &&
                state.getValue(WaterslideEntranceBlock.WATER_ACTIVE)
            ) {
                return true
            }
        }
        return false
    }
}
