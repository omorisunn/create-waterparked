package net.omori_sunny.create_waterparked.content.registry

import com.mojang.serialization.Codec
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.core.component.DataComponentType
import net.minecraft.network.codec.ByteBufCodecs
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

    // encoded slide config line while the clipboard paste mode is active
    val SLIDE_PASTE_LINE: DataComponentType<String> by
    REGISTRY.register("slide_paste_line") { ->
        DataComponentType.builder<String>()
            .persistent(Codec.STRING)
            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
            .build()
    }
}
