package net.omori_sunny.create_waterparked.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.track.CoasterBezierHandleEdit;
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.omori_sunny.create_waterparked.config.ModConfig;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// allow waterslide in the bezier editor
@Mixin(CoasterBezierHandleEdit.class)
public abstract class CoasterBezierHandleEditMixin {

// actionbar lift readout without the radius offset
    @WrapOperation(
        method = "commitLift(Lnet/minecraft/server/level/ServerPlayer;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Ldev/silvergold/simulatedcoasters/track/anchor/CoasterAnchorpointBlockEntity;"
                + "getLiftBlocks()F"
        )
    )
    private static float waterslide$actionbarLift(CoasterAnchorpointBlockEntity be, Operation<Float> original) {
        float value = original.call(be);
        return be instanceof WaterslideAnchorBlockEntity slide ? value - slide.getRadius() : value;
    }

// keep the drag raw lift free of the radius offset
    @Inject(
        method = "computeLiftBlocksFromVirtualTarget(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;)Ljava/lang/Float;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$liftDrag(
        Level level,
        BlockPos pos,
        Vec3 target,
        CallbackInfoReturnable<Float> cir
    ) {
        if (!(level.getBlockEntity(pos) instanceof WaterslideAnchorBlockEntity be)) return;
        Vec3 base = CoasterAnchorpointBlockEntity.worldCenterBase(level, pos);
        Vec3 dir = CoasterAnchorpointBlockEntity.anchorLiftDirection(level, pos);
        double raw = target.subtract(base).dot(dir) - 1.4 - be.getRadius();
        float maxStored = Math.max(ModConfig.INSTANCE.maxSlideLift() - be.getRadius(), 0.25f);
        cir.setReturnValue((float) Mth.clamp(raw, 0.25, maxStored));
    }

// snap target above the radius-shifted hub
    @Inject(
        method = "liftVirtualTargetWorldForStoredLift(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;F)"
            + "Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void waterslide$liftTarget(
        Level level,
        BlockPos pos,
        Vec3 target,
        float storedLift,
        CallbackInfoReturnable<Vec3> cir
    ) {
        if (!(level.getBlockEntity(pos) instanceof WaterslideAnchorBlockEntity be)) return;
        Vec3 dir = CoasterAnchorpointBlockEntity.anchorLiftDirection(level, pos);
        cir.setReturnValue(cir.getReturnValue().add(dir.scale(be.getRadius())));
    }

// allow the secondary copy so drag preview works from either endpoint
    @Inject(
        method = "fetchPrimary(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/BlockPos;)Lcom/simibubi/create/content/trains/track/BezierConnection;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void waterslide$fetchPrimary(
        Level level,
        BlockPos host,
        BlockPos remote,
        CallbackInfoReturnable<BezierConnection> cir
    ) {
        if (!(level.getBlockEntity(host) instanceof CoasterAnchorpointBlockEntity ape)) return;
        BezierConnection c = ape.getAnchorPeerCurvesView().get(remote);
        if (c != null && !c.isPrimary() && WaterslideTrackMaterials.isWaterslide(c)) {
            cir.setReturnValue(c.secondary());
        }
    }

    @WrapOperation(
        method = "incidentPrimaryCoasterCurves(Lnet/minecraft/world/level/Level;"
            + "Ldev/silvergold/simulatedcoasters/track/anchor/CoasterAnchorpointBlockEntity;)Ljava/util/List;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$incident(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

    @WrapOperation(
        method = "computePreview(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/phys/Vec3;)"
            + "Ldev/silvergold/simulatedcoasters/track/CoasterBezierHandleEdit$PreviewResult;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$preview(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

    @WrapOperation(
        method = "snapBezierTangentVirtualTargetWithGuidePlanes(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/phys/Vec3;ZD)"
            + "Ldev/silvergold/simulatedcoasters/track/CoasterBezierHandleEdit$TangentGuideSnapResult;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$snap(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }

    @WrapOperation(
        method = "lambda$applyPreviewResult$1(Ldev/silvergold/simulatedcoasters/track/CoasterBezierHandleEdit$PreviewResult;"
            + "Lnet/minecraft/server/level/ServerLevel;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;equals(Ljava/lang/Object;)Z")
    )
    private static boolean waterslide$apply(ResourceLocation self, Object other, Operation<Boolean> original) {
        return WaterslideTrackMaterials.isCoasterOrWaterslideEquals(self, other) || original.call(self, other);
    }
}
