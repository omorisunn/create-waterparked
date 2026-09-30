package net.omori_sunny.create_waterparked.client.flywheel

import dev.engine_room.flywheel.lib.model.baked.PartialModel
import net.minecraft.resources.ResourceLocation
import net.omori_sunny.create_waterparked.CreateWaterparked

// partial models must exist before Flywheel registers them for baking, so they are touched from client setup
object ModPartialModels {

    val ROLLER: PartialModel = PartialModel.of(
        ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "block/roller_conveyor/roller")
    )

    // the shaft a hinged deck draws through itself, its two ends only
    val ROLLER_SHAFT: PartialModel = PartialModel.of(
        ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "block/roller_conveyor/shaft_inserted")
    )
}
