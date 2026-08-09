package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// bank geometry for waterslide curves
@Mixin(CoasterBezierRailFrames.class)
public abstract class CoasterBezierRailFramesMixin {

    @Inject(method = "isCoaster", at = @At("HEAD"), cancellable = true)
    private static void waterslide$isCoaster(BezierConnection bc, CallbackInfoReturnable<Boolean> cir) {
        if (WaterslideTrackMaterials.isWaterslide(bc)) {
            cir.setReturnValue(true);
        }
    }
}
