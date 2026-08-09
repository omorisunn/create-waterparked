package net.omori_sunny.create_waterparked.mixin;

import dev.silvergold.simulatedcoasters.track.CoasterOpenEndExtension;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// allow open-end extension for slide curves
@Mixin(CoasterOpenEndExtension.class)
public abstract class CoasterOpenEndExtensionMixin {

    @WrapOperation(
        method = "primaryCoasterCurveAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$primary(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }
}
