package net.omori_sunny.create_waterparked.mixin.ponder;

import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(PonderLevel.class)
public interface WaterslidePonderLevelAccessor {
    @Accessor("originalBlockEntities")
    Map<BlockPos, CompoundTag> create_waterparked$originalBlockEntities();
}
