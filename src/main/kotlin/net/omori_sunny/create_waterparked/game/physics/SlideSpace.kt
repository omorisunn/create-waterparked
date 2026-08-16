package net.omori_sunny.create_waterparked.game.physics

import dev.ryanhcode.sable.Sable
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import java.util.UUID

sealed interface SlideSpace {
    data object Main : SlideSpace
    data class SubLevel(val id: UUID) : SlideSpace

    fun cacheKey(level: Level): String =
        level.dimension().location().toString() + "|" + when (this) {
            is Main -> "main"
            is SubLevel -> "sub:$id"
        }

    companion object {
        fun of(sub: dev.ryanhcode.sable.sublevel.SubLevel?): SlideSpace =
            if (sub == null) Main else SubLevel(sub.uniqueId)

        fun ofLevelAndSub(level: Level, anchor: BlockPos): SlideSpace =
            of(Sable.HELPER.getContaining(level, anchor))
    }
}
