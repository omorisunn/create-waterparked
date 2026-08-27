package net.omori_sunny.create_waterparked.network

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

// resolve a plot local pos to a world pos in main or sub level space
internal fun resolveSubLevelPos(level: ServerLevel, pos: BlockPos): BlockPos {
    if (level.getBlockEntity(pos) != null) return pos
    val subLevels = SubLevelContainer.getContainer(level)?.allSubLevels ?: return pos
    for (raw in subLevels) {
        val sub = raw as? ServerSubLevel ?: continue
        val candidate = pos.offset(sub.getPlot().getCenterBlock())
        if (level.getBlockEntity(candidate) != null) return candidate
    }
    return pos
}
