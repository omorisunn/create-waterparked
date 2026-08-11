package net.omori_sunny.create_waterparked.game

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Method

// CCS neighbor smoothing via its private placement pipeline
object WaterslideNeighborSmoothing {

    private var lastErrorLog = 0L

    data class Batch(
        val primary: BezierConnection,
        val neighbors: List<*>
    )

    private val finalizeMethod: Method by lazy {
        CoasterTrackPlacement::class.java.getDeclaredMethod(
            "finalizePlacementSmoothingBatch",
            Level::class.java,
            BlockPos::class.java,
            BlockPos::class.java,
            BezierConnection::class.java,
            CoasterAnchorBezierOptimizer.AnchorAnchorBuildResult::class.java
        ).also { it.isAccessible = true }
    }

    private val putNeighborsMethod: Method by lazy {
        CoasterTrackPlacement::class.java.getDeclaredMethod(
            "putPlacementNeighborCurves",
            ServerLevel::class.java,
            List::class.java
        ).also { it.isAccessible = true }
    }

// build the smoothing batch
    fun build(
        level: Level,
        a: BlockPos,
        b: BlockPos,
        primary: BezierConnection,
        result: CoasterAnchorBezierOptimizer.AnchorAnchorBuildResult
    ): Batch? {
        return try {
            val batch = finalizeMethod.invoke(null, level, a, b, primary, result)
            var finalPrimary = primary
            var neighbors: List<*> = emptyList<Any>()
            if (batch != null) {
                val primaryMethod = batch.javaClass.getMethod("primary").also { it.isAccessible = true }
                val neighborsMethod = batch.javaClass.getMethod("neighbors").also { it.isAccessible = true }
                finalPrimary = primaryMethod.invoke(batch) as? BezierConnection ?: primary
                neighbors = (neighborsMethod.invoke(batch) as? List<*>) ?: emptyList<Any>()
            }
// force exact opposite handles at every shared anchor
            val forcedNeighbors = neighbors.mapNotNull { n ->
                var curve = n as? BezierConnection ?: return@mapNotNull null
                for (w in result.neighborJoinWrites) {
                    val shared = w.sharedAnchor()
                    if (curve.bePositions.getFirst() != shared &&
                        curve.bePositions.getSecond() != shared
                    ) continue
                    val axis = w.axisIntoCurveAtSharedAnchor().normalize()
                    curve = CoasterTrackPlacement.curveWithAxisAtEndpointPreservingPeerForJoin(
                        level, curve, shared, axis
                    )
                    finalPrimary = CoasterTrackPlacement.curveWithAxisAtEndpointForEdit(
                        level, finalPrimary, shared, axis.scale(-1.0)
                    )
                }
                curve
            }
            val finalCurve = if (neighbors.isEmpty()) {
                alignWithExisting(level, a, b, finalPrimary)
            } else {
                finalPrimary
            }
            Batch(finalCurve, forcedNeighbors)
        } catch (e: ReflectiveOperationException) {
            logError("Waterslide neighbor smoothing failed", e)
            null
        }
    }

    // align with neighbors CCS did not discover for custom materials
    private fun alignWithExisting(
        level: Level,
        a: BlockPos,
        b: BlockPos,
        curve: BezierConnection
    ): BezierConnection {
        var out = curve
        for (anchor in listOf(a, b)) {
            val existing = existingCurvesAt(level, anchor).firstOrNull() ?: continue
            val axis = axisAt(existing, anchor)?.normalize() ?: continue
            if (axis.lengthSqr() < 1.0E-12) continue
            CreateWaterparked.LOGGER.debug("Waterslide fallback align at {}", anchor)
            out = CoasterTrackPlacement.curveWithAxisAtEndpointForEdit(
                level, out, anchor, axis.scale(-1.0)
            )
        }
        return out
    }

    private fun existingCurvesAt(level: Level, anchor: BlockPos): Sequence<BezierConnection> {
        val be = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return emptySequence()
        return be.anchorPeerCurvesView.values.asSequence().mapNotNull { raw ->
            if (raw.isPrimary) raw else raw.secondary()
        }
    }

    private fun axisAt(bc: BezierConnection, anchor: BlockPos): Vec3? = when {
        bc.bePositions.getFirst() == anchor -> bc.axes.getFirst()
        bc.bePositions.getSecond() == anchor -> bc.axes.getSecond()
        else -> null
    }

// commit the smoothed neighbor curves
    fun commitNeighbors(level: ServerLevel, neighbors: List<*>) {
        try {
            putNeighborsMethod.invoke(null, level, neighbors)
        } catch (e: ReflectiveOperationException) {
            logError("Waterslide neighbor smoothing commit failed", e)
        }
    }

    private fun logError(message: String, e: ReflectiveOperationException) {
        val now = System.currentTimeMillis()
        if (now - lastErrorLog < 5000) return
        lastErrorLog = now
        CreateWaterparked.LOGGER.error(message, e)
    }
}
