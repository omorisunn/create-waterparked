package net.omori_sunny.create_waterparked.mixin.ponder;

import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PonderLevel.class)
public interface WaterslidePonderLevelInvoker {
    @Invoker("onBEAdded")
    void create_waterparked$onBEAdded(BlockEntity blockEntity, BlockPos pos);
}
