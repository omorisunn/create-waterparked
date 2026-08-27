package net.omori_sunny.create_waterparked.client.editor

import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.sublevel.ClientSubLevel
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

// resolves editor anchors inside a Sable sub level through the plot center
object SableClientEdit {

    data class AnchorCtx(
        val sub: ClientSubLevel?,
        val globalPos: BlockPos,
        val be: WaterslideAnchorBlockEntity
    )

    fun resolve(level: Level, anchor: BlockPos): AnchorCtx? {
        // plot global anchors resolve directly, keep the containing sub level
        val direct = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity
        if (direct != null) {
            val sub = Sable.HELPER.getContaining(level, anchor) as? ClientSubLevel
            return AnchorCtx(sub, anchor, direct)
        }
        val container = SubLevelContainer.getContainer(level) ?: return null
        for (raw in container.allSubLevels) {
            val sub = raw as? ClientSubLevel ?: continue
            val global = anchor.offset(sub.getPlot().getCenterBlock())
            val be = level.getBlockEntity(global) as? WaterslideAnchorBlockEntity ?: continue
            return AnchorCtx(sub, global, be)
        }
        return null
    }

    // logical pose maps plot global coords straight into world space
    fun toWorld(sub: ClientSubLevel, plotGlobal: Vec3): Vec3 {
        val out = sub.logicalPose().transformPosition(JOMLConversion.toJOML(plotGlobal), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    fun toWorldNormal(sub: ClientSubLevel, normal: Vec3): Vec3 {
        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(normal), Vector3d())
        return JOMLConversion.toMojang(out).normalize()
    }

    fun worldToPlot(sub: ClientSubLevel, world: Vec3): Vec3 {
        val out = sub.logicalPose().transformPositionInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out)
    }
}
