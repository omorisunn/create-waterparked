package net.omori_sunny.create_waterparked.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.silvergold.simulatedcoasters.client.track.ChainLiftAnchorShaftRenderer;
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// fallback rendering for parent-type anchors
@Mixin(ChainLiftAnchorShaftRenderer.class)
public abstract class ChainLiftAnchorShaftRendererMixin {

    @Inject(
        method = "renderSafe(Ldev/silvergold/simulatedcoasters/track/anchor/CoasterAnchorpointBlockEntity;"
            + "FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$renderParentType(
        CoasterAnchorpointBlockEntity be,
        float partialTicks,
        PoseStack ms,
        MultiBufferSource buffer,
        int light,
        int overlay,
        CallbackInfo ci
    ) {
        boolean hasWaterslide = be.getAnchorPeerCurvesView().values().stream()
            .map(c -> c.isPrimary() ? c : c.secondary())
            .anyMatch(c -> WaterslideTrackMaterials.isWaterslide(c));
        if (hasWaterslide) {
            WaterslideCurveRenderer.renderFromParent(be, ms, buffer);
            ci.cancel();
        }
    }
}
