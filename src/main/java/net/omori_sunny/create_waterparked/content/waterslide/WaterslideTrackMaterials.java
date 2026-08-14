package net.omori_sunny.create_waterparked.content.waterslide;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackMaterialFactory;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import dev.silvergold.simulatedcoasters.CoasterTrackMaterials;
import net.omori_sunny.create_waterparked.content.registry.ModBlocks;
import net.minecraft.resources.ResourceLocation;

// Slide track material, registered like CCS's coaster material.
public final class WaterslideTrackMaterials {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "waterslide_track");

    public static final TrackMaterial WATERSLIDE =
        TrackMaterialFactory.make(ID)
            .lang("Water Slide Track")
            .block(NonNullSupplier.lazy(() -> () -> ModBlocks.INSTANCE.getWATERSLIDE_TRACK()))
            .particle(ResourceLocation.fromNamespaceAndPath("create_waterparked", "block/waterslide_track"))
            .standardModels()
            .noRecipeGen()
            .build();

    private WaterslideTrackMaterials() {
    }

    public static boolean isWaterslide(BezierConnection bc) {
        return bc != null && bc.getMaterial().id.equals(ID);
    }

    public static boolean isWaterslideId(ResourceLocation id) {
        return ID.equals(id);
    }

    // Treat coaster and waterslide ids as equal.
    public static boolean isCoasterOrWaterslideEquals(ResourceLocation self, Object other) {
        if (!(other instanceof ResourceLocation otherId)) {
            return false;
        }
        return (CoasterTrackMaterials.COASTER.id.equals(self) && ID.equals(otherId))
            || (ID.equals(self) && CoasterTrackMaterials.COASTER.id.equals(otherId));
    }
}
