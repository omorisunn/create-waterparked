package net.omori_sunny.create_waterparked.mixin.client;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge;
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// single centerline preview
@Mixin(CoasterTrackPlacement.class)
public abstract class CoasterTrackPlacementPreviewMixin {

    @Redirect(
        method = "drawCurvePreviewInner(Lnet/minecraft/world/level/Level;"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;Ljava/lang/String;IDIFFZ)I",
        at = @At(
            value = "INVOKE",
            target = "Ldev/silvergold/simulatedcoasters/track/CoasterTrackGauge;coasterPreviewRailHalfGauge()F"
        )
    )
    private static float waterslide$previewRailHalf(
        Level level,
        BezierConnection bc,
        String key,
        int railcolor,
        double previewLift,
        int prevCount,
        float smoothBlend,
        float lineWidth,
        boolean drawOpenEndExtensions
    ) {
        return WaterslideTrackMaterials.isWaterslide(bc) ? 0.0F : CoasterTrackGauge.coasterPreviewRailHalfGauge();
    }
}
