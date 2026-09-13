package net.omori_sunny.create_waterparked.client.flywheel

import com.simibubi.create.content.trains.track.BezierConnection
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.instance.Instancer
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.api.visual.BlockEntityVisual
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual
import dev.engine_room.flywheel.api.visual.ShaderLightVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.visual.AbstractVisual
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual
import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.sublevel.SubLevel
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import it.unimi.dsi.fastutil.longs.LongArraySet
import it.unimi.dsi.fastutil.longs.LongSet
import net.createmod.catnip.animation.AnimationTickHolder
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat
import net.omori_sunny.create_waterparked.client.editor.SubLevelEditFocus
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.physics.SlideSpace
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import org.joml.Vector3d
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// one visual per anchor
class WaterslideTubeVisual(
    ctx: VisualizationContext,
    private val be: WaterslideAnchorBlockEntity,
    partialTick: Float
) : AbstractVisual(ctx, be.level, partialTick),
    BlockEntityVisual<WaterslideAnchorBlockEntity>,
    ShaderLightVisual,
    SimpleDynamicVisual {

    private val subLevel: SubLevel? = Sable.HELPER.getContaining(be)
    private val curves = CopyOnWriteArrayList<TubeCurve>()
    private var lastDataSig = ""
    private var lastStreamPoseSig = ""
    private var lastWaterTime = -1f
    private var beamInstance: SupportInstance? = null
    private var lightSections: SectionTrackedVisual.SectionCollector? = null

    init {
        collect()
        ACTIVE.add(this)
    }

            private fun pickSupportInternal(start: Vec3, dir: Vec3, currentBest: Double): SupportPick? {
                var best: SupportPick? = null
                var bestDist = currentBest
                var curveIndex = 0
                for (c in curves) {
                    if (c.frames.isEmpty()) continue
                    if (curveIndex == 0) {
                        val beam = c.beamSupportPick(start, dir, bestDist)
                        if (beam != null) {
                            best = beam
                            bestDist = beam.distance
                        }
                    }
                    val bracket = c.bracketSupportPick(start, dir, bestDist)
                    if (bracket != null) {
                        best = bracket
                        bestDist = bracket.distance
                    }
                    curveIndex++
                }
                return best
            }

    // copycat support target: anchor, part and an outline AABB
    class SupportPick(
        @JvmField val anchorPos: BlockPos,
        @JvmField val part: Int,
        @JvmField val distance: Double,
        @JvmField val outlineBox: AABB,
        @JvmField val outline: List<Vec3>,
        @JvmField val arcDistance: Double
    )

    override fun update(partialTick: Float) {
        val scale = ModClientConfig.polygonScale()
        if (scale != lastPolygonScale) {
            lastPolygonScale = scale
            WaterslideTubeMesh.clearModels()
        }
        val sub = subLevel
        if (sub != null) {
            val pose = sub.logicalPose().position()
            val orient = sub.logicalPose().orientation()
            val poseSig = "${pose.x},${pose.y},${pose.z}|${orient.x},${orient.y},${orient.z},${orient.w}"
            if (poseSig != lastStreamPoseSig) {
                lastStreamPoseSig = poseSig
                for (c in curves) {
                    c.refreshStream()
                }
            }
        }
        val sig = dataSignature()
        if (sig == lastDataSig) return
        lastDataSig = sig
        collect()
    }

    // radius/config only; water data arrives through the sync version
    private fun dataSignature(): String {
        val sb = StringBuilder()
        sb.append(WaterFlowSimulation.version()).append('|')
        sb.append(ModClientConfig.polygonScale()).append('|')
        sb.append(ModConfig.wallThickness()).append('|')
        sb.append(be.radius).append('|')
        sb.append(be.supportMaterial(WaterslideSupportPart.BRACKET)).append('|')
        sb.append(be.supportMaterial(WaterslideSupportPart.BEAM)).append('|')
        sb.append(be.isSupportVisible(WaterslideSupportPart.BRACKET)).append('|')
        sb.append(be.isSupportVisible(WaterslideSupportPart.BEAM)).append('|')
        sb.append(be.waterActive).append('|')
        for ((key, value) in be.wateredCurves) {
            sb.append(key.asLong()).append('=').append(value).append(';')
        }
        sb.append('|')
        for ((key, cfg) in be.sectorConfigs) {
            sb.append(key.asLong()).append('=')
            sb.append(cfg.startAngle).append(';')
            for (s in cfg.sectors) {
                sb.append(s.id).append(',').append(s.material).append(',').append(s.blockId)
                    .append(',').append(s.type).append(',').append(s.widthDegrees).append(';')
            }
        }
        val lvl = be.level
        for ((key, raw) in be.anchorPeerCurvesView) {
            if (raw == null) continue
            val bc = if (raw.isPrimary) raw else raw.secondary()
            val h0 = bc.starts.first
            val h1 = bc.starts.second
            sb.append(key.asLong()).append('=')
                .append(bc.getSegmentCount()).append(',')
                .append(h0.x).append(',').append(h0.y).append(',').append(h0.z).append(',')
                .append(h1.x).append(',').append(h1.y).append(',').append(h1.z).append(',')
                .append(WaterslideRadiusEdit.radiusAt(
                    lvl!!, bc.bePositions.second, ModConfig.defaultSlideRadius()
                )).append(';')
        }
        return sb.toString()
    }

    override fun beginFrame(ctx: DynamicVisual.Context) {
        val lvl = be.level ?: return
        val now = AnimationTickHolder.getRenderTime(lvl)
        lastWaterTime = now
        for (c in curves) {
            for (w in c.waterInstances) {
                w.phaseStart = (w.flowUpstream * now) % 1.0f
                w.phaseEnd = (w.flowUpstream * now) % 1.0f
                w.phaseUpstream = (w.flowUpstream * now) % 1.0f
                w.jitterTime = now
                w.setChanged()
            }
        }
    }

    private fun collect() {
        for (c in curves) {
            c.delete()
        }
        curves.clear()
        for ((key, raw) in be.anchorPeerCurvesView) {
            if (raw == null) continue
            val bc = if (raw.isPrimary) raw else raw.secondary()
            if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
            curves.add(TubeCurve(key, bc, raw.isPrimary))
        }
        for (c in curves) {
            c.rebuildInstances()
        }
        buildSupportBeam()
        val sections = lightSections
        if (sections != null) {
            sections.sections(collectLightSections())
        }
    }

    // beam connects anchor top to the bracket bottom face center
    private fun buildSupportBeam() {
        beamInstance?.delete()
        beamInstance = null
        if (curves.isEmpty()) return
        if (!be.supportBeamVisible) return
        val c = curves[0]
        val anchorPos = be.blockPos
        val atFirst = c.curve.bePositions.first == anchorPos
        val frames = c.frames
        if (frames.isEmpty()) return
        val f = if (atFirst) frames[0] else frames[frames.size - 1]
        val s0 = if (atFirst) f.prevSpine else f.currSpine
        val s1 = if (atFirst) f.currSpine else f.prevSpine
        val spine = s0.add(s1).scale(0.5)
        val tan = if (atFirst) f.prevTangent else f.currTangent
        val lat = if (atFirst) f.prevLateral else f.currLateral
        val faceUp = tan.cross(lat).normalize()
        val wallOuter = ModConfig.wallThickness() - WaterslideTubeMesh.BASE_WALL
        val rOut = max(0.1f, if (atFirst) f.prevRadius else f.currRadius) +
            wallOuter + ModClientConfig.supportThickness() +
            WaterslideTubeMesh.SUPPORT_HUG_EPSILON
        val bottomLocal = spine.subtract(faceUp.scale(rOut.toDouble()))
        val anchorCenterLocal = Vec3.atLowerCornerOf(anchorPos)
            .add(0.5, 1.0, 0.5)
            .subtract(c.origin)
        val axis = bottomLocal.subtract(anchorCenterLocal)
        val len = axis.length().toFloat()
        if (len < 0.05f) return
        val axisN = axis.scale(1.0 / len)
        val refV = if (abs(axisN.y) < 0.9)
            Vec3(0.0, 1.0, 0.0)
        else
            Vec3(1.0, 0.0, 0.0)
        val b1 = refV.cross(axisN).normalize()
        val b2 = axisN.cross(b1).normalize()
        val halfS = ModClientConfig.supportBeamSize() * 0.5f
        val tops = beamTopOffsets(
            f, atFirst, rOut, len, axisN, anchorCenterLocal, b1, b2, halfS
        )
        val bottoms = beamBottomOffsets(axisN, anchorCenterLocal, b1, b2, halfS)
        val beamMaterial = be.supportMaterial(WaterslideSupportPart.BEAM)
        val sprite: TextureAtlasSprite = WaterslideTubeMesh.supportSprite(beamMaterial) ?: return
        val beamModel: Model = WaterslideTubeMesh.supportBeamModelFor(
            anchorCenterLocal, axisN, len, sprite, beamMaterial, tops, bottoms
        )
        val beamInstancer: Instancer<SupportInstance> =
            instancerProvider().instancer(SupportInstanceType.INSTANCE, beamModel)
        val b = beamInstancer.createInstance()
        val midWorld = anchorCenterLocal.add(axis.scale(0.5)).add(c.origin)
        b.setOrigin(Vec3.ZERO)
            .light(tubeLight(level!!, midWorld))
            .setChanged()
        b.setBounds(anchorCenterLocal.add(axis.scale(0.5)), len * 0.5f + 0.5f)
            .setChanged()
        b.fullTileMode = 1f
        val bspr = spriteRect(beamMaterial)
        if (bspr != null) {
            b.spriteU0 = bspr[0]; b.spriteU1 = bspr[1]
            b.spriteV0 = bspr[2]; b.spriteV1 = bspr[3]
        }
        beamInstance = b
    }

    private fun beamTopOffsets(
        f: WaterslideTubeMesh.TubeSegmentFrame,
        atFirst: Boolean,
        rOut: Float,
        len: Float,
        axisN: Vec3,
        anchorCenterLocal: Vec3,
        b1: Vec3,
        b2: Vec3,
        halfS: Float
    ): FloatArray = FloatArray(4) { 0.15f }

    private fun beamBottomOffsets(
        axisN: Vec3,
        anchorCenterLocal: Vec3,
        b1: Vec3,
        b2: Vec3,
        halfS: Float
    ): FloatArray {
        val bottoms = FloatArray(4)
        val signs = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
        for (i in 0..3) {
            val corner = anchorCenterLocal
                .add(b1.scale((signs[i][0] * halfS).toDouble()))
                .add(b2.scale((signs[i][1] * halfS).toDouble()))
            if (axisN.y <= 1.0E-6) {
                bottoms[i] = 0f
            } else {
                val t = corner.y - anchorCenterLocal.y
                bottoms[i] = max(t, 0.0).toFloat()
            }
        }
        return bottoms
    }

    fun collectLightSections(): LongSet {
        val out = LongArraySet()
        for (c in curves) {
            val bounds = c.curve.bounds
            val minX = Mth.floor(bounds.minX) - 1
            val minY = Mth.floor(bounds.minY) - 1
            val minZ = Mth.floor(bounds.minZ) - 1
            val maxX = Mth.ceil(bounds.maxX) + 1
            val maxY = Mth.ceil(bounds.maxY) + 1
            val maxZ = Mth.ceil(bounds.maxZ) + 1
            val minSectionX = SectionPos.blockToSectionCoord(minX)
            val minSectionY = SectionPos.blockToSectionCoord(minY)
            val minSectionZ = SectionPos.blockToSectionCoord(minZ)
            val maxSectionX = SectionPos.blockToSectionCoord(maxX)
            val maxSectionY = SectionPos.blockToSectionCoord(maxY)
            val maxSectionZ = SectionPos.blockToSectionCoord(maxZ)
            for (x in minSectionX..maxSectionX) {
                for (y in minSectionY..maxSectionY) {
                    for (z in minSectionZ..maxSectionZ) {
                        out.add(SectionPos.asLong(x, y, z))
                    }
                }
            }
        }
        return out
    }

    override fun setSectionCollector(sectionCollector: SectionTrackedVisual.SectionCollector) {
        lightSections = sectionCollector
        sectionCollector.sections(collectLightSections())
    }

    override fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
        for (c in curves) {
            c.collectCrumblingInstances(consumer)
        }
    }

    override fun _delete() {
        beamInstance?.delete()
        beamInstance = null
        for (c in curves) {
            c.delete()
        }
        curves.clear()
        ACTIVE.remove(this)
    }

    private fun refreshAnchorCurves(anchor: BlockPos) {
        for (c in ArrayList(curves)) {
            if (!curves.contains(c)) continue
            if (anchor == c.curve.bePositions.first ||
                anchor == c.curve.bePositions.second
            ) {
                c.refresh()
            }
        }
    }

    private fun tubeLight(level: Level, pos: Vec3): Int =
        LevelRenderer.getLightColor(level, BlockPos.containing(toWorldPos(pos)))

    private fun toWorldPos(plotGlobal: Vec3): Vec3 {
        val sub = subLevel ?: return plotGlobal
        val out = sub.logicalPose().transformPosition(
            JOMLConversion.toJOML(plotGlobal), Vector3d()
        )
        return JOMLConversion.toMojang(out)
    }

    private fun waterLight(level: Level, pos: Vec3): Int {
        val world = toWorldPos(pos)
        val bp = BlockPos.containing(world)
        val block = level.getBrightness(LightLayer.BLOCK, bp) + 3
        val sky = level.getBrightness(LightLayer.SKY, bp) + 3
        return LightTexture.pack(Mth.clamp(block, 0, 15), Mth.clamp(sky, 0, 15))
    }

    // world gravity in local space, water follows the rotated sub level
    private fun localGravity(): Vec3 {
        val sub = subLevel ?: return Vec3(0.0, -32.0, 0.0)
        val out = sub.logicalPose().transformNormalInverse(
            JOMLConversion.toJOML(Vec3(0.0, -32.0, 0.0)), Vector3d()
        )
        return JOMLConversion.toMojang(out)
    }

    private inner class TubeCurve(
        private val peer: BlockPos,
        val curve: BezierConnection,
        private val renderTube: Boolean
    ) {
        private val level: Level = be.level!!
        var frames: List<WaterslideTubeMesh.TubeSegmentFrame>
        private var waterFrames: List<WaterslideTubeMesh.TubeSegmentFrame>
        private lateinit var waterPrefixArcs: FloatArray
        private var waterTotalArc = 0f
        private lateinit var wallPrefixArcs: FloatArray
        private var models: WaterslideTubeMesh.TubeModels
        private var config: WaterslideSectorConfig
        private val instances = ArrayList<WaterslideTubeInstance>()
        private var streamWater: WaterFlowSimulation.CurveWater? = null
        private var streamSegments: List<StreamSegment>? = null
        private val streamInstances = ArrayList<WaterslideTubeInstance>()
        val waterInstances = ArrayList<WaterslideTubeInstance>()
        private var translucent = false
        private var showSkeleton = false
        private var mirror = 1f
        private var bracketInstance: SupportInstance? = null
        private var water: WaterFlowSimulation.CurveWater? = null
        var streamWorldOuter: List<List<Vec3>>? = null
        var streamWorldInner: List<List<Vec3>>? = null
        private var streamNeedsRebuild = false
        val origin: Vec3 = Vec3.atLowerCornerOf(renderOrigin())

        init {
            val a = curve.bePositions.first
            val b = curve.bePositions.second
            val r0 = WaterslideRadiusEdit.radiusAt(
                level, a, ModConfig.defaultSlideRadius()
            )
            val r1 = WaterslideRadiusEdit.radiusAt(
                level, b, ModConfig.defaultSlideRadius()
            )
            frames = WaterslideTubeMesh.sampleSegments(
                level, curve, r0, r1, origin, subLevel != null
            )
            waterFrames = buildWaterFrames(r0, r1)
            rebuildWaterArcs()
            rebuildWallArcs()
            val sub = subLevel
            if (sub != null) {
                val raw0 = curve.getPosition(0.0)
                val raw1 = curve.getPosition(1.0)
                val loc0 = raw0.subtract(origin)
                val loc1 = raw1.subtract(origin)
                val err = max(
                    abs(loc0.x.toFloat() - loc0.x),
                    max(
                        abs(loc0.y.toFloat() - loc0.y),
                        max(
                            abs(loc0.z.toFloat() - loc0.z),
                            max(
                                abs(loc1.x.toFloat() - loc1.x),
                                max(
                                    abs(loc1.y.toFloat() - loc1.y),
                                    abs(loc1.z.toFloat() - loc1.z)
                                )
                            )
                        )
                    )
                )
                CreateWaterparked.LOGGER.debug(
                    "[TubeDiag] sub={} plotCenter={} renderOrigin={} pose={} curve={}->{} raw0={} raw1={} loc0={} loc1={} floatErr={} frames={}",
                    sub.uniqueId, sub.plot.centerBlock, renderOrigin(),
                    sub.logicalPose(),
                    curve.bePositions.first, curve.bePositions.second,
                    raw0, raw1, loc0, loc1, err,
                    if (frames.isEmpty()) "empty"
                    else "${frames[0].prevSpine}->${frames[0].currSpine}"
                )
            }
            logJunctionDiagnostics()
            config = be.sectorConfigFor(peer)
            models = WaterslideTubeMesh.modelsFor(
                level, config, (r0 + r1) * 0.5f
            )
            water = WaterFlowSimulation.resultFor(level, curve)
        }

        // rebuild from preview
        fun refresh() {
            val a = curve.bePositions.first
            val b = curve.bePositions.second
            val r0 = WaterslideRadiusEdit.radiusAt(
                level, a, ModConfig.defaultSlideRadius()
            )
            val r1 = WaterslideRadiusEdit.radiusAt(
                level, b, ModConfig.defaultSlideRadius()
            )
            frames = WaterslideTubeMesh.sampleSegments(
                level, curve, r0, r1, origin, subLevel != null
            )
            waterFrames = buildWaterFrames(r0, r1)
            rebuildWaterArcs()
            rebuildWallArcs()
            logJunctionDiagnostics()
            val preview = WaterslideSectorEdit.previewConfigFor(a, b)
            config = preview ?: be.sectorConfigFor(peer)
            models = WaterslideTubeMesh.modelsFor(level, config, (r0 + r1) * 0.5f)
            water = WaterFlowSimulation.resultFor(level, curve)
            rebuildInstances()
        }

        // uniform 0.5 chord sampling, the old merge drifted on curves
        private fun buildWaterFrames(r0: Float, r1: Float): List<WaterslideTubeMesh.TubeSegmentFrame> {
            val sf = SlideCurveGeometry.sampleFrames(level, curve, r0, r1, 0.5, true)
            val out = ArrayList<WaterslideTubeMesh.TubeSegmentFrame>()
            if (sf.size < 2) return out
            val prefix = DoubleArray(sf.size)
            for (i in 1 until sf.size) {
                prefix[i] = prefix[i - 1] + sf[i - 1].center.distanceTo(sf[i].center)
            }
            val total = prefix[sf.size - 1]
            if (total < 1.0E-6) return out
            var segCount = ceil(total / 0.5).toInt()
            if (segCount < 1) segCount = 1

            var prevCenter = sf[0].center
            val firstT = sf[0].t
            var prevTan = CoasterBezierRailFrames.unitTangentAt(curve, firstT)
            if (prevTan.lengthSqr() < 1.0E-9) prevTan = sf[0].tangent
            prevTan = prevTan.normalize()
            var prevLat = CoasterBezierRailFrames.lateralAt(curve, firstT, level)
            if (prevLat.lengthSqr() < 1.0E-9) prevLat = sf[0].lateral
            prevLat = prevLat.normalize()
            var prevRadius = sf[0].radius
            var scan = 1
            for (s in 1..segCount) {
                val targetChord = min(s * 0.5, total)
                while (scan + 1 < sf.size && prefix[scan] < targetChord) scan++
                val segLen = prefix[scan] - prefix[scan - 1]
                val f = if (segLen > 1.0E-9) (targetChord - prefix[scan - 1]) / segLen else 0.0
                val a = sf[scan - 1]
                val b = sf[scan]
                val center = a.center.add(b.center.subtract(a.center).scale(f))
                val t = (a.t + (b.t - a.t) * f).toFloat()
                var tan = CoasterBezierRailFrames.unitTangentAt(curve, t)
                if (tan.lengthSqr() < 1.0E-9) tan = a.tangent
                tan = tan.normalize()
                var lat = CoasterBezierRailFrames.lateralAt(curve, t, level)
                if (lat.lengthSqr() < 1.0E-9) lat = prevLat
                if (lat.dot(prevLat) < 0.0) lat = lat.scale(-1.0)
                var chordDir = center.subtract(prevCenter)
                if (chordDir.lengthSqr() > 1.0E-12) {
                    chordDir = chordDir.normalize()
                    if (prevTan.dot(chordDir) < 0.0) prevTan = prevTan.scale(-1.0)
                    if (tan.dot(chordDir) < 0.0) tan = tan.scale(-1.0)
                }
                val radius = (a.radius + (b.radius - a.radius) * f).toFloat()
                val frame = WaterslideTubeMesh.TubeSegmentFrame(
                    prevCenter.subtract(origin), center.subtract(origin),
                    prevTan, tan, prevLat, lat, prevRadius, radius
                )
                out.add(frame)
                val chord = frame.currSpine.subtract(frame.prevSpine)
                val chordLen = chord.length()
                val tangentDot = if (chordLen < 1.0E-9) 1.0 else chord.normalize().dot(tan)
                if (tangentDot < 0.25) {
                    CreateWaterparked.LOGGER.debug(
                        "[WaterFrame] edge=({},{}) idx={} t={} dot={} chord={} tan={} lat={} r0={} r1={}",
                        curve.bePositions.first.asLong(), curve.bePositions.second.asLong(),
                        out.size - 1, t, tangentDot, chord, tan, lat, prevRadius, radius
                    )
                }
                prevCenter = center
                prevTan = tan
                prevLat = lat
                prevRadius = radius
            }
            return out
        }

        private fun rebuildWaterArcs() {
            waterPrefixArcs = FloatArray(waterFrames.size + 1)
            for (i in waterFrames.indices) {
                waterPrefixArcs[i + 1] = waterPrefixArcs[i] +
                    WaterslideTubeMesh.arcLength(waterFrames[i])
            }
            waterTotalArc = max(waterPrefixArcs[waterFrames.size], 1.0E-4f)
        }

        private fun rebuildWallArcs() {
            wallPrefixArcs = FloatArray(frames.size + 1)
            for (i in frames.indices) {
                wallPrefixArcs[i + 1] = wallPrefixArcs[i] +
                    WaterslideTubeMesh.arcLength(frames[i])
            }
        }

        // diagnostic: junction spike, end ring frame vs neighbor frame
        private fun logJunctionDiagnostics() {
            if (waterFrames.size < 2) return
            for (atFirst in booleanArrayOf(true, false)) {
                val anchor = if (atFirst)
                    curve.bePositions.first
                else
                    curve.bePositions.second
                val nb = neighborCurveAt(anchor) ?: continue
                val nbAtFirst = nb.bePositions.first == anchor
                val nr0 = WaterslideRadiusEdit.radiusAt(
                    level, nb.bePositions.first, ModConfig.defaultSlideRadius()
                )
                val nr1 = WaterslideRadiusEdit.radiusAt(
                    level, nb.bePositions.second, ModConfig.defaultSlideRadius()
                )
                val nf = SlideCurveGeometry.sampleFrames(
                    level, nb, nr0, nr1, 0.5, false
                )
                if (nf.size < 2) continue
                val nbFrame = if (nbAtFirst) nf[0] else nf[nf.size - 1]
                val idx = if (atFirst) 0 else waterFrames.size - 1
                val f = waterFrames[idx]
                val ownAway = if (atFirst) f.prevTangent else f.currTangent.scale(-1.0)
                val ownLatAway = if (atFirst) f.prevLateral else f.currLateral.scale(-1.0)
                val nbAway = if (nbAtFirst) nbFrame.tangent else nbFrame.tangent.scale(-1.0)
                val nbLatAway = if (nbAtFirst) nbFrame.lateral else nbFrame.lateral.scale(-1.0)
                val ownR = if (atFirst) f.prevRadius else f.currRadius
                CreateWaterparked.LOGGER.debug(
                    "[WaterJunction] edge=({},{}) side={} anchor={} tanDot={} latDot={} rDiff={} ownTan={} nbTan={} ownLat={} nbLat={}",
                    curve.bePositions.first.asLong(), curve.bePositions.second.asLong(),
                    if (atFirst) "first" else "last", anchor,
                    ownAway.normalize().dot(nbAway.normalize()),
                    ownLatAway.normalize().dot(nbLatAway.normalize()),
                    ownR - nbFrame.radius,
                    ownAway, nbAway, ownLatAway, nbLatAway
                )
            }
        }

        // the other watered curve sharing a junction anchor, if any
        private fun neighborCurveAt(anchor: BlockPos): BezierConnection? {
            val anchorBe = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return null
            if (anchorBe.legCount() != 2) return null
            for ((_, raw) in anchorBe.anchorPeerCurvesView) {
                if (raw == null) continue
                val bc = (if (raw.isPrimary) raw else raw.secondary()) ?: continue
                if (sameEdge(bc, curve)) continue
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                if (bc.bePositions.first == anchor || bc.bePositions.second == anchor) {
                    return bc
                }
            }
            return null
        }

        private fun sameEdge(x: BezierConnection, y: BezierConnection): Boolean {
            val xa = x.bePositions.first.asLong()
            val xb = x.bePositions.second.asLong()
            val ya = y.bePositions.first.asLong()
            val yb = y.bePositions.second.asLong()
            return (xa == ya && xb == yb) || (xa == yb && xb == ya)
        }

        private fun toWorldPolylines(src: List<List<Vec3>>): List<List<Vec3>> {
            val out = ArrayList<List<Vec3>>(src.size)
            for (poly in src) {
                val converted = ArrayList<Vec3>(poly.size)
                for (p in poly) converted.add(this@WaterslideTubeVisual.toWorldPos(p))
                out.add(converted)
            }
            return out
        }

        // map a world stream point into instance space with the current pose
        private fun toStreamInstancePos(world: Vec3): Vec3 {
            val sub = subLevel ?: return world.subtract(origin)
            val plotGlobal = sub.logicalPose().transformPositionInverse(
                JOMLConversion.toJOML(world), Vector3d()
            )
            return JOMLConversion.toMojang(plotGlobal).subtract(origin)
        }

        private fun buildStreamSegments(): List<StreamSegment>? {
            val w = water ?: return null
            val exit = w.exit ?: return null
            if (waterFrames.isEmpty()) return null
            val forward = w.flowSign < 0f
            val outlet = if (forward)
                waterFrames[waterFrames.size - 1]
            else
                waterFrames[0]
            val outletCenter = if (forward) outlet.currSpine else outlet.prevSpine
            val outletTan = if (forward)
                outlet.currTangent
            else
                outlet.prevTangent.scale(-1.0)
            val lat0 = if (forward) outlet.currLateral else outlet.prevLateral.scale(-1.0)
            var up0 = outletTan.cross(lat0)
            if (up0.lengthSqr() < 1.0E-9) {
                up0 = Vec3(0.0, 1.0, 0.0)
            }
            up0 = up0.normalize()
            val radius = if (forward) outlet.currRadius else outlet.prevRadius
            val rIn = max(0.05f, radius - WALL_THICKNESS * 1.5f)
            val rSurf = max(0.01f, rIn - min(0.25f, radius * 0.25f))
            var c0 = 210f
            var c1 = 330f
            if (!forward) {
                val t = c0
                c0 = -c1
                c1 = -t
            }
            val own = ArrayList<Vec3>()
            for (f in frames) {
                own.add(f.prevSpine.add(origin))
                own.add(f.currSpine.add(origin))
            }
            val throwSpeed: Double
            if (subLevel != null) {
                throwSpeed = max(0.25, exit.vel.dot(outletTan))
            } else {
                throwSpeed = exit.vel.length()
            }
            val throwVel = outletTan.scale(throwSpeed)
            val sub = subLevel
            if (sub != null) {
                CreateWaterparked.LOGGER.debug(
                    "[StreamThrow] sub={} edge=({},{}) forward={} flowSign={} mouth={} tan={} lat={} up={} r={} exitPos={} exitVel={} throwSpeed={}",
                    sub.uniqueId,
                    curve.bePositions.first.asLong(), curve.bePositions.second.asLong(),
                    forward, w.flowSign, outletCenter, outletTan, lat0, up0, radius,
                    exit.pos, exit.vel, throwSpeed
                )
            }
            val streamSpace = if (sub == null)
                SlideSpace.Main
            else
                SlideSpace.SubLevel(sub.uniqueId)
            val res = WaterFlowSimulation.predictStreams(
                level, exit.pos, throwVel, outletCenter.add(origin), lat0, up0,
                rIn, rSurf, c0, c1, own, localGravity(), streamSpace
            ) ?: return null
            val outer = res.first
            val inner = res.second
            if (outer.isEmpty() || inner.isEmpty()) return null
            if (streamWorldOuter == null || streamWorldInner == null) {
                streamWorldOuter = toWorldPolylines(outer)
                streamWorldInner = toWorldPolylines(inner)
            }
            val outerW = streamWorldOuter ?: return null
            val innerW = streamWorldInner ?: return null
            if (outerW.isEmpty() || innerW.isEmpty()) return null
            var bestRay = 0
            var bestLen = outerW[0].size
            for (i in 1 until outerW.size) {
                if (outerW[i].size > bestLen) {
                    bestRay = i
                    bestLen = outerW[i].size
                }
            }
            val o = outerW[bestRay]
            val inn = innerW[bestRay]
            if (o.size < 2 || inn.size < 2) return null

            val samples = o.size
            val streamMaxSegments = max(
                4, (48 * ModClientConfig.polygonScale()).roundToInt()
            )
            var streamLen = 0f
            for (k in 1 until samples) {
                streamLen += o[k].distanceTo(o[k - 1]).toFloat()
            }
            val desired = max(streamMaxSegments, ceil(streamLen / 0.5).toInt())
            val stride = max(1, (samples - 1) / desired)
            val dirWorld = o[0].subtract(inn[0]).normalize()
            val tubeRadius = radius

            val centers = ArrayList<Vec3>()
            for (k in 0 until samples step stride) {
                val worldCenter = o[k].subtract(dirWorld.scale(rIn.toDouble()))
                centers.add(toStreamInstancePos(worldCenter))
            }
            if (centers.size < 2) return null

            var tan0 = centers[1].subtract(centers[0]).normalize()
            if (tan0.lengthSqr() < 1.0E-6) tan0 = Vec3(0.0, 0.0, 1.0)

            val tans = arrayOfNulls<Vec3>(centers.size)
            for (i in centers.indices) {
                val a = centers[max(0, i - 1)]
                val b = centers[min(centers.size - 1, i + 1)]
                var t = b.subtract(a).normalize()
                if (t.lengthSqr() < 1.0E-6) t = lat0.cross(up0).normalize()
                tans[i] = t
            }
            val lats = arrayOfNulls<Vec3>(centers.size)
            val ups = arrayOfNulls<Vec3>(centers.size)
            var lat = lat0
            var up = up0
            for (i in centers.indices) {
                val tangent = tans[i]!!
                var l = lat.subtract(tangent.scale(lat.dot(tangent)))
                if (l.lengthSqr() < 1.0E-6) l = lat
                l = l.normalize()
                var u = tangent.cross(l).normalize()
                if (l.dot(lat) < 0.0) {
                    l = l.scale(-1.0)
                    u = u.scale(-1.0)
                }
                lats[i] = l
                ups[i] = u
                lat = l
                up = u
            }

            val speed = exit.vel.length().toFloat() *
                WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK / 40f *
                ModClientConfig.waterFlowScale()
            val segs = ArrayList<StreamSegment>()
            var arcBase = 0f
            for (i in 0 until centers.size - 1) {
                val c0p = centers[i]
                val c1p = centers[i + 1]
                segs.add(StreamSegment(
                    c0p, c1p, tans[i]!!, tans[i + 1]!!, lats[i]!!, lats[i + 1]!!,
                    tubeRadius, tubeRadius, arcBase, speed
                ))
                arcBase += 0.5f
            }
            return if (segs.isEmpty()) null else segs
        }

        private fun firstSectorSprite(): FloatArray? {
            for (s in config.sectors) {
                if (s.material == SectorMaterial.OPEN) continue
                val blockId = s.blockId ?: continue
                val r = WaterslideTubeMesh.spriteRectFor(blockId.toString())
                if (r != null) return r
            }
            return null
        }

        // bridge bracket, arc band at the anchor end, CPU baked in instance space
        private fun buildSupportBracket() {
            bracketInstance?.delete()
            bracketInstance = null
            if (!be.supportBracketVisible) return
            if (frames.isEmpty()) return
            val anchorPos = be.blockPos
            val atFirst = curve.bePositions.first == anchorPos
            val f = if (atFirst)
                frames[0]
            else
                frames[frames.size - 1]
            val bracketMaterial = be.supportMaterial(WaterslideSupportPart.BRACKET)
            val sprite = WaterslideTubeMesh.supportSprite(bracketMaterial) ?: return
            val segLen = WaterslideTubeMesh.arcLength(f)
            if (segLen < 0.01f) return
            val thickness = ModClientConfig.supportBracketThickness()
            val tStart: Float
            val tEnd: Float
            if (atFirst) {
                tStart = 0f
                tEnd = min(thickness / segLen, 1f)
            } else {
                tEnd = 1f
                tStart = max(1f - thickness / segLen, 0f)
            }
            if (tEnd - tStart < 0.01f) return
            val bracket: Model = WaterslideTubeMesh.supportBracketModelFor(
                f, config, sprite, tStart, tEnd, bracketMaterial
            )
            val bracketInstancer: Instancer<SupportInstance> =
                instancerProvider().instancer(SupportInstanceType.INSTANCE, bracket)
            val mid = f.prevSpine.add(f.currSpine).scale(0.5).add(origin)
            val light = tubeLight(level, mid)
            val s = bracketInstancer.createInstance()
            s.setOrigin(Vec3.ZERO)
                .light(light)
                .setChanged()
            val rAvgBracket = max(0.1f, (f.prevRadius + f.currRadius) * 0.5f) +
                ModConfig.wallThickness() + ModClientConfig.supportThickness() + 0.75f
            s.setBounds(mid.subtract(origin), rAvgBracket)
                .setChanged()
            s.fullTileMode = 0f
            val sspr = spriteRect(bracketMaterial)
            if (sspr != null) {
                s.spriteU0 = sspr[0]; s.spriteU1 = sspr[1]
                s.spriteV0 = sspr[2]; s.spriteV1 = sspr[3]
            }
            bracketInstance = s
        }

        private fun anchorFrame(atFirst: Boolean): WaterslideTubeMesh.TubeSegmentFrame =
            if (atFirst) frames[0] else frames[frames.size - 1]

        private fun worldSupportPoint(instanceLocal: Vec3): Vec3 =
            this@WaterslideTubeVisual.toWorldPos(instanceLocal.add(origin))

        fun beamSupportPick(rayStart: Vec3, rayDir: Vec3, currentBest: Double): SupportPick? {
            // hidden parts stay pickable: the wrench restores them
            val anchorPos = be.blockPos
            val atFirst = curve.bePositions.first == anchorPos
            val f = anchorFrame(atFirst)
            val s0 = if (atFirst) f.prevSpine else f.currSpine
            val s1 = if (atFirst) f.currSpine else f.prevSpine
            val spine = s0.add(s1).scale(0.5)
            val tangent = if (atFirst) f.prevTangent else f.currTangent
            val lateral = if (atFirst) f.prevLateral else f.currLateral
            val faceUp = tangent.cross(lateral).normalize()
            val wallOuter = ModConfig.wallThickness() - WaterslideTubeMesh.BASE_WALL
            val rOut = max(0.1f, if (atFirst) f.prevRadius else f.currRadius) +
                wallOuter + ModClientConfig.supportThickness() +
                WaterslideTubeMesh.SUPPORT_HUG_EPSILON
            val bottomLocal = spine.subtract(faceUp.scale(rOut.toDouble()))
            val anchorCenterLocal = Vec3.atLowerCornerOf(anchorPos)
                .add(0.5, 1.0, 0.5)
                .subtract(origin)
            val axis = bottomLocal.subtract(anchorCenterLocal)
            val len = axis.length()
            if (len < 0.05) return null
            val axisN = axis.scale(1.0 / len)

            val ref = if (abs(axisN.y) < 0.9)
                Vec3(0.0, 1.0, 0.0)
            else
                Vec3(1.0, 0.0, 0.0)
            val uv1 = ref.cross(axisN).normalize()
            val uv2 = axisN.cross(uv1).normalize()
            val half = ModClientConfig.supportBeamSize() * 0.5f
            var topExt = 0f
            var botExt = 0f
            val tops = beamTopOffsets(
                f, atFirst, rOut, len.toFloat(), axisN,
                anchorCenterLocal, uv1, uv2, half
            )
            for (t in tops) topExt = max(topExt, t)
            val bottoms = beamBottomOffsets(axisN, anchorCenterLocal, uv1, uv2, half)
            for (bt in bottoms) botExt = max(botExt, bt)

            val worldBase = worldSupportPoint(anchorCenterLocal.subtract(0.0, botExt.toDouble(), 0.0))
            val worldTop = worldSupportPoint(
                anchorCenterLocal.add(axisN.scale(len + topExt))
            )
            val worldCenter = worldSupportPoint(anchorCenterLocal.add(axis.scale(0.5)))
            val pickRadius = len * 0.5 + 0.5 + topExt + botExt + 0.25
            val dist = raySphereDistance(rayStart, rayDir, worldCenter, pickRadius)
            if (dist < 0.0 || dist >= currentBest) return null
            val axisDist = raySegmentDistance(rayStart, rayDir, worldBase, worldTop)
            if (axisDist > half + SUPPORT_PICK_MARGIN) return null
            val arcDist = raySegmentArcDistance(rayStart, rayDir, worldBase, worldTop)

            val eb1 = uv1.scale(half.toDouble())
            val eb2 = uv2.scale(half.toDouble())
            val outline = ArrayList<Vec3>(8)
            val baseCorners = arrayOf(
                worldBase.add(eb1).add(eb2), worldBase.subtract(eb1).add(eb2),
                worldBase.subtract(eb1).subtract(eb2), worldBase.add(eb1).subtract(eb2)
            )
            val topCorners = arrayOf(
                worldTop.add(eb1).add(eb2), worldTop.subtract(eb1).add(eb2),
                worldTop.subtract(eb1).subtract(eb2), worldTop.add(eb1).subtract(eb2)
            )
            for (k in 0..3) outline.add(baseCorners[k])
            for (k in 0..3) outline.add(topCorners[k])
            var box: AABB? = null
            for (corner in outline) {
                val current = box
                box = if (current == null) AABB(corner, corner) else current.minmax(AABB(corner, corner))
            }
            return SupportPick(anchorPos, 0, dist, box!!, outline, arcDist)
        }

        fun bracketSupportPick(rayStart: Vec3, rayDir: Vec3, currentBest: Double): SupportPick? {
            // hidden parts stay pickable: the wrench restores them
            var hasShell = false
            for (s in config.sectors) {
                if (s.material != SectorMaterial.OPEN) {
                    hasShell = true
                    break
                }
            }
            if (!hasShell) return null

            val anchorPos = be.blockPos
            val atFirst = curve.bePositions.first == anchorPos
            val f = anchorFrame(atFirst)
            val segLen = WaterslideTubeMesh.arcLength(f)
            if (segLen < 0.01f) return null

            val bracketLen = ModClientConfig.supportBracketThickness()
            val tStart = if (atFirst)
                0f
            else
                max(1f - bracketLen / segLen, 0f)
            val tEnd = if (atFirst)
                min(bracketLen / segLen, 1f)
            else
                1f
            if (tEnd - tStart < 0.01f) return null

            val c0 = f.prevSpine
            val c3 = f.currSpine
            val chord = c3.subtract(c0)
            val handle = chord.length() / 3.0
            val c1 = c0.add(f.prevTangent.scale(handle))
            val c2 = c3.subtract(f.currTangent.scale(handle))

            val wallOuter = ModConfig.wallThickness() - WaterslideTubeMesh.BASE_WALL
            val radiusOffset = wallOuter + WaterslideTubeMesh.SUPPORT_HUG_EPSILON +
                ModClientConfig.supportThickness()
            val arcLo = WaterslideTubeMesh.bracketArcLo()
            val arcHi = WaterslideTubeMesh.bracketArcHi()
            val arcRadians = Math.toRadians((arcHi - arcLo).toDouble())
            val rAvg = max(0.1f, (f.prevRadius + f.currRadius) * 0.5f)

            val tSteps = max(8, ceil((tEnd - tStart) * 12.0).toInt())
            var angleSteps = max(12, ceil(arcRadians * (rAvg + radiusOffset) * 4.0).toInt())
            angleSteps = min(angleSteps, 160)

            var best = currentBest
            var bestArc = Double.MAX_VALUE
            var box: AABB? = null
            for (ti in 0..tSteps) {
                val t = tStart + (tEnd - tStart) * ti / tSteps
                val omt = 1f - t
                val omt2 = omt * omt
                val t2 = t * t
                val spine = c0.scale((omt2 * omt).toDouble())
                    .add(c1.scale(3.0 * omt2 * t))
                    .add(c2.scale(3.0 * omt * t2))
                    .add(c3.scale((t2 * t).toDouble()))
                var tangent = c1.subtract(c0).scale(3.0 * omt2)
                    .add(c2.subtract(c1).scale(6.0 * omt * t))
                    .add(c3.subtract(c2).scale(3.0 * t2))
                tangent = if (tangent.lengthSqr() > 1e-12) tangent.normalize() else f.prevTangent
                val latLin = f.prevLateral.scale(1.0 - t).add(f.currLateral.scale(t.toDouble()))
                var lat = latLin.subtract(tangent.scale(latLin.dot(tangent)))
                if (lat.lengthSqr() < 1e-8) lat = if (abs(tangent.y) < 0.9)
                    Vec3(0.0, 1.0, 0.0)
                else
                    Vec3(1.0, 0.0, 0.0)
                lat = lat.normalize()
                val faceUp = tangent.cross(lat).normalize()
                val radius = Mth.lerp(t, f.prevRadius, f.currRadius) + radiusOffset

                for (ai in 0..angleSteps) {
                    val angle = Math.toRadians((arcLo + (arcHi - arcLo) * ai / angleSteps).toDouble())
                    val local = spine
                        .add(lat.scale(Math.cos(angle) * radius))
                        .add(faceUp.scale(Math.sin(angle) * radius))
                    val world = worldSupportPoint(local)
                    val rayEnd = rayStart.add(rayDir.scale(SUPPORT_PICK_RANGE))
                    val dist = pointSegmentDistance(world, rayStart, rayEnd)
                    if (dist <= ModClientConfig.supportThickness() + SUPPORT_PICK_MARGIN) {
                        val current = box
                        box = if (current == null) AABB(world, world) else current.minmax(AABB(world, world))
                        if (dist < best) {
                            best = dist
                            bestArc = world.subtract(rayStart).dot(rayDir)
                        }
                    }
                }
            }

            if (box == null || best >= currentBest) return null
            val outline = bracketOutline(
                c0, c1, c2, c3, f, tStart, tEnd,
                arcLo, arcHi, rAvg + radiusOffset
            )
            return SupportPick(
                anchorPos, 1, best,
                box.inflate(ModClientConfig.supportThickness().toDouble()),
                outline, bestArc
            )
        }

        private fun bracketOutline(
            c0: Vec3, c1: Vec3, c2: Vec3, c3: Vec3,
            f: WaterslideTubeMesh.TubeSegmentFrame,
            tStart: Float, tEnd: Float,
            arcLo: Float, arcHi: Float, radius: Float
        ): List<Vec3> {
            // polygon fitted outline, sample the cross section grid angles
            val crossN = WaterslideTubeMesh.crossSections()
            val degStep = 360f / crossN
            val grid = TreeSet<Int>()
            for (j in 0 until crossN) {
                val a = 90.0 + j * degStep
                if (a >= arcLo - 0.5 && a <= arcHi + 0.5) grid.add(Math.round(a * 8.0).toInt())
            }
            val out = ArrayList<Vec3>()
            out.add(shellPoint(c0, c1, c2, c3, f, tStart, arcLo, radius))
            for (g in grid) {
                out.add(shellPoint(c0, c1, c2, c3, f, tStart, g / 8f, radius))
            }
            out.add(shellPoint(c0, c1, c2, c3, f, tStart, arcHi, radius))
            out.add(shellPoint(c0, c1, c2, c3, f, tEnd, arcHi, radius))
            for (g in grid.descendingSet()) {
                out.add(shellPoint(c0, c1, c2, c3, f, tEnd, g / 8f, radius))
            }
            out.add(shellPoint(c0, c1, c2, c3, f, tEnd, arcLo, radius))
            return out
        }

        private fun shellPoint(
            c0: Vec3, c1: Vec3, c2: Vec3, c3: Vec3,
            f: WaterslideTubeMesh.TubeSegmentFrame,
            t: Float, angleDeg: Float, radius: Float
        ): Vec3 {
            val omt = 1f - t
            val spine = c0.scale((omt * omt * omt).toDouble())
                .add(c1.scale(3.0 * omt * omt * t))
                .add(c2.scale(3.0 * omt * t * t))
                .add(c3.scale((t * t * t).toDouble()))
            var tangent = c1.subtract(c0).scale(3.0 * omt * omt)
                .add(c2.subtract(c1).scale(6.0 * omt * t))
                .add(c3.subtract(c2).scale(3.0 * t * t))
            tangent = if (tangent.lengthSqr() > 1e-12) tangent.normalize() else f.prevTangent
            val latLin = f.prevLateral.scale(1.0 - t).add(f.currLateral.scale(t.toDouble()))
            var lat = latLin.subtract(tangent.scale(latLin.dot(tangent)))
            if (lat.lengthSqr() < 1e-8) lat = if (abs(tangent.y) < 0.9)
                Vec3(0.0, 1.0, 0.0)
            else
                Vec3(1.0, 0.0, 0.0)
            lat = lat.normalize()
            val faceUp = tangent.cross(lat).normalize()
            val angle = Math.toRadians(angleDeg.toDouble())
            val local = spine
                .add(lat.scale(Math.cos(angle) * radius))
                .add(faceUp.scale(Math.sin(angle) * radius))
            return worldSupportPoint(local)
        }

        private fun buildWaterBand(wallThickness: Float, mirror: Float) {
            val w = water ?: return
            if (!w.exists) return
            val segments: List<ServerWaterSimulation.WaterSegment> = w.segments
            if (segments.isEmpty()) return
            if (waterFrames.isEmpty()) return
            if (!::waterPrefixArcs.isInitialized) return
            val now = AnimationTickHolder.getRenderTime(level)
            val scale = ModClientConfig.waterFlowScale()
            for (i in segments.indices) {
                val seg = segments[i]
                val nxt = if (i + 1 < segments.size) segments[i + 1] else seg
                renderBand(
                    seg.arc, seg.speed, nxt.speed,
                    wallThickness, mirror, scale, now
                )
                if (i + 1 < segments.size) {
                    val gap = nxt.arc - seg.arc
                    if (gap > 0.75f) {
                        val midArc = seg.arc + gap * 0.5f
                        val midSpeed = (seg.speed + nxt.speed) * 0.5f
                        renderBand(
                            midArc, midSpeed, nxt.speed,
                            wallThickness, mirror, scale, now
                        )
                    }
                }
            }
        }

        private fun renderBand(
            arc: Float, speed: Float, speedNext: Float,
            wallThickness: Float, mirror: Float, scale: Float, now: Float
        ) {
            val segForward = speed >= 0f
            val frameIdx = frameIndexAtArc(arc)
            val f = waterFrames[frameIdx]
            val frameRadius = max(0.1f, (f.prevRadius + f.currRadius) * 0.5f)
            val rInFrac = WATER_IN_FRAC
            val rSurfFrac = WATER_SURF_FRAC
            val verts = WaterslideTubeMesh.bandVertices(rInFrac, rSurfFrac, !segForward)
            val vertsHalf = verts.size / 2
            val waterModel: Model = WaterslideTubeMesh.waterModelFor(
                verts.subList(0, vertsHalf), verts.subList(vertsHalf, verts.size), frameRadius
            )
            val waterInstancer: Instancer<WaterslideTubeInstance> = instancerProvider().instancer(
                WaterslideTubeInstanceType.INSTANCE, waterModel
            )
            val w = waterInstancer.createInstance()
            val ps: Vec3
            val cs: Vec3
            val pt: Vec3
            val ct: Vec3
            val pl: Vec3
            val cl: Vec3
            val pr: Float
            val cr: Float
            if (segForward) {
                ps = f.prevSpine; cs = f.currSpine
                pt = f.prevTangent; ct = f.currTangent
                pl = f.prevLateral; cl = f.currLateral
                pr = f.prevRadius; cr = f.currRadius
            } else {
                ps = f.currSpine; cs = f.prevSpine
                pt = f.currTangent.scale(-1.0); ct = f.prevTangent.scale(-1.0)
                pl = f.currLateral.scale(-1.0); cl = f.prevLateral.scale(-1.0)
                pr = f.currRadius; cr = f.prevRadius
            }
            val k = WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK * scale
            val ownFlow = abs(speed) * k / 40f
            val flow: Float
            val flowEnd: Float
            if (segForward) {
                flow = ownFlow
                flowEnd = abs(speedNext) * k / 40f
            } else {
                flow = abs(speedNext) * k / 40f
                flowEnd = ownFlow
            }
            val mid = ps.add(cs).scale(0.5).add(origin)
            val light = waterLight(level, mid)
            val wtc = waterTint()
            w.setSegment(ps, cs, pt, ct, pl, cl, pr, cr)
                .light(light)
                .color(wtc[0], wtc[1], wtc[2], 0.75f)
            w.wallThickness = wallThickness
            w.mirror = mirror
            w.waterTileSpan = 1f
            w.isWater = 1f
            w.waterAtlasUV = if (IrisColorwheelCompat.iterationRpWaterMode()) 1f else 0f
            val wspr = WaterslideTubeMesh.waterSpriteRect()
            w.spriteU0 = wspr[0]; w.spriteU1 = wspr[1]
            w.spriteV0 = wspr[2]; w.spriteV1 = wspr[3]
            w.arcBase = if (segForward)
                waterPrefixArcs[frameIdx]
            else
                waterTotalArc - waterPrefixArcs[frameIdx + 1]
            w.flowSign = -1f
            w.flowStart = flow
            w.flowEnd = flowEnd
            w.flowUpstream = ownFlow
            w.downstreamMix = 1f
            w.jitterScale = waterJitterScale()
            w.jitterFrequency = ModClientConfig.waterJitterFrequency()
            w.jitterTimeScale = ModClientConfig.waterJitterTimeScale()
            w.phaseUpstream = 0f
            w.phaseStart = (now * ownFlow) % 1.0f
            w.phaseEnd = (now * ownFlow) % 1.0f
            w.setChanged()
            instances.add(w)
            waterInstances.add(w)
        }

        private fun frameIndexAtArc(arc: Float): Int {
            var idx = Math.floor((arc / 0.5f).toDouble()).toInt()
            if (idx < 0) idx = 0
            if (idx >= waterFrames.size) idx = waterFrames.size - 1
            return idx
        }

        // true open end only when the anchor carries a single curve
        private fun isOpenEnd(anchor: BlockPos): Boolean =
            (level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity)?.legCount() == 1

        private fun buildStream(wallThickness: Float, mirror: Float) {
            val w = water
            if (w == null || !w.exists || w.exit == null) {
                streamWater = null
                streamSegments = null
                streamWorldOuter = null
                streamWorldInner = null
                streamNeedsRebuild = false
                return
            }
            val streamForward = w.flowSign < 0f
            val outletAnchor = if (streamForward)
                curve.bePositions.second
            else
                curve.bePositions.first
            if (!isOpenEnd(outletAnchor)) {
                streamWater = null
                streamSegments = null
                streamWorldOuter = null
                streamWorldInner = null
                streamNeedsRebuild = false
                return
            }
            if (streamWater !== w) {
                streamWater = w
                streamWorldOuter = null
                streamWorldInner = null
                streamNeedsRebuild = false
                streamSegments = buildStreamSegments()
            } else if (streamNeedsRebuild) {
                streamNeedsRebuild = false
                streamSegments = buildStreamSegments()
            }
            val segs = streamSegments ?: return
            if (waterFrames.isEmpty()) return
            val outletF = if (streamForward)
                waterFrames[waterFrames.size - 1]
            else
                waterFrames[0]
            val outletRadius = max(0.1f,
                if (streamForward) outletF.currRadius else outletF.prevRadius)
            val rInFrac = WATER_IN_FRAC
            val rSurfFrac = WATER_SURF_FRAC
            val ring = WaterslideTubeMesh.bandVertices(rInFrac, rSurfFrac, false)
            val ringHalf = ring.size / 2
            val streamModel: Model = WaterslideTubeMesh.waterModelFor(
                ring.subList(0, ringHalf), ring.subList(ringHalf, ring.size), outletRadius
            )
            val streamInstancer: Instancer<WaterslideTubeInstance> = instancerProvider().instancer(
                WaterslideTubeInstanceType.INSTANCE,
                streamModel
            )
            val arr = arrayOfNulls<WaterslideTubeInstance>(segs.size)
            streamInstancer.createInstances(arr)
            val fadeStart = max(0, arr.size - arr.size / 3)
            val fadeStartArc = fadeStart * 0.5f
            val fadeEndArc = arr.size * 0.5f
            for (i in arr.indices) {
                val s = segs[i]
                var jitterBoost = 1f
                if (arr.size > 1 && i >= fadeStart) {
                    val tailT = (i - fadeStart + 1).toFloat() / (arr.size - fadeStart + 1)
                    jitterBoost = 1f + tailT * tailT * 8.0f
                }
                val mid = s.prevSpine.add(s.currSpine).scale(0.5).add(origin)
                val light = waterLight(level, mid)
                val stc = waterTint()
                val inst = arr[i]!!
                inst
                    .setSegment(
                        s.prevSpine, s.currSpine,
                        s.prevTangent, s.currTangent,
                        s.prevLateral, s.currLateral,
                        s.prevRadius, s.currRadius
                    )
                    .light(light)
                    .color(stc[0], stc[1], stc[2], 0.75f)
                inst.wallThickness = wallThickness
                inst.mirror = mirror
                inst.waterTileSpan = 1f
                inst.isWater = 1f
                inst.waterAtlasUV = if (IrisColorwheelCompat.iterationRpWaterMode()) 1f else 0f
                val wsprs = WaterslideTubeMesh.waterSpriteRect()
                inst.spriteU0 = wsprs[0]; inst.spriteU1 = wsprs[1]
                inst.spriteV0 = wsprs[2]; inst.spriteV1 = wsprs[3]
                inst.arcBase = s.arcBase
                inst.flowSign = -1f
                inst.flowStart = s.speed
                inst.flowEnd = s.speed
                inst.flowUpstream = s.speed
                inst.downstreamMix = 1f
                inst.jitterScale = waterJitterScale() * jitterBoost
                inst.jitterFrequency = ModClientConfig.waterJitterFrequency()
                inst.jitterTimeScale = ModClientConfig.waterJitterTimeScale()
                inst.tailFadeStart = fadeStartArc
                inst.tailFadeEnd = fadeEndArc
                inst.phaseUpstream = 0f
                inst.phaseStart = 0f
                inst.phaseEnd = 0f
                inst.setChanged()
                streamInstances.add(inst)
                waterInstances.add(inst)
            }
        }

        fun refreshStream() {
            if (streamWater == null && streamSegments == null) return
            streamNeedsRebuild = true
            rebuildInstances()
        }

        fun setTranslucent(value: Boolean) {
            if (translucent == value) return
            translucent = value
            rebuildInstances()
        }

        fun setShowSkeleton(value: Boolean) {
            if (showSkeleton == value) return
            showSkeleton = value
            if (translucent) {
                rebuildInstances()
            }
        }

        fun rebuildInstances() {
            delete()
            if (!renderTube) {
                buildSupportBracket()
                return
            }
            val wallThickness = ModConfig.wallThickness()
            val mirror = this.mirror
            if (translucent) {
                val wallInstancer: Instancer<WaterslideTubeInstance> =
                    instancerProvider().instancer(
                        WaterslideTubeInstanceType.INSTANCE, models.wallTranslucent)
                val wall = arrayOfNulls<WaterslideTubeInstance>(frames.size)
                wallInstancer.createInstances(wall)
                val spr = firstSectorSprite()
                for (i in wall.indices) {
                    val f = frames[i]
                    val mid = f.prevSpine.add(f.currSpine).scale(0.5).add(origin)
                    val light = tubeLight(level, mid)
                    val inst = wall[i]!!
                    inst
                        .setSegment(
                            f.prevSpine, f.currSpine,
                            f.prevTangent, f.currTangent,
                            f.prevLateral, f.currLateral,
                            f.prevRadius, f.currRadius
                        )
                        .light(light)
                        .setChanged()
                    inst.wallThickness = wallThickness
                    inst.mirror = mirror
                    inst.isWater = 0f
                    if (spr != null) {
                        inst.spriteU0 = spr[0]; inst.spriteU1 = spr[1]
                        inst.spriteV0 = spr[2]; inst.spriteV1 = spr[3]
                    }
                    inst.color(1f, 1f, 1f, 0.35f)
                    instances.add(inst)
                }
            } else {
                for (sw in models.sectorWalls) {
                    val spr = WaterslideTubeMesh.spriteRectFor(sw.blockId) ?: continue
                    val wallInstancer: Instancer<WaterslideTubeInstance> =
                        instancerProvider().instancer(
                            WaterslideTubeInstanceType.INSTANCE, sw.model)
                    val wall = arrayOfNulls<WaterslideTubeInstance>(frames.size)
                    wallInstancer.createInstances(wall)
                    for (i in wall.indices) {
                        val f = frames[i]
                        val mid = f.prevSpine.add(f.currSpine).scale(0.5).add(origin)
                        val light = tubeLight(level, mid)
                        val inst = wall[i]!!
                        inst
                            .setSegment(
                                f.prevSpine, f.currSpine,
                                f.prevTangent, f.currTangent,
                                f.prevLateral, f.currLateral,
                                f.prevRadius, f.currRadius
                            )
                            .light(light)
                            .setChanged()
                        inst.wallThickness = wallThickness
                        inst.mirror = mirror
                        inst.isWater = 0f
                        inst.spriteU0 = spr[0]; inst.spriteU1 = spr[1]
                        inst.spriteV0 = spr[2]; inst.spriteV1 = spr[3]
                        if (sw.translucent) {
                            inst.waterTileSpan = 2f
                            inst.arcBase = wallPrefixArcs[i]
                            inst.downstreamMix = wallPrefixArcs[frames.size]
                        }
                        instances.add(inst)
                    }
                }
            }

            val first = frames[0]
            val last = frames[frames.size - 1]

            if (isOpenEnd(curve.bePositions.first)) {
                val startCapInstancer: Instancer<WaterslideTubeInstance> =
                    instancerProvider().instancer(
                        WaterslideTubeInstanceType.INSTANCE,
                        if (translucent) models.startCapTranslucent else models.startCap
                    )
                val startCap = startCapInstancer.createInstance()
                val startTip = first.prevSpine
                val startTan = first.prevTangent
                val startLight = tubeLight(level, startTip.add(origin))
                startCap
                    .setSegment(
                        startTip, startTip.add(startTan.scale(0.001)),
                        startTan, startTan,
                        first.prevLateral, first.prevLateral,
                        first.prevRadius, first.prevRadius
                    )
                    .light(startLight)
                    .setChanged()
                startCap.wallThickness = wallThickness
                startCap.mirror = mirror
                startCap.isWater = 0f
                val capSpr = firstSectorSprite()
                if (capSpr != null) {
                    startCap.spriteU0 = capSpr[0]; startCap.spriteU1 = capSpr[1]
                    startCap.spriteV0 = capSpr[2]; startCap.spriteV1 = capSpr[3]
                }
                if (translucent) {
                    startCap.color(1f, 1f, 1f, 0.35f)
                }
                instances.add(startCap)
            }

            if (isOpenEnd(curve.bePositions.second)) {
                val endCapInstancer: Instancer<WaterslideTubeInstance> =
                    instancerProvider().instancer(
                        WaterslideTubeInstanceType.INSTANCE,
                        if (translucent) models.endCapTranslucent else models.endCap
                    )
                val endCap = endCapInstancer.createInstance()
                val endTip = last.currSpine
                val endTan = last.currTangent
                val endLight = tubeLight(level, endTip.add(origin))
                endCap
                    .setSegment(
                        endTip, endTip.add(endTan.scale(0.001)),
                        endTan, endTan,
                        last.currLateral, last.currLateral,
                        last.currRadius, last.currRadius
                    )
                    .light(endLight)
                    .setChanged()
                endCap.wallThickness = wallThickness
                endCap.mirror = mirror
                endCap.isWater = 0f
                val capSprEnd = firstSectorSprite()
                if (capSprEnd != null) {
                    endCap.spriteU0 = capSprEnd[0]; endCap.spriteU1 = capSprEnd[1]
                    endCap.spriteV0 = capSprEnd[2]; endCap.spriteV1 = capSprEnd[3]
                }
                if (translucent) {
                    endCap.color(1f, 1f, 1f, 0.35f)
                }
                instances.add(endCap)
            }

            buildWaterBand(wallThickness, mirror)

            buildSupportBracket()

            buildStream(wallThickness, mirror)

            if (translucent && showSkeleton) {
                val ringInstancer: Instancer<WaterslideTubeInstance> =
                    instancerProvider().instancer(
                        WaterslideTubeInstanceType.INSTANCE,
                        models.ringTranslucent
                    )
                for (i in 1 until frames.size - 1) {
                    val f = frames[i]
                    val junction = f.prevSpine
                    val tan = f.prevTangent
                    val ringLight = LevelRenderer.getLightColor(
                        level, BlockPos.containing(junction.add(origin))
                    )
                    val ring = ringInstancer.createInstance()
                    ring
                        .setSegment(
                            junction, junction.add(tan.scale(0.001)),
                            tan, tan,
                            f.prevLateral, f.prevLateral,
                            f.prevRadius, f.prevRadius
                        )
                        .light(ringLight)
                        .color(1f, 1f, 1f, 0.35f)
                        .setChanged()
                    ring.wallThickness = wallThickness
                    ring.mirror = mirror
                    instances.add(ring)
                }
            }
        }

        fun delete() {
            bracketInstance?.delete()
            bracketInstance = null
            for (inst in instances) {
                inst.delete()
            }
            for (inst in streamInstances) {
                inst.delete()
            }
            waterInstances.clear()
            streamInstances.clear()
            instances.clear()
        }

        fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
            for (inst in instances) {
                consumer.accept(inst)
            }
        }
    }

    private class StreamSegment(
        val prevSpine: Vec3,
        val currSpine: Vec3,
        val prevTangent: Vec3,
        val currTangent: Vec3,
        val prevLateral: Vec3,
        val currLateral: Vec3,
        val prevRadius: Float,
        val currRadius: Float,
        val arcBase: Float,
        val speed: Float
    )

    companion object {
        // concurrent registry, the set is touched off the render thread
        val ACTIVE: MutableSet<WaterslideTubeVisual> = ConcurrentHashMap.newKeySet()
        private const val WALL_THICKNESS = 0.1f
        // fixed cross section fractions shared by every segment, bed stays inside the wall
        private const val WATER_IN_FRAC = 0.85f
        private const val WATER_SURF_FRAC = 0.8f

        private const val SUPPORT_PICK_RANGE = 64.0
        private const val SUPPORT_PICK_MARGIN = 0.08

        var wasEditing = false
        var wasDragging = false
        var lastEditAnchor: BlockPos? = null
        var lastPolygonScale = -1f
        var lastShaderPack: String? = null
        var lastShaderShading = false

        // ray pick the rendered support geometry across every anchor
        @JvmStatic
        fun pickSupport(start: Vec3, dir: Vec3): SupportPick? {
            var best = Double.MAX_VALUE
            var bestPick: SupportPick? = null
            for (visual in ArrayList(ACTIVE)) {
                val pick = visual.pickSupportInternal(start, dir, best)
                if (pick != null) {
                    best = pick.distance
                    bestPick = pick
                }
            }
            return bestPick
        }

        private fun pointSegmentDistance(p: Vec3, a: Vec3, b: Vec3): Double {
            val ab = b.subtract(a)
            val lenSq = ab.lengthSqr()
            val t = if (lenSq > 1e-12) p.subtract(a).dot(ab) / lenSq else 0.0
            val tc = Mth.clamp(t, 0.0, 1.0)
            return p.distanceTo(a.add(ab.scale(tc)))
        }

        private fun raySphereDistance(rayStart: Vec3, rayDir: Vec3, center: Vec3, radius: Double): Double {
            val oc = rayStart.subtract(center)
            val b = oc.dot(rayDir)
            val c = oc.lengthSqr() - radius * radius
            val disc = b * b - c
            if (disc < 0.0) return -1.0
            var s = -b - Math.sqrt(disc)
            if (s < 0.0) s = -b + Math.sqrt(disc)
            return s
        }

        private fun raySegmentDistance(rayStart: Vec3, rayDir: Vec3, a: Vec3, b: Vec3): Double {
            val rayEnd = rayStart.add(rayDir.scale(SUPPORT_PICK_RANGE))
            val u = rayEnd.subtract(rayStart)
            val v = b.subtract(a)
            val w = rayStart.subtract(a)
            val aCoef = u.dot(u)
            val bCoef = u.dot(v)
            val cCoef = v.dot(v)
            val dCoef = u.dot(w)
            val eCoef = v.dot(w)
            val denom = aCoef * cCoef - bCoef * bCoef
            var sN: Double
            var tN: Double
            if (denom > 1e-12) {
                sN = (bCoef * eCoef - cCoef * dCoef) / denom
                tN = (aCoef * eCoef - bCoef * dCoef) / denom
            } else {
                sN = 0.0
                tN = eCoef / max(cCoef, 1e-12)
            }
            sN = Mth.clamp(sN, 0.0, 1.0)
            tN = Mth.clamp(tN, 0.0, 1.0)
            val closestOnRay = rayStart.add(u.scale(sN))
            val closestOnSeg = a.add(v.scale(tN))
            return closestOnRay.distanceTo(closestOnSeg)
        }

        // distance along the ray, same units as block hit distance
        private fun raySegmentArcDistance(rayStart: Vec3, rayDir: Vec3, a: Vec3, b: Vec3): Double {
            val rayEnd = rayStart.add(rayDir.scale(SUPPORT_PICK_RANGE))
            val u = rayEnd.subtract(rayStart)
            val v = b.subtract(a)
            val w = rayStart.subtract(a)
            val aCoef = u.dot(u)
            val bCoef = u.dot(v)
            val cCoef = v.dot(v)
            val dCoef = u.dot(w)
            val eCoef = v.dot(w)
            val denom = aCoef * cCoef - bCoef * bCoef
            var sN: Double
            if (denom > 1e-12) {
                var tN = (aCoef * eCoef - bCoef * dCoef) / denom
                sN = (bCoef * eCoef - cCoef * dCoef) / denom
                tN = Mth.clamp(tN, 0.0, 1.0)
            } else {
                sN = 0.0
            }
            sN = Mth.clamp(sN, 0.0, 1.0)
            return sN * u.length()
        }

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = Mth.clamp((x - edge0) / (edge1 - edge0), 0f, 1f)
            return t * t * (3f - 2f * t)
        }

        // white tint under iterationRP keeps the albedo neutral, blue elsewhere
        private fun waterTint(): FloatArray =
            if (IrisColorwheelCompat.iterationRpWaterMode())
                floatArrayOf(1f, 1f, 1f)
            else
                floatArrayOf(0.3f, 0.6f, 1f)

        // mesh is static under iterationRP, jitter stays for other packs
        private fun waterJitterScale(): Float =
            if (IrisColorwheelCompat.iterationRpWaterMode())
                0f
            else
                ModClientConfig.waterJitterScale()

        private fun spriteRect(material: BlockState): FloatArray? {
            val sprite = WaterslideTubeMesh.supportSprite(material) ?: return null
            return floatArrayOf(sprite.u0, sprite.u1, sprite.v0, sprite.v1)
        }

        // translucent when edited; refresh while dragging
        @JvmStatic
        fun tickVisibility() {
            val mc = Minecraft.getInstance()
            val lvl = mc.level
            if (lvl == null) {
                wasEditing = false
                wasDragging = false
                lastEditAnchor = null
                return
            }
            val pack = IrisColorwheelCompat.shaderpackName()
            val shading = IrisColorwheelCompat.waterShadingActive()
            val packOrShadingChanged = if (pack != null)
                pack != lastShaderPack
            else
                lastShaderPack != null || shading != lastShaderShading
            if (packOrShadingChanged) {
                lastShaderPack = pack
                lastShaderShading = shading
                WaterslideTubeMesh.clearWaterModels()
                refreshAll()
            }
            val edit = BezierHandleEditMode.isActive() || SubLevelEditFocus.isActive(lvl)
            val editAnchor = if (edit) SubLevelEditFocus.activeAnchor(lvl) else null
            val editingNow = edit && editAnchor != null
            val editExited = wasEditing && !editingNow
            val showSkeleton = ModClientConfig.showSkeletonWhenTranslucent()
            val dragging =
                WaterslideSectorEdit.isDraggingControlPoint() ||
                    WaterslideRadiusEdit.isDragging() ||
                    BezierHandleDragManager.isDraggingTangentHandle()
            val dragEnded = wasDragging && !dragging
            val refreshAnchor = if (editExited || dragEnded)
                lastEditAnchor
            else if (editingNow)
                editAnchor
            else
                null
            if ((editExited || dragEnded) && refreshAnchor != null) {
                refreshChainAfterEdit(lvl, refreshAnchor)
            }
            for (visual in ArrayList(ACTIVE)) {
                if (visual.be.isRemoved || visual.be.level !== lvl) continue
                for (c in ArrayList(visual.curves)) {
                    if (!visual.curves.contains(c)) continue
                    c.setShowSkeleton(showSkeleton)
                    val belongs = edit && editAnchor != null &&
                        (editAnchor == c.curve.bePositions.first ||
                            editAnchor == c.curve.bePositions.second)
                    c.setTranslucent(belongs)
                }
                if (refreshAnchor != null) {
                    visual.refreshAnchorCurves(refreshAnchor)
                }
            }
            wasEditing = editingNow
            wasDragging = dragging
            if (editingNow) {
                lastEditAnchor = editAnchor
            } else if (editExited) {
                lastEditAnchor = null
            }
        }

        // rebuild after a BE data packet
        @JvmStatic
        fun refreshAnchor(anchor: BlockPos) {
            val mc = Minecraft.getInstance()
            val lvl = mc.level ?: return
            var changed = false
            for (visual in ArrayList(ACTIVE)) {
                if (visual.be.isRemoved || visual.be.level !== lvl) continue
                if (visual.be.blockPos == anchor) {
                    val sig = visual.dataSignature()
                    if (sig == visual.lastDataSig) continue
                    visual.lastDataSig = sig
                    visual.collect()
                    changed = true
                }
            }
            if (changed) {
                refreshChainAfterEdit(lvl, anchor)
                for (visual in ArrayList(ACTIVE)) {
                    if (visual.be.isRemoved || visual.be.level !== lvl) continue
                    visual.refreshAnchorCurves(anchor)
                }
            }
        }

        // refresh water for every leg of the edited chain
        @JvmStatic
        fun refreshChain(level: Level, edges: List<Pair<Long, Long>>, skipAnchor: BlockPos) {
            for (visual in ArrayList(ACTIVE)) {
                if (visual.be.isRemoved || visual.be.level !== level) continue
                visual.collect()
            }
        }

        // the server recomputes and syncs water after an edit; just redraw locally
        @JvmStatic
        fun refreshChainAfterEdit(level: Level, anchor: BlockPos) {
            for (visual in ArrayList(ACTIVE)) {
                if (visual.be.isRemoved || visual.be.level !== level) continue
                visual.collect()
            }
        }

        // water sync data changed; redraw every visual
        @JvmStatic
        fun refreshAll() {
            for (visual in ArrayList(ACTIVE)) {
                if (visual.be.isRemoved) continue
                visual.collect()
            }
        }

        // world space sheet polylines rendered in the AFTER_LEVEL pass
        @JvmStatic
        fun worldStreamSheets(): List<Pair<List<List<Vec3>>, List<List<Vec3>>>> {
            val mc = Minecraft.getInstance()
            val level: Level? = if (mc == null) null else mc.level
            if (level == null) return emptyList()
            val out = ArrayList<Pair<List<List<Vec3>>, List<List<Vec3>>>>()
            for (visual in ArrayList(ACTIVE)) {
                if (visual.be.level !== level || visual.be.isRemoved) continue
                for (c in visual.curves) {
                    val outer = c.streamWorldOuter ?: continue
                    val inner = c.streamWorldInner ?: continue
                    if (outer.isEmpty() || inner.isEmpty()) continue
                    out.add(Pair(outer, inner))
                }
            }
            return out
        }

    }
}
