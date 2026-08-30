package net.omori_sunny.create_waterparked.mixin.client;
// Mixin: radius-aware coaster curve pick + fake ghost block hit for the slide.

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.client.track.AnchorPeerCurveHit;
import dev.silvergold.simulatedcoasters.client.track.AnchorPeerCurvePick;
import net.omori_sunny.create_waterparked.client.editor.WaterslideGhostPlacement;
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit;
import net.omori_sunny.create_waterparked.config.ModConfig;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnchorPeerCurvePick.class)
public abstract class AnchorPeerCurvePickMixin {

    private static final float RADIUS_PADDING = 0.45f;

    @Inject(method = "refineAfterCreatePass", at = @At("TAIL"))
    private static void waterslide$ghostHitAfterRefine(Minecraft mc, CallbackInfo ci) {
        try {
            net.minecraft.world.phys.BlockHitResult hit = WaterslideGhostPlacement.fakeGhostHit(mc);
            if (hit != null) {
                mc.hitResult = hit;
                AnchorPeerCurveHit.clear();
            }
        } catch (Throwable t) {
            net.omori_sunny.create_waterparked.CreateWaterparked.INSTANCE.getLOGGER().warn(
                "[WaterslideGhostHit] refine pass failed", t
            );
        }
    }

    @WrapOperation(
        method = "refineAfterCreatePass",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;contains(Lnet/minecraft/world/phys/Vec3;)Z"
        )
    )
    private static boolean waterslide$curveContains(
        AABB box,
        Vec3 vec,
        Operation<Boolean> original,
        @Local(name = "bc") BezierConnection bc
    ) {
        if (!WaterslideTrackMaterials.isWaterslide(bc)) return original.call(box, vec);
        return original.call(inflateByRadius(box, bc), vec);
    }

    @WrapOperation(
        method = "refineAfterCreatePass",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;clip(Lnet/minecraft/world/phys/Vec3;"
                + "Lnet/minecraft/world/phys/Vec3;)Ljava/util/Optional;",
            ordinal = 0
        )
    )
    private static Optional<Vec3> waterslide$curveClip(
        AABB box,
        Vec3 from,
        Vec3 to,
        Operation<Optional<Vec3>> original,
        @Local(name = "bc") BezierConnection bc
    ) {
        if (!WaterslideTrackMaterials.isWaterslide(bc)) return original.call(box, from, to);
        return original.call(inflateByRadius(box, bc), from, to);
    }

    @WrapOperation(
        method = "refineAfterCreatePass",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;clip(Lnet/minecraft/world/phys/Vec3;"
                + "Lnet/minecraft/world/phys/Vec3;)Ljava/util/Optional;",
            ordinal = 1
        )
    )
    private static Optional<Vec3> waterslide$segmentClip(
        AABB box,
        Vec3 from,
        Vec3 to,
        Operation<Optional<Vec3>> original,
        @Local(name = "bc") BezierConnection bc,
        @Local(name = "t1") float t1
    ) {
        if (!WaterslideTrackMaterials.isWaterslide(bc)) return original.call(box, from, to);
        float[] r = endpointRadii(bc);
        if (r == null) return original.call(box, from, to);
        float half = Mth.lerp(t1, r[0], r[1]) + RADIUS_PADDING;
        return new AABB(-half, -half, -half, half, half, half).clip(from, to);
    }

    private static AABB inflateByRadius(AABB box, BezierConnection bc) {
        float[] r = endpointRadii(bc);
        if (r == null) return box;
        return box.inflate(Math.max(r[0], r[1]) + RADIUS_PADDING);
    }

    private static float[] endpointRadii(BezierConnection bc) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        return new float[] {
            WaterslideRadiusEdit.INSTANCE.radiusAt(
                level, bc.bePositions.getFirst(), ModConfig.INSTANCE.defaultSlideRadius()
            ),
            WaterslideRadiusEdit.INSTANCE.radiusAt(
                level, bc.bePositions.getSecond(), ModConfig.INSTANCE.defaultSlideRadius()
            )
        };
    }
}