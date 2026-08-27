package net.omori_sunny.create_waterparked.game.water

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.minecraft.server.level.ServerLevel

// Water source tracking and drain.
object SlideWaterManager {

    private const val REPORT_INTERVAL = 200L
    private const val TICKS_PER_SECOND = 20.0

    @JvmStatic
    fun tickServer(level: ServerLevel, be: WaterslideAnchorBlockEntity) {
        // diagnostic: periodic water amount report
        if (level.gameTime % REPORT_INTERVAL == 0L && be.hasWater()) {
            CreateWaterparked.LOGGER.info(
                "Water anchor {} amount={} mb", be.blockPos, be.waterAmount()
            )
        }

        // source anchors drain continuously
        if (!be.hasWater()) {
            be.resetDrainAccum()
            return
        }
        val rate = ModConfig.waterDrainRateMbPerSecond()
        be.addDrainAccum(rate / TICKS_PER_SECOND)
        val want = be.waterDrainAccum().toInt()
        if (want <= 0) return
        val drained = be.drainWater(want)
        be.addDrainAccum(-drained.toDouble())
        if (drained < want) be.resetDrainAccum()
    }
}
