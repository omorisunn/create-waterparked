package net.omori_sunny.create_waterparked.content.registry

import com.simibubi.create.api.behaviour.display.DisplaySource
import com.simibubi.create.api.registry.CreateRegistries
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorDisplaySource
import java.util.function.Supplier

// binding goes through Create's BY_BLOCK map, which is what the Display Link GUI lists
object ModDisplaySources {

    val REGISTRY: DeferredRegister<DisplaySource> =
        DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, CreateWaterparked.ID)

    val MECHANICAL_DOOR: DeferredHolder<DisplaySource, MechanicalDoorDisplaySource> =
        REGISTRY.register("mechanical_door", Supplier { MechanicalDoorDisplaySource() })

    // runs from common setup: the binding block must be registered first
    fun bindToBlocks() {
        DisplaySource.BY_BLOCK.add(
            ModSlideAttachments.MECHANICAL_DOOR.block.get(),
            MECHANICAL_DOOR.get()
        )
    }
}
