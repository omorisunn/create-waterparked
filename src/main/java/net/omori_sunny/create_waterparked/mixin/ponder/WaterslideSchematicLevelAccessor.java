package net.omori_sunny.create_waterparked.mixin.ponder;

import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Map;

@Mixin(SchematicLevel.class)
public interface WaterslideSchematicLevelAccessor {
    @Accessor("blockEntities")
    Map<BlockPos, BlockEntity> create_waterparked$blockEntities();

    @Accessor("renderedBlockEntities")
    List<BlockEntity> create_waterparked$renderedBlockEntities();

    @Invoker("onBEadded")
    void create_waterparked$onBEadded(BlockEntity blockEntity, BlockPos pos);
}
