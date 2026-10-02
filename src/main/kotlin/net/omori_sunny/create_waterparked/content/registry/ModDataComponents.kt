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

    val CONNECTING_FROM: DataComponentType<BlockPos> by
    REGISTRY.register("waterslide_connecting_from") { ->
        DataComponentType.builder<BlockPos>()
            .persistent(BlockPos.CODEC)
            .networkSynchronized(BlockPos.STREAM_CODEC)
            .build()
    }

    val SLIDE_PASTE_LINE: DataComponentType<String> by
    REGISTRY.register("slide_paste_line") { ->
        DataComponentType.builder<String>()
            .persistent(Codec.STRING)
            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
            .build()
    }

    val SLIDE_ATTACHMENT_POS: DataComponentType<net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentPos> by
        REGISTRY.register("slide_attachment_pos") { ->
            DataComponentType.builder<net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentPos>()
                .persistent(net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockItem.POSITION_CODEC)
                .networkSynchronized(net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockItem.POSITION_STREAM_CODEC)
                .build()
        }

    val SLIDE_SKETCH: DataComponentType<net.omori_sunny.create_waterparked.game.SlideSketchData> by
        REGISTRY.register("slide_sketch") { ->
            DataComponentType.builder<net.omori_sunny.create_waterparked.game.SlideSketchData>()
                .persistent(net.omori_sunny.create_waterparked.game.SlideSketchData.CODEC)
                .networkSynchronized(net.omori_sunny.create_waterparked.game.SlideSketchData.STREAM_CODEC)
                .build()
        }
}
