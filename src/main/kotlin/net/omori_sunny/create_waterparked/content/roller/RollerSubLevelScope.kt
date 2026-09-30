package net.omori_sunny.create_waterparked.content.roller

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer
import dev.ryanhcode.sable.companion.math.Pose3d
import net.minecraft.core.BlockPos
import java.util.UUID

// marks the assembly window in which a roller run may claim a sub-level of its own
object RollerSubLevelScope {

    private var depth = 0

    @JvmStatic
    fun active(): Boolean = depth > 0

    // builds the run's sub-level with the identity and the biome the stock container would have given it
    @JvmStatic
    fun create(container: ServerSubLevelContainer, plotX: Int, plotZ: Int, pose: Pose3d, uuid: UUID): RollerSubLevel {
        val level = container.level
        val sub = RollerSubLevel(level, plotX, plotZ, pose)
        sub.setUniqueId(uuid)
        val position = pose.position()
        val blockPos = BlockPos.containing(position.x, position.y, position.z)
        if (level.isLoaded(blockPos)) {
            val key = level.getBiome(blockPos).unwrapKey()
            if (key.isPresent) sub.plot.setBiome(key.get())
        }
        return sub
    }

    @JvmStatic
    fun <T> around(block: () -> T): T {
        depth++
        return try {
            block()
        } finally {
            depth--
        }
    }
}
