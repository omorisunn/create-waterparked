package net.omori_sunny.create_waterparked.content.registry

import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackBlock
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlock
import net.omori_sunny.create_waterparked.content.roller.RollerWorldShaftBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

object ModBlocks {
    val REGISTRY: DeferredRegister.Blocks = DeferredRegister.createBlocks(CreateWaterparked.ID)

    val WATERSLIDE_TRACK: WaterslideTrackBlock by REGISTRY.register("waterslide_track_block") { ->
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

    val WATERSLIDE_ANCHOR: WaterslideAnchorBlock by REGISTRY.register("waterslide_anchor") { ->
        WaterslideAnchorBlock(WaterslideAnchorBlock.defaultProperties())
    }

    val SLIDE_DRAFTING_TABLE: net.omori_sunny.create_waterparked.content.sketch.SlideDraftingTableBlock by
        REGISTRY.register("slide_drafting_table") { ->
            net.omori_sunny.create_waterparked.content.sketch.SlideDraftingTableBlock(
                net.omori_sunny.create_waterparked.content.sketch.SlideDraftingTableBlock.defaultProperties()
            )
        }

    val WATERSLIDE_RIVET: net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetBlock by
    REGISTRY.register("waterslide_rivet") { ->
        net.omori_sunny.create_waterparked.content.waterslide.WaterslideRivetBlock(
            dev.silvergold.simulatedcoasters.rivet.RivetBlock.defaultProperties()
        )
    }

    val ROLLER_CONVEYOR: RollerConveyorBlock by REGISTRY.register("roller_conveyor") { ->
        RollerConveyorBlock(RollerConveyorBlock.defaultProperties())
    }

    val ROLLER_HINGE_SHAFT: RollerWorldShaftBlock by REGISTRY.register("roller_hinge_shaft") { ->
        RollerWorldShaftBlock(RollerWorldShaftBlock.defaultProperties())
    }
}
