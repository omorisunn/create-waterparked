package net.omori_sunny.create_waterparked.mixin;

import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// allow waterslide in edit validation
@Mixin(CoasterTrackPlacement.class)
public abstract class CoasterTrackPlacementMixin {

    @WrapOperation(
        method = "validateEditedCoasterCurve(Lnet/minecraft/world/level/Level;"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;Ljava/util/List;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$validate(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

    @WrapOperation(
        method = "withAxisAtEndpoint(Lnet/minecraft/world/level/Level;"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/phys/Vec3;Z)Lcom/simibubi/create/content/trains/track/BezierConnection;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$withAxis(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

    @WrapOperation(
        method = "curveWithStartAndAxisAtEndpointForEdit(Lnet/minecraft/world/level/Level;"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$withStart(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }
}
