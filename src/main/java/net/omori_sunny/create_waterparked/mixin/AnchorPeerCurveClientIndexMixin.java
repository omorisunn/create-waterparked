package net.omori_sunny.create_waterparked.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.silvergold.simulatedcoasters.track.anchor.AnchorPeerCurveClientIndex;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// allow waterslide curves in the client anchor index
@Mixin(AnchorPeerCurveClientIndex.class)
public abstract class AnchorPeerCurveClientIndexMixin {

    @WrapOperation(
        method = "lambda$refreshMembership$2(Lcom/simibubi/create/content/trains/track/BezierConnection;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$refresh(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }
}
