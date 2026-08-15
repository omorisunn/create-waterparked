package net.omori_sunny.create_waterparked.client.editor

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
        (level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity)?.let {
            return AnchorCtx(null, anchor, it)
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

    fun plotCenter(sub: ClientSubLevel): Vec3 = Vec3.atLowerCornerOf(sub.getPlot().getCenterBlock())

    fun toWorld(sub: ClientSubLevel, plotGlobal: Vec3): Vec3 {
        val local = plotGlobal.subtract(plotCenter(sub))
        val out = sub.logicalPose().transformPosition(JOMLConversion.toJOML(local), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    fun toWorldNormal(sub: ClientSubLevel, normal: Vec3): Vec3 {
        val out = sub.logicalPose().transformNormal(JOMLConversion.toJOML(normal), Vector3d())
        return JOMLConversion.toMojang(out).normalize()
    }

    fun worldToPlot(sub: ClientSubLevel, world: Vec3): Vec3 {
        val local = sub.logicalPose().transformPositionInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(local).add(plotCenter(sub))
    }
}
