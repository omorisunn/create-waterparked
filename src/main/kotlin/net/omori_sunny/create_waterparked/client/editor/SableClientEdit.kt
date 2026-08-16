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

// Resolves editor anchors that live inside a Sable sub-level. Sub-level block
// entities are stored at plot-global positions while the editor UI receives
// local content positions, so every lookup converts through the plot center.
object SableClientEdit {

    data class AnchorCtx(
        val sub: ClientSubLevel?,
        val globalPos: BlockPos,
        val be: WaterslideAnchorBlockEntity
    )

    fun resolve(level: Level, anchor: BlockPos): AnchorCtx? {
        // Plot-global positions resolve directly against the parent level, but
        // still have to keep their containing sub-level so render/hit code can
        // transform the plot geometry into world space.
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

    // Sable's logical pose already maps the parent level's plot-global block
    // coordinates straight into world space; there is no extra plot-center
    // offset to apply.
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
