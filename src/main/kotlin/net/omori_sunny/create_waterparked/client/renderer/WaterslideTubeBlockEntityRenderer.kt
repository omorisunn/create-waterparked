package net.omori_sunny.create_waterparked.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.simibubi.create.content.trains.track.BezierConnection
import dev.engine_room.flywheel.api.visualization.VisualizationManager
import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.companion.math.JOMLConversion
import dev.ryanhcode.sable.sublevel.ClientSubLevel
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.SpriteContents
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.model.data.ModelData
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat
import net.omori_sunny.create_waterparked.client.editor.SubLevelEditFocus
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.ponder.PonderSlideEditUiElement
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.physics.SlideSpace
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// thrown stream cross section thickness, stream physics uses the fixed value
private const val STREAM_WALL_THICKNESS = 0.1f

/**
 * Full-fidelity fallback BlockEntityRenderer for the waterslide anchor.
 *
 * Mirrors WaterslideTubeVisual + WaterslideTubeInstance + the instance vertex
 * shader one-to-one, but emits plain vertices (CPU-side expansion of every
 * shader fold):
 *   - tube wall, inner/outer rings per frame (sector slices, sprite UVs)
 *   - glass wall V-end-band fold (waterTileSpan > 1.5 path)
 *   - end caps at open ends
 *   - water band (bandVertices bed/surface + phase scroll + jitter FBM)
 *   - thrown stream (tail fade + jitter ramp)
 *   - support beam + bracket
 *   - skeleton rings (translucent ghost)
 *
 * All geometry is emitted in the ANCHOR-LOCAL space of the frames (the pose
 * stack carries the anchor translation: Ponder pre-translates, real worlds get
 * our own translate).
 */
class WaterslideTubeBlockEntityRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<WaterslideAnchorBlockEntity> {

    private class CurveGeometry(
        val peer: BlockPos,
        val bc: BezierConnection,
        val frames: List<WaterslideTubeMesh.TubeSegmentFrame>,
        val waterFrames: List<WaterslideTubeMesh.TubeSegmentFrame>,
        val wallPrefixArcs: FloatArray,
        val waterPrefixArcs: FloatArray,
        val waterTotalArc: Float,
        val config: WaterslideSectorConfig,
        val meshRadius: Float,
        val renderTube: Boolean,
        val water: WaterFlowSimulation.CurveWater?
    )

    private class StreamSeg(
        val prevSpine: Vec3, val currSpine: Vec3,
        val prevTangent: Vec3, val currTangent: Vec3,
        val prevLateral: Vec3, val currLateral: Vec3,
        val prevRadius: Float, val currRadius: Float,
        val arcBase: Float, val speed: Float
    )

    // cache per (entity position, signature): rebuild geometry only on edits
    private val cache = HashMap<Long, Pair<String, List<CurveGeometry>>>()
    private var lastSignature = ""

