package net.omori_sunny.create_waterparked.mixin.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SortedSet;

// destruction progress accessor
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("destructionProgress")
    Long2ObjectMap<SortedSet<BlockDestructionProgress>> getWaterslideDestructionProgress();
}
