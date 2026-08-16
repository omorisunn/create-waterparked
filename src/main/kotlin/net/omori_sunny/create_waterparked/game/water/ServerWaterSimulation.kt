package net.omori_sunny.create_waterparked.game.water

import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.companion.math.Pose3d
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.physics.MainSlideSpaceAccess
import net.omori_sunny.create_waterparked.game.physics.SlideSpace
import net.omori_sunny.create_waterparked.game.physics.SlideSpaceAccess
import net.omori_sunny.create_waterparked.game.physics.SubSlideSpaceAccess
import net.omori_sunny.create_waterparked.network.WaterslideDebugTrajectoryPayload
import net.omori_sunny.create_waterparked.network.WaterslideWaterSyncPayload
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import org.joml.Vector3d
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Server-side water shape from point-mass trajectories.
object ServerWaterSimulation {

    // one watered sub-segment of a curve
    data class WaterSegment(
        val arc: Float,
        val speed: Float
    )

    data class ExitInfo(val pos: Vec3, val vel: Vec3)

    data class CurveField(val segments: List<WaterSegment>, val exit: ExitInfo?)

    private data class TubeSeg(
        val a: Vec3,
        val b: Vec3,
        val rOut: Float,
        val rIn: Float,
        val curveKey: Pair<Long, Long>,
        val arcStart: Float,
        val len: Float,
        val lat: Vec3,
        val up: Vec3
    )

    private data class LegStart(
        val center: Vec3,
        val tangent: Vec3,
        val lateral: Vec3,
        val up: Vec3,
        val rIn: Float,
        val curveKey: Pair<Long, Long>,
        val towardSecond: Boolean,
        val launchSpeed: Double,
        val crossSpace: Boolean = false
    )

    private class SegmentAcc {
        var velSum = Vec3.ZERO
        var axisSum = Vec3.ZERO
        var count = 0

        fun add(vel: Vec3, axis: Vec3) {
            velSum = velSum.add(vel)
            axisSum = axisSum.add(axis)
            count++
        }

        // average particle velocity projected onto the segment's average axis;
        // positive = along the curve forward direction (downstream)
        fun signedSpeed(): Float {
            if (count == 0) return 0f
            val avgAxis = axisSum.normalize()
            if (avgAxis.lengthSqr() < 1.0E-12) return 0f
            return velSum.scale(1.0 / count).dot(avgAxis).toFloat()
        }
    }

    private class SegGrid {
        private val buckets = HashMap<Long, MutableList<TubeSeg>>()

