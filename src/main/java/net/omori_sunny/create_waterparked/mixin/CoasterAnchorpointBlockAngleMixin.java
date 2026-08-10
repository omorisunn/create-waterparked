package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlock;
import net.omori_sunny.create_waterparked.config.ModConfig;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// no leg-angle limit for slides
@Mixin(CoasterAnchorpointBlock.class)
public abstract class CoasterAnchorpointBlockAngleMixin {

    @Inject(
        method = "connectionBelowMinLegAngle(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
            + "Lcom/simibubi/create/content/trains/track/BezierConnection;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$noLegAngle(
        Level level,
        BlockPos a,
        BlockPos b,
        BezierConnection bc,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (ModConfig.INSTANCE.disableSlideCurveAngleLimit() && WaterslideTrackMaterials.isWaterslide(bc)) {
            cir.setReturnValue(false);
        }
    }
}
