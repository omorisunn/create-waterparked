package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.core.component.DataComponentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModDataComponents {
    val REGISTRY: DeferredRegister<DataComponentType<*>> =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CreateWaterparked.ID)

    // first selected anchor
    val CONNECTING_FROM: DataComponentType<BlockPos> by
    REGISTRY.register("waterslide_connecting_from") { ->
        DataComponentType.builder<BlockPos>()
            .persistent(BlockPos.CODEC)
            .networkSynchronized(BlockPos.STREAM_CODEC)
            .build()
    }
}
