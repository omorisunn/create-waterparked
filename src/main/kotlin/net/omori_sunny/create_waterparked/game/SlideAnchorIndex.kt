package net.omori_sunny.create_waterparked.game

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

// Server-side anchor index for entry detection.
object SlideAnchorIndex {

    private val anchors = mutableMapOf<ResourceKey<Level>, MutableSet<BlockPos>>()

    @JvmStatic
    fun register(level: Level, pos: BlockPos) {
        if (level.isClientSide) return
        anchors.getOrPut(level.dimension()) { mutableSetOf() } += pos.immutable()
    }

    @JvmStatic
    fun unregister(level: Level, pos: BlockPos) {
        if (level.isClientSide) return
        anchors[level.dimension()]?.remove(pos.immutable())
    }

    @JvmStatic
    fun all(level: Level): Set<BlockPos> = anchors[level.dimension()] ?: emptySet()
}
