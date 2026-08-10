package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.CoasterPeerCurveHandleLengths;
import net.createmod.catnip.data.Couple;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// allow handle length edits for slide curves
@Mixin(CoasterPeerCurveHandleLengths.class)
public abstract class CoasterPeerCurveHandleLengthsMixin {

    @Inject(method = "isCoaster", at = @At("HEAD"), cancellable = true)
    private static void waterslide$isCoaster(BezierConnection bc, CallbackInfoReturnable<Boolean> cir) {
        if (WaterslideTrackMaterials.isWaterslide(bc)) {
            cir.setReturnValue(true);
        }
    }

// persist handle lengths even with an empty canonical map
    @Inject(method = "getCanonicalLengths", at = @At("RETURN"), cancellable = true)
    private static void waterslide$canonicalFallback(
        BezierConnection bc,
        CallbackInfoReturnable<Couple<Double>> cir
    ) {
        if (cir.getReturnValue() == null && WaterslideTrackMaterials.isWaterslide(bc)) {
            double h = bc.starts.getFirst().distanceTo(bc.starts.getSecond()) / 3.0;
            cir.setReturnValue(Couple.create(h, h));
        }
    }
}
