package net.omori_sunny.create_waterparked.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.silvergold.simulatedcoasters.client.track.AnchorPeerCurveOutlineSupport;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// allow waterslide selection outline
@Mixin(AnchorPeerCurveOutlineSupport.class)
public abstract class AnchorPeerCurveOutlineSupportMixin {

    @WrapOperation(
        method = "resolveHostedPrimary(Lnet/minecraft/world/level/Level;"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;)"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$resolve(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }
}
