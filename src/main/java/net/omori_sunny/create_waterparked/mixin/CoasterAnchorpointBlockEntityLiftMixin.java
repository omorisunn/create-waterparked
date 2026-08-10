package net.omori_sunny.create_waterparked.mixin;

import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// radius offset in explicit-lift positions
@Mixin(CoasterAnchorpointBlockEntity.class)
public abstract class CoasterAnchorpointBlockEntityLiftMixin {

    @Inject(
        method = "worldCenterWithLift(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;F)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void waterslide$liftCenter(
        Level level,
        BlockPos pos,
        float lift,
        CallbackInfoReturnable<Vec3> cir
    ) {
        if (!(level.getBlockEntity(pos) instanceof WaterslideAnchorBlockEntity be)) return;
        Vec3 dir = CoasterAnchorpointBlockEntity.anchorLiftDirection(level, pos);
        cir.setReturnValue(cir.getReturnValue().add(dir.scale(be.getRadius())));
    }
}
