package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Item
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

    // sequenced-assembly interim item
    val INCOMPLETE_WATERSLIDE_TRACK: Item by REGISTRY.registerItem(
        "incomplete_waterslide_track",
        ::Item,
        Item.Properties()
    )

    val WATERSLIDE_ANCHOR: BlockItem by REGISTRY.registerSimpleBlockItem(
        "waterslide_anchor",
        Supplier { ModBlocks.WATERSLIDE_ANCHOR },
        Item.Properties().stacksTo(64)
    )

    // dyed inflatable boat (16 wool colors), spawns the boat entity on right click
    val INFLATABLE_BOAT_1X2: InflatableBoat1x2Item by REGISTRY.registerItem(
        "inflatable_boat_1x2",
        ::InflatableBoat1x2Item,
        Item.Properties().stacksTo(64)
    )
}
