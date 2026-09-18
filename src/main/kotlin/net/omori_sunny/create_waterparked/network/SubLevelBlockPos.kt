package net.omori_sunny.create_waterparked.network

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity

// resolve a plot local pos to a world pos in main or sub level space
internal fun resolveSubLevelPos(level: ServerLevel, pos: BlockPos): BlockPos {
    if (level.getBlockEntity(pos) != null) return pos
    return findSubLevelAnchor(level, pos) ?: pos
}

// the plot offset can point at a distant unloaded chunk, and asking for its
// block entity would generate it and crash, so only loaded candidates count
internal fun findSubLevelAnchor(level: ServerLevel, pos: BlockPos): BlockPos? {
    val subLevels = SubLevelContainer.getContainer(level)?.allSubLevels ?: return null
    for (raw in subLevels) {
        val sub = raw as? ServerSubLevel ?: continue
        val candidate = pos.offset(sub.getPlot().getCenterBlock())
        if (!level.hasChunkAt(candidate)) continue
        if (level.getBlockEntity(candidate) is WaterslideAnchorBlockEntity) return candidate
    }
    return null
}
