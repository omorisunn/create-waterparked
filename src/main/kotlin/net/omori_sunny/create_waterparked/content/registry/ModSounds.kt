package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

// sound events
object ModSounds {
    val REGISTRY: DeferredRegister<SoundEvent> =
        DeferredRegister.create(Registries.SOUND_EVENT, CreateWaterparked.ID)

    val WATER_FLOW: DeferredHolder<SoundEvent, SoundEvent> =
        REGISTRY.register("waterslide_water_flow") { name ->
            SoundEvent.createFixedRangeEvent(name, 64f)
        }
}
