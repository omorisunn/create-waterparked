package net.omori_sunny.create_waterparked.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// anchor-specific normals for slide anchors
@Mixin(CoasterAnchorBezierOptimizer.class)
public abstract class CoasterAnchorBezierOptimizerMixin {

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
