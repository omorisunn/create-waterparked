package net.omori_sunny.create_waterparked.mixin;

import dev.silvergold.simulatedcoasters.track.anchor.AnchorPeerFakeTracks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// allow waterslide curve hit resolution
@Mixin(AnchorPeerFakeTracks.class)
public abstract class AnchorPeerFakeTracksMixin {

    @WrapOperation(
        method = "hostedPrimaryCoasterCurvesContainingCell(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;)Ljava/util/List;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$hosted(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

    @WrapOperation(
        method = "distanceSqToCoasterCurveInteraction(Lcom/simibubi/create/content/trains/track/BezierConnection;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;)D",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$distance(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }
}
