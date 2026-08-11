package net.omori_sunny.create_waterparked.datagen

import net.neoforged.neoforge.data.event.GatherDataEvent

// datagen entry point
object CreateWaterparkedDataGen {

    @JvmStatic
    fun gatherData(event: GatherDataEvent) {
        val generator = event.generator
        generator.addProvider(true, WaterslideDyeJsonProvider(generator.packOutput, "create_waterparked"))
    }
}
