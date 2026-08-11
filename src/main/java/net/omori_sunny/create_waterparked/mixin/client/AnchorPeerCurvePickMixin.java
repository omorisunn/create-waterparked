package net.omori_sunny.create_waterparked.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.track.BezierConnection;
import dev.silvergold.simulatedcoasters.client.track.AnchorPeerCurvePick;
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

// radius-aware curve picking
@Mixin(AnchorPeerCurvePick.class)
public abstract class AnchorPeerCurvePickMixin {

// radius-aware coarse curve bounds
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

// per-segment radius at the segment midpoint
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
        Level level = Minecraft.getInstance().level;
        if (level == null) return original.call(box, from, to);
        float r0 = WaterslideRadiusEdit.INSTANCE.radiusAt(
            level, bc.bePositions.getFirst(), ModConfig.INSTANCE.defaultSlideRadius()
        );
        float r1 = WaterslideRadiusEdit.INSTANCE.radiusAt(
            level, bc.bePositions.getSecond(), ModConfig.INSTANCE.defaultSlideRadius()
        );
        float half = Mth.lerp(t1, r0, r1) + 0.45f;
        return new AABB(-half, -half, -half, half, half, half).clip(from, to);
    }

    private static AABB inflateByRadius(AABB box, BezierConnection bc) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return box;
        float r0 = WaterslideRadiusEdit.INSTANCE.radiusAt(
            level, bc.bePositions.getFirst(), ModConfig.INSTANCE.defaultSlideRadius()
        );
        float r1 = WaterslideRadiusEdit.INSTANCE.radiusAt(
            level, bc.bePositions.getSecond(), ModConfig.INSTANCE.defaultSlideRadius()
        );
        return box.inflate(Math.max(r0, r1) + 0.45f);
    }
}
