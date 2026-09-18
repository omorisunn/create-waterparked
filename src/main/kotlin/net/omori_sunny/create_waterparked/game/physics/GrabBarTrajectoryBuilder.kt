package net.omori_sunny.create_waterparked.game.physics

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.grab_bar.GrabBarAttachment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// single worker warming the grab bar trajectory build off the server thread
object GrabBarTrajectoryBuilder {

    private const val RUNNING_TIMEOUT_NANOS = 5_000_000_000L

    private val trajectoryExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Create-Waterparked-GrabBarTraj").apply { isDaemon = true }
    }
    private val trajectoryRunning = ConcurrentHashMap<String, Long>()

    private fun keyOf(level: ServerLevel, sabPos: BlockPos): String =
        level.dimension().location().toString() + "|" + sabPos.asLong()

    // the caller prepares the immutable input on the server thread and passes the pure step
    fun request(
        level: ServerLevel,
        sabPos: BlockPos,
        signature: String,
        step: () -> SlideTrajectory?
    ): Boolean {
        val key = keyOf(level, sabPos)
        val started = System.nanoTime()
        if (!acquire(key, started)) return false

        trajectoryExecutor.execute {
            try {
                val trajectory = step()
                val calcMs = (System.nanoTime() - started) / 1_000_000.0
                CreateWaterparked.LOGGER.debug(
                    "[GrabBarPerf] warmUpSamples={} trajMs={}", trajectory?.samples?.size ?: 0, calcMs
                )
                level.server.execute {
                    try {
                        finishBuild(level, sabPos, signature)
                    } catch (e: Exception) {
                        CreateWaterparked.LOGGER.error("Grab bar warm up failed at {}", sabPos, e)
                    } finally {
                        trajectoryRunning.remove(key, started)
                    }
                }
            } catch (e: Exception) {
                CreateWaterparked.LOGGER.error("Grab bar trajectory worker failed at {}", sabPos, e)
                level.server.execute {
                    try {
                        finishBuild(level, sabPos, signature)
                    } finally {
                        trajectoryRunning.remove(key, started)
                    }
                }
            }
        }
        return true
    }

    // a run whose apply task never executes must not reserve the bar forever
    private fun acquire(key: String, started: Long): Boolean {
        while (true) {
            val previous = trajectoryRunning[key]
            if (previous == null) {
                if (trajectoryRunning.putIfAbsent(key, started) == null) return true
                continue
            }
            if (started - previous < RUNNING_TIMEOUT_NANOS) return false
            if (trajectoryRunning.replace(key, previous, started)) return true
        }
    }

    // the off thread result stays here: the synchronous build is the cache of record
    private fun finishBuild(level: ServerLevel, sabPos: BlockPos, signature: String) {
        if (!level.isLoaded(sabPos)) return
        val be = level.getBlockEntity(sabPos) as? SlideAttachmentBlockEntity ?: return
        if (be.isRemoved) return
        val attachment = be.attachment() as? GrabBarAttachment ?: return
        attachment.workerFinished(signature)
    }
}
