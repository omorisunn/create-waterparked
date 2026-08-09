package net.omori_sunny.create_waterparked.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.client.track.CoasterCurveSelectionOutlineRenderer;
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// radius-matched selection outline
@Mixin(CoasterCurveSelectionOutlineRenderer.class)
public abstract class CoasterCurveSelectionOutlineRendererMixin {

    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;Lnet/minecraft/core/BlockPos;FFFFF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$render(
        PoseStack ms,
        MultiBufferSource buffer,
        Vec3 camera,
        Level level,
        BezierConnection bc,
        BlockPos sublevelAnchor,
        float partialTick,
        float outlineR,
        float outlineG,
        float outlineB,
        float outlineA,
        CallbackInfo ci
    ) {
        if (WaterslideTrackMaterials.isWaterslide(bc)) {
            WaterslideCurveRenderer.renderSelectionOutline(
                ms, buffer, camera, level, bc, partialTick,
                outlineR, outlineG, outlineB, outlineA
            );
            ci.cancel();
        }
    }
}
