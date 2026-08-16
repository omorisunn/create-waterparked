package net.omori_sunny.create_waterparked.content.registry

import dev.silvergold.simulatedcoasters.SimulatedCoasters
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent

// Adds Create Waterparked items to the "Simulated Coasters" creative tab
// (simulatedcoasters:main). Simulated Coasters is a hard dependency. This code
// does NOT touch the Aeronautics mod or the Simulated library: it works the
// same with Aeronautics disabled.
object CoasterCreativeTabIntegration {

    @JvmStatic
    fun onBuildCreativeModeTabContents(event: BuildCreativeModeTabContentsEvent) {
        if (event.tab !== SimulatedCoasters.MAIN_CREATIVE_TAB.get()) return
        event.accept(ModItems.WATERSLIDE_TRACK)
        event.accept(ModItems.WATERSLIDE_ANCHOR)
    }
}
