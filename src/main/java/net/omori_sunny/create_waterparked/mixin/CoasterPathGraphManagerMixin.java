package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.graph.CoasterPathGraphManager;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// include slide curves in the path graph
@Mixin(CoasterPathGraphManager.class)
public abstract class CoasterPathGraphManagerMixin {

    @Inject(method = "isCoasterGraphBezier", at = @At("HEAD"), cancellable = true)
    private static void waterslide$isCoaster(BezierConnection bc, CallbackInfoReturnable<Boolean> cir) {
        if (WaterslideTrackMaterials.isWaterslide(bc)) {
            cir.setReturnValue(true);
        }
    }
}
