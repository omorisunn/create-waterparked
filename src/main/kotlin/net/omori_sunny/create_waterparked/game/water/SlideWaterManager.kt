package net.omori_sunny.create_waterparked.game.water

import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.minecraft.server.level.ServerLevel

// Water source tracking and drain.
object SlideWaterManager {

    @JvmStatic
    fun tickServer(level: ServerLevel, be: WaterslideAnchorBlockEntity) {
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
}
