package net.omori_sunny.create_waterparked.mixin;

import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// package-style centring: absorbing belts/depots poll
// PackageEntity.centerPackage every tick; the default returns true instantly
// for non-package entities, so reroute the boat through the same sliding
// insertion countdown a package gets
@Mixin(PackageEntity.class)
public abstract class PackageEntityCenterBoatMixin {

    @Inject(method = "centerPackage", at = @At("HEAD"), cancellable = true)
    private static void waterparked$centerBoat(Entity entity, Vec3 target, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof InflatableBoat1x2Entity boat) {
            cir.setReturnValue(boat.decreaseInsertionTimer(target));
        }
    }
}
