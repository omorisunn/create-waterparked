package net.omori_sunny.create_waterparked.content.registry

import dev.silvergold.simulatedcoasters.SimulatedCoasters
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent

// adds Waterparked items to the Simulated Coasters creative tab
object CoasterCreativeTabIntegration {

    @JvmStatic
    fun onBuildCreativeModeTabContents(event: BuildCreativeModeTabContentsEvent) {
        if (event.tab !== SimulatedCoasters.MAIN_CREATIVE_TAB.get()) return
        event.accept(ModItems.WATERSLIDE_TRACK)
        event.accept(ModItems.WATERSLIDE_ANCHOR)
    }
}
