package net.omori_sunny.create_waterparked.game

import net.omori_sunny.create_waterparked.game.physics.SlideSpace
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB

object SlideAnchorIndex {
    private data class Key(
        val dim: ResourceKey<Level>,
        val space: SlideSpace,
        val section: Long
    )

    private val anchors = HashMap<Key, MutableSet<BlockPos>>()

    private fun keyOf(level: Level, space: SlideSpace, pos: BlockPos): Key =
        Key(
            level.dimension(),
            space,
            SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.x),
                SectionPos.blockToSectionCoord(pos.y),
                SectionPos.blockToSectionCoord(pos.z)
            )
        )

    fun register(level: Level, pos: BlockPos) {
        val space = SlideSpace.ofLevelAndSub(level, pos)
        anchors.getOrPut(keyOf(level, space, pos)) { HashSet() }.add(pos)
    }

    fun unregister(level: Level, pos: BlockPos) {
        val space = SlideSpace.ofLevelAndSub(level, pos)
        val key = keyOf(level, space, pos)
        val set = anchors[key] ?: return
        set.remove(pos)
        if (set.isEmpty()) anchors.remove(key)
    }

    fun all(level: Level): Set<BlockPos> {
        val out = HashSet<BlockPos>()
        for ((key, set) in anchors) {
            if (key.dim == level.dimension()) out.addAll(set)
        }
        return out
    }

    fun all(level: Level, space: SlideSpace): Set<BlockPos> {
        val out = HashSet<BlockPos>()
        for ((key, set) in anchors) {
            if (key.dim == level.dimension() && key.space == space) out.addAll(set)
        }
        return out
    }

    fun allInBounds(level: Level, space: SlideSpace, box: AABB): Set<BlockPos> {
        val out = HashSet<BlockPos>()
        val minX = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(box.minX))
        val minY = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(box.minY))
        val minZ = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(box.minZ))
        val maxX = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(box.maxX))
        val maxY = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(box.maxY))
        val maxZ = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(box.maxZ))
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val section = SectionPos.asLong(x, y, z)
                    val key = Key(level.dimension(), space, section)
                    anchors[key]?.let(out::addAll)
                }
            }
        }
        return out
    }
}
