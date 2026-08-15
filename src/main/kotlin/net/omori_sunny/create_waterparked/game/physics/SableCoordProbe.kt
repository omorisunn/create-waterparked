package net.omori_sunny.create_waterparked.game.physics

import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.minecraft.server.level.ServerLevel

object SableCoordProbe {
    fun dump(level: ServerLevel) {
        if (level.gameTime % 200 != 0L) return
        val container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
            ?: return
        for (sub in container.allSubLevels) {
            val serverSub = sub as? ServerSubLevel ?: continue
            val pose = serverSub.logicalPose()
            val plot = serverSub.getPlot()
            CreateWaterparked.LOGGER.info(
                "[SableProbe] sub={} pose={} center={} pos={}",
                serverSub.uniqueId, pose, plot.getCenterBlock(), pose.position()
            )
            for (pos in SlideAnchorIndex.all(level)) {
                val be = plot.getEmbeddedLevelAccessor().getBlockEntity(pos) as? WaterslideAnchorBlockEntity
                if (be != null) {
                    CreateWaterparked.LOGGER.info(
                        "[SableProbe] anchorLocal={} bePos={} beLevel={} plotOffset={}",
                        pos, be.blockPos, be.level === level,
                        be.blockPos.subtract(pos)
                    )
                }
            }
        }
    }
}