    // dedicated render types, never share a buffer with world section rendering
    private val tubeCutout: RenderType = RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS)
    private val tubeTranslucent: RenderType = RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)

    private class RingPoint(val x: Float, val y: Float, val z: Float, val nx: Float, val ny: Float, val nz: Float)

    private class FrameEval(
        val spine: Vec3, val tangent: Vec3, val lateral: Vec3, val faceUp: Vec3, val radius: Float
    )

    private class CellCorner(
        val lo: Float, val hi: Float,
        val f0: Float, val f1: Float,
        val c0: Float, val s0: Float,
        val c1: Float, val s1: Float,
        val cm: Float, val sm: Float
    )

    // wall/cap corner: position + normal + fully folded atlas uv
    private class CornerPoint(val p: RingPoint, val u: Float, val v: Float)

    override fun render(
        be: WaterslideAnchorBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val level = be.level
        net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer.registerClientAnchor(be)
        // ghosts ride the BER pass for non-main levels (Ponder); the real
        // world draws them from the RenderLevelStageEvent instead
        if (level != null && level !== net.minecraft.client.Minecraft.getInstance().level) {
            try {
                net.omori_sunny.create_waterparked.client.render.WaterslideGhostRenderer
                    .renderForBlockEntity(be, poseStack, buffers)
            } catch (t: Throwable) {
                net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.error(
                    "[WaterslideBER] ghost render failed at {}", be.blockPos, t
                )
            }
        }
        // fallback gate, only draw when visualization is unavailable
        if (level == null || VisualizationManager.supportsVisualization(level)) return
        try {
            renderSafe(be, level!!, poseStack, buffers, partialTick)
        } catch (t: Throwable) {
            // rendersafe pattern: a geometry hiccup must never break the render
            // loop (the ponder chunk renderer removes BEs that throw)
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.error(
                "[WaterslideBER] render failed at {}", be.blockPos, t
            )
        }
    }

    private fun renderSafe(
        be: WaterslideAnchorBlockEntity,
        level: Level,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        partialTick: Float
    ) {
        val curves = geometryFor(be, level) ?: return
        if (curves.isEmpty()) return

        val base = Vec3.atLowerCornerOf(be.blockPos)

        // The dispatcher ALWAYS pre-translates the pose to the block entity
        // position: LevelRenderer translates by (bePos - cameraPos) for both
        // section and off-screen BEs, and Ponder's renderBlockEntities
        // translates by the BE pos. So all geometry must be emitted in ANCHOR-
        // LOCAL space (which the frames already are) — never add our own
        // translate — and every vertex must be transformed by this pose
        // (vertex() reads lastPose), otherwise the tube lands around the
        // camera origin and follows the player.
        poseStack.pushPose()
        lastPose = poseStack.last()

        val cutout = buffers.getBuffer(tubeCutout)
        val translucentBuf = buffers.getBuffer(tubeTranslucent)
        val now = AnimationTickHolderRender(level)
        var tubeOwner: CurveGeometry? = null
        for (c in curves) {
            if (!c.renderTube) continue
            tubeOwner = c
            emitWalls(be, level, c, cutout, translucentBuf)
            emitCaps(be, level, c, cutout, translucentBuf)
            if (c.water?.exists == true) {
                emitWaterBand(be, level, c, translucentBuf, now)
            }
            emitStream(be, level, c, translucentBuf, now)
            emitSkeleton(be, level, c, translucentBuf)
        }
        // Ponder edit UI: cyan rings + control points (real editor UI, no custom geometry)
        if (tubeOwner != null && PonderSlideEditUiElement.ponderEditAnchorFor(level) != null) {
            try {
                emitPonderEditUi(be, level, tubeOwner, poseStack, buffers)
            } catch (t: Throwable) {
                CreateWaterparked.LOGGER.error("[WaterslideBER] edit UI failed at {}", be.blockPos, t)
            }
        }
        // support: built for BOTH directions (primary + secondary)
        for (c in curves) {
            emitSupportBracket(be, level, c, cutout)
        }
        emitSupportBeam(be, level, curves, cutout)
        lastPose = null
        poseStack.popPose()
    }

    // geometry cache access shared by render + the CPU support pick
    private fun geometryFor(be: WaterslideAnchorBlockEntity, level: Level): List<CurveGeometry>? {
        val eid = be.blockPos.asLong()
        val sig = signature(be, level)
        if (sig != lastSignature || !cache.containsKey(eid)) {
            cache.clear()
            lastSignature = sig
            cache[eid] = sig to buildCurves(be, level)
        }
        return cache[eid]?.second
    }

    // ------------------------------------------------------------------
    // CPU fallback support pick (flywheel-inactive worlds: the visual's
    // pickSupport runs over ACTIVE flywheel instances, which do not exist
    // there - mirror its beam/bracket ray math here)
    // ------------------------------------------------------------------

    companion object {
        const val LENGTH_SUBDIVISIONS = 4
        const val WATER_IN_FRAC = 0.85f
        const val WATER_SURF_FRAC = 0.8f
        private const val PICK_RANGE = 64.0
        private const val PICK_MARGIN = 0.08
        // the real editor's control ring / boundary ring constants
        private const val CONTROL_RING_OFFSET = 0.75f
        private const val BOUNDARY_RING_GAP = 0.55f

        // CPU fallback support pick: flywheel-inactive worlds have no ACTIVE
        // visuals, so the visual's pickSupport sees nothing - ray-pick the
        // bracket shell / beam from the BER geometry instead (mirrors the
        // visual's beamSupportPick / bracketSupportPick math)
        @JvmStatic
        fun pickSupport(level: Level, rayStart: Vec3, rayDir: Vec3): WaterslideTubeVisual.SupportPick? {
            var bestD = Double.MAX_VALUE
            var best: WaterslideTubeVisual.SupportPick? = null
            val mc = Minecraft.getInstance()
            val dispatcher = mc.blockEntityRenderDispatcher
            for (be in net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer.clientAnchors()) {
                if (be.isRemoved) continue
                // sublevel anchors live in plot-local space: keep the CPU pick
                // reachable even when their BE level differs from the render
                // level (the pose transform is applied inside pickFor)
                if (be.level !== level && Sable.HELPER.getContaining(be) == null) continue
                val renderer = dispatcher.getRenderer(be) as? WaterslideTubeBlockEntityRenderer ?: continue
                val pick = try {
                    renderer.pickFor(be, level, rayStart, rayDir, bestD)
                } catch (t: Throwable) {
                    null
                } ?: continue
                if (pick.distance < bestD) {
                    bestD = pick.distance
                    best = pick
                }
            }
            return best
        }
    }

    // one anchor: bracket shell pick (part 1) + beam pick (part 0)
    private fun pickFor(
        be: WaterslideAnchorBlockEntity,
        level: Level,
        rayStart: Vec3,
        rayDir: Vec3,
        currentBest: Double
    ): WaterslideTubeVisual.SupportPick? {
        // sublevel anchors live in plot-local space: mirror the visual's pose
        // transform so the ray is compared in the same space the frames are
        val sub = Sable.HELPER.getContaining(be) as? ClientSubLevel
        if (sub == null) return pickInPlotSpace(be, level, rayStart, rayDir, currentBest)
        return try {
            pickInPlotSpace(
                be, level,
                worldToPlotSpace(sub, rayStart),
                worldDirToPlotSpace(sub, rayDir),
                currentBest
            )
        } catch (t: Throwable) {
            // a pose hiccup must never break the pick; best effort fallback
            pickInPlotSpace(be, level, rayStart, rayDir, currentBest)
        }
    }

    private fun worldToPlotSpace(sub: ClientSubLevel, world: Vec3): Vec3 {
        val out = sub.logicalPose().transformPositionInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out)
    }

    private fun worldDirToPlotSpace(sub: ClientSubLevel, world: Vec3): Vec3 {
        val out = sub.logicalPose().transformNormalInverse(JOMLConversion.toJOML(world), Vector3d())
        return JOMLConversion.toMojang(out).normalize()
    }

    private fun pickInPlotSpace(
        be: WaterslideAnchorBlockEntity,
        level: Level,
        rayStart: Vec3,
        rayDir: Vec3,
        currentBest: Double
    ): WaterslideTubeVisual.SupportPick? {
        val curves = geometryFor(be, level) ?: return null
        if (curves.isEmpty()) return null
        val base = Vec3.atLowerCornerOf(be.blockPos)
        val rayEnd = rayStart.add(rayDir.scale(PICK_RANGE))
        var bestD = currentBest
        var bestPart = -1
        var bestBox: net.minecraft.world.phys.AABB? = null
        var bestArc = 0.0

        fun consider(part: Int, d: Double, p: Vec3, arc: Double) {
            if (d > bestD) return
            bestD = d
            bestPart = part
            bestArc = arc
            val expanded = net.minecraft.world.phys.AABB(p, p)
            val current = bestBox
            bestBox = if (current == null) expanded else current.minmax(expanded)
        }

        val wallThickness = net.omori_sunny.create_waterparked.config.ModConfig.wallThickness()
        val wallOuter = wallThickness - WaterslideTubeMesh.BASE_WALL
        val supportThickness = ModClientConfig.supportThickness()

        for (c in curves) {
            val frames = c.frames
            if (frames.isEmpty()) continue
            val atFirst = c.bc.bePositions.first == be.blockPos
            val f = if (atFirst) frames[0] else frames[frames.size - 1]
            val segLen = WaterslideTubeMesh.arcLength(f)
            if (segLen < 0.01f) continue

            // ---- bracket shell (mirror of the visual's bracketSupportPick) ----
            // hidden parts stay pickable so the wrench can restore them (same
            // semantics as the flywheel pick; the emit side still gates the mesh)
            val config = c.config
            val hasShell = config.sectors.any { it.material != SectorMaterial.OPEN }
            if (hasShell) {
                val thickness = ModClientConfig.supportBracketThickness()
                val tStart = if (atFirst) 0f else max(1f - thickness / segLen, 0f)
                val tEnd = if (atFirst) min(thickness / segLen, 1f) else 1f
                if (tEnd - tStart >= 0.01f) {
                    val c0 = f.prevSpine
                    val chord = f.currSpine.subtract(f.prevSpine)
                    val handle = chord.length() / 3.0
                    val c1 = f.prevSpine.add(f.prevTangent.scale(handle))
                    val c2 = f.currSpine.subtract(f.currTangent.scale(handle))
                    val c3 = f.currSpine
                    val radiusOffset = wallOuter + WaterslideTubeMesh.SUPPORT_HUG_EPSILON + supportThickness
                    val arcLo = WaterslideTubeMesh.bracketArcLo()
                    val arcHi = WaterslideTubeMesh.bracketArcHi()
                    val arcRadians = Math.toRadians((arcHi - arcLo).toDouble())
                    val rAvg = max(0.1f, (f.prevRadius + f.currRadius) * 0.5f)
                    val tSteps = max(8, ceil((tEnd - tStart) * 12.0).toInt())
                    var angleSteps = max(12, ceil(arcRadians * (rAvg + radiusOffset) * 4.0).toInt())
                    angleSteps = min(angleSteps, 160)
                    for (ti in 0..tSteps) {
                        val t = tStart + (tEnd - tStart) * ti / tSteps
                        val spine = bezierPoint(c0, c1, c2, c3, t)
                        val tangent = bezierNormalize(bezierDerivative(c0, c1, c2, c3, t))
                        val latLin = f.prevLateral.scale(1.0 - t).add(f.currLateral.scale(t.toDouble()))
                        var lat = latLin.subtract(tangent.scale(latLin.dot(tangent)))
                        if (lat.lengthSqr() < 1.0E-8) {
                            lat = if (abs(tangent.y) < 0.9) Vec3(0.0, 1.0, 0.0)
                            else Vec3(1.0, 0.0, 0.0)
                        }
                        lat = bezierNormalize(lat)
                        val faceUp = bezierNormalize(tangent.cross(lat))
                        val radius = Mth.lerp(t, f.prevRadius, f.currRadius) + radiusOffset
                        for (ai in 0..angleSteps) {
                            val angle = Math.toRadians((arcLo + (arcHi - arcLo) * ai / angleSteps).toDouble())
                            val local = spine
                                .add(lat.scale(Math.cos(angle) * radius))
                                .add(faceUp.scale(Math.sin(angle) * radius))
                            val world = local.add(base)
                            val d = pointSegmentDistance(world, rayStart, rayEnd)
                            if (d <= supportThickness + PICK_MARGIN) {
                                consider(1, d, world, world.subtract(rayStart).dot(rayDir))
                            }
                        }
                    }
                }
            }

            // ---- beam (mirror of the visual's beamSupportPick) ----
            // hidden parts stay pickable so the wrench can restore them
            val s0 = if (atFirst) f.prevSpine else f.currSpine
            val s1 = if (atFirst) f.currSpine else f.prevSpine
            val spine = s0.add(s1).scale(0.5)
            val tan = if (atFirst) f.prevTangent else f.currTangent
            val lat = if (atFirst) f.prevLateral else f.currLateral
            val faceUp = tan.cross(lat).normalize()
            val rOut = max(0.1f, if (atFirst) f.prevRadius else f.currRadius) +
                wallOuter + supportThickness + WaterslideTubeMesh.SUPPORT_HUG_EPSILON
            val bottomLocal = spine.subtract(faceUp.scale(rOut.toDouble()))
            val anchorCenterLocal = Vec3(0.5, 1.0, 0.5)
            val axis = bottomLocal.subtract(anchorCenterLocal)
            val len = axis.length()
            if (len >= 0.05) {
                val axisN = axis.scale(1.0 / len)
                val refV = if (abs(axisN.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
                val b1 = refV.cross(axisN).normalize()
                val b2 = axisN.cross(b1).normalize()
                val half = ModClientConfig.supportBeamSize() * 0.5f
                val botExt = max(0.0, anchorCenterLocal.y)
                val topExt = 0.15f
                val worldBase = anchorCenterLocal.subtract(0.0, botExt, 0.0).add(base)
                val worldTop = anchorCenterLocal.add(axisN.scale(len + topExt)).add(base)
                val axisDist = raySegmentDistance(rayStart, rayDir, worldBase, worldTop)
                if (axisDist <= half + PICK_MARGIN) {
                    val worldCenter = anchorCenterLocal.add(axis.scale(0.5)).add(base)
                    consider(0, pointSegmentDistance(worldCenter, rayStart, rayEnd), worldCenter, 0.0)
                }
            }
        }

        if (bestPart < 0) return null
        return WaterslideTubeVisual.SupportPick(
            be.blockPos, bestPart, bestD, bestBox ?: net.minecraft.world.phys.AABB(be.blockPos),
            emptyList(), bestArc
        )
    }

    private fun pointSegmentDistance(p: Vec3, a: Vec3, b: Vec3): Double {
        val ab = b.subtract(a)
        val lenSq = ab.lengthSqr()
        val t = if (lenSq > 1e-12) p.subtract(a).dot(ab) / lenSq else 0.0
        val tc = t.coerceIn(0.0, 1.0)
        return p.distanceTo(a.add(ab.scale(tc)))
    }

    private fun raySegmentDistance(rayStart: Vec3, rayDir: Vec3, a: Vec3, b: Vec3): Double {
        val rayEnd = rayStart.add(rayDir.scale(PICK_RANGE))
        val u = rayEnd.subtract(rayStart)
        val v = b.subtract(a)
        val w = rayStart.subtract(a)
        val aC = u.dot(u)
        val bC = u.dot(v)
        val cC = v.dot(v)
        val dC = u.dot(w)
        val eC = v.dot(w)
        val denom = aC * cC - bC * bC
        var sN: Double
        var tN: Double
        if (denom > 1e-12) {
            sN = (bC * eC - cC * dC) / denom
            tN = (aC * eC - bC * dC) / denom
        } else {
            sN = 0.0
            tN = eC / max(cC, 1e-12)
        }
        sN = sN.coerceIn(0.0, 1.0)
        tN = tN.coerceIn(0.0, 1.0)
        val closestOnRay = rayStart.add(u.scale(sN))
        val closestOnSeg = a.add(v.scale(tN))
        return closestOnRay.distanceTo(closestOnSeg)
    }

    // the tube far outlives the anchor block: keep rendering + cull with a
    // world-distance check like the legacy curve renderer
    override fun shouldRenderOffScreen(blockEntity: WaterslideAnchorBlockEntity): Boolean = true

    override fun getViewDistance(): Int = 192

    // animation tick in seconds (matches AnimationTickHolder.getRenderTime)
    private fun AnimationTickHolderRender(level: Level): Float =
        net.createmod.catnip.animation.AnimationTickHolder.getRenderTime(level)

    // geometry caches
    private fun buildCurves(be: WaterslideAnchorBlockEntity, level: Level): List<CurveGeometry> {
        val out = ArrayList<CurveGeometry>()
        val origin = Vec3.atLowerCornerOf(be.blockPos)
        val defRadius = ModConfig.defaultSlideRadius()
        for (e in be.anchorPeerCurvesView) {
            val raw = e.value ?: continue
            val bc = if (raw.isPrimary) raw else raw.secondary()
            if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
            // peer = the map key, exactly like the visual's TubeCurve
            val peer = e.key
            val r0 = WaterslideRadiusEdit.radiusAt(level, bc.bePositions.first, defRadius)
            val r1 = WaterslideRadiusEdit.radiusAt(level, bc.bePositions.second, defRadius)
            val frames = try {
                WaterslideTubeMesh.sampleSegments(level, bc, r0, r1, origin)
            } catch (t: Throwable) {
                continue
            }
            if (frames.size < 2) continue
            val waterFrames = buildWaterFrames(level, bc, r0, r1, origin)
            val wallPrefix = FloatArray(frames.size + 1)
            for (i in frames.indices) {
                wallPrefix[i + 1] = wallPrefix[i] + WaterslideTubeMesh.arcLength(frames[i])
            }
            val waterPrefix = FloatArray(waterFrames.size + 1)
            for (i in waterFrames.indices) {
                waterPrefix[i + 1] = waterPrefix[i] + WaterslideTubeMesh.arcLength(waterFrames[i])
            }
            val water = WaterFlowSimulation.fieldFor(level, bc.bePositions.first, bc.bePositions.second)
            out += CurveGeometry(
                peer, bc, frames, waterFrames, wallPrefix, waterPrefix,
                max(waterPrefix[waterFrames.size], 1.0E-4f), be.sectorConfigFor(peer),
                (r0 + r1) * 0.5f, raw.isPrimary, water
            )
        }
        return out
    }

    // exact mirror of TubeCurve.buildWaterFrames: uniform 0.5 chord sampling
    private fun buildWaterFrames(
        level: Level, bc: BezierConnection,
        r0: Float, r1: Float, origin: Vec3
    ): List<WaterslideTubeMesh.TubeSegmentFrame> {
        val sf = try {
            SlideCurveGeometry.sampleFrames(level, bc, r0, r1, 0.5, true)
        } catch (t: Throwable) {
            return emptyList()
        }
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
        var prevTan = CoasterBezierRailFrames.unitTangentAt(bc, firstT)
        if (prevTan.lengthSqr() < 1.0E-9) prevTan = sf[0].tangent
        prevTan = prevTan.normalize()
        var prevLat = CoasterBezierRailFrames.lateralAt(bc, firstT, level)
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
            var tan = CoasterBezierRailFrames.unitTangentAt(bc, t)
            if (tan.lengthSqr() < 1.0E-9) tan = a.tangent
            tan = tan.normalize()
            var lat = CoasterBezierRailFrames.lateralAt(bc, t, level)
            if (lat.lengthSqr() < 1.0E-9) lat = prevLat
            if (lat.dot(prevLat) < 0.0) lat = lat.scale(-1.0)
            val chordDir = center.subtract(prevCenter)
            if (chordDir.lengthSqr() > 1.0E-12) {
                val cd = chordDir.normalize()
                if (prevTan.dot(cd) < 0.0) prevTan = prevTan.scale(-1.0)
                if (tan.dot(cd) < 0.0) tan = tan.scale(-1.0)
            }
            val radius = (a.radius + (b.radius - a.radius) * f).toFloat()
            out += WaterslideTubeMesh.TubeSegmentFrame(
                prevCenter.subtract(origin), center.subtract(origin),
                prevTan, tan, prevLat, lat, prevRadius, radius
            )
            prevCenter = center
            prevTan = tan
            prevLat = lat
            prevRadius = radius
        }
        return out
    }

    private fun signature(be: WaterslideAnchorBlockEntity, level: Level): String {
        val sb = StringBuilder()
        sb.append(WaterFlowSimulation.version()).append('|')
            .append(ModClientConfig.polygonScale()).append('|')
            .append(net.omori_sunny.create_waterparked.config.ModConfig.wallThickness()).append('|')
            .append(be.radius).append('|')
            // support state: the BER emits beam/bracket from these - a change
            // (wrench fill/clear/cycle, axe delete, storyboard dump beat) MUST
            // invalidate the geometry cache or the Ponder scene / any BER-only
            // world keeps showing the old support look
            .append(be.supportMaterial(net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET).let {
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(it.block)
            }).append('|')
            .append(be.supportMaterial(net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM).let {
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(it.block)
            }).append('|')
            .append(be.isSupportVisible(net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET)).append('|')
            .append(be.isSupportVisible(net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM)).append('|')
            .append(be.waterActive).append('|')
        for ((peer, watered) in be.wateredCurves) {
            sb.append(peer.asLong()).append('=').append(watered).append(';')
        }
        sb.append('|')
        for ((peer, cfg) in be.sectorConfigs) {
            sb.append(peer.asLong()).append('=')
            for (s in cfg.sectors) {
                sb.append(s.id).append(',').append(s.material).append(',').append(s.blockId).append(';')
            }
        }
        for (e in be.anchorPeerCurvesView) {
            val raw = e.value ?: continue
            val bc = if (raw.isPrimary) raw else raw.secondary()
            sb.append(e.key.asLong()).append('=')
                .append(raw.isPrimary).append(',')
                .append(bc.bePositions.first.asLong()).append(',')
                .append(bc.bePositions.second.asLong()).append(',')
                .append(bc.getSegmentCount()).append(';')
            // spline geometry: shape edits (ponder ease) must invalidate the cache
            sb.append(bc.starts.first.x).append(',').append(bc.starts.first.y).append(',').append(bc.starts.first.z).append(',')
                .append(bc.starts.second.x).append(',').append(bc.starts.second.y).append(',').append(bc.starts.second.z).append(',')
                .append(bc.axes.first.x).append(',').append(bc.axes.first.y).append(',').append(bc.axes.first.z).append(',')
                .append(bc.axes.second.x).append(',').append(bc.axes.second.y).append(',').append(bc.axes.second.z).append(';')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // shader-mirrored helpers (instance vertex shader fold formulas)
    // ------------------------------------------------------------------

    // exact mirror of arcLenTo in waterslide_tube.vert
    private fun arcLenTo(c0: Vec3, c1: Vec3, c2: Vec3, c3: Vec3, v: Float): Float {
        var sum = 0.0
        for (i in 0 until 8) {
            val t0 = v * i / 8f
            val t1 = v * (i + 1) / 8f
            val m0 = 1f - t0
            val m1 = 1f - t1
            val d0 = c1.subtract(c0).scale(3.0 * m0 * m0)
                .add(c2.subtract(c1).scale(6.0 * m0 * t0))
                .add(c3.subtract(c2).scale(3.0 * t0 * t0))
            val d1 = c1.subtract(c0).scale(3.0 * m1 * m1)
                .add(c2.subtract(c1).scale(6.0 * m1 * t1))
                .add(c3.subtract(c2).scale(3.0 * t1 * t1))
            sum += (d0.length() + d1.length()) * 0.5 * (t1 - t0).toDouble()
        }
        return sum.toFloat()
    }

    private fun bezierPoint(c0: Vec3, c1: Vec3, c2: Vec3, c3: Vec3, t: Float): Vec3 {
        val omt = 1.0 - t
        val td = t.toDouble()
        return c0.scale(omt * omt * omt)
            .add(c1.scale(3.0 * omt * omt * td))
            .add(c2.scale(3.0 * omt * td * td))
            .add(c3.scale(td * td * td))
    }

    private fun bezierDerivative(c0: Vec3, c1: Vec3, c2: Vec3, c3: Vec3, t: Float): Vec3 {
        val omt = 1.0 - t
        return c1.subtract(c0).scale(3.0 * omt * omt)
            .add(c2.subtract(c1).scale(6.0 * omt * t))
            .add(c3.subtract(c2).scale(3.0 * t * t))
    }

    private fun bezierNormalize(v: Vec3): Vec3 {
        val l = v.length()
        return if (l > 1.0E-8) v.scale(1.0 / l) else Vec3(0.0, 1.0, 0.0)
    }

    private fun bezierArcLengthTo(c0: Vec3, c1: Vec3, c2: Vec3, c3: Vec3, t: Float): Float {
        var sum = 0.0
        val steps = 8
        for (i in 0 until steps) {
            val a = t * i / steps
            val b = t * (i + 1) / steps
            sum += (bezierDerivative(c0, c1, c2, c3, a).length()
                + bezierDerivative(c0, c1, c2, c3, b).length()) * 0.5 * (b - a)
        }
        return sum.toFloat()
    }

    // frame evaluation matching the instance vertex shader reconstruction
    private fun evalFrame(f: WaterslideTubeMesh.TubeSegmentFrame, t: Float): FrameEval {
        val td = t.toDouble()
        val omt = 1.0 - td
        val chord = f.currSpine.subtract(f.prevSpine)
        val chordLen = chord.length()
        val handle = chordLen / 3.0
        val c0 = f.prevSpine
        val c1 = f.prevSpine.add(f.prevTangent.scale(handle))
        val c2 = f.currSpine.subtract(f.currTangent.scale(handle))
        val c3 = f.currSpine
        val spine = c0.scale(omt * omt * omt)
            .add(c1.scale(3.0 * omt * omt * td))
            .add(c2.scale(3.0 * omt * td * td))
            .add(c3.scale(td * td * td))
        val deriv = c1.subtract(c0).scale(3.0 * omt * omt)
            .add(c2.subtract(c1).scale(6.0 * omt * td))
            .add(c3.subtract(c2).scale(3.0 * td * td))
        val dLenSq = deriv.lengthSqr()
        val tangent = if (dLenSq > 1.0E-12) deriv.scale(1.0 / sqrt(dLenSq))
        else if (chord.lengthSqr() > 1.0E-12) chord.normalize()
        else Vec3(0.0, 0.0, 1.0)
        val latLin = f.prevLateral.scale(omt).add(f.currLateral.scale(td))
        val latPerp = latLin.subtract(tangent.scale(latLin.dot(tangent)))
        val lateral = if (latPerp.lengthSqr() > 1.0E-12) latPerp.normalize()
        else {
            val fallback = if (abs(tangent.y) < 0.9) Vec3(0.0, 1.0, 0.0)
            else Vec3(1.0, 0.0, 0.0)
            fallback.subtract(tangent.scale(fallback.dot(tangent))).normalize()
        }
        val faceUp = tangent.cross(lateral).normalize()
        val radius = max(Mth.lerp(t, f.prevRadius, f.currRadius), 0.001f)
        return FrameEval(spine, tangent, lateral, faceUp, radius)
    }

    // GLSL fract (positive for any sign, matches mod(x, 1.0))
    private fun frac(x: Float): Float = x - floor(x)

    // exact mirror of jitterHash13/jitterNoise3/jitterFbm in waterslide_tube.vert
    private fun jitterHash13(px: Float, py: Float, pz: Float): Float {
        var x = frac(px * 0.1031f)
        var y = frac(py * 0.1031f)
        var z = frac(pz * 0.1031f)
        val ax = x
        val ay = y
        val az = z
        val d = ax * (az + 31.32f) + ay * (ay + 31.32f) + az * (ax + 31.32f)
        x = ax + d
        y = ay + d
        z = az + d
        return frac((x + y) * z)
    }

    private fun jitterNoise3(px: Float, py: Float, pz: Float): Float {
        val ix = floor(px)
        val iy = floor(py)
        val iz = floor(pz)
        val fx = px - ix
        val fy = py - iy
        val fz = pz - iz
        val sxf = fx * fx * (3f - 2f * fx)
        val syf = fy * fy * (3f - 2f * fy)
        val szf = fz * fz * (3f - 2f * fz)
        val n000 = jitterHash13(ix, iy, iz)
        val n100 = jitterHash13(ix + 1f, iy, iz)
        val n010 = jitterHash13(ix, iy + 1f, iz)
        val n110 = jitterHash13(ix + 1f, iy + 1f, iz)
        val n001 = jitterHash13(ix, iy, iz + 1f)
        val n101 = jitterHash13(ix + 1f, iy, iz + 1f)
        val n011 = jitterHash13(ix, iy + 1f, iz + 1f)
        val n111 = jitterHash13(ix + 1f, iy + 1f, iz + 1f)
        val nx00 = n000 + (n100 - n000) * sxf
        val nx10 = n010 + (n110 - n010) * sxf
        val nx01 = n001 + (n101 - n001) * sxf
        val nx11 = n011 + (n111 - n011) * sxf
        val nxy0 = nx00 + (nx10 - nx00) * syf
        val nxy1 = nx01 + (nx11 - nx01) * syf
        return nxy0 + (nxy1 - nxy0) * szf
    }

    private fun jitterFbm(px: Float, py: Float, pz: Float): Float =
        jitterNoise3(px, py, pz) * 0.5f +
            jitterNoise3(px * 2.13f + 17.7f, py * 2.13f + 17.7f, pz * 2.13f + 17.7f) * 0.3f +
            jitterNoise3(px * 4.29f + 31.1f, py * 4.29f + 31.1f, pz * 4.29f + 31.1f) * 0.2f

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ------------------------------------------------------------------
    // light + sprite helpers
    // ------------------------------------------------------------------

    private fun tubeLight(level: Level, world: Vec3): Int =
        LevelRenderer.getLightColor(level, BlockPos.containing(world))

    private fun waterLight(level: Level, world: Vec3): Int {
        val bp = BlockPos.containing(world)
        val block = Mth.clamp(level.getBrightness(LightLayer.BLOCK, bp) + 3, 0, 15)
        val sky = Mth.clamp(level.getBrightness(LightLayer.SKY, bp) + 3, 0, 15)
        return LightTexture.pack(block, sky)
    }

    private fun waterTint(): FloatArray =
        if (IrisColorwheelCompat.iterationRpWaterMode()) floatArrayOf(1f, 1f, 1f)
        else floatArrayOf(0.3f, 0.6f, 1f)

    private fun waterJitterScale(): Float =
        if (IrisColorwheelCompat.iterationRpWaterMode()) 0f
        else ModClientConfig.waterJitterScale()

    private fun isOpenEnd(level: Level, anchor: BlockPos): Boolean {
        val anchorBe = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return false
        return anchorBe.legCount() == 1
    }

    private fun isEditing(level: Level, be: WaterslideAnchorBlockEntity): Boolean {
        // Ponder: the whole tube is "under edit" while the storyboard edit state is on
        if (PonderSlideEditUiElement.ponderEditAnchorFor(level) != null) return true
        val edit = dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode.isActive() ||
            SubLevelEditFocus.isActive(level)
        if (!edit) return false
        val anchor = SubLevelEditFocus.activeAnchor(level) ?: return false
        for (raw in be.anchorPeerCurvesView.values) {
            val bc = if (raw.isPrimary) raw else raw.secondary() ?: continue
            if (anchor == bc.bePositions.first || anchor == bc.bePositions.second) return true
        }
        return false
    }

    // dominant face sprite, exact mirror of WaterslideTubeMesh.spriteFor
    private fun spriteFor(blockId: ResourceLocation): TextureAtlasSprite? {
        val block = BuiltInRegistries.BLOCK.get(blockId) ?: return null
        val state = block.defaultBlockState()
        val model = Minecraft.getInstance().blockRenderer.getBlockModel(state)
        val counts = java.util.HashMap<TextureAtlasSprite, Int>()
        val random = RandomSource.create()
        for (dir in listOf<Direction?>(null) + Direction.entries.toList()) {
            for (quad in model.getQuads(state, dir, random, ModelData.EMPTY, null)) {
                val s = quad.sprite ?: continue
                counts.merge(s, 1, Int::plus)
            }
        }
        counts.entries.maxByOrNull { it.value }?.key?.let { return it }
        return model.getParticleIcon(ModelData.EMPTY)
    }

    // exact mirror of WaterslideTubeMesh.isTranslucent
    private fun isTranslucentBlock(blockId: ResourceLocation): Boolean =
        blockId.path.split('_').any { it.contains("glass") }

    // exact mirror of WaterslideTubeMesh.borderPxOf (reflection scan)
    private fun borderPxOf(sprite: TextureAtlasSprite): Int {
        val key = sprite.contents().name().toString()
        return borderCache.getOrPut(key) {
            try {
                val field = SpriteContents::class.java.getDeclaredField("mipmapLevelsImages")
                field.isAccessible = true
                val img = (field.get(sprite.contents()) as? Array<*>)?.firstOrNull()
                    ?: return@getOrPut 1
                val cls = img.javaClass
                val w = cls.getMethod("getWidth").invoke(img) as Int
                val h = cls.getMethod("getHeight").invoke(img) as Int
                val rgba = cls.getMethod("getPixelRGBA", Int::class.java, Int::class.java)
                fun alpha(x: Int, y: Int): Int =
                    ((rgba.invoke(img, x, y) as Int) ushr 24) and 0xFF
                var left = Int.MAX_VALUE
                var right = Int.MAX_VALUE
                var top = Int.MAX_VALUE
                var bottom = Int.MAX_VALUE
                for (y in 0 until h) {
                    var l = 0
                    for (x in 0 until w) { if (alpha(x, y) >= 128) l++ else break }
                    var r = 0
                    for (x in w - 1 downTo 0) { if (alpha(x, y) >= 128) r++ else break }
                    if (l < left) left = l
                    if (r < right) right = r
                }
                for (x in 0 until w) {
                    var t = 0
                    for (y in 0 until h) { if (alpha(x, y) >= 128) t++ else break }
                    var b = 0
                    for (y in h - 1 downTo 0) { if (alpha(x, y) >= 128) b++ else break }
                    if (t < top) top = t
                    if (b < bottom) bottom = b
                }
                maxOf(left, right, top, bottom).coerceIn(1, 8)
            } catch (e: Throwable) {
                1
            }
        }
    }

    private val borderCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ------------------------------------------------------------------
    // vertex emission
    // ------------------------------------------------------------------

    // The dispatcher pre-translates the pose to the block entity position
    // (LevelRenderer: bePos - camera; Ponder: bePos). The local geometry is
    // only correct when EVERY vertex goes through that pose matrix - a raw
    // addVertex(x, y, z) ignores the translation and pins the tube to the
    // camera origin (the "tube floats above my head and follows me" symptom).
    private var lastPose: PoseStack.Pose? = null

    private fun vertex(v: VertexConsumer, p: RingPoint, u: Float, vt: Float, light: Int) {
        val pose = lastPose
        val builder = if (pose != null) v.addVertex(pose, p.x, p.y, p.z)
        else v.addVertex(p.x, p.y, p.z)
        builder.setColor(1f, 1f, 1f, 1f)
            .setUv(u, vt)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(p.nx, p.ny, p.nz)
    }

    private fun vertexColor(
        v: VertexConsumer, p: RingPoint, u: Float, vt: Float, light: Int,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val pose = lastPose
        val builder = if (pose != null) v.addVertex(pose, p.x, p.y, p.z)
        else v.addVertex(p.x, p.y, p.z)
        builder.setColor(r, g, b, a)
            .setUv(u, vt)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(p.nx, p.ny, p.nz)
    }

    private fun verts4(
        buf: VertexConsumer,
        a: RingPoint, b: RingPoint, d: RingPoint, e: RingPoint,
        ua: Float, va: Float, ub: Float, vb: Float, ud: Float, vd: Float, ue: Float, ve: Float,
        light: Int
    ) {
        vertex(buf, a, ua, va, light)
        vertex(buf, b, ub, vb, light)
        vertex(buf, d, ud, vd, light)
        vertex(buf, e, ue, ve, light)
    }

    private fun verts4Color(
        buf: VertexConsumer,
        a: RingPoint, b: RingPoint, d: RingPoint, e: RingPoint,
        ua: Float, va: Float, ub: Float, vb: Float, ud: Float, vd: Float, ue: Float, ve: Float,
        light: Int, r: Float, g: Float, bl: Float, al: Float
    ) {
        vertexColor(buf, a, ua, va, light, r, g, bl, al)
        vertexColor(buf, b, ub, vb, light, r, g, bl, al)
        vertexColor(buf, d, ud, vd, light, r, g, bl, al)
        vertexColor(buf, e, ue, ve, light, r, g, bl, al)
    }

    private fun verts4ColorV(
        buf: VertexConsumer,
        a: RingPoint, b: RingPoint, d: RingPoint, e: RingPoint,
        ua: Float, va: Float, ub: Float, vb: Float, ud: Float, vd: Float, ue: Float, ve: Float,
        light: Int, r: Float, g: Float, bl: Float,
        aa: Float, ab: Float, ad: Float, ae: Float
    ) {
        vertexColor(buf, a, ua, va, light, r, g, bl, aa)
        vertexColor(buf, b, ub, vb, light, r, g, bl, ab)
        vertexColor(buf, d, ud, vd, light, r, g, bl, ad)
        vertexColor(buf, e, ue, ve, light, r, g, bl, ae)
    }

    // double-sided sheets: both windings, same per-corner uv (the visuals use
    // backfaceCulling(false) materials for water/stream/support shell)
    private fun verts4Double(
        buf: VertexConsumer,
        a: RingPoint, b: RingPoint, d: RingPoint, e: RingPoint,
        ua: Float, va: Float, ub: Float, vb: Float, ud: Float, vd: Float, ue: Float, ve: Float,
        light: Int
    ) {
        verts4(buf, a, b, d, e, ua, va, ub, vb, ud, vd, ue, ve, light)
        verts4(buf, a, e, d, b, ua, va, ue, ve, ud, vd, ub, vb, light)
    }

    private fun waterQuadDouble(
        buf: VertexConsumer,
        a: RingPoint, b: RingPoint, d: RingPoint, e: RingPoint,
        ua: Float, va: Float, ub: Float, vb: Float, ud: Float, vd: Float, ue: Float, ve: Float,
        light: Int, r: Float, g: Float, bl: Float,
        aa: Float, ab: Float, ad: Float, ae: Float
    ) {
        verts4ColorV(buf, a, b, d, e, ua, va, ub, vb, ud, vd, ue, ve, light, r, g, bl, aa, ab, ad, ae)
        verts4ColorV(buf, a, e, d, b, ua, va, ue, ve, ud, vd, ub, vb, light, r, g, bl, aa, ae, ad, ab)
    }

    private fun Vec3.toRingWithN(n: Vec3): RingPoint =
        RingPoint(x.toFloat(), y.toFloat(), z.toFloat(),
            n.x.toFloat(), n.y.toFloat(), n.z.toFloat())

    // ------------------------------------------------------------------
    // generic sector grid cell iteration (mirrors build()/buildBracket())
    // ------------------------------------------------------------------

    private fun forEachCell(
        startNorm: Float, sectorDegrees: Float,
        crossN: Int, degStep: Float, gridAnchor: Float,
        clipLo: Float, clipHi: Float,
        action: (CellCorner) -> Unit
    ) {
        val intervals = if (startNorm + sectorDegrees <= 360f)
            listOf(startNorm to startNorm + sectorDegrees)
        else
            listOf(startNorm to 360f, 0f to startNorm + sectorDegrees - 360f)
        for ((lo, hi) in intervals) {
            val wrap = if (lo == startNorm) 0f else 360f
            for (j in 0 until crossN) {
                val raw0 = gridAnchor + j * degStep
                val raw1 = gridAnchor + (j + 1) * degStep
                val cells = if (raw1 <= 360f) listOf(raw0 to raw1)
                else if (raw0 >= 360f) listOf(raw0 - 360f to raw1 - 360f)
                else listOf(raw0 to 360f, 0f to raw1 - 360f)
                for ((cg0, cg1) in cells) {
                    val s = max(max(cg0, lo), clipLo)
                    val e = min(min(cg1, hi), clipHi)
                    if (e <= s) continue
                    val f0 = (s + wrap - startNorm) / sectorDegrees
                    val f1 = (e + wrap - startNorm) / sectorDegrees
                    val a0 = Math.toRadians(s.toDouble())
                    val a1 = Math.toRadians(e.toDouble())
                    val c0 = cos(a0).toFloat()
                    val s0 = sin(a0).toFloat()
                    val c1 = cos(a1).toFloat()
                    val s1 = sin(a1).toFloat()
                    val midA = a0 + (a1 - a0) / 2.0
                    val cm = cos(midA).toFloat()
                    val sm = sin(midA).toFloat()
                    action(CellCorner(s, e, f0, f1, c0, s0, c1, s1, cm, sm))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // walls (per sector bucket, folded UVs, glass end-band fold)
    // ------------------------------------------------------------------

    // cross point with folded sprite uv (mirrors the mesh add() fold + the
    // vertex shader's glass V override) — t == mesh v for wall vertices
    private fun wallCorner(
        frame: WaterslideTubeMesh.TubeSegmentFrame, frameIndex: Int,
        wallPrefixArcs: FloatArray, totalArc: Float,
        ghost: Boolean, glass: Boolean, t: Float, x: Float, y: Float, u: Float,
        inner: Boolean, wallThickness: Float,
        texW: Float, texH: Float, border: Float, centerW: Float, centerH: Float,
        su0: Float, su1: Float, sv0: Float, sv1: Float,
        uTilesRaw: Float, uTiles: Float, cm: Float, sm: Float
    ): CornerPoint {
        val ev = evalFrame(frame, t)
        val radial = if (inner) max(ev.radius - WaterslideTubeMesh.BASE_WALL, 0.001f)
        else max(ev.radius + (wallThickness - WaterslideTubeMesh.BASE_WALL), 0.001f)
        val nx = if (inner) -cm else cm
        val ny = if (inner) -sm else sm
        val pos = ev.spine.add(ev.lateral.scale((x * radial).toDouble()))
            .add(ev.faceUp.scale((y * radial).toDouble()))
        val n = ev.lateral.scale(nx.toDouble()).add(ev.faceUp.scale(ny.toDouble()))
        // mesh add() uv fold
        val uFrac: Float
        val vFrac: Float
        if (glass) {
            val sPx = u * uTilesRaw * texW
            uFrac = when {
                sPx < border -> max(sPx, 0.05f) / texW
                sPx > uTilesRaw * texW - border ->
                    min(texW - border + (sPx - (uTilesRaw * texW - border)), texW - 0.05f) / texW
                else -> (border + (sPx % centerW)) / texW
            }
            val vPx = t * 0.5f * texH
            vFrac = ((border + (vPx % centerH)) % texH) / texH
        } else {
            val sPx = u * uTiles * texW
            uFrac = ((border + (sPx % centerW)) % texW) / texW
            val vPx = t * 0.5f * texH
            vFrac = ((border + (vPx % centerH)) % texH) / texH
        }
        var uAtlas = su0 + uFrac * (su1 - su0)
        var vAtlas = sv0 + vFrac * (sv1 - sv0)
        // glass wall: the shader overrides V inside the two end bands with the
        // tile's border rows (waterTileSpan > 1.5 path)
        if (glass && !ghost) {
            val chord = frame.currSpine.subtract(frame.prevSpine)
            val handle = chord.length() / 3.0
            val c0 = frame.prevSpine
            val c1 = frame.prevSpine.add(frame.prevTangent.scale(handle))
            val c2 = frame.currSpine.subtract(frame.currTangent.scale(handle))
            val c3 = frame.currSpine
            val arc = wallPrefixArcs[frameIndex] + arcLenTo(c0, c1, c2, c3, t)
            val total = max(totalArc, 0.1f)
            val gTexH = (((sv1 - sv0) * 1024f).roundToInt().toFloat()).coerceIn(1f, 64f)
            val shaderBorder = 2f
            var vPx = -1f
            if (arc < shaderBorder / 16f) {
                vPx = max(arc * 16f, 0.05f)
            } else if (arc > total - shaderBorder / 16f) {
                vPx = min(gTexH - shaderBorder + (arc - (total - shaderBorder / 16f)) * 16f, gTexH - 0.05f)
            }
            if (vPx >= 0f) {
                vAtlas = sv0 + (vPx / gTexH) * (sv1 - sv0)
            }
        }
        return CornerPoint(
            RingPoint(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat(),
                n.x.toFloat(), n.y.toFloat(), n.z.toFloat()),
            uAtlas, vAtlas
        )
    }

    private fun emitWalls(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        cutout: VertexConsumer, translucentBuf: VertexConsumer
    ) {
        val frames = c.frames
        val crossN = WaterslideTubeMesh.crossSections()
        val degStep = 360f / crossN
        val gridAnchor = 90f
        val wallThickness = net.omori_sunny.create_waterparked.config.ModConfig.wallThickness()
        val radius = c.meshRadius
        val totalArc = c.wallPrefixArcs[frames.size]
        val ghost = isEditing(level, be)
        val placed = WaterslideSectorLayout.place(c.config)
        val base = Vec3.atLowerCornerOf(be.blockPos)

        val frameLights = IntArray(frames.size)
        for (i in frames.indices) {
            val f = frames[i]
            val mid = f.prevSpine.add(f.currSpine).scale(0.5)
            frameLights[i] = tubeLight(level, mid.add(base))
        }

        for (p in placed) {
            val sec = p.sector
            if (sec.material == SectorMaterial.OPEN) continue
            val blockId = sec.blockId ?: continue
            val sprite = spriteFor(blockId) ?: continue
            val glass = isTranslucentBlock(blockId)
            val buf = if (glass || ghost) translucentBuf else cutout
            val texW = sprite.contents().width().toFloat()
            val texH = sprite.contents().height().toFloat()
            val border = if (glass) borderPxOf(sprite).toFloat() else ModConfig.sectorBorderPx().toFloat()
            val su0 = sprite.u0
            val su1 = sprite.u1
            val sv0 = sprite.v0
            val sv1 = sprite.v1
            val sectorDegrees = p.endAngle - p.startAngle
            if (sectorDegrees <= 0.001f) continue
            val sectorRadians = Math.toRadians(sectorDegrees.toDouble()).toFloat()
            val uTilesRaw = (radius - WaterslideTubeMesh.BASE_WALL).coerceAtLeast(0.1f) * sectorRadians
            val uTiles = max(uTilesRaw, 1f)
            val centerW = max(texW - 2f * border, 1f)
            val centerH = max(texH - 2f * border, 1f)
            val startNorm = WaterslideSectorLayout.normalize(p.startAngle)

            forEachCell(startNorm, sectorDegrees, crossN, degStep, gridAnchor, 0f, 360f) { cell ->
                for (k in 0 until LENGTH_SUBDIVISIONS) {
                    val z0 = k / (2f * LENGTH_SUBDIVISIONS)
                    val z1 = (k + 1) / (2f * LENGTH_SUBDIVISIONS)
                    val t0 = z0 * 2f
                    val t1 = z1 * 2f
                    for (fi in frames.indices) {
                        val frame = frames[fi]
                        val light = frameLights[fi]
                        val alpha = if (ghost) 0.35f else 1f
                        // inner wall first, translucent buckets blend in mesh order
                        val ia = wallCorner(frame, fi, c.wallPrefixArcs, totalArc, ghost, glass,
                            t0, cell.c0, cell.s0, cell.f0, true, wallThickness,
                            texW, texH, border, centerW, centerH, su0, su1, sv0, sv1, uTilesRaw, uTiles, cell.cm, cell.sm)
                        val ib = wallCorner(frame, fi, c.wallPrefixArcs, totalArc, ghost, glass,
                            t1, cell.c0, cell.s0, cell.f0, true, wallThickness,
                            texW, texH, border, centerW, centerH, su0, su1, sv0, sv1, uTilesRaw, uTiles, cell.cm, cell.sm)
                        val id = wallCorner(frame, fi, c.wallPrefixArcs, totalArc, ghost, glass,
                            t1, cell.c1, cell.s1, cell.f1, true, wallThickness,
                            texW, texH, border, centerW, centerH, su0, su1, sv0, sv1, uTilesRaw, uTiles, cell.cm, cell.sm)
                        val ie = wallCorner(frame, fi, c.wallPrefixArcs, totalArc, ghost, glass,
                            t0, cell.c1, cell.s1, cell.f1, true, wallThickness,
                            texW, texH, border, centerW, centerH, su0, su1, sv0, sv1, uTilesRaw, uTiles, cell.cm, cell.sm)
                        // opaque cutout buckets are double sided (backfaceCulling
                        // false), glass buckets single sided (cull on), ghost too
                        emitWallQuad(buf, ia, ib, id, ie, !glass || ghost, light, alpha)
                        // outer wall (drawn last)
                        val oa = wallCorner(frame, fi, c.wallPrefixArcs, totalArc, ghost, glass,
                            t0, cell.c0, cell.s0, cell.f0, false, wallThickness,
                            texW, texH, border, centerW, centerH, su0, su1, sv0, sv1, uTilesRaw, uTiles, cell.cm, cell.sm)
                        val ob = wallCorner(frame, fi, c.wallPrefixArcs, totalArc, ghost, glass,
                            t0, cell.c1, cell.s1, cell.f1, false, wallThickness,
                            texW, texH, border, centerW, centerH, su0, su1, sv0, sv1, uTilesRaw, uTiles, cell.cm, cell.sm)
                        val od = wallCorner(frame, fi, c.wallPrefixArcs, totalArc, ghost, glass,
                            t1, cell.c1, cell.s1, cell.f1, false, wallThickness,
                            texW, texH, border, centerW, centerH, su0, su1, sv0, sv1, uTilesRaw, uTiles, cell.cm, cell.sm)
                        val oe = wallCorner(frame, fi, c.wallPrefixArcs, totalArc, ghost, glass,
                            t1, cell.c0, cell.s0, cell.f0, false, wallThickness,
                            texW, texH, border, centerW, centerH, su0, su1, sv0, sv1, uTilesRaw, uTiles, cell.cm, cell.sm)
                        emitWallQuad(buf, oa, ob, od, oe, !glass || ghost, light, alpha)
                    }
                }
            }

            // side walls next to open sectors (folded into this sector's mesh,
            // bucket only — never into the composite wallVerts)
            if (!ghost) {
                val idx = placed.indexOf(p)
                val prev = placed[(idx - 1 + placed.size) % placed.size]
                val next = placed[(idx + 1) % placed.size]
                val prevOpenLike = prev.sector.material == SectorMaterial.OPEN ||
                    (!glass && prev.sector.blockId != null &&
                        isTranslucentBlock(prev.sector.blockId))
                val nextOpenLike = next.sector.material == SectorMaterial.OPEN ||
                    (!glass && next.sector.blockId != null &&
                        isTranslucentBlock(next.sector.blockId))
                if (prevOpenLike) {
                    emitSideWall(frames, frameLights, buf, p.startAngle, -1f,
                        wallThickness, radius, texW, texH, border, su0, su1, sv0, sv1,
                        centerW, centerH, glass)
                }
                if (nextOpenLike) {
                    emitSideWall(frames, frameLights, buf, p.endAngle, 1f,
                        wallThickness, radius, texW, texH, border, su0, su1, sv0, sv1,
                        centerW, centerH, glass)
                }
            }
        }
    }

    // double-sided (cutout/translucent material with backfaceCulling(false)) vs
    // single-sided (glass GLASS_TRANSLUCENT backfaceCulling(true))
    private fun emitWallQuad(
        buf: VertexConsumer,
        a: CornerPoint, b: CornerPoint, d: CornerPoint, e: CornerPoint,
        doubleSided: Boolean, light: Int, alpha: Float
    ) {
        if (doubleSided) {
            verts4Color(buf, a.p, b.p, d.p, e.p,
                a.u, a.v, b.u, b.v, d.u, d.v, e.u, e.v, light, 1f, 1f, 1f, alpha)
            verts4Color(buf, a.p, e.p, d.p, b.p,
                a.u, a.v, e.u, e.v, d.u, d.v, b.u, b.v, light, 1f, 1f, 1f, alpha)
        } else {
            verts4Color(buf, a.p, b.p, d.p, e.p,
                a.u, a.v, b.u, b.v, d.u, d.v, e.u, e.v, light, 1f, 1f, 1f, alpha)
        }
    }

    private fun emitSideWall(
        frames: List<WaterslideTubeMesh.TubeSegmentFrame>, frameLights: IntArray, buf: VertexConsumer,
        angleDeg: Float, dir: Float,
        wallThickness: Float, radius: Float,
        texW: Float, texH: Float, border: Float, su0: Float, su1: Float, sv0: Float, sv1: Float,
        centerW: Float, centerH: Float, glass: Boolean
    ) {
        val a = Math.toRadians(angleDeg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val nx = c
        val ny = s
        val innerR = 0.92f
        // mesh addSideWall: tiny sectorRadians so uTiles clamps to 1
        val sideRadians = 0.2f / 16f
        val uTilesS = max((radius - WaterslideTubeMesh.BASE_WALL).coerceAtLeast(0.1f) * sideRadians, 1f)

        fun uAtlas(u: Float): Float {
            val sPx = u * uTilesS * texW
            val f = ((border + (sPx % centerW)) % texW) / texW
            return su0 + f * (su1 - su0)
        }

        fun vAtlas(v: Float): Float {
            val vPx = v * 0.5f * texH
            val f = ((border + (vPx % centerH)) % texH) / texH
            return sv0 + f * (sv1 - sv0)
        }

        for (k in 0 until LENGTH_SUBDIVISIONS) {
            val z0 = k / (2f * LENGTH_SUBDIVISIONS)
            val z1 = (k + 1) / (2f * LENGTH_SUBDIVISIONS)
            val v0 = k / LENGTH_SUBDIVISIONS.toFloat()
            val v1 = (k + 1) / LENGTH_SUBDIVISIONS.toFloat()
            for (fi in frames.indices) {
                val frame = frames[fi]
                val light = frameLights[fi]
                fun pt(t: Float, rFactor: Float): RingPoint {
                    val ev = evalFrame(frame, t)
                    val radial = if (rFactor < 1f) max(ev.radius - WaterslideTubeMesh.BASE_WALL, 0.001f)
                    else max(ev.radius + (wallThickness - WaterslideTubeMesh.BASE_WALL), 0.001f)
                    val x = c * rFactor
                    val y = s * rFactor
                    val pos = ev.spine.add(ev.lateral.scale((x * radial).toDouble()))
                        .add(ev.faceUp.scale((y * radial).toDouble()))
                    val n = ev.lateral.scale(nx.toDouble()).add(ev.faceUp.scale(ny.toDouble()))
                    return RingPoint(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat(),
                        n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
                }
                if (dir > 0f) {
                    val p0 = pt(z0 * 2f, 1f)
                    val p1 = pt(z0 * 2f, innerR)
                    val p2 = pt(z1 * 2f, innerR)
                    val p3 = pt(z1 * 2f, 1f)
                    verts4(buf, p0, p1, p2, p3,
                        uAtlas(0f), vAtlas(v0), uAtlas(1f), vAtlas(v0),
                        uAtlas(1f), vAtlas(v1), uAtlas(0f), vAtlas(v1), light)
                    if (!glass) {
                        verts4(buf, p0, p3, p2, p1,
                            uAtlas(0f), vAtlas(v0), uAtlas(0f), vAtlas(v1),
                            uAtlas(1f), vAtlas(v1), uAtlas(1f), vAtlas(v0), light)
                    }
                } else {
                    val p0 = pt(z0 * 2f, 1f)
                    val p1 = pt(z1 * 2f, 1f)
                    val p2 = pt(z1 * 2f, innerR)
                    val p3 = pt(z0 * 2f, innerR)
                    verts4(buf, p0, p1, p2, p3,
                        uAtlas(0f), vAtlas(v0), uAtlas(0f), vAtlas(v1),
                        uAtlas(1f), vAtlas(v1), uAtlas(1f), vAtlas(v0), light)
                    if (!glass) {
                        verts4(buf, p0, p3, p2, p1,
                            uAtlas(0f), vAtlas(v0), uAtlas(1f), vAtlas(v0),
                            uAtlas(1f), vAtlas(v1), uAtlas(0f), vAtlas(v1), light)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // caps (only at true open ends) and skeleton rings
    // ------------------------------------------------------------------

    private fun capCorner(
        spine: Vec3, lateral: Vec3, tangent: Vec3, radius: Float,
        x: Float, y: Float, u: Float, v: Float,
        inner: Boolean, capNormal: Vec3,
        glass: Boolean, wallThickness: Float,
        texW: Float, texH: Float, border: Float, centerW: Float, centerH: Float,
        su0: Float, su1: Float, sv0: Float, sv1: Float,
        uTilesRaw: Float, uTiles: Float
    ): CornerPoint {
        val radial = if (inner) max(radius - WaterslideTubeMesh.BASE_WALL, 0.001f)
        else max(radius + (wallThickness - WaterslideTubeMesh.BASE_WALL), 0.001f)
        val up = tangent.cross(lateral).normalize()
        val pos = spine.add(lateral.scale((x * radial).toDouble()))
            .add(up.scale((y * radial).toDouble()))
        val uFrac: Float
        val vFrac: Float
        if (glass) {
            val sPx = u * uTilesRaw * texW
            uFrac = when {
                sPx < border -> max(sPx, 0.05f) / texW
                sPx > uTilesRaw * texW - border ->
                    min(texW - border + (sPx - (uTilesRaw * texW - border)), texW - 0.05f) / texW
                else -> (border + (sPx % centerW)) / texW
            }
            // cap radial three zone fold
            val vPx = v * (wallThickness * 16f)
            vFrac = when {
                vPx < border -> max(vPx, 0.05f) / texH
                vPx > wallThickness * 16f - border ->
                    min(texH - border + (vPx - (wallThickness * 16f - border)), texH - 0.05f) / texH
                else -> (border + (vPx % centerH)) / texH
            }
        } else {
            val sPx = u * uTiles * texW
            uFrac = ((border + (sPx % centerW)) % texW) / texW
            val vPx = v * 0.5f * texH
            vFrac = ((border + (vPx % centerH)) % texH) / texH
        }
        val uAtlas = su0 + uFrac * (su1 - su0)
        val vAtlas = sv0 + vFrac * (sv1 - sv0)
        return CornerPoint(
            RingPoint(pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat(),
                capNormal.x.toFloat(), capNormal.y.toFloat(), capNormal.z.toFloat()),
            uAtlas, vAtlas
        )
    }

    private fun emitCapQuads(
        buf: VertexConsumer, level: Level, base: Vec3,
        spine: Vec3, lateral: Vec3, tangent: Vec3, radius: Float,
        config: WaterslideSectorConfig, meshRadius: Float,
        start: Boolean, ghost: Boolean, doubleSided: Boolean, light: Int
    ) {
        val crossN = WaterslideTubeMesh.crossSections()
        val degStep = 360f / crossN
        val gridAnchor = 90f
        val wallThickness = net.omori_sunny.create_waterparked.config.ModConfig.wallThickness()
        val placed = WaterslideSectorLayout.place(config)
        val capNormal = if (start) tangent.scale(-1.0) else tangent
        for (p in placed) {
            val sec = p.sector
            if (sec.material == SectorMaterial.OPEN) continue
            val blockId = sec.blockId ?: continue
            val sprite = spriteFor(blockId) ?: continue
            val glass = isTranslucentBlock(blockId)
            val texW = sprite.contents().width().toFloat()
            val texH = sprite.contents().height().toFloat()
            val border = if (glass) borderPxOf(sprite).toFloat() else ModConfig.sectorBorderPx().toFloat()
            val su0 = sprite.u0; val su1 = sprite.u1
            val sv0 = sprite.v0; val sv1 = sprite.v1
            val sectorDegrees = p.endAngle - p.startAngle
            if (sectorDegrees <= 0.001f) continue
            val sectorRadians = Math.toRadians(sectorDegrees.toDouble()).toFloat()
            val uTilesRaw = (meshRadius - WaterslideTubeMesh.BASE_WALL).coerceAtLeast(0.1f) * sectorRadians
            val uTiles = max(uTilesRaw, 1f)
            val centerW = max(texW - 2f * border, 1f)
            val centerH = max(texH - 2f * border, 1f)
            val startNorm = WaterslideSectorLayout.normalize(p.startAngle)
            forEachCell(startNorm, sectorDegrees, crossN, degStep, gridAnchor, 0f, 360f) { cell ->
                // annulus: inner (v = 0) to outer (v = 1) radially
                val alpha = if (ghost) 0.35f else 1f
                if (start) {
                    // (c0 outer v1) (c0 inner v0) (c1 inner v0) (c1 outer v1)
                    val a = capCorner(spine, lateral, tangent, radius,
                        cell.c0, cell.s0, cell.f0, 1f, false, capNormal,
                        glass, wallThickness, texW, texH, border, centerW, centerH,
                        su0, su1, sv0, sv1, uTilesRaw, uTiles)
                    val b = capCorner(spine, lateral, tangent, radius,
                        cell.c0, cell.s0, cell.f0, 0f, true, capNormal,
                        glass, wallThickness, texW, texH, border, centerW, centerH,
                        su0, su1, sv0, sv1, uTilesRaw, uTiles)
                    val d = capCorner(spine, lateral, tangent, radius,
                        cell.c1, cell.s1, cell.f1, 0f, true, capNormal,
                        glass, wallThickness, texW, texH, border, centerW, centerH,
                        su0, su1, sv0, sv1, uTilesRaw, uTiles)
                    val e = capCorner(spine, lateral, tangent, radius,
                        cell.c1, cell.s1, cell.f1, 1f, false, capNormal,
                        glass, wallThickness, texW, texH, border, centerW, centerH,
                        su0, su1, sv0, sv1, uTilesRaw, uTiles)
                    emitWallQuad(buf, a, b, d, e, doubleSided, light, alpha)
                } else {
                    // (c0 outer v1) (c1 outer v1) (c1 inner v0) (c0 inner v0)
                    val a = capCorner(spine, lateral, tangent, radius,
                        cell.c0, cell.s0, cell.f0, 1f, false, capNormal,
                        glass, wallThickness, texW, texH, border, centerW, centerH,
                        su0, su1, sv0, sv1, uTilesRaw, uTiles)
                    val b = capCorner(spine, lateral, tangent, radius,
                        cell.c1, cell.s1, cell.f1, 1f, false, capNormal,
                        glass, wallThickness, texW, texH, border, centerW, centerH,
                        su0, su1, sv0, sv1, uTilesRaw, uTiles)
                    val d = capCorner(spine, lateral, tangent, radius,
                        cell.c1, cell.s1, cell.f1, 0f, true, capNormal,
                        glass, wallThickness, texW, texH, border, centerW, centerH,
                        su0, su1, sv0, sv1, uTilesRaw, uTiles)
                    val e = capCorner(spine, lateral, tangent, radius,
                        cell.c0, cell.s0, cell.f0, 0f, true, capNormal,
                        glass, wallThickness, texW, texH, border, centerW, centerH,
                        su0, su1, sv0, sv1, uTilesRaw, uTiles)
                    emitWallQuad(buf, a, b, d, e, doubleSided, light, alpha)
                }
            }
        }
    }

    private fun emitCaps(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        cutout: VertexConsumer, translucentBuf: VertexConsumer
    ) {
        val frames = c.frames
        if (frames.size < 2) return
        val first = frames[0]
        val last = frames[frames.size - 1]
        val ghost = isEditing(level, be)
        val buf = if (ghost) translucentBuf else cutout
        val base = Vec3.atLowerCornerOf(be.blockPos)

        if (isOpenEnd(level, c.bc.bePositions.first)) {
            val tip = first.prevSpine
            val tan = first.prevTangent
            val light = tubeLight(level, tip.add(base))
            emitCapQuads(buf, level, base, tip, first.prevLateral, tan, first.prevRadius,
                c.config, c.meshRadius, start = true, ghost = ghost,
                doubleSided = ghost, light = light)
        }
        if (isOpenEnd(level, c.bc.bePositions.second)) {
            val tip = last.currSpine
            val tan = last.currTangent
            val light = tubeLight(level, tip.add(base))
            emitCapQuads(buf, level, base, tip, last.currLateral, tan, last.currRadius,
                c.config, c.meshRadius, start = false, ghost = ghost,
                doubleSided = ghost, light = light)
        }
    }

    // Ponder edit UI: exact mirror of the real editor UI built from the shared
    // editor API (drawAnchorCircle / renderControlPoints / drawHandleTip)
    private fun emitPonderEditUi(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        poseStack: PoseStack, buffers: MultiBufferSource
    ) {
        // draw into the BER's own tubeTranslucent buffer: custom editor RenderTypes
        // are not stable on the Ponder SuperRenderTypeBuffer, the entity type is
        val mat = poseStack.last().pose()
        val buf = buffers.getBuffer(tubeTranslucent)
        val uv = whiteSpriteUv()
        val crossN = WaterslideTubeMesh.crossSections()
        val ringSegs = 24
        val frames = c.frames
        val endFrames = listOf(
            Pair(frames.first(), c.bc.bePositions.first),
            Pair(frames.last(), c.bc.bePositions.second)
        )
        for ((endFrame, anchor) in endFrames) {
            val lat = endFrame.prevLateral
            val tan = endFrame.prevTangent
            if (lat.lengthSqr() < 1.0E-12) continue
            val up = tan.cross(lat).normalize()
            if (up.lengthSqr() < 1.0E-12) continue
            val centerLocal = endFrame.prevSpine
            val latN = lat.normalize()
            val radius = WaterslideRadiusEdit.radiusAt(level, anchor, ModConfig.defaultSlideRadius())
            val light = tubeLight(level, centerLocal.add(Vec3.atLowerCornerOf(be.blockPos)))
            // tube opening ring, same as WaterslideRadiusEdit.drawAnchorCircle
            ringQuadsLocal(buf, mat, centerLocal, latN, up, radius, ringSegs, 0.0, uv, light,
                0.2f, 0.9f, 1.0f, 1.0f)
            // editor control ring, same as WaterslideSectorEdit.renderControlPoints
            val ringRadius = radius + CONTROL_RING_OFFSET
            ringQuadsLocal(buf, mat, centerLocal, latN, up, ringRadius, crossN, 90.0, uv, light,
                0.15f, 0.85f, 1.0f, 1.0f)
            // sector control points + boundary handles, same as controlPoints()
            val anchorBe = level.getBlockEntity(anchor) as? WaterslideAnchorBlockEntity
            val peer = if (c.bc.bePositions.first == anchor) c.bc.bePositions.second else c.bc.bePositions.first
            if (anchorBe != null) {
                val placed = WaterslideSectorLayout.place(anchorBe.sectorConfigFor(peer))
                for (p in placed) {
                    val rad = Math.toRadians(p.centerAngle.toDouble())
                    val pos = centerLocal.add(latN.scale(Math.cos(rad) * ringRadius)).add(up.scale(Math.sin(rad) * ringRadius))
                    diamondQuadLocal(buf, mat, pos, latN, up, 0.11f, uv, light, 1f, 0.9f, 0.1f)
                }
                if (placed.size >= 2) {
                    val boundaryRadius = ringRadius + BOUNDARY_RING_GAP
                    val seen = HashSet<Float>()
                    for (p in placed) {
                        val angle = WaterslideSectorLayout.normalize(p.endAngle)
                        if (!seen.add(angle)) continue
                        val rad = Math.toRadians(angle.toDouble())
                        val pos = centerLocal.add(latN.scale(Math.cos(rad) * boundaryRadius)).add(up.scale(Math.sin(rad) * boundaryRadius))
                        val normal = latN.scale(Math.cos(rad)).add(up.scale(Math.sin(rad))).normalize()
                        diamondQuadLocal(buf, mat, pos, normal, up, 0.11f, uv, light, 1f, 1f, 1f)
                    }
                }
            }
            // lift handle sprite, same as WaterslideRadiusEdit.drawHandleTip
            val tip = centerLocal.add(latN.scale(radius.toDouble()))
            diamondQuadLocal(buf, mat, tip, latN, up, 0.22f, uv, light, 1f, 0.9f, 0.1f)
        }
    }

    private fun ringQuadsLocal(
        buf: VertexConsumer, mat: org.joml.Matrix4f,
        center: Vec3, lat: Vec3, up: Vec3, radius: Float, segments: Int,
        startDeg: Double, uv: FloatArray, light: Int, r: Float, g: Float, b: Float, a: Float
    ) {
        val degStep = 360.0 / segments
        for (i in 0 until segments) {
            val a0 = Math.toRadians(startDeg + i * degStep)
            val a1 = Math.toRadians(startDeg + (i + 1) * degStep)
            val p0 = center.add(lat.scale(Math.cos(a0) * radius)).add(up.scale(Math.sin(a0) * radius))
            val p1 = center.add(lat.scale(Math.cos(a1) * radius)).add(up.scale(Math.sin(a1) * radius))
            val am = (a0 + a1) / 2.0
            val w = lat.scale(Math.cos(am)).add(up.scale(Math.sin(am))).normalize().scale(0.045)
            coloredQuadLocal(buf, mat, p0.subtract(w), p0.add(w), p1.add(w), p1.subtract(w),
                uv, light, r, g, b, a)
        }
    }

    private fun diamondQuadLocal(
        buf: VertexConsumer, mat: org.joml.Matrix4f,
        center: Vec3, ax: Vec3, ay: Vec3, half: Float,
        uv: FloatArray, light: Int, r: Float, g: Float, b: Float
    ) {
        val h = half.toDouble()
        coloredQuadLocal(buf, mat,
            center.add(ax.scale(h)), center.add(ay.scale(h)),
            center.subtract(ax.scale(h)), center.subtract(ay.scale(h)),
            uv, light, r, g, b, 1f)
    }

    private fun coloredQuadLocal(
        buf: VertexConsumer, mat: org.joml.Matrix4f,
        p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3,
        uv: FloatArray, light: Int, r: Float, g: Float, b: Float, a: Float
    ) {
        for (p in listOf(p0, p1, p2, p3)) {
            buf.addVertex(mat, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                .setColor(r, g, b, a)
                .setUv(uv[0], uv[1])
                .setLight(light)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setNormal(0f, 1f, 0f)
        }
    }

    private var whiteUvCache: FloatArray? = null

    private fun whiteSpriteUv(): FloatArray {
        val cached = whiteUvCache
        if (cached != null) return cached
        val sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(ResourceLocation.withDefaultNamespace("block/white_concrete"))
        val uv = floatArrayOf((sprite.u0 + sprite.u1) * 0.5f, (sprite.v0 + sprite.v1) * 0.5f)
        whiteUvCache = uv
        return uv
    }

    // skeleton rings: endCap mesh at interior junctions (translucent ghost only)
    private fun emitSkeleton(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        translucentBuf: VertexConsumer
    ) {
        if (!isEditing(level, be)) return
        // skeleton rings hidden in Ponder: the storyboard shows ring/handle UI instead
        if (PonderSlideEditUiElement.ponderEditAnchorFor(level) != null) return
        if (!ModClientConfig.showSkeletonWhenTranslucent()) return
        val frames = c.frames
        if (frames.size < 2) return
        val base = Vec3.atLowerCornerOf(be.blockPos)
        // end rings included: the storyboard edit state reads the opening rings as the UI
        for (i in 0 until frames.size) {
            val f = frames[i]
            val junction = f.prevSpine
            val tan = f.prevTangent
            val light = tubeLight(level, junction.add(base))
            emitCapQuads(translucentBuf, level, base, junction, f.prevLateral, tan, f.prevRadius,
                c.config, c.meshRadius, start = false, ghost = true, doubleSided = true, light = light)
        }
    }

    // ------------------------------------------------------------------
    // water band (bandVertices bed + surface, phase scroll, jitter FBM)
    // ------------------------------------------------------------------

    private fun frameIndexAtArc(arc: Float, waterFrames: List<WaterslideTubeMesh.TubeSegmentFrame>): Int {
        var idx = floor(arc / 0.5f).toInt()
        if (idx < 0) idx = 0
        if (idx >= waterFrames.size) idx = waterFrames.size - 1
        return idx
    }

    private fun emitWaterBand(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        buf: VertexConsumer, now: Float
    ) {
        val water = c.water ?: return
        if (!water.exists) return
        val segments = water.segments
        if (segments.isEmpty()) return
        val scale = ModClientConfig.waterFlowScale()
        val wallThickness = net.omori_sunny.create_waterparked.config.ModConfig.wallThickness()
        val tint = waterTint()
        for (i in segments.indices) {
            val seg = segments[i]
            val nxt = if (i + 1 < segments.size) segments[i + 1] else seg
            emitBandSegment(be, level, c, seg.arc, seg.speed, nxt.speed,
                wallThickness, scale, tint, buf, now)
            if (i + 1 < segments.size) {
                val gap = nxt.arc - seg.arc
                if (gap > 0.75f) {
                    val midArc = seg.arc + gap * 0.5f
                    val midSpeed = (seg.speed + nxt.speed) * 0.5f
                    emitBandSegment(be, level, c, midArc, midSpeed, nxt.speed,
                        wallThickness, scale, tint, buf, now)
                }
            }
        }
    }

    private fun emitBandSegment(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        arc: Float, speed: Float, speedNext: Float,
        wallThickness: Float, scale: Float, tint: FloatArray,
        buf: VertexConsumer, now: Float
    ) {
        val segForward = speed >= 0f
        val frameIdx = frameIndexAtArc(arc, c.waterFrames)
        val f = c.waterFrames[frameIdx]
        val frameRadius = max(0.1f, (f.prevRadius + f.currRadius) * 0.5f)
        val ring = WaterslideTubeMesh.bandVertices(WATER_IN_FRAC, WATER_SURF_FRAC, !segForward)
        val nA = ring.size / 2
        val ps: Vec3; val cs: Vec3; val pt: Vec3; val ct: Vec3
        val pl: Vec3; val cl: Vec3; val pr: Float; val cr: Float
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
        val seg = WaterslideTubeMesh.TubeSegmentFrame(ps, cs, pt, ct, pl, cl, pr, cr)
        val k = WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK * scale
        val ownFlow = abs(speed) * k / 40f
        val flow = if (segForward) ownFlow else abs(speedNext) * k / 40f
        val flowEnd = if (segForward) abs(speedNext) * k / 40f else ownFlow
        val arcBase = if (segForward) c.waterPrefixArcs[frameIdx]
        else c.waterTotalArc - c.waterPrefixArcs[frameIdx + 1]
        val phase = (now * ownFlow) % 1.0f
        val tiles = (2f * Math.PI.toFloat() * frameRadius * (330f - 210f) / 360f).coerceAtLeast(0.5f)
        val light = waterLight(level, ps.add(cs).scale(0.5).add(Vec3.atLowerCornerOf(be.blockPos)))
        emitWaterSheet(
            seg, ring, nA, tiles, arcBase, flow, flowEnd, phase,
            waterJitterScale(), ModClientConfig.waterJitterFrequency(),
            ModClientConfig.waterJitterTimeScale(), now, light, wallThickness,
            tint, buf, -1f, -1f
        )
    }

    // one water sheet (band segment or stream segment): the mesh quad order
    private fun emitWaterSheet(
        seg: WaterslideTubeMesh.TubeSegmentFrame,
        ring: List<Float>, nA: Int,
        tiles: Float, arcBase: Float, flow: Float, flowEnd: Float,
        phase: Float, jitterScale: Float, jitterFrequency: Float, jitterTimeScale: Float,
        now: Float, light: Int, wallThickness: Float, tint: FloatArray,
        buf: VertexConsumer, fadeStart: Float, fadeEnd: Float
    ) {
        val spr = WaterslideTubeMesh.waterSpriteRect()
        val su0 = spr[0]; val su1 = spr[1]; val sv0 = spr[2]; val sv1 = spr[3]
        val chord = seg.currSpine.subtract(seg.prevSpine)
        val handle = chord.length() / 3.0
        val c0 = seg.prevSpine
        val c1 = seg.prevSpine.add(seg.prevTangent.scale(handle))
        val c2 = seg.currSpine.subtract(seg.currTangent.scale(handle))
        val c3 = seg.currSpine
        val segArc = arcLenTo(c0, c1, c2, c3, 1f)
        val shaderUpNormals = IrisColorwheelCompat.waterShadingActive()
        val hasFade = fadeEnd > fadeStart + 0.0001f

        fun ringVertex(idx: Int, row0: Boolean): RingPoint {
            val rowZ = if (row0) 0f else 0.5f
            val x = ring[idx * 2]
            val y = ring[idx * 2 + 1]
            val t = rowZ * 2f
            val ev = evalFrame(seg, t)
            val r = sqrt(x * x + y * y).coerceAtLeast(0.001f)
            val dir = ev.lateral.scale(x.toDouble()).add(ev.faceUp.scale(y.toDouble()))
            val dirLen = dir.length().coerceAtLeast(1.0E-8)
            val radial = dir.scale(1.0 / dirLen)
            val r0 = r * ev.radius
            var worldBase = ev.spine.add(radial.scale(r0.toDouble()))
            if (jitterScale > 0.0001f) {
                val speedT = flow + (flowEnd - flow) * t
                val flowJitter = speedT.coerceIn(0f, 1f)
                val timePhase = now * jitterTimeScale * speedT
                val ang = atan2(y, x)
                val angKey = (cos(2.0 * ang) * 2.0f).toFloat()
                val tangSign = (cos(ang).toFloat() / 0.85f).coerceIn(-1f, 1f)
                val npX = ev.spine.x.toFloat() * jitterFrequency
                val npY = ev.spine.y.toFloat() * jitterFrequency + angKey
                val npZ = ev.spine.z.toFloat() * jitterFrequency
                val nRadial = jitterFbm(npX, npY, npZ + timePhase)
                val nTang = jitterFbm(npX + 5.2f, npY + 1.3f, npZ + timePhase * 1.3f) * tangSign
                val amp = min(flowJitter * 1.0f * jitterScale * 0.25f, 0.06f)
                var radialOff = (nRadial * 2f - 1f) * amp
                val tangOff = (nTang * 2f - 1f) * amp * 0.6f
                val maxOut = max(ev.radius - wallThickness - r0, 0f)
                radialOff = radialOff.coerceIn(-r0, maxOut)
                val tangential = ev.tangent.cross(radial)
                worldBase = worldBase.add(radial.scale(radialOff.toDouble()))
                    .add(tangential.scale(tangOff.toDouble()))
            }
            val nx = if (shaderUpNormals) 0f else x / r
            val ny = if (shaderUpNormals) 1f else y / r
            val n = ev.lateral.scale(nx.toDouble()).add(ev.faceUp.scale(ny.toDouble()))
            return RingPoint(worldBase.x.toFloat(), worldBase.y.toFloat(), worldBase.z.toFloat(),
                n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
        }

        fun uvAt(idx: Int, row0: Boolean): Pair<Float, Float> {
            val uTex = idx.toFloat() / nA * tiles
            val vf = if (!row0) 1f else 0f
            val basesArc = if (!row0) segArc else 0f
            // downstreamMix = 1 -> the frag mixes to the down sample;
            // flowSign = -1 subtracts the phase
            val vSpan = (arcBase + basesArc) - phase * 1f
            val u = su0 + frac(uTex) * (su1 - su0)
            val v = sv0 + frac(vSpan) * (sv1 - sv0)
            return u to v
        }

        for (i in 0 until nA - 1) {
            // mesh order: (z0,i) (z1,i) (z1,i+1) (z0,i+1)
            val a0 = ringVertex(i, true)
            val a1 = ringVertex(i, false)
            val b1 = ringVertex(i + 1, false)
            val b0 = ringVertex(i + 1, true)
            val ua = uvAt(i, true); val ub = uvAt(i + 1, true)
            val uc = uvAt(i + 1, false); val ud = uvAt(i, false)
            val alphaZ0: Float
            val alphaZ1: Float
            if (hasFade) {
                // streamArc = arcBase + t * 0.5 (fixed 0.5 arc step per segment)
                alphaZ0 = 0.75f * (1f - smoothstep(fadeStart, fadeEnd, arcBase + 0f * 0.5f))
                alphaZ1 = 0.75f * (1f - smoothstep(fadeStart, fadeEnd, arcBase + 0.5f))
            } else {
                alphaZ0 = 0.75f
                alphaZ1 = 0.75f
            }
            // bottom band: water bed arc
            waterQuadDouble(buf, a0, a1, b1, b0,
                ua.first, ua.second, ud.first, ud.second, uc.first, uc.second, ub.first, ub.second,
                light, tint[0], tint[1], tint[2], alphaZ0, alphaZ1, alphaZ1, alphaZ0)
            // top band: water surface arc
            waterQuadDouble(buf, a0, a1, b1, b0,
                ua.first, ua.second, ud.first, ud.second, uc.first, uc.second, ub.first, ub.second,
                light, tint[0], tint[1], tint[2], alphaZ0, alphaZ1, alphaZ1, alphaZ0)
        }
    }

    // ------------------------------------------------------------------
    // thrown stream (predictStreams + tail fade + jitter ramp)
    // ------------------------------------------------------------------

    private fun buildStreamSegments(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        water: WaterFlowSimulation.CurveWater, streamForward: Boolean
    ): List<StreamSeg>? {
        val exit = water.exit ?: return null
        if (c.waterFrames.isEmpty()) return null
        val outlet = if (streamForward) c.waterFrames[c.waterFrames.size - 1] else c.waterFrames[0]
        val outletCenter = if (streamForward) outlet.currSpine else outlet.prevSpine
        val outletTan = if (streamForward) outlet.currTangent else outlet.prevTangent.scale(-1.0)
        val lat0 = if (streamForward) outlet.currLateral else outlet.prevLateral.scale(-1.0)
        var up0 = outletTan.cross(lat0)
        if (up0.lengthSqr() < 1.0E-9) up0 = Vec3(0.0, 1.0, 0.0)
        up0 = up0.normalize()
        val radius = if (streamForward) outlet.currRadius else outlet.prevRadius
        val rIn = max(0.05f, radius - STREAM_WALL_THICKNESS * 1.5f)
        val rSurf = max(0.01f, rIn - min(0.25f, radius * 0.25f))
        var c0 = 210f
        var c1 = 330f
        if (!streamForward) {
            val t = c0
            c0 = -c1
            c1 = -t
        }
        val origin = Vec3.atLowerCornerOf(be.blockPos)
        val own = ArrayList<Vec3>()
        for (f in c.frames) {
            own.add(f.prevSpine.add(origin))
            own.add(f.currSpine.add(origin))
        }
        val throwSpeed = exit.vel.length()
        val throwVel = outletTan.scale(throwSpeed)
        val res = WaterFlowSimulation.predictStreams(
            level, exit.pos, throwVel, outletCenter.add(origin), lat0, up0,
            rIn, rSurf, c0, c1, own, Vec3(0.0, -32.0, 0.0), SlideSpace.Main
        ) ?: return null
        val outer = res.first
        val inner = res.second
        if (outer.isEmpty() || inner.isEmpty()) return null
        var bestRay = 0
        var bestLen = outer[0].size
        for (i in 1 until outer.size) {
            if (outer[i].size > bestLen) {
                bestRay = i
                bestLen = outer[i].size
            }
        }
        val o = outer[bestRay]
        val inn = inner[bestRay]
        if (o.size < 2 || inn.size < 2) return null
        val samples = o.size
        val streamMaxSegments = max(4, (48 * ModClientConfig.polygonScale()).roundToInt())
        var streamLen = 0f
        for (kk in 1 until samples) {
            streamLen += o[kk].distanceTo(o[kk - 1]).toFloat()
        }
        val desired = max(streamMaxSegments, ceil(streamLen / 0.5).toInt())
        val stride = max(1, (samples - 1) / desired)
        val dirWorld = o[0].subtract(inn[0]).normalize()
        val tubeRadius = radius
        val centers = ArrayList<Vec3>()
        var kk = 0
        while (kk < samples) {
            val worldCenter = o[kk].subtract(dirWorld.scale(rIn.toDouble()))
            centers.add(worldCenter.subtract(origin))
            kk += stride
        }
        if (centers.size < 2) return null
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
            val tan = tans[i]!!
            var l = lat.subtract(tan.scale(lat.dot(tan)))
            if (l.lengthSqr() < 1.0E-6) l = lat
            l = l.normalize()
            var u = tan.cross(l).normalize()
            if (l.dot(lat) < 0.0) {
                l = l.scale(-1.0)
                u = u.scale(-1.0)
            }
            lats[i] = l
            ups[i] = u
            lat = l
            up = u
        }
        val speed = exit.vel.length().toFloat() * WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK / 40f *
            ModClientConfig.waterFlowScale()
        val segs = ArrayList<StreamSeg>()
        var arcBase = 0f
        for (i in 0 until centers.size - 1) {
            segs += StreamSeg(
                centers[i], centers[i + 1], tans[i]!!, tans[i + 1]!!, lats[i]!!, lats[i + 1]!!,
                tubeRadius, tubeRadius, arcBase, speed
            )
            arcBase += 0.5f
        }
        return if (segs.isEmpty()) null else segs
    }

    private fun emitStream(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        buf: VertexConsumer, now: Float
    ) {
        val water = c.water ?: return
        if (!water.exists) return
        val exit = water.exit ?: return
        val streamForward = water.flowSign < 0f
        val outletAnchor = if (streamForward) c.bc.bePositions.second else c.bc.bePositions.first
        if (!isOpenEnd(level, outletAnchor)) return
        if (c.waterFrames.isEmpty()) return
        val segs = buildStreamSegments(be, level, c, water, streamForward) ?: return
        val outletF = if (streamForward) c.waterFrames[c.waterFrames.size - 1] else c.waterFrames[0]
        val outletRadius = max(0.1f, if (streamForward) outletF.currRadius else outletF.prevRadius)
        val ring = WaterslideTubeMesh.bandVertices(WATER_IN_FRAC, WATER_SURF_FRAC, false)
        val nA = ring.size / 2
        val tiles = (2f * Math.PI.toFloat() * outletRadius * (330f - 210f) / 360f).coerceAtLeast(0.5f)
        val tint = waterTint()
        val wallThickness = net.omori_sunny.create_waterparked.config.ModConfig.wallThickness()
        val base = Vec3.atLowerCornerOf(be.blockPos)
        val n = segs.size
        val fadeStart = max(0, n - n / 3)
        val fadeStartArc = fadeStart * 0.5f
        val fadeEndArc = n * 0.5f
        for (i in segs.indices) {
            val s = segs[i]
            var jitterBoost = 1f
            if (n > 1 && i >= fadeStart) {
                val tailT = (i - fadeStart + 1).toFloat() / (n - fadeStart + 1)
                jitterBoost = 1f + tailT * tailT * 8.0f
            }
            val mid = s.prevSpine.add(s.currSpine).scale(0.5)
            val light = waterLight(level, mid.add(base))
            val seg = WaterslideTubeMesh.TubeSegmentFrame(
                s.prevSpine, s.currSpine, s.prevTangent, s.currTangent,
                s.prevLateral, s.currLateral, s.prevRadius, s.currRadius
            )
            emitWaterSheet(
                seg, ring, nA, tiles, s.arcBase, s.speed, s.speed, 0f,
                waterJitterScale() * jitterBoost, ModClientConfig.waterJitterFrequency(),
                ModClientConfig.waterJitterTimeScale(), now, light, wallThickness,
                tint, buf, fadeStartArc, fadeEndArc
            )
        }
    }

    // ------------------------------------------------------------------
    // support bracket + beam
    // ------------------------------------------------------------------

    private fun emitSupportBracket(
        be: WaterslideAnchorBlockEntity, level: Level, c: CurveGeometry,
        cutout: VertexConsumer
    ) {
        if (!be.supportBracketVisible) return
        val frames = c.frames
        if (frames.isEmpty()) return
        val atFirst = c.bc.bePositions.first == be.blockPos
        val f = if (atFirst) frames[0] else frames[frames.size - 1]
        val material = be.supportMaterial(WaterslideSupportPart.BRACKET)
        val sprite = WaterslideTubeMesh.supportSprite(material) ?: return
        val segLen = WaterslideTubeMesh.arcLength(f)
        if (segLen < 0.01f) return
        val thickness = ModClientConfig.supportBracketThickness()
        val tStart = if (atFirst) 0f else max(1f - thickness / segLen, 0f)
        val tEnd = if (atFirst) min(thickness / segLen, 1f) else 1f
        if (tEnd - tStart < 0.01f) return
        val mid = f.prevSpine.add(f.currSpine).scale(0.5)
        val light = tubeLight(level, mid.add(Vec3.atLowerCornerOf(be.blockPos)))
        buildBracketQuads(f, c.config, sprite, tStart, tEnd, light, cutout)
    }

    private fun buildBracketQuads(
        frame: WaterslideTubeMesh.TubeSegmentFrame,
        config: WaterslideSectorConfig,
        sprite: TextureAtlasSprite,
        tStart: Float, tEnd: Float,
        light: Int, buf: VertexConsumer
    ) {
        val placed = WaterslideSectorLayout.place(config)
        val crossN = WaterslideTubeMesh.crossSections()
        val degStep = 360f / crossN
        val gridAnchor = 90f
        val arcLo = WaterslideTubeMesh.bracketArcLo()
        val arcHi = WaterslideTubeMesh.bracketArcHi()
        val su0 = sprite.u0; val su1 = sprite.u1; val sv0 = sprite.v0; val sv1 = sprite.v1
        val texW = sprite.contents().width().toFloat()
        val texH = sprite.contents().height().toFloat()
        val border = ModConfig.sectorBorderPx().toFloat()
        if (tEnd - tStart <= 0.001f) return

        val chord = frame.currSpine.subtract(frame.prevSpine)
        val handle = (chord.length() / 3.0).toFloat()
        val c0 = frame.prevSpine
        val c1 = frame.prevSpine.add(frame.prevTangent.scale(handle.toDouble()))
        val c2 = frame.currSpine.subtract(frame.currTangent.scale(handle.toDouble()))
        val c3 = frame.currSpine
        val supportThickness = ModClientConfig.supportThickness()
        val wallOuter = net.omori_sunny.create_waterparked.config.ModConfig.wallThickness() - WaterslideTubeMesh.BASE_WALL
        val rBase0 = frame.prevRadius
        val rBase1 = frame.currRadius
        val lat0 = frame.prevLateral
        val lat1 = frame.currLateral
        val arcStart = bezierArcLengthTo(c0, c1, c2, c3, tStart)

        // current cell state, set in the cell loop before each layer/side emit
        var cA0x = 0f
        var sA0x = 0f
        var cA1x = 0f
        var sA1x = 0f
        var cm0x = 0f
        var sm0x = 0f
        var cSlo = 0f
        var cShi = 0f

        val centerH = max(texH - 2f * border, 1f)
        val stripW = max(texW - 2f * border, 1f)
        fun stripU(local: Float): Float {
            val f = (border + local * stripW) / texW
            return su0 + f * (su1 - su0)
        }

        fun vAtlas(vTile: Float): Float {
            val px = vTile * texH
            val f = ((border + (px % centerH)) % texH) / texH
            return sv0 + f * (sv1 - sv0)
        }

        fun cor(
            angleCos: Float, angleSin: Float, tf: Float, radiusOffset: Float
        ): Triple<Vec3, Vec3, Vec3> {
            val t = tStart + (tEnd - tStart) * tf
            val spine = bezierPoint(c0, c1, c2, c3, t)
            val deriv = bezierDerivative(c0, c1, c2, c3, t)
            val tangent = bezierNormalize(deriv)
            val latLin = lat0.scale(1.0 - t).add(lat1.scale(t.toDouble()))
            var lat = latLin.subtract(tangent.scale(latLin.dot(tangent)))
            if (lat.lengthSqr() < 1.0E-8) {
                lat = if (abs(tangent.y) < 0.9) Vec3(0.0, 1.0, 0.0)
                else Vec3(1.0, 0.0, 0.0)
            }
            lat = bezierNormalize(lat)
            val faceUp = bezierNormalize(tangent.cross(lat))
            val radius = Mth.lerp(t, rBase0, rBase1) + radiusOffset
            val pos = spine
                .add(lat.scale((angleCos * radius).toDouble()))
                .add(faceUp.scale((angleSin * radius).toDouble()))
            return Triple(pos, lat, faceUp)
        }

        fun emitLayer(radiusOffset: Float) {
            for (k in 0 until LENGTH_SUBDIVISIONS) {
                val tf0 = k / LENGTH_SUBDIVISIONS.toFloat()
                val tf1 = (k + 1) / LENGTH_SUBDIVISIONS.toFloat()
                val vTile0 = bezierArcLengthTo(c0, c1, c2, c3, tStart + (tEnd - tStart) * tf0) - arcStart
                val vTile1 = bezierArcLengthTo(c0, c1, c2, c3, tStart + (tEnd - tStart) * tf1) - arcStart
                val (p00, lat00, up00) = cor(cA0x, sA0x, tf0, radiusOffset)
                val (p10, lat10, up10) = cor(cA1x, sA1x, tf0, radiusOffset)
                val (p11, _, _) = cor(cA1x, sA1x, tf1, radiusOffset)
                val (p01, _, _) = cor(cA0x, sA0x, tf1, radiusOffset)
                val n0 = bezierNormalize(lat00.scale(cm0x.toDouble()).add(up00.scale(sm0x.toDouble())))
                val n1 = bezierNormalize(lat10.scale(cm0x.toDouble()).add(up10.scale(sm0x.toDouble())))
                verts4Double(buf,
                    p00.toRingWithN(n0), p10.toRingWithN(n1),
                    p11.toRingWithN(n1), p01.toRingWithN(n0),
                    stripU(0f), vAtlas(vTile0), stripU(1f), vAtlas(vTile0),
                    stripU(1f), vAtlas(vTile1), stripU(0f), vAtlas(vTile1),
                    light)
            }
        }

        fun angularSidePanel(angleDeg: Float) {
            val a = Math.toRadians(angleDeg.toDouble())
            val cA = cos(a).toFloat()
            val sA = sin(a).toFloat()
            val tA = a + Math.PI / 2.0
            val ct = cos(tA).toFloat()
            val st = sin(tA).toFloat()
            val sideRIn = wallOuter + WaterslideTubeMesh.SUPPORT_HUG_EPSILON
            val sideROut = sideRIn + supportThickness
            val sideCenterH = max(texH - 2f * border, 1f)
            val sideStripW = max(texW - 2f * border, 1f)
            fun sideU(local: Float): Float {
                val f = (border + local * sideStripW) / texW
                return su0 + f * (su1 - su0)
            }

            fun sideV(vTile: Float): Float {
                val px = vTile * texH
                val f = ((border + (px % sideCenterH)) % texH) / texH
                return sv0 + f * (sv1 - sv0)
            }

            for (k in 0 until LENGTH_SUBDIVISIONS) {
                val tf0 = k / LENGTH_SUBDIVISIONS.toFloat()
                val tf1 = (k + 1) / LENGTH_SUBDIVISIONS.toFloat()
                val vTile0 = bezierArcLengthTo(c0, c1, c2, c3, tStart + (tEnd - tStart) * tf0) - arcStart
                val vTile1 = bezierArcLengthTo(c0, c1, c2, c3, tStart + (tEnd - tStart) * tf1) - arcStart
                val (pIn0, lat0n, up0n) = cor(cA, sA, tf0, sideRIn)
                val (pOut0, _, _) = cor(cA, sA, tf0, sideROut)
                val (pOut1, _, _) = cor(cA, sA, tf1, sideROut)
                val (pIn1, _, _) = cor(cA, sA, tf1, sideRIn)
                val n = bezierNormalize(lat0n.scale(ct.toDouble()).add(up0n.scale(st.toDouble())))
                verts4Double(buf,
                    pIn0.toRingWithN(n), pOut0.toRingWithN(n),
                    pOut1.toRingWithN(n), pIn1.toRingWithN(n),
                    sideU(0f), sideV(vTile0), sideU(1f), sideV(vTile0),
                    sideU(1f), sideV(vTile1), sideU(0f), sideV(vTile1),
                    light)
            }
        }

        fun axialSidePanel(tf: Float) {
            val t = tStart + (tEnd - tStart) * tf
            val tangent = bezierNormalize(bezierDerivative(c0, c1, c2, c3, t))
            val latLin = lat0.scale(1.0 - t).add(lat1.scale(t.toDouble()))
            var lat = latLin.subtract(tangent.scale(latLin.dot(tangent)))
            if (lat.lengthSqr() < 1.0E-8) {
                lat = if (abs(tangent.y) < 0.9) Vec3(0.0, 1.0, 0.0)
                else Vec3(1.0, 0.0, 0.0)
            }
            lat = bezierNormalize(lat)
            val up = bezierNormalize(tangent.cross(lat))
            val sideRIn = wallOuter + WaterslideTubeMesh.SUPPORT_HUG_EPSILON
            val sideROut = sideRIn + supportThickness
            val sideStripW = max(texW - 2f * border, 1f)
            fun sideV(vTile: Float): Float {
                val px = vTile * texH
                val f = ((border + (px % centerH)) % texH) / texH
                return sv0 + f * (sv1 - sv0)
            }

            for (j2 in 0 until crossN) {
                val raw0 = gridAnchor + j2 * degStep
                val raw1 = gridAnchor + (j2 + 1) * degStep
                val cs0 = max(raw0, cSlo)
                val ce1 = min(raw1, cShi)
                if (ce1 <= cs0) continue
                if (raw1 <= cSlo || raw0 >= cShi) continue
                val a0 = Math.toRadians(cs0.toDouble())
                val a1 = Math.toRadians(ce1.toDouble())
                fun pt(ang: Double, rOffset: Float): Vec3 {
                    val pos = bezierPoint(c0, c1, c2, c3, t)
                        .add(lat.scale((cos(ang) * (Mth.lerp(t, rBase0, rBase1) + rOffset)).toDouble()))
                        .add(up.scale((sin(ang) * (Mth.lerp(t, rBase0, rBase1) + rOffset)).toDouble()))
                    return pos
                }

                val pA0 = pt(a0, sideRIn)
                val pA1 = pt(a1, sideRIn)
                val pB1 = pt(a1, sideROut)
                val pB0 = pt(a0, sideROut)
                val n = if (tf <= 0.001f) tangent.scale(-1.0) else tangent
                val frac0 = (cs0 - cSlo) / (cShi - cSlo)
                val frac1 = (ce1 - cSlo) / (cShi - cSlo)
                fun cellU(frac: Float): Float =
                    su0 + ((border + frac * sideStripW) / texW) * (su1 - su0)
                verts4Double(buf,
                    pA0.toRingWithN(n), pB0.toRingWithN(n),
                    pB1.toRingWithN(n), pA1.toRingWithN(n),
                    cellU(frac0), sideV(0f), cellU(frac0), sideV(0.5f),
                    cellU(frac1), sideV(0.5f), cellU(frac1), sideV(0f),
                    light)
            }
        }

        fun materialNear(angleDeg: Float): SectorMaterial? {
            var aa = WaterslideSectorLayout.normalize(angleDeg)
            for (pp in placed) {
                val st = WaterslideSectorLayout.normalize(pp.startAngle)
                val w = pp.endAngle - pp.startAngle
                val inside = if (st + w <= 360f)
                    aa >= st - 0.001f && aa <= st + w + 0.001f
                else
                    aa >= st - 0.001f || aa <= st + w - 360f + 0.001f
                if (inside) return pp.sector.material
            }
            return null
        }

        fun emitShellSides(sDeg: Float, eDeg: Float) {
            val panelStart = sDeg <= arcLo + 0.001f ||
                materialNear(sDeg - 1.5f) == SectorMaterial.OPEN ||
                materialNear(sDeg - 1.5f) == null
            val panelEnd = eDeg >= arcHi - 0.001f ||
                materialNear(eDeg + 1.5f) == SectorMaterial.OPEN ||
                materialNear(eDeg + 1.5f) == null
            if (panelStart) angularSidePanel(sDeg)
            if (panelEnd) angularSidePanel(eDeg)
            axialSidePanel(0f)
            axialSidePanel(1f)
        }

        for (p in placed) {
            if (p.sector.material == SectorMaterial.OPEN) continue
            val sectorDegrees = p.endAngle - p.startAngle
            if (sectorDegrees <= 0.001f) continue
            val startNorm = WaterslideSectorLayout.normalize(p.startAngle)
            val intervals = if (startNorm + sectorDegrees <= 360f)
                listOf(startNorm to startNorm + sectorDegrees)
            else
                listOf(startNorm to 360f, 0f to startNorm + sectorDegrees - 360f)
            for ((lo, hi) in intervals) {
                val wrap = if (lo == startNorm) 0f else 360f
                for (j in 0 until crossN) {
                    val raw0 = gridAnchor + j * degStep
                    val raw1 = gridAnchor + (j + 1) * degStep
                    val cells = if (raw1 <= 360f) listOf(raw0 to raw1)
                    else if (raw0 >= 360f) listOf(raw0 - 360f to raw1 - 360f)
                    else listOf(raw0 to 360f, 0f to raw1 - 360f)
                    for ((cg0, cg1) in cells) {
                        val s = max(max(cg0, lo), arcLo)
                        val e = min(min(cg1, hi), arcHi)
                        if (e <= s) continue
                        val a0 = Math.toRadians(s.toDouble())
                        val a1 = Math.toRadians(e.toDouble())
                        cA0x = cos(a0).toFloat(); sA0x = sin(a0).toFloat()
                        cA1x = cos(a1).toFloat(); sA1x = sin(a1).toFloat()
                        val midA = a0 + (a1 - a0) / 2.0
                        cm0x = cos(midA).toFloat(); sm0x = sin(midA).toFloat()
                        cSlo = s
                        cShi = e
                        // inner shell (tube-hugging) + outer shell
                        emitLayer(wallOuter + WaterslideTubeMesh.SUPPORT_HUG_EPSILON)
                        if (supportThickness > 0.001f) {
                            emitLayer(wallOuter + WaterslideTubeMesh.SUPPORT_HUG_EPSILON + supportThickness)
                        }
                        emitShellSides(s, e)
                    }
                }
            }
        }
    }

    private fun emitSupportBeam(
        be: WaterslideAnchorBlockEntity, level: Level, curves: List<CurveGeometry>,
        cutout: VertexConsumer
    ) {
        if (curves.isEmpty()) return
        if (!be.supportBeamVisible) return
        val c = curves[0]
        val frames = c.frames
        if (frames.isEmpty()) return
        val atFirst = c.bc.bePositions.first == be.blockPos
        val f = if (atFirst) frames[0] else frames[frames.size - 1]
        val s0 = if (atFirst) f.prevSpine else f.currSpine
        val s1 = if (atFirst) f.currSpine else f.prevSpine
        val spine = s0.add(s1).scale(0.5)
        val tan = if (atFirst) f.prevTangent else f.currTangent
        val lat = if (atFirst) f.prevLateral else f.currLateral
        val faceUp = tan.cross(lat).normalize()
        val wallOuter = net.omori_sunny.create_waterparked.config.ModConfig.wallThickness() - WaterslideTubeMesh.BASE_WALL
        val rOut = max(0.1f, if (atFirst) f.prevRadius else f.currRadius) +
            wallOuter + ModClientConfig.supportThickness() + WaterslideTubeMesh.SUPPORT_HUG_EPSILON
        val bottomLocal = spine.subtract(faceUp.scale(rOut.toDouble()))
        // anchor top-face center, in instance space (frames are origin-relative)
        val anchorCenterLocal = Vec3(0.5, 1.0, 0.5)
        val axis = bottomLocal.subtract(anchorCenterLocal)
        val len = axis.length().toFloat()
        if (len < 0.05f) return
        val axisN = axis.scale(1.0 / len)
        val refV = if (abs(axisN.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        var b1 = refV.cross(axisN).normalize()
        if (b1.lengthSqr() < 1.0E-8) b1 = Vec3(1.0, 0.0, 0.0)
        val b2 = axisN.cross(b1).normalize()
        val halfS = ModClientConfig.supportBeamSize() * 0.5f
        val tops = FloatArray(4) { 0.15f }
        val bottoms = beamBottomOffsets(axisN, anchorCenterLocal, b1, b2, halfS)
        val material = be.supportMaterial(WaterslideSupportPart.BEAM)
        val sprite = WaterslideTubeMesh.supportSprite(material) ?: return
        val midWorld = anchorCenterLocal.add(axis.scale(0.5)).add(Vec3.atLowerCornerOf(be.blockPos))
        val light = tubeLight(level, midWorld)
        buildBeamQuads(anchorCenterLocal, axisN, len, sprite, tops, bottoms, light, cutout)
    }

    private fun beamBottomOffsets(
        axisN: Vec3, anchorCenterLocal: Vec3, b1: Vec3, b2: Vec3, halfS: Float
    ): FloatArray {
        val bottoms = FloatArray(4)
        val signs = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
        for (i in 0 until 4) {
            val corner = anchorCenterLocal
                .add(b1.scale(signs[i][0] * halfS.toDouble()))
                .add(b2.scale(signs[i][1] * halfS.toDouble()))
            if (axisN.y <= 1.0E-6) {
                bottoms[i] = 0f
            } else {
                val t = corner.y - anchorCenterLocal.y
                bottoms[i] = max(t, 0.0).toFloat()
            }
        }
        return bottoms
    }

    private fun buildBeamQuads(
        base: Vec3, axisN: Vec3, len: Float,
        sprite: TextureAtlasSprite,
        topOffsets: FloatArray, bottomOffsets: FloatArray,
        light: Int, buf: VertexConsumer
    ) {
        val size = ModClientConfig.supportBeamSize()
        val half = size / 2f
        val su0 = sprite.u0; val su1 = sprite.u1; val sv0 = sprite.v0; val sv1 = sprite.v1
        val (n1, n2) = orthonormalBasis(axisN)

        fun side(n: Vec3, w: Vec3) {
            fun quadrant(wSign: Float): Int {
                val n1c = (n.dot(n1) + w.dot(n1) * wSign) * half.toDouble()
                val n2c = (n.dot(n2) + w.dot(n2) * wSign) * half.toDouble()
                return (if (n1c >= 0) 2 else 0) + (if (n2c >= 0) 1 else 0)
            }

            fun topOff(wSign: Float): Float =
                topOffsets.getOrNull(quadrant(wSign)) ?: 0f

            fun bottomOff(wSign: Float): Float =
                bottomOffsets.getOrNull(quadrant(wSign)) ?: 0f

            fun pt(ws: Float, ts: Float, drop: Float): Vec3 =
                base.add(n.scale(half.toDouble())).add(w.scale(ws.toDouble()))
                    .add(axisN.scale(ts.toDouble())).add(0.0, -drop.toDouble(), 0.0)

            val c0 = pt(-half, 0f, bottomOff(-1f))
            val c1 = pt(half, 0f, bottomOff(1f))
            val c2 = pt(half, len + topOff(1f), 0f)
            val c3 = pt(-half, len + topOff(-1f), 0f)
            val vLo0 = -bottomOff(-1f)
            val vHi0 = len + topOff(-1f)
            val vLo1 = -bottomOff(1f)
            val vHi1 = len + topOff(1f)
            val tMin = min(vLo0, vLo1)
            val tMax = max(vHi0, vHi1)
            val totalBlocks = tMax - tMin
            fun uV(vv: Float): Float = su0 + vv * (su1 - su0)
            fun vV(vv: Float): Float = sv0 + vv * (sv1 - sv0)
            val segments = max(1, ceil(totalBlocks).toInt())
            for (k in 0 until segments) {
                val fa = tMin + k
                val fb = min(fa + 1f, tMax)
                val span = fb - fa
                fun edgePos(left: Boolean, fv: Float): Vec3 {
                    val a = if (left) c0 else c1
                    val b = if (left) c3 else c2
                    val runLo = if (left) vLo0 else vLo1
                    val runHi = if (left) vHi0 else vHi1
                    val t01 = (((fv - runLo) / (runHi - runLo)).toDouble()).coerceIn(0.0, 1.0)
                    return a.add(b.subtract(a).scale(t01))
                }

                val pa = edgePos(true, fa)
                val pb = edgePos(false, fa)
                val pc = edgePos(false, fb)
                val pd = edgePos(true, fb)
                verts4(buf,
                    pa.toRingWithN(n), pd.toRingWithN(n),
                    pc.toRingWithN(n), pb.toRingWithN(n),
                    uV(0f), vV(0f), uV(0f), vV(span),
                    uV(size), vV(span), uV(size), vV(0f),
                    light)
            }
        }

        side(n1, n2.scale(-1.0))
        side(n1.scale(-1.0), n2)
        side(n2, n1)
        side(n2.scale(-1.0), n1.scale(-1.0))
    }

    // must match the basis used by beamTopOffsets/beamBottomOffsets (same ref)
    private fun orthonormalBasis(axis: Vec3): Pair<Vec3, Vec3> {
        val ref = if (abs(axis.y.toFloat()) < 0.9f) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        var n1 = bezierNormalize(ref.cross(axis))
        if (n1.lengthSqr() < 1.0E-8) n1 = Vec3(1.0, 0.0, 0.0)
        val n2 = bezierNormalize(axis.cross(n1))
        return Pair(n1, n2)
    }
}
