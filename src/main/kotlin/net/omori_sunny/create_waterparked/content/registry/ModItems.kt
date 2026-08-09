package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackItem
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import java.util.function.Supplier
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModItems {
    val REGISTRY: DeferredRegister.Items = DeferredRegister.createItems(CreateWaterparked.ID)

    // slide connection tool
    val WATERSLIDE_TRACK: WaterslideTrackItem by REGISTRY.registerItem(
        "waterslide_track",
        ::WaterslideTrackItem,
        Item.Properties().stacksTo(64)
    )

    val WATERSLIDE_ANCHOR: BlockItem by REGISTRY.registerSimpleBlockItem(
        "waterslide_anchor",
        Supplier { ModBlocks.WATERSLIDE_ANCHOR },
        Item.Properties().stacksTo(64)
    )

    val WATERSLIDE_ENTRANCE: BlockItem by REGISTRY.registerSimpleBlockItem(
        "waterslide_entrance",
        Supplier { ModBlocks.WATERSLIDE_ENTRANCE },
        Item.Properties().stacksTo(64)
    )
}