        fun add(seg: TubeSeg) {
            val r = seg.rOut.toDouble() + 0.5
            val minX = Mth.floor((min(seg.a.x, seg.b.x) - r) / GRID)
            val maxX = Mth.floor((max(seg.a.x, seg.b.x) + r) / GRID)
            val minY = Mth.floor((min(seg.a.y, seg.b.y) - r) / GRID)
            val maxY = Mth.floor((max(seg.a.y, seg.b.y) + r) / GRID)
            val minZ = Mth.floor((min(seg.a.z, seg.b.z) - r) / GRID)
            val maxZ = Mth.floor((max(seg.a.z, seg.b.z) + r) / GRID)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        buckets.getOrPut(key(x, y, z)) { ArrayList() } += seg
                    }
                }
            }
        }

        fun nearest(p: Vec3): TubeSeg? {
            val cx = Mth.floor(p.x / GRID)
            val cy = Mth.floor(p.y / GRID)
            val cz = Mth.floor(p.z / GRID)
            var best: TubeSeg? = null
            var bestD = Double.MAX_VALUE
            for (x in cx - 1..cx + 1) {
                for (y in cy - 1..cy + 1) {
                    for (z in cz - 1..cz + 1) {
                        val list = buckets[key(x, y, z)] ?: continue
                        for (s in list) {
                            val ab = s.b.subtract(s.a)
                            val lenSq = ab.lengthSqr()
                            if (lenSq < 1.0E-12) continue
                            val t = ((p.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                            val closest = s.a.add(ab.scale(t))
                            val d = p.distanceToSqr(closest)
                            if (d < bestD) {
                                bestD = d
                                best = s
                            }
                        }
                    }
                }
            }
            return best
        }

        companion object {
            const val GRID = 8.0

            fun key(x: Int, y: Int, z: Int): Long =
                ((x.toLong() and 0x1FFFFF) shl 42) or
                    ((y.toLong() and 0x1FFFFF) shl 21) or
                    (z.toLong() and 0x1FFFFF)
        }
    }


    private val fields = HashMap<String, Map<Pair<Long, Long>, CurveField>>()
    private val dirty = mutableSetOf<String>()
    private val lastCalc = mutableMapOf<String, Long>()
    private val lastCalcAll = mutableMapOf<ResourceKey<Level>, Long>()
    private val lastCrossLinkSig = mutableMapOf<ResourceKey<Level>, String>()
    private val pendingCrossSigWhileMoving = mutableMapOf<ResourceKey<Level>, String>()
    private val segCache = HashMap<String, Pair<String, List<TubeSeg>>>()
    private val lastSig = HashMap<String, String>()
    private val lastStableSig = HashMap<String, String>()
    private val lastStableCheckTick = HashMap<String, Long>()
    private val lastSigCheckTick = HashMap<String, Long>()
    private val debugPlayers = mutableSetOf<UUID>()

    private data class PreparedCalc(
        val level: ServerLevel,
        val spaces: List<CalcSpace>,
        val subIdByKey: Map<String, UUID?>,
        val sourcesByAccess: Map<String, List<LegStart>>,
        val segmentsByAccess: Map<String, List<TubeSeg>>,
        val gridsByAccess: Map<String, SegGrid>,
        val accByAccess: MutableMap<String, HashMap<Pair<Long, Long>, HashMap<Int, SegmentAcc>>>,
        val exitsByAccess: MutableMap<String, HashMap<Pair<Long, Long>, ExitInfo>>,
        val worldGrid: WorldGrid,
        val flatSources: List<Pair<CalcSpace, LegStart>>,
        val sigSeed: Long,
        val particleCount: Int,
        val collectDebug: Boolean,
        val maxBlocks: Double,
        val segmentLength: Float,
        val waterFriction: Double
    )

    private data class CalcResult(
        val accByAccess: Map<String, HashMap<Pair<Long, Long>, HashMap<Int, SegmentAcc>>>,
        val exitsByAccess: Map<String, HashMap<Pair<Long, Long>, ExitInfo>>,
        val debugOut: MutableList<MutableList<Vec3>>?,
        val handoffs: Int
    )

    private val waterExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Create-Waterparked-WaterSim").apply { isDaemon = true }
    }
    private val waterCalcRunning = java.util.concurrent.ConcurrentHashMap.newKeySet<ResourceKey<Level>>()

    private fun spaceKey(access: SlideSpaceAccess): String = access.space.cacheKey(access.level)

    // client debug toggle; recomputes once so trajectories get collected
    fun setDebug(player: ServerPlayer, enable: Boolean) {
        if (enable) {
            if (debugPlayers.add(player.uuid)) {
                resync(player.level())
            }
        } else {
            debugPlayers.remove(player.uuid)
        }
    }

    fun markDirty(level: Level) {
        if (!level.isClientSide) dirty += SlideSpace.Main.cacheKey(level)
    }

    // force a recalculation + resend for a joined player
    fun resync(level: Level) {
        if (level.isClientSide) return
        val key = SlideSpace.Main.cacheKey(level)
        dirty += key
        lastSig.remove(key)
    }

    // resend the current field to players who just joined
    fun resendTo(level: ServerLevel) {
        val key = MainSlideSpaceAccess(level).space.cacheKey(level)
        val f = fields[key] ?: return
        val payload = WaterslideWaterSyncPayload(toEntries(f))
        CreateWaterparked.LOGGER.info("Water resend entries={}", payload.entries.size)
        for (player in level.players()) {
            PacketDistributor.sendToPlayer(player, payload)
        }
    }

    private fun subId(access: SlideSpaceAccess): UUID? =
        (access.space as? SlideSpace.SubLevel)?.id

    private fun toEntries(f: Map<Pair<Long, Long>, CurveField>): List<WaterslideWaterSyncPayload.Entry> =
        f.map { (key, field) ->
            WaterslideWaterSyncPayload.Entry(
                key.first, key.second, field.segments.map {
                    WaterslideWaterSyncPayload.Segment(it.arc, it.speed)
                }, field.exit?.pos, field.exit?.vel
            )
        }

    fun field(level: Level, a: BlockPos, b: BlockPos): CurveField? =
        fields[SlideSpace.Main.cacheKey(level)]?.get(edgeKey(a, b))

    fun field(access: SlideSpaceAccess, a: BlockPos, b: BlockPos): CurveField? =
        fields[spaceKey(access)]?.get(edgeKey(a, b))

    // water flow velocity (blocks/s) at a world position, for pushing players.
    // Returns null when the position isn't inside a simulated water tube.
    @JvmStatic
    fun waterVelocityAt(level: ServerLevel, pos: Vec3): Vec3? {
        val access = MainSlideSpaceAccess(level)
        val fieldMap = fields[spaceKey(access)] ?: return null
        if (fieldMap.isEmpty()) return null
        val segs = allSegments(access, structureSignatureCached(access))
        var best: TubeSeg? = null
        var bestD = Double.MAX_VALUE
        var bestT = 0.0
        for (s in segs) {
            val ab = s.b.subtract(s.a)
            val lenSq = ab.lengthSqr()
            if (lenSq < 1.0E-12) continue
            val t = ((pos.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
            val closest = s.a.add(ab.scale(t))
            val d = pos.distanceToSqr(closest)
            if (d < bestD) { bestD = d; best = s; bestT = t }
        }
        val seg = best ?: return null
        if (bestD > (seg.rIn * seg.rIn).toDouble()) return null
        val arc = seg.arcStart + (bestT * seg.len).toFloat()
        val field = fieldMap[seg.curveKey] ?: return null
        if (field.segments.isEmpty()) return null
        val segLen = ModConfig.waterSegmentLength().toFloat()
        val idx = (arc / segLen).toInt().coerceIn(0, field.segments.size - 1)
        val speed = field.segments[idx].speed
        if (kotlin.math.abs(speed) < 0.01f) return Vec3.ZERO
        return seg.b.subtract(seg.a).normalize().scale(speed.toDouble())
    }

    // called every server tick; recalculates all spaces together when needed
    fun tickAll(level: ServerLevel) {
        val accesses = allAccesses(level)
        if (accesses.isEmpty()) return

        // Cheap per-tick query: count main<->sub-level slide mouth pairs in
        // WORLD space. Recalculation only happens when this count changes (or
        // a space has no field yet); there is no periodic automatic refresh.
        // While a physics staff (or any other source) is actively moving a
        // sub-level, the same mouth pair can cross the distance threshold many
        // times per second; deferring until the sub-level settles keeps one
        // drag from queueing dozens of recalculations.
        val crossSig = crossLinkSignature(level)
        val dim = level.dimension()
        // Only defer for moving sub-levels that actually contain a waterslide.
        // Other moving sub-levels cannot make the cross-link signature churn.
        val movingFast = accesses.any { a ->
            val sub = (a as? SubSlideSpaceAccess)?.sub ?: return@any false
            if (SlideAnchorIndex.all(level, a.space).isEmpty()) return@any false
            sub.latestLinearVelocity.lengthSquared() > 4.0
        }
        if (!movingFast) {
            val pending = pendingCrossSigWhileMoving.remove(dim)
            if (pending != null && pending != lastCrossLinkSig[dim]) {
                lastCrossLinkSig[dim] = pending
                for (a in accesses) dirty += spaceKey(a)
                CreateWaterparked.LOGGER.debug("[WaterCross] settled after movement -> recalc")
            }
        }
        if (crossSig != lastCrossLinkSig[dim]) {
            if (movingFast) {
                pendingCrossSigWhileMoving[dim] = crossSig
                CreateWaterparked.LOGGER.debug("[WaterCross] deferred while sub-level is moving")
            } else {
                lastCrossLinkSig[dim] = crossSig
                for (a in accesses) dirty += spaceKey(a)
                CreateWaterparked.LOGGER.debug("[WaterCross] link signature changed -> recalc")
            }
        }

        // Slide graph/water-source edits also need a recalculation. This
        // signature is pose-independent (no logical-pose / exit-field data),
        // so dragging a sub-level cannot dirty it, and it is only rescanned at
        // most every 20 ticks.
        for (a in accesses) {
            val key = spaceKey(a)
            val last = lastStableCheckTick[key] ?: Long.MIN_VALUE
            if (level.gameTime - last < 20) continue
            lastStableCheckTick[key] = level.gameTime
            val sig = try {
                stableStructureSignature(a)
            } catch (e: Exception) {
                CreateWaterparked.LOGGER.error("Stable water sig failed space={}", key, e)
                continue
            }
            val old = lastStableSig[key]
            if (old != null && old != sig) {
                dirty += key
                CreateWaterparked.LOGGER.debug("[WaterCross] slide structure changed -> recalc")
            }
            lastStableSig[key] = sig
        }
        for (a in accesses) {
            if (fields[spaceKey(a)] == null) dirty += spaceKey(a)
        }
        if (accesses.none { dirty.contains(spaceKey(it)) }) return

        if (!waterCalcRunning.add(dim)) return
        val last = lastCalcAll[dim]
        if (last != null && level.gameTime - last < ModConfig.waterSimCooldownTicks()) {
            waterCalcRunning.remove(dim)
            return
        }
        for (a in accesses) dirty.remove(spaceKey(a))
        lastCalcAll[dim] = level.gameTime

        val prepared = try {
            prepareCalc(level, accesses)
        } catch (e: Exception) {
            CreateWaterparked.LOGGER.error("Water prepare failed", e)
            waterCalcRunning.remove(dim)
            return
        }
        if (prepared == null) {
            waterCalcRunning.remove(dim)
            return
        }

        val t0 = System.nanoTime()
        waterExecutor.execute {
            try {
                val result = runCalc(prepared)
                val calcMs = (System.nanoTime() - t0) / 1_000_000.0
                level.server.execute {
                    try {
                        applyCalc(prepared, result)
                    } catch (e: Exception) {
                        CreateWaterparked.LOGGER.error("Water apply failed", e)
                    } finally {
                        waterCalcRunning.remove(dim)
                        CreateWaterparked.LOGGER.debug("[WaterPerf] calcAllMs={}", calcMs)
                    }
                }
            } catch (e: Exception) {
                CreateWaterparked.LOGGER.error("Water worker failed", e)
                level.server.execute { waterCalcRunning.remove(dim) }
            }
        }
    }

    // Server-thread preparation: read block entities/poses, build immutable
    // grids, then hand the heavy particle integration to the water worker.
    private fun prepareCalc(level: ServerLevel, accesses: List<SlideSpaceAccess>): PreparedCalc? {
        val spaces = accesses.map { CalcSpace(it) }
        val sigByAccess = HashMap<String, String>()
        for ((access, calc) in accesses.zip(spaces)) {
            val key = calc.key
            val sig = try {
                structureSignature(access)
            } catch (e: Exception) {
                CreateWaterparked.LOGGER.error("Water sig failed space={}", key, e)
                return null
            }
            lastSig[key] = sig
            sigByAccess[key] = sig
        }

        val sourcesByAccess = HashMap<String, List<LegStart>>()
        val segmentsByAccess = HashMap<String, List<TubeSeg>>()
        val gridsByAccess = HashMap<String, SegGrid>()
        val accByAccess = HashMap<String, HashMap<Pair<Long, Long>, HashMap<Int, SegmentAcc>>>()
        val exitsByAccess = HashMap<String, HashMap<Pair<Long, Long>, ExitInfo>>()
        val worldGrid = WorldGrid()
        val subIdByKey = HashMap<String, UUID?>()
        for ((access, calc) in accesses.zip(spaces)) {
            val key = calc.key
            subIdByKey[key] = calc.subId
            val sig = sigByAccess[key] ?: continue
            sourcesByAccess[key] = collectSources(access)
            val segments = allSegments(access, sig)
            segmentsByAccess[key] = segments
            val grid = SegGrid()
            for (s in segments) grid.add(s)
            gridsByAccess[key] = grid
            accByAccess[key] = HashMap()
            exitsByAccess[key] = HashMap()
            val scale = maxSpaceScale(access)
            for (s in segments) {
                worldGrid.add(
                    WorldTubeSeg(
                        key, calc,
                        access.toWorld(s.a), access.toWorld(s.b),
                        s.rOut.toDouble() * scale
                    )
                )
            }
        }

        val totalSources = sourcesByAccess.values.sumOf { it.size }
        if (totalSources == 0) {
            for (calc in spaces) {
                fields[calc.key] = emptyMap()
                PacketDistributor.sendToPlayersInDimension(
                    level, WaterslideWaterSyncPayload(emptyList(), calc.subId)
                )
            }
            CreateWaterparked.LOGGER.debug("Water sim: no water sources")
            return null
        }

        val collectDebug = debugPlayers.any { uuid ->
            level.server.playerList.getPlayer(uuid)?.level() == level
        }

        val sortedSpaces = spaces.sortedBy { it.key }
        val flatSources = ArrayList<Pair<CalcSpace, LegStart>>()
        for (calc in sortedSpaces) {
            val key = calc.key
            val sorted = sourcesByAccess[key].orEmpty().sortedWith(
                compareBy<LegStart>(
                    { it.center.x }, { it.center.y }, { it.center.z },
                    { it.tangent.x }, { it.tangent.y }, { it.tangent.z },
                    { it.lateral.x }, { it.lateral.y }, { it.lateral.z },
                    { it.rIn }
                )
            )
            for (src in sorted) flatSources += calc to src
        }

        val sigSeed = sortedSpaces.joinToString("|") { it.key }
            .plus("|").plus(flatSources.size).hashCode().toLong()
        val count = ModConfig.waterSimParticleCount()
        return PreparedCalc(
            level, sortedSpaces, subIdByKey, sourcesByAccess, segmentsByAccess,
            gridsByAccess, accByAccess, exitsByAccess, worldGrid, flatSources,
            sigSeed, count, collectDebug,
            ModConfig.waterSimMaxBlocks(), ModConfig.waterSegmentLength(), ModConfig.slideWaterFriction()
        )
    }

    // Worker-thread particle integration. Pure computation: no level, block
    // entity or packet access happens here.
    private fun runCalc(prepared: PreparedCalc): CalcResult {
        val debugOut = if (prepared.collectDebug) ArrayList<MutableList<Vec3>>() else null
        val perLeg = max(1, prepared.particleCount / max(1, prepared.flatSources.size))
        var handoffs = 0
        var idx = 0
        for ((sourceIndex, pair) in prepared.flatSources.withIndex()) {
            val (calc, src) = pair
            for (i in 0 until perLeg) {
                val seed = prepared.sigSeed + sourceIndex * -7046029254386353131L + i * -2960836687051489901L
                val rng = java.util.Random(seed)
                handoffs += integrateCross(
                    calc, src, prepared.gridsByAccess, prepared.accByAccess,
                    prepared.exitsByAccess, prepared.worldGrid, debugOut,
                    rng.nextDouble(), rng.nextDouble(),
                    prepared.maxBlocks, prepared.segmentLength, prepared.waterFriction
                )
                if (++idx >= prepared.particleCount * 2) break
            }
        }

        // Fallback for near-level sub-level curves with too little coverage.
        for (calc in prepared.spaces) {
            if (!calc.isSub) continue
            val key = calc.key
            val acc = prepared.accByAccess[key] ?: continue
            val segments = prepared.segmentsByAccess[key] ?: continue
            val fallbackSegLen = prepared.segmentLength
            for (src in prepared.sourcesByAccess[key].orEmpty()) {
                if (src.crossSpace) continue
                if ((acc[src.curveKey]?.size ?: 0) >= 3) continue
                val segMap = acc.getOrPut(src.curveKey) { HashMap() }
                var filled = 0
                for (s in segments) {
                    if (s.curveKey != src.curveKey) continue
                    val axis = s.b.subtract(s.a).normalize()
                    if (axis.lengthSqr() < 1.0E-12) continue
                    val segIdx = ((s.arcStart + s.len * 0.5f) / fallbackSegLen).toInt()
                    if (segMap.containsKey(segIdx)) continue
                    val flowDir = if (src.towardSecond) axis else axis.scale(-1.0)
                    segMap[segIdx] = SegmentAcc().also {
                        it.add(flowDir.scale(src.launchSpeed), axis)
                    }
                    filled++
                }
                if (filled > 0) {
                    CreateWaterparked.LOGGER.debug(
                        "[WaterFallback] curve={},{} filled={} launchSpeed={}",
                        src.curveKey.first, src.curveKey.second, filled, src.launchSpeed
                    )
                }
            }
        }

        CreateWaterparked.LOGGER.debug("[WaterCross] trajectory handoffs={}", handoffs)
        return CalcResult(prepared.accByAccess, prepared.exitsByAccess, debugOut, handoffs)
    }

    // Server-thread application: publish fields and send packets.
    private fun applyCalc(prepared: PreparedCalc, result: CalcResult) {
        val level = prepared.level
        val segLen = ModConfig.waterSegmentLength().toFloat()
        for (calc in prepared.spaces) {
            val key = calc.key
            val acc = result.accByAccess[key] ?: continue
            val exits = result.exitsByAccess[key] ?: continue
            val out = HashMap<Pair<Long, Long>, CurveField>()
            for ((edge, segMap) in acc) {
                val list = segMap.entries.sortedBy { it.key }.map { (segIdx, a) ->
                    WaterSegment(
                        (segIdx * segLen + segLen / 2f),
                        a.signedSpeed()
                    )
                }
                out[edge] = CurveField(list, exits[edge])
            }
            fields[key] = out
            for ((edge, field) in out) {
                if (field.exit != null) {
                    CreateWaterparked.LOGGER.debug(
                        "[WaterExit] space={} edge=({},{}) pos={} vel={}",
                        key, edge.first, edge.second, field.exit.pos, field.exit.vel
                    )
                }
            }
            val entries = out.map { (edge, field) ->
                WaterslideWaterSyncPayload.Entry(
                    edge.first, edge.second, field.segments.map {
                        WaterslideWaterSyncPayload.Segment(it.arc, it.speed)
                    }, field.exit?.pos, field.exit?.vel
                )
            }
            CreateWaterparked.LOGGER.debug(
                "Water sim done space={} curves={} segments={}", key, entries.size,
                entries.sumOf { it.segments.size }
            )
            try {
                PacketDistributor.sendToPlayersInDimension(
                    level, WaterslideWaterSyncPayload(entries, prepared.subIdByKey[key])
                )
            } catch (e: Exception) {
                CreateWaterparked.LOGGER.error("Water payload send failed space={}", key, e)
            }
        }

        val debugOut = result.debugOut ?: return
        val debugPayload = WaterslideDebugTrajectoryPayload(debugOut)
        for (uuid in debugPlayers.toList()) {
            val p = level.server.playerList.getPlayer(uuid) ?: continue
            if (p.level() == level) {
                PacketDistributor.sendToPlayer(p, debugPayload)
            }
        }
    }

    private fun allAccesses(level: ServerLevel): List<SlideSpaceAccess> {
        val out = ArrayList<SlideSpaceAccess>()
        out += MainSlideSpaceAccess(level)
        SubLevelContainer.getContainer(level)?.allSubLevels?.forEach { raw ->
            val sub = raw as? ServerSubLevel ?: return@forEach
            out += SubSlideSpaceAccess(level, sub)
        }
        return out
    }

    private fun maxSpaceScale(access: SlideSpaceAccess): Double {
        val sub = (access as? SubSlideSpaceAccess)?.sub ?: return 1.0
        val s = sub.logicalPose().scale()
        return maxOf(s.x(), s.y(), s.z()).coerceAtLeast(0.1).toDouble()
    }

    // Immutable snapshot of a slide space. Created on the server thread before
    // a calculation is handed to the water worker; it deep-copies the Sable
    // logical pose so the worker never touches live level/sub-level data.
    private class CalcSpace(access: SlideSpaceAccess) {
        val key: String = spaceKey(access)
        val subId: UUID? = (access.space as? SlideSpace.SubLevel)?.id
        val isSub: Boolean = access is SubSlideSpaceAccess
        val minBuildHeight: Int = access.level.minBuildHeight
        val gravity: Vec3 = access.localGravity()
        private val pose: Pose3d? = (access as? SubSlideSpaceAccess)?.let { Pose3d(it.sub.logicalPose()) }

        fun toWorld(local: Vec3): Vec3 {
            val p = pose ?: return local
            return JOMLConversion.toMojang(
                p.transformPosition(JOMLConversion.toJOML(local), Vector3d())
            )
        }

        fun toWorldNormal(local: Vec3): Vec3 {
            val p = pose ?: return local.normalize()
            return JOMLConversion.toMojang(
                p.transformNormal(JOMLConversion.toJOML(local), Vector3d())
            ).normalize()
        }

        fun worldToLocal(world: Vec3): Vec3 {
            val p = pose ?: return world
            return JOMLConversion.toMojang(
                p.transformPositionInverse(JOMLConversion.toJOML(world), Vector3d())
            )
        }

        fun worldNormalToLocal(world: Vec3): Vec3 {
            val p = pose ?: return world
            return JOMLConversion.toMojang(
                p.transformNormalInverse(JOMLConversion.toJOML(world), Vector3d())
            )
        }
    }

    private data class WorldTubeSeg(
        val accessKey: String,
        val access: CalcSpace,
        val a: Vec3,
        val b: Vec3,
        val radius: Double
    )

    private class WorldGrid {
        private val buckets = HashMap<Long, MutableList<WorldTubeSeg>>()

        fun add(seg: WorldTubeSeg) {
            val r = seg.radius + 0.5
            val minX = Mth.floor((min(seg.a.x, seg.b.x) - r) / 8.0)
            val maxX = Mth.floor((max(seg.a.x, seg.b.x) + r) / 8.0)
            val minY = Mth.floor((min(seg.a.y, seg.b.y) - r) / 8.0)
            val maxY = Mth.floor((max(seg.a.y, seg.b.y) + r) / 8.0)
            val minZ = Mth.floor((min(seg.a.z, seg.b.z) - r) / 8.0)
            val maxZ = Mth.floor((max(seg.a.z, seg.b.z) + r) / 8.0)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        buckets.getOrPut(key(x, y, z)) { ArrayList() } += seg
                    }
                }
            }
        }

        fun nearest(p: Vec3): WorldTubeSeg? {
            val cx = Mth.floor(p.x / 8.0)
            val cy = Mth.floor(p.y / 8.0)
            val cz = Mth.floor(p.z / 8.0)
            var best: WorldTubeSeg? = null
            var bestD = Double.MAX_VALUE
            for (x in cx - 1..cx + 1) {
                for (y in cy - 1..cy + 1) {
                    for (z in cz - 1..cz + 1) {
                        val list = buckets[key(x, y, z)] ?: continue
                        for (s in list) {
                            val ab = s.b.subtract(s.a)
                            val lenSq = ab.lengthSqr()
                            if (lenSq < 1.0E-12) continue
                            val t = ((p.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                            val closest = s.a.add(ab.scale(t))
                            val d = p.distanceToSqr(closest)
                            if (d < bestD) {
                                bestD = d
                                best = s
                            }
                        }
                    }
                }
            }
            return best
        }

        companion object {
            fun key(x: Int, y: Int, z: Int): Long =
                ((x.toLong() and 0x1FFFFF) shl 42) or
                    ((y.toLong() and 0x1FFFFF) shl 21) or
                    (z.toLong() and 0x1FFFFF)
        }
    }

    // Cross-space mouth topology used by the per-tick invalidation query.
    private data class MouthPoint(val space: String, val pos: Long, val x: Double, val y: Double, val z: Double)

    private fun crossLinkSignature(level: ServerLevel): String {
        val points = ArrayList<MouthPoint>(8)
        for (access in allAccesses(level)) {
            val key = spaceKey(access)
            for (pos in SlideAnchorIndex.all(level, access.space)) {
                val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
                for (raw in be.anchorPeerCurvesView.values) {
                    val bc = if (raw.isPrimary) raw else raw.secondary()
                    if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                    val a = bc.bePositions.getFirst()
                    val b = bc.bePositions.getSecond()
                    val wa = access.toWorld(Vec3.atCenterOf(a))
                    val wb = access.toWorld(Vec3.atCenterOf(b))
                    points += MouthPoint(key, a.asLong(), wa.x, wa.y, wa.z)
                    points += MouthPoint(key, b.asLong(), wb.x, wb.y, wb.z)
                }
            }
        }
        val links = StringBuilder()
        var count = 0
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val pa = points[i]
                val pb = points[j]
                if (pa.space == pb.space) continue
                if (pa.pos == pb.pos) continue
                val dx = pa.x - pb.x
                val dy = pa.y - pb.y
                val dz = pa.z - pb.z
                if (dx * dx + dy * dy + dz * dz <= 64.0) {
                    count++
                    val ka = if (pa.space < pb.space) pa.space else pb.space
                    val kb = if (pa.space < pb.space) pb.space else pa.space
                    val na = min(pa.pos, pb.pos)
                    val nb = max(pa.pos, pb.pos)
                    links.append(ka).append('~').append(kb).append(',').append(na).append('-').append(nb).append(';')
                }
            }
        }
        return "$count|$links"
    }

    private fun collectSources(access: SlideSpaceAccess): List<LegStart> {
        val level = access.level
        val out = ArrayList<LegStart>()
        for (pos in SlideAnchorIndex.all(level, access.space)) {
            val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            if (!be.hasWater()) continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(access, a)
                val r1 = SlideCurveGeometry.radiusAt(access, b)
                val frames = SlideCurveGeometry.sampleFrames(access, bc, r0, r1, 0.5, includeExtensions = false)
                if (frames.size < 2) continue
                val atFirst = a == pos
                val f = if (atFirst) frames.first() else frames.last()
                val tangent = if (atFirst) f.tangent else f.tangent.scale(-1.0)
                val lateral = if (atFirst) f.lateral else f.lateral.scale(-1.0)
                // Give the source enough speed to climb the curve's total rise.
                // A nearly level (or gently rising) sub-level slide used to
                // stall the 0.5 blocks/s particles at the first wall contact,
                // leaving only 1-2 water segments and no in-tube water.
                // Main-world slides keep the original 0.5 blocks/s behavior
                // exactly - do not change their field.
                val exitFrame = if (atFirst) frames.last() else frames.first()
                val rise = (exitFrame.center.y - f.center.y).coerceAtLeast(0.0)
                val gravity = access.localGravity().length().coerceAtLeast(1.0)
                val launchSpeed = if (access is SubSlideSpaceAccess)
                    max(2.5, sqrt(2.0 * gravity * rise + 0.25))
                else 0.5
                out += LegStart(
                    f.center, tangent.normalize(), lateral.normalize(), f.up,
                    (f.radius - 0.15f).coerceAtLeast(0.2f),
                    edgeKey(a, b), atFirst, launchSpeed
                )
            }
        }
        return out
    }

    // A curve only throws across spaces from a true open end (single-leg
    // anchor). Interior junctions must not spawn cross-space water through
    // their seam, mirroring the client's stream rendering rule.
    private fun isOpenEndThrow(access: SlideSpaceAccess, edge: Pair<Long, Long>): Boolean {
        val a = BlockPos.of(edge.first)
        val b = BlockPos.of(edge.second)
        val legsA = (access.getBlockEntity(a) as? WaterslideAnchorBlockEntity)?.legCount() ?: 0
        val legsB = (access.getBlockEntity(b) as? WaterslideAnchorBlockEntity)?.legCount() ?: 0
        return legsA <= 1 || legsB <= 1
    }

    private fun segContains(seg: TubeSeg, p: Vec3, extra: Double): Boolean {
        val ab = seg.b.subtract(seg.a)
        val lenSq = ab.lengthSqr()
        if (lenSq < 1.0E-12) return p.distanceToSqr(seg.a) <= (seg.rOut + extra) * (seg.rOut + extra)
        val t = ((p.subtract(seg.a)).dot(ab) / lenSq)
        if (t < 0.0 || t > 1.0) return false
        val axis = seg.a.add(ab.scale(t))
        return p.distanceToSqr(axis) <= (seg.rOut.toDouble() + extra) * (seg.rOut.toDouble() + extra)
    }

    private fun integrateCross(
        startAccess: CalcSpace,
        src: LegStart,
        grids: Map<String, SegGrid>,
        accByAccess: Map<String, HashMap<Pair<Long, Long>, HashMap<Int, SegmentAcc>>>,
        exitsByAccess: Map<String, HashMap<Pair<Long, Long>, ExitInfo>>,
        worldGrid: WorldGrid,
        debugOut: MutableList<MutableList<Vec3>>?,
        u: Double,
        ang: Double,
        maxBlocks: Double,
        segLen: Float,
        waterFriction: Double
    ): Int {
        val dt = 0.03
        val maxSteps = (maxBlocks / 0.2).toInt()
        var access = startAccess
        var key = access.key
        var gravityStep = access.gravity.scale(dt)
        var outsideLimit = if (access.isSub) 3 else 1
        val freeFlightLimit = 160
        // uniform disc sample on the anchor opening (deterministic per particle)
        val rr = kotlin.math.sqrt(u) * src.rIn
        var pos = src.center
            .add(src.lateral.scale(cos(ang) * rr))
            .add(src.up.scale(sin(ang) * rr))
        var vel = src.tangent.scale(src.launchSpeed)
        var lastKey: Pair<Long, Long>? = null
        var lastOwner: CalcSpace? = null
        var lastInsideWorld: Vec3? = null
        var lastExitVelWorld: Vec3? = null
        var outside = 0
        var handoffs = 0
        val poly = debugOut?.let { ArrayList<Vec3>().also { l -> debugOut.add(l) } }
        poly?.add(access.toWorld(pos))
        for (step in 0 until maxSteps) {
            gravityStep = access.gravity.scale(dt)
            vel = vel.add(gravityStep)
            var newPos = pos.add(vel.scale(dt))
            var localSeg = grids[key]?.nearest(newPos)

            // The current-space grid ALWAYS has a "nearest" segment, even when
            // the particle is far outside every tube, so a non-null result is
            // not enough. Only treat the point as inside when that segment
            // actually contains it. Otherwise query the world-space grid for a
            // tube in another Sable space before falling further.
            val containedHere = localSeg != null && segContains(localSeg, newPos, 1.0)
            if (!containedHere) {
                val worldPos = access.toWorld(newPos)
                val worldSeg = worldGrid.nearest(worldPos)
                if (worldSeg != null && worldSeg.accessKey != key) {
                    val targetAccess = worldSeg.access
                    val targetPos = targetAccess.worldToLocal(worldPos)
                    val speed = vel.length()
                    val targetVel = targetAccess.worldNormalToLocal(
                        access.toWorldNormal(vel)
                    ).scale(speed)
                    val targetKey = targetAccess.key
                    val targetSeg = grids[targetKey]?.nearest(targetPos)
                    if (targetSeg != null && segContains(targetSeg, targetPos, 1.0)) {
                        access = targetAccess
                        key = targetKey
                        outsideLimit = if (access.isSub) 3 else 1
                        pos = targetPos
                        vel = targetVel
                        newPos = targetPos
                        localSeg = targetSeg
                        handoffs++
                    }
                }
            }

            val seg = localSeg
            if (seg != null && segContains(seg, newPos, 1.0)) {
                val ab = seg.b.subtract(seg.a)
                val lenSq = ab.lengthSqr()
                if (lenSq < 1.0E-12) {
                    pos = newPos
                } else {
                    val tRaw = ((newPos.subtract(seg.a)).dot(ab) / lenSq)
                    val axis = seg.a.add(ab.scale(tRaw.coerceIn(0.0, 1.0)))
                    val radial = newPos.subtract(axis)
                    val dist = radial.length()
                    if (tRaw < 0.0 || tRaw > 1.0) {
                        pos = newPos
                    } else if (dist <= seg.rOut + 1.0) {
                        if (dist > seg.rIn) {
                            val n = radial.scale(1.0 / dist)
                            val vn = vel.dot(n)
                            if (vn > 0.0) {
                                vel = vel.subtract(n.scale(vn))
                                if (vel.lengthSqr() < 1.0E-9) {
                                    vel = ab.normalize().scale(vn)
                                }
                            }
                            pos = axis.add(n.scale(seg.rIn.toDouble()))
                            vel = vel.scale((1.0 - waterFriction).coerceAtLeast(0.0))
                        } else {
                            pos = newPos
                        }
                        lastKey = seg.curveKey
                        lastOwner = access
                        lastInsideWorld = access.toWorld(pos)
                        lastExitVelWorld = access.toWorldNormal(vel).scale(vel.length())
                    } else {
                        pos = newPos
                    }
                }
            } else {
                pos = newPos
            }

            if (step % 2 == 0) {
                poly?.add(access.toWorld(pos))
                val seg2 = grids[key]?.nearest(pos)
                var inTube = false
                if (seg2 != null) {
                    val ab2 = seg2.b.subtract(seg2.a)
                    val lenSq2 = ab2.lengthSqr()
                    val t2 = if (lenSq2 < 1.0E-12) 0.0
                    else ((pos.subtract(seg2.a)).dot(ab2) / lenSq2).coerceIn(0.0, 1.0)
                    val axis2 = seg2.a.add(ab2.scale(t2))
                    val radial2 = pos.subtract(axis2)
                    val dist2 = radial2.length()
                    if (dist2 <= seg2.rOut) {
                        inTube = true
                        outside = 0
                        val arc = seg2.arcStart + (t2 * seg2.len).toFloat()
                        val segIdx = (arc / segLen).toInt()
                        val segMap = accByAccess.getValue(key).getOrPut(seg2.curveKey) { HashMap() }
                        val segAcc = segMap.getOrPut(segIdx) { SegmentAcc() }
                        segAcc.add(vel, seg2.b.subtract(seg2.a).normalize())
                    }
                }
                if (!inTube) {
                    outside++
                    if (outside >= outsideLimit && lastKey != null && lastOwner != null) {
                        val ownerKey = lastOwner!!.key
                        val exitPos = lastOwner!!.worldToLocal(lastInsideWorld ?: pos)
                        val exitVel = lastOwner!!.worldNormalToLocal(lastExitVelWorld ?: vel)
                        exitsByAccess.getValue(ownerKey).putIfAbsent(lastKey!!, ExitInfo(exitPos, exitVel))
                    }
                    if (outside >= freeFlightLimit) break
                    if (pos.y < access.minBuildHeight - 10) break
                }
            }
        }
        return handoffs
    }

    private fun allSegments(access: SlideSpaceAccess, sig: String): List<TubeSeg> {
        val key = spaceKey(access)
        segCache[key]?.let { if (it.first == sig) return it.second }
        val out = ArrayList<TubeSeg>()
        for (pos in SlideAnchorIndex.all(access.level, access.space)) {
            val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(access, a)
                val r1 = SlideCurveGeometry.radiusAt(access, b)
                val frames = SlideCurveGeometry.sampleFrames(access, bc, r0, r1, 0.5)
                var arc = 0.0
                for (i in 0 until frames.size - 1) {
                    val fa = frames[i]
                    val fb = frames[i + 1]
                    val len = fa.center.distanceTo(fb.center)
                    if (len < 1.0E-6) continue
                    val avgR = (fa.radius + fb.radius) / 2f
                    val lat = fa.lateral.lerp(fb.lateral, 0.5).normalize()
                    val up = fa.up.lerp(fb.up, 0.5).normalize()
                    out += TubeSeg(
                        fa.center, fb.center,
                        avgR + 0.2f,
                        (avgR - 0.15f).coerceAtLeast(0.1f),
                        edgeKey(a, b),
                        arc.toFloat(),
                        len.toFloat(),
                        lat, up
                    )
                    arc += len
                }
            }
        }
        if (segCache.size > 8) segCache.clear()
        segCache[key] = sig to out
        return out
    }

    // Cached signature for the per-tick standing-player query. Full recalcs
    // always write lastSig, so this only rescans the graph at most every 20
    // ticks between water recalculations.
    private fun structureSignatureCached(access: SlideSpaceAccess): String {
        val key = spaceKey(access)
        val old = lastSig[key]
        val last = lastSigCheckTick[key] ?: Long.MIN_VALUE
        if (old != null && access.level.gameTime - last < 20) return old
        val sig = structureSignature(access)
        lastSig[key] = sig
        lastSigCheckTick[key] = access.level.gameTime
        return sig
    }

    private fun structureSignature(access: SlideSpaceAccess, includePose: Boolean = true, includeCrossFields: Boolean = true): String {
        val sb = StringBuilder()
        if (includePose && access is SubSlideSpaceAccess) {
            val pose = access.sub.logicalPose()
            sb.append("pose=").append(pose.position().x).append(',').append(pose.position().y).append(',').append(pose.position().z)
                .append('|').append(pose.orientation().x).append(',').append(pose.orientation().y).append(',')
                .append(pose.orientation().z).append(',').append(pose.orientation().w)
                .append('|').append(pose.scale().x).append(',').append(pose.scale().y).append(',').append(pose.scale().z)
                .append(';')
        }
        for (pos in SlideAnchorIndex.all(access.level, access.space).sortedBy { it.asLong() }) {
            val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            sb.append(pos.asLong()).append(if (be.hasWater()) 'w' else '.').append('|')
            for (e in be.anchorPeerCurvesView.entries.sortedBy { it.key.asLong() }) {
                val raw = e.value
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                sb.append(a.asLong()).append(',').append(b.asLong()).append(',')
                    .append(bc.getSegmentCount()).append(',')
                    .append(bc.starts.getFirst().x).append(',')
                    .append(bc.starts.getFirst().y).append(',')
                    .append(bc.starts.getFirst().z).append(',')
                    .append(bc.starts.getSecond().x).append(',')
                    .append(bc.starts.getSecond().y).append(',')
                    .append(bc.starts.getSecond().z).append(',')
                    .append(SlideCurveGeometry.radiusAt(access, a)).append(',')
                    .append(SlideCurveGeometry.radiusAt(access, b)).append(';')
            }
        }
        if (includeCrossFields) appendCrossSpaceExitSignature(sb, access)
        return sb.toString()
    }

    // Pose-independent graph/water-source signature used to detect real slide
    // edits. Moving a sub-level (physics staff) must NOT change this signature.
    private fun stableStructureSignature(access: SlideSpaceAccess): String =
        structureSignature(access, includePose = false, includeCrossFields = false)

    private fun appendCrossSpaceExitSignature(sb: StringBuilder, access: SlideSpaceAccess) {
        val level = access.level
        val others = ArrayList<SlideSpaceAccess>()
        if (access.space != SlideSpace.Main) others += MainSlideSpaceAccess(level)
        val container = SubLevelContainer.getContainer(level)
        container?.allSubLevels?.forEach { raw ->
            val sub = raw as? ServerSubLevel ?: return@forEach
            if (access.space == SlideSpace.SubLevel(sub.uniqueId)) return@forEach
            others += SubSlideSpaceAccess(level, sub)
        }
        for (other in others.sortedBy { it.space.cacheKey(level) }) {
            sb.append("cross:").append(other.space.cacheKey(level)).append('=')
            for ((edge, field) in fields[spaceKey(other)].orEmpty()) {
                val exit = field.exit ?: continue
                if (!isOpenEndThrow(other, edge)) continue
                sb.append(edge.first).append(',').append(edge.second).append(',')
                    .append(exit.pos.x).append(',').append(exit.pos.y).append(',').append(exit.pos.z).append(',')
                    .append(exit.vel.x).append(',').append(exit.vel.y).append(',').append(exit.vel.z).append(';')
            }
        }
    }

    private fun edgeKey(a: BlockPos, b: BlockPos): Pair<Long, Long> =
        if (a.asLong() <= b.asLong()) a.asLong() to b.asLong() else b.asLong() to a.asLong()
}
