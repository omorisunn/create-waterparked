package net.omori_sunny.create_waterparked.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleOverlayRenderTypes;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleOverlay;
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit;
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit;
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// slide handles plus sector control point overlays
@Mixin(BezierHandleOverlay.class)
public abstract class BezierHandleOverlayMixin {

    @WrapOperation(
        method = "renderWrenchRangeCurves(Lnet/minecraft/client/Minecraft;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "Lnet/minecraft/world/phys/Vec3;Lorg/joml/Matrix4f;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$renderHandles(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

    @Inject(method = "renderWrenchRangeCurves", at = @At("RETURN"))
    private static void waterslide$renderSectorControlPoints(
        Minecraft mc,
        PoseStack ms,
        MultiBufferSource buffer,
        Vec3 cameraPos,
        Matrix4f cameraRotation,
        CallbackInfo ci
    ) {
        WaterslideSectorEdit.renderControlPoints(mc, ms, buffer, cameraPos, cameraRotation);
        WaterslideRadiusEdit.renderHandle(mc, ms, buffer, cameraPos, cameraRotation);
        if (buffer instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch(WaterslideEditorRenderTypes.INSTANCE.getSEE_THROUGH_LINES());
            bufferSource.endBatch(WaterslideEditorRenderTypes.INSTANCE.getCOLORED_QUADS());
            WaterslideEditorRenderTypes.INSTANCE.endBoundaryHandleBillboardBatches(bufferSource);
            BezierHandleOverlayRenderTypes.endTangentHandleBillboardBatches(bufferSource);
        }
    }
}
