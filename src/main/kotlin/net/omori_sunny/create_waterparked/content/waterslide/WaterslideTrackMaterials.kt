package net.omori_sunny.create_waterparked.content.waterslide

import com.simibubi.create.content.trains.track.BezierConnection
import com.simibubi.create.content.trains.track.TrackMaterial
import com.simibubi.create.content.trains.track.TrackMaterialFactory
import com.tterrag.registrate.util.nullness.NonNullSupplier
import dev.silvergold.simulatedcoasters.CoasterTrackMaterials
import net.minecraft.resources.ResourceLocation
import net.omori_sunny.create_waterparked.content.registry.ModBlocks

// slide track material, registered like CCS's coaster material
object WaterslideTrackMaterials {

    @JvmField
    val ID: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "waterslide_track")

    @JvmField
    val WATERSLIDE: TrackMaterial = TrackMaterialFactory.make(ID)
        .lang("Water Slide Track")
        .block(NonNullSupplier.lazy { NonNullSupplier { ModBlocks.WATERSLIDE_TRACK } })
        .particle(ResourceLocation.fromNamespaceAndPath("create_waterparked", "block/waterslide_track_block"))
        .standardModels()
        .noRecipeGen()
        .build()

    @JvmStatic
    fun isWaterslide(bc: BezierConnection?): Boolean = bc != null && bc.material.id == ID

    @JvmStatic
    fun isWaterslideId(id: ResourceLocation?): Boolean = ID == id

    // treat coaster and waterslide ids as equal
    @JvmStatic
    fun isCoasterOrWaterslideEquals(self: ResourceLocation, other: Any?): Boolean {
        val otherId = other as? ResourceLocation ?: return false
        return (CoasterTrackMaterials.COASTER.id == self && ID == otherId) ||
            (ID == self && CoasterTrackMaterials.COASTER.id == otherId)
    }
}
