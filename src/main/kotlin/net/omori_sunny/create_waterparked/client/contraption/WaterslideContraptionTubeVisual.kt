package net.omori_sunny.create_waterparked.client.contraption

import com.simibubi.create.content.contraptions.behaviour.MovementContext
import com.simibubi.create.content.contraptions.render.ActorVisual
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import com.simibubi.create.content.trains.track.BezierConnection
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeInstance
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeInstanceType
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials

// contraption local clone of the tube visual, decoded from captured anchor NBT
class WaterslideContraptionTubeVisual(
    visualizationContext: VisualizationContext,
    simulationWorld: VirtualRenderWorld,
    movementContext: MovementContext
) : ActorVisual(visualizationContext, simulationWorld, movementContext) {

    private companion object {
        const val WALL_THICKNESS = 0.1f
        // fixed cross section fractions, band stays inside the inner wall
        const val WATER_IN_FRAC = 0.85f
        const val WATER_SURF_FRAC = 0.8f
        const val WATER_COLOR_R = 0.3f
        const val WATER_COLOR_G = 0.6f
        const val WATER_COLOR_B = 1f
        const val WATER_COLOR_A = 0.75f
    }

    private val curves: List<MountedTubeCurve> = decodeCurves(movementContext)
    private val moveCtx: MovementContext = movementContext

    init {
        net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.debug(
            "[ContraptionWaterVisual] created curves={}",
            curves.size
        )
    }

    private fun decodeCurves(movementContext: MovementContext): List<MountedTubeCurve> {
        val tag = movementContext.blockEntityData ?: return emptyList()

        val radius = if (tag.contains("Radius", Tag.TAG_FLOAT.toInt())) {
            ModConfig.clampSlideRadius(tag.getFloat("Radius"))
        } else {
            ModConfig.defaultSlideRadius()
        }

        // sector configs, peer to config
        val sectorConfigs = HashMap<BlockPos, WaterslideSectorConfig>()
        if (tag.contains("SectorConfigs", Tag.TAG_LIST.toInt())) {
            for (entry in tag.getList("SectorConfigs", Tag.TAG_COMPOUND.toInt())) {
                if (entry !is CompoundTag) continue
                if (!entry.contains("Peer", Tag.TAG_LONG.toInt()) || !entry.contains("Config", Tag.TAG_COMPOUND.toInt())) continue
                sectorConfigs[BlockPos.of(entry.getLong("Peer"))] =
                    WaterslideSectorConfig.read(entry.getCompound("Config"))
            }
        }

        // watered curves, peer to watered flag
        val wateredPeers = HashSet<BlockPos>()
        if (tag.contains("WateredCurves", Tag.TAG_LIST.toInt())) {
            for (entry in tag.getList("WateredCurves", Tag.TAG_COMPOUND.toInt())) {
                if (entry !is CompoundTag) continue
                if (!entry.contains("Peer", Tag.TAG_LONG.toInt()) || !entry.contains("Watered", Tag.TAG_BYTE.toInt())) continue
                if (entry.getBoolean("Watered")) {
                    wateredPeers += BlockPos.of(entry.getLong("Peer"))
                }
            }
        }

        // rebuild curves with localTo the captured anchor world position
        val out = ArrayList<MountedTubeCurve>()
        if (!tag.contains("AnchorPeerCurves", Tag.TAG_LIST.toInt())) return out
        for (entry in tag.getList("AnchorPeerCurves", Tag.TAG_COMPOUND.toInt())) {
            if (entry !is CompoundTag) continue
            if (!entry.contains("Peer", Tag.TAG_LONG.toInt()) || !entry.contains("Bezier", Tag.TAG_COMPOUND.toInt())) continue
            val peer = BlockPos.of(entry.getLong("Peer"))
            val bezierTag = entry.getCompound("Bezier")
            val raw = try {
                BezierConnection(bezierTag, movementContext.localPos)
            } catch (e: Throwable) {
                // a malformed curve should never crash the whole contraption
                continue
            }
            // only the primary host renders, dedupes the reciprocal copy
            if (!raw.isPrimary) continue
            if (!WaterslideTrackMaterials.isWaterslide(raw)) continue
            val config = sectorConfigs[peer] ?: WaterslideSectorConfig.defaultConfig()
            out += MountedTubeCurve(peer, raw, config, radius, wateredPeers.contains(peer))
        }
        return out
    }

    // packed light from the virtual render world
    private fun packedLight(pos: Vec3): Int {
        val bp = BlockPos.containing(pos)
        val block = simulationWorld.getBrightness(LightLayer.BLOCK, bp).coerceIn(0, 15)
        val sky = simulationWorld.getBrightness(LightLayer.SKY, bp).coerceIn(0, 15)
        return LightTexture.pack(block, sky)
    }

    // static water, no flow phases in the captured NBT
    override fun beginFrame() {
    }

    override fun update(partialTicks: Float) {
        // re check a late water field sync so the tube can start flowing
        if (++refreshCounter % 10 == 0) {
            for (curve in curves) curve.rebuildWaterIfNeeded()
        }
    }

    private var refreshCounter = 0

    override fun _delete() {
        for (curve in curves) {
            curve.delete()
        }
    }

    private inner class MountedTubeCurve(
        val peer: BlockPos,
        val curve: BezierConnection,
        val config: WaterslideSectorConfig,
        val radius: Float,
        val watered: Boolean
    ) {
        // this anchor radius for both ends, no live peer to read from
        private val frames: List<WaterslideTubeMesh.TubeSegmentFrame> =
            WaterslideTubeMesh.sampleSegments(curve, radius, radius, Vec3.ZERO)

        private val models = WaterslideTubeMesh.modelsFor(config, radius)

        // server synced water field, flows even when the captured NBT was empty
        private val waterField: net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation.CurveWater? =
            try {
                net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation.fieldFor(
                    this@WaterslideContraptionTubeVisual.moveCtx.world,
                    curve.bePositions.getFirst(), curve.bePositions.getSecond()
                )
            } catch (_: Throwable) {
                null
            }

        // cumulative arc length per frame for shared UV at boundary rings
        private val prefixArcs: FloatArray = FloatArray(frames.size + 1).also { arcs ->
            for (i in frames.indices) {
                arcs[i + 1] = arcs[i] + WaterslideTubeMesh.arcLength(frames[i])
            }
        }

        // packed light cache (wall + water share the same frame midpoints)
        private val lights: IntArray = IntArray(frames.size) { i ->
            val f = frames[i]
            packedLight(f.prevSpine.add(f.currSpine).scale(0.5))
        }

        private val wallInstances = ArrayList<WaterslideTubeInstance>()
        private val waterInstances = ArrayList<WaterslideTubeInstance>()
        private var waterBuilt = false
        private var builtWaterCrossSections = 0

        init {
            buildWall()
            rebuildWaterIfNeeded()
        }

        // builds the water band lazily, water field sync can lag creation
        fun rebuildWaterIfNeeded() {
            val crossSections = WaterslideTubeMesh.waterCrossSections()
            if (waterBuilt && crossSections == builtWaterCrossSections) return
            val fieldPresent = waterField != null
            if (watered || fieldPresent) {
                deleteWater()
                buildWater()
                waterBuilt = true
                builtWaterCrossSections = crossSections
                net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.debug(
                    "[ContraptionWaterVisual] built water for peer={} wateredNbt={} fieldPresent={} fieldSegs={} crossSections={}",
                    peer, watered, fieldPresent, waterField?.segments?.size, crossSections
                )
            }
        }

        private fun deleteWater() {
            for (w in waterInstances) w.delete()
            waterInstances.clear()
        }

        private fun buildWall() {
            val wallInstancer = instancerProvider.instancer(
                WaterslideTubeInstanceType.INSTANCE, models.wall
            )
            for ((i, f) in frames.withIndex()) {
                val wall = wallInstancer.createInstance()
                wall.setSegment(
                    f.prevSpine, f.currSpine,
                    f.prevTangent, f.currTangent,
                    f.prevLateral, f.currLateral,
                    f.prevRadius, f.currRadius
                )
                wall.light(lights[i])
                wall.setChanged()
                wall.wallThickness = WALL_THICKNESS
                wall.mirror = 1f
                wallInstances += wall
            }
        }

        private fun buildWater() {
            val verts = WaterslideTubeMesh.bandVertices(WATER_IN_FRAC, WATER_SURF_FRAC, false)
            val half = verts.size / 2
            val waterModel = WaterslideTubeMesh.waterModelFor(
                verts.subList(0, half), verts.subList(half, verts.size), radius
            )
            val waterInstancer = instancerProvider.instancer(
                WaterslideTubeInstanceType.INSTANCE, waterModel
            )
            // average flow speed from the server synced field, drives the scroll
            val avgFlow = waterField?.let { f ->
                if (f.segments.isEmpty()) 0f
                else (f.segments.sumOf { it.speed.toDouble() } / f.segments.size.toDouble()).toFloat()
            } ?: 0f
            for ((i, f) in frames.withIndex()) {
                val w = waterInstancer.createInstance()
                w.setSegment(
                    f.prevSpine, f.currSpine,
                    f.prevTangent, f.currTangent,
                    f.prevLateral, f.currLateral,
                    f.prevRadius, f.currRadius
                )
                w.light(lights[i])
                // iterationRP light blue albedo, keeps the background readable
                if (IrisColorwheelCompat.waterUvAtlasMode()) {
                    w.color(0.86f, 0.92f, 1f, WATER_COLOR_A)
                } else {
                    w.color(WATER_COLOR_R, WATER_COLOR_G, WATER_COLOR_B, WATER_COLOR_A)
                }
                w.wallThickness = WALL_THICKNESS
                w.mirror = 1f
                w.arcBase = prefixArcs[i]
                w.flowSign = if (avgFlow >= 0f) -1f else 1f
                // flowing water driven by the contraption internal water sim
                val flow = avgFlow / 40f
                w.flowStart = flow
                w.flowEnd = flow
                w.flowUpstream = flow
                w.downstreamMix = 1f
                // iterationRP shades water through its own path, keep it static there
                w.jitterScale = if (IrisColorwheelCompat.iterationRpWaterMode()) 0f
                    else ModClientConfig.waterJitterScale()
                w.jitterFrequency = ModClientConfig.waterJitterFrequency()
                w.jitterTimeScale = ModClientConfig.waterJitterTimeScale()
                w.jitterTime = 0f
                w.phaseUpstream = 0f
                w.phaseStart = 0f
                w.phaseEnd = 0f
                w.setChanged()
                waterInstances += w
            }
        }

        fun delete() {
            for (w in wallInstances) w.delete()
            deleteWater()
            wallInstances.clear()
        }
    }
}
