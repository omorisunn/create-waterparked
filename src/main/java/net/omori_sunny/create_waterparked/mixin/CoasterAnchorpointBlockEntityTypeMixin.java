package net.omori_sunny.create_waterparked.mixin;

import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// swap the BE type at construction
@Mixin(CoasterAnchorpointBlockEntity.class)
public abstract class CoasterAnchorpointBlockEntityTypeMixin {

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;<init>"
                + "(Lnet/minecraft/world/level/block/entity/BlockEntityType;"
                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"
        ),
        index = 0
    )
    private static BlockEntityType<?> waterslide$pendingType(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        BlockEntityType<?> pending = WaterslideAnchorBlockEntity.pendingType();
        return pending != null ? pending : type;
    }
}
