package net.omori_sunny.create_waterparked.game

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.lang.reflect.Method

// CCS neighbor smoothing via its private placement pipeline
object WaterslideNeighborSmoothing {

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
        level: ServerLevel,
        a: BlockPos,
        b: BlockPos,
        primary: BezierConnection,
        result: CoasterAnchorBezierOptimizer.AnchorAnchorBuildResult
    ): Batch? {
        return try {
            val batch = finalizeMethod.invoke(null, level, a, b, primary, result) ?: return null
            val finalPrimary = batch.javaClass.getMethod("primary").invoke(batch) as? BezierConnection
                ?: return null
            val neighbors = batch.javaClass.getMethod("neighbors").invoke(batch) as? List<*>
                ?: return null
            Batch(finalPrimary, neighbors)
        } catch (e: ReflectiveOperationException) {
            CreateWaterparked.LOGGER.error("Waterslide neighbor smoothing failed", e)
            null
        }
    }

// commit the smoothed neighbor curves
    fun commitNeighbors(level: ServerLevel, neighbors: List<*>) {
        try {
            putNeighborsMethod.invoke(null, level, neighbors)
        } catch (e: ReflectiveOperationException) {
            CreateWaterparked.LOGGER.error("Waterslide neighbor smoothing commit failed", e)
        }
    }
}
