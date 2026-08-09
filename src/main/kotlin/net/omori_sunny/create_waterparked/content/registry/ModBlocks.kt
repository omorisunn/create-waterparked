package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.content.entrance.WaterslideEntranceBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackBlock
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModBlocks {
    val REGISTRY: DeferredRegister.Blocks = DeferredRegister.createBlocks(CreateWaterparked.ID)

    // slide curve block
    val WATERSLIDE_TRACK: WaterslideTrackBlock by REGISTRY.register("waterslide_track") { ->
        WaterslideTrackBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(0.8f)
                .sound(SoundType.METAL)
                .noOcclusion()
                .forceSolidOn(),
            WaterslideTrackMaterials.WATERSLIDE
        )
    }

    // slide anchor
    val WATERSLIDE_ANCHOR: WaterslideAnchorBlock by REGISTRY.register("waterslide_anchor") { ->
        WaterslideAnchorBlock(WaterslideAnchorBlock.defaultProperties())
    }

    // slide entrance
    val WATERSLIDE_ENTRANCE: WaterslideEntranceBlock by REGISTRY.register("waterslide_entrance") { ->
        WaterslideEntranceBlock(WaterslideEntranceBlock.defaultProperties())
    }
}
