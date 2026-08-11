package net.omori_sunny.create_waterparked.game.water

import com.simibubi.create.content.trains.track.BezierConnection
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.entrance.WaterslideEntranceBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

// Watered-state propagation and drain.
object SlideWaterManager {

    private val waterAnchors = mutableMapOf<ResourceKey<Level>, MutableSet<BlockPos>>()
    private val dirtyLevels = mutableSetOf<ResourceKey<Level>>()
    private val lastPeriodicTick = mutableMapOf<ResourceKey<Level>, Long>()
    private val lastWateredCount = mutableMapOf<ResourceKey<Level>, Int>()

    @JvmStatic
    fun tickServer(level: ServerLevel, be: WaterslideAnchorBlockEntity) {
        val dim = level.dimension()
        val set = waterAnchors.getOrPut(dim) { mutableSetOf() }
        val had = be.blockPos in set
        if (hasAdjacentWetEntrance(level, be.blockPos)) be.refillWater()
        val has = be.hasWater()
        if (has && !had) {
            set += be.blockPos
            dirtyLevels += dim
            CreateWaterparked.LOGGER.info(
                "Anchor {} has water amount {}", be.blockPos, be.waterAmount()
            )
        } else if (!has && had) {
            set -= be.blockPos
            dirtyLevels += dim
        }

        if (dirtyLevels.remove(dim)) {
            recomputeAll(level)
        }
        val last = lastPeriodicTick[dim] ?: Long.MIN_VALUE
        if (level.gameTime - last >= 100) {
            lastPeriodicTick[dim] = level.gameTime
            recomputeAll(level)
        }

        if (!has || be.wateredCurves.isEmpty()) {
            be.resetDrainAccum()
            return
        }

        val rate = ModConfig.waterDrainRateMbPerSecond()
        be.addDrainAccum(rate / 20.0)
        val want = be.waterDrainAccum().toInt()
        if (want <= 0) return
        val drained = be.drainWater(want)
        be.addDrainAccum(-drained.toDouble())
        if (drained < want) be.resetDrainAccum()
    }

    private fun recomputeAll(level: ServerLevel) {
        val set = waterAnchors[level.dimension()] ?: return
        val iter = set.iterator()
        while (iter.hasNext()) {
            if (level.getBlockEntity(iter.next()) !is WaterslideAnchorBlockEntity) iter.remove()
        }
        if (set.isEmpty()) return
        val wateredEdges = mutableSetOf<Pair<Long, Long>>()
        val involved = mutableSetOf<BlockPos>()

        for (anchorPos in set) {
            val be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity ?: continue
            if (!be.hasWater()) continue
            val visited = mutableSetOf<BlockPos>()
            val queue = ArrayDeque<BlockPos>()
            queue.add(anchorPos)
            visited.add(anchorPos)

            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val curBe = level.getBlockEntity(cur) as? WaterslideAnchorBlockEntity ?: continue
                for (raw in curBe.anchorPeerCurvesView.values) {
                    val bc = if (raw.isPrimary) raw else raw.secondary()
                    if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                    if (!isBelow(level, bc, cur)) continue
                    val a = bc.bePositions.getFirst()
                    val b = bc.bePositions.getSecond()
                    val key = if (a.asLong() <= b.asLong()) a.asLong() to b.asLong()
                    else b.asLong() to a.asLong()
                    wateredEdges += key
                    involved += a
                    involved += b
                    val far = if (a == cur) b else a
                    if (visited.add(far)) queue.add(far)
                }
            }
        }

        val dimKey = level.dimension()
        val prevCount = lastWateredCount[dimKey] ?: -1
        if (wateredEdges.size != prevCount) {
            lastWateredCount[dimKey] = wateredEdges.size
            CreateWaterparked.LOGGER.info(
                "Water recompute: {} curves from anchors {} in {}",
                wateredEdges.size, set, level.dimension()
            )
            if (wateredEdges.isEmpty() && set.isNotEmpty()) {
                for (anchorPos in set) {
                    val be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity ?: continue
                    CreateWaterparked.LOGGER.info(
                        "Water anchor {} curves={}",
                        anchorPos, be.anchorPeerCurvesView.size
                    )
                }
            }
        }

        for (pos in involved) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            for (peer in be.anchorPeerCurvesView.keys) {
                val bc = be.anchorPeerCurvesView[peer] ?: continue
                val p = if (bc.isPrimary) bc else bc.secondary()
                val a = p.bePositions.getFirst()
                val b = p.bePositions.getSecond()
                val key = if (a.asLong() <= b.asLong()) a.asLong() to b.asLong()
                else b.asLong() to a.asLong()
                be.setCurveWatered(peer, key in wateredEdges)
            }
        }
    }

    private fun isBelow(level: ServerLevel, bc: BezierConnection, anchorPos: BlockPos): Boolean {
        val anchorY = anchorPos.y
        val r0 = SlideCurveGeometry.radiusAt(level, bc.bePositions.getFirst())
        val r1 = SlideCurveGeometry.radiusAt(level, bc.bePositions.getSecond())
        val frames = SlideCurveGeometry.sampleFrames(level, bc, r0, r1)
        if (frames.isEmpty()) return false
        val far = if (bc.bePositions.getFirst() == anchorPos)
            bc.bePositions.getSecond()
        else bc.bePositions.getFirst()
        return SlideCurveGeometry.averageCenterY(frames) <= anchorY || far.y <= anchorY
    }

    private fun hasAdjacentWetEntrance(level: ServerLevel, pos: BlockPos): Boolean {
        for (dir in Direction.entries) {
            val state = level.getBlockState(pos.relative(dir))
            if (state.block is WaterslideEntranceBlock &&
                state.getValue(WaterslideEntranceBlock.WATER_ACTIVE)
            ) {
                return true
            }
        }
        return false
    }
}
