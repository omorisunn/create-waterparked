package net.omori_sunny.create_waterparked.client.editor.pliers

import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import com.simibubi.create.content.trains.track.BezierConnection
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

// three setting rings (align x space x plane) combining into the drag constraint
enum class PlierRing(val langKey: String) {
    ALIGN("create_waterparked.pliers.ring.align"),
    SPACE("create_waterparked.pliers.ring.space"),
    PLANE("create_waterparked.pliers.ring.plane");

    fun next(): PlierRing = entries[(ordinal + 1) % entries.size]
}

enum class PlierAlign(val langKey: String) {
    CARTESIAN("create_waterparked.pliers.align.cartesian"),
    POLAR("create_waterparked.pliers.align.polar");

    fun next(): PlierAlign = entries[(ordinal + 1) % entries.size]
}

enum class PlierSpace(val langKey: String) {
    ABSOLUTE("create_waterparked.pliers.space.absolute"),
    RELATIVE("create_waterparked.pliers.space.relative");

    fun next(): PlierSpace = entries[(ordinal + 1) % entries.size]
}

enum class PlierPlane(val langKey: String, val label: String) {
    OXY("create_waterparked.pliers.plane.oxy", "Oxy"),
    OXZ("create_waterparked.pliers.plane.oxz", "Oxz"),
    OYZ("create_waterparked.pliers.plane.oyz", "Oyz");

    fun next(): PlierPlane = entries[(ordinal + 1) % entries.size]

    val missingAxisIndex: Int
        get() = when (this) {
            OXY -> 2
            OXZ -> 1
            OYZ -> 0
        }
}

// local frame at a curve endpoint: x = lateral, y = up, z = tangent
data class TrackFrame(val lateral: Vec3, val up: Vec3, val tangent: Vec3) {
    fun axis(index: Int): Vec3 = when (index) {
        0 -> lateral
        1 -> up
        else -> tangent
    }
}

object BrassPliersModes {

    var align: PlierAlign = PlierAlign.CARTESIAN
    var space: PlierSpace = PlierSpace.ABSOLUTE
    var plane: PlierPlane = PlierPlane.OXY
    var ring: PlierRing = PlierRing.ALIGN

    fun cycleInRing(forward: Boolean) {
        val dir = if (forward) 1 else -1
        when (ring) {
            PlierRing.ALIGN -> align = cycle(align.ordinal, dir, PlierAlign.entries.size) { PlierAlign.entries[it] }
            PlierRing.SPACE -> space = cycle(space.ordinal, dir, PlierSpace.entries.size) { PlierSpace.entries[it] }
            PlierRing.PLANE -> plane = cycle(plane.ordinal, dir, PlierPlane.entries.size) { PlierPlane.entries[it] }
        }
    }

    fun planeNormalPlot(
        level: Level?,
        anchor: BlockPos?,
        frame: TrackFrame?
    ): Vec3 {
        val idx = plane.missingAxisIndex
        val world = when (idx) {
            0 -> Vec3(1.0, 0.0, 0.0)
            1 -> Vec3(0.0, 1.0, 0.0)
            else -> Vec3(0.0, 0.0, 1.0)
        }
        if (space == PlierSpace.RELATIVE) return frame?.axis(idx) ?: world
        if (level == null || anchor == null) return world
        return dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
            .toPlotLocalDirection(level, anchor, world)
    }

    fun planeNormal(frame: TrackFrame?): Vec3 {
        val idx = plane.missingAxisIndex
        return when (space) {
            PlierSpace.ABSOLUTE -> when (idx) {
                0 -> Vec3(1.0, 0.0, 0.0)
                1 -> Vec3(0.0, 1.0, 0.0)
                else -> Vec3(0.0, 0.0, 1.0)
            }
            PlierSpace.RELATIVE -> frame?.axis(idx) ?: when (idx) {
                0 -> Vec3(1.0, 0.0, 0.0)
                1 -> Vec3(0.0, 1.0, 0.0)
                else -> Vec3(0.0, 0.0, 1.0)
            }
        }
    }

    fun breadcrumb(): String =
        "${align.langKey}|${space.langKey}|${plane.langKey}"

    private fun <T> cycle(index: Int, dir: Int, n: Int, get: (Int) -> T): T =
        get(((index + dir) % n + n) % n)
}
