package net.omori_sunny.create_waterparked.mixin.client;

import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import net.omori_sunny.create_waterparked.client.SlideSableOrientation;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniondc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Sable's extension point for addon-driven entity orientation.
@Mixin(EntitySubLevelUtil.class)
public abstract class SableEntityOrientationMixin {

    @Inject(method = "getCustomEntityOrientation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void waterslide$getCustomEntityOrientation(
        Entity entity, float partialTicks, CallbackInfoReturnable<Quaterniondc> cir
    ) {
        Quaterniondc orientation = SlideSableOrientation.get(entity, partialTicks);
        if (orientation != null) {
            cir.setReturnValue(orientation);
        }
    }

    @Inject(method = "hasCustomEntityOrientation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void waterslide$hasCustomEntityOrientation(
        Entity entity, CallbackInfoReturnable<Boolean> cir
    ) {
        if (SlideSableOrientation.has(entity)) {
            cir.setReturnValue(true);
        }
    }
}
