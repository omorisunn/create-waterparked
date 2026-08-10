package net.omori_sunny.create_waterparked.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer;
import net.omori_sunny.create_waterparked.config.ModConfig;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// anchor-specific normals for slide anchors
@Mixin(CoasterAnchorBezierOptimizer.class)
public abstract class CoasterAnchorBezierOptimizerMixin {

// no min-radius limit for slides
    @Inject(
        method = "meetsMinCurveRadiusWithHandleLengths(Lcom/simibubi/create/content/trains/track/BezierConnection;DD)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$noMinRadius(
        BezierConnection bc,
        double h0,
        double h1,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (ModConfig.INSTANCE.disableSlideCurveAngleLimit() && WaterslideTrackMaterials.isWaterslide(bc)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(
        method = "meetsAltSuppressMinCurveRadius(Lcom/simibubi/create/content/trains/track/BezierConnection;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$noMinRadiusSuppress(
        BezierConnection bc,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (ModConfig.INSTANCE.disableSlideCurveAngleLimit() && WaterslideTrackMaterials.isWaterslide(bc)) {
            cir.setReturnValue(true);
        }
    }

// no sharpness limit for slides
    @Inject(
        method = "isBuiltPlacementCurveValid(Lcom/simibubi/create/content/trains/track/BezierConnection;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$alwaysValid(BezierConnection bc, CallbackInfoReturnable<Boolean> cir) {
        if (ModConfig.INSTANCE.disableSlideCurveAngleLimit() && WaterslideTrackMaterials.isWaterslide(bc)) {
            cir.setReturnValue(true);
        }
    }

    @WrapOperation(
        method = "normalAtPos2ForOptimizer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;is"
            + "(Lnet/minecraft/world/level/block/Block;)Z")
    )
    private static boolean waterslide$isAnchor(BlockState state, Block block, Operation<Boolean> original) {
        return (state.getBlock() instanceof WaterslideAnchorBlock) || original.call(state, block);
    }
}
