package net.omori_sunny.create_waterparked.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.texture.OverlayTexture
import com.simibubi.create.content.trains.track.BezierConnection
import dev.engine_room.flywheel.api.visualization.VisualizationManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.model.data.ModelData
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Vanilla (non-Flywheel) FALLBACK renderer for the tube around a
// WaterslideAnchorBlockEntity. It only draws when Flywheel's visualization is
// unavailable, mirroring how Create/CCS ship a BerRender fallback
// (AnchorPeerTrackCurveBerRender gates on
// !VisualizationManager.supportsVisualization). The Flywheel visual
// (WaterslideTubeVisual / WaterslideTubeMesh) is untouched and remains the
// primary renderer whenever Flywheel is available.
class WaterslideTubeBlockEntityRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<WaterslideAnchorBlockEntity> {

    private class CurveGeometry(
        val peer: BlockPos,
        val frames: List<WaterslideTubeMesh.TubeSegmentFrame>,
        val flowSpeed: Float,
        val watered: Boolean
    )

    // cache per (entity position, signature): rebuild geometry only on edits
    private val cache = HashMap<Long, Pair<String, List<CurveGeometry>>>()
    private var lastSignature = ""

    // Dedicated RenderTypes so the fallback never shares a buffer with the
    // world's own section rendering (a shared cutoutMipped/translucent buffer
    // is already ended before the block-entity stage -> "Not building!").
    private val tubeCutout: RenderType = RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS)
    private val tubeTranslucent: RenderType = RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)

    override fun render(
        be: WaterslideAnchorBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val level = be.level
        // Keep the client anchor index fed: WaterslideCurveRenderer populates
        // CLIENT_ANCHORS from its own render path; our fallback renderer must
        // register them too (regardless of the flywheel gate) or
        // IrisWaterInjection finds nothing to draw.
        net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer.registerClientAnchor(be)
        // Flywheel fallback gate: only render ourselves when visualization is
        // unavailable (same pattern as Create/CCS BerRender fallbacks).
        if (level == null || VisualizationManager.supportsVisualization(level)) return
        val eid = be.blockPos.asLong()
        val sig = signature(be, level)
        if (sig != lastSignature || !cache.containsKey(eid)) {
            cache.clear()
            lastSignature = sig
            cache[eid] = sig to buildCurves(be, level)
        }
        val (_, curves) = cache[eid] ?: return
        if (curves.isEmpty()) return

        // BESR pose: the stack is at the evaluator origin, NOT world space - so
        // inverse-transform the world frames back to the anchor's local space:
        // push the anchor's corner, emit relative coords, pop.
        val base = Vec3.atLowerCornerOf(be.blockPos)
        poseStack.pushPose()
        poseStack.translate(base.x.toFloat(), base.y.toFloat(), base.z.toFloat())

        val cutout = buffers.getBuffer(tubeCutout)
        val translucent = buffers.getBuffer(tubeTranslucent)
        val now = 0.05f * level.gameTime.toFloat()
        for (c in curves) {
            emitWall(be, level, c, cutout, packedLight, base)
            if (c.watered) emitWater(c, translucent, now, packedLight, base)
        }
        poseStack.popPose()
    }

    // ------------------------------------------------------------------
    // geometry caches
    // ------------------------------------------------------------------

    private fun buildCurves(be: WaterslideAnchorBlockEntity, level: Level): List<CurveGeometry> {
        val out = ArrayList<CurveGeometry>()
        val sub = dev.ryanhcode.sable.Sable.HELPER.getContaining(be)
        val origin = Vec3.atLowerCornerOf(be.blockPos)
        val defRadius = ModConfig.defaultSlideRadius()
        for (e in be.anchorPeerCurvesView) {
            val raw = e.value ?: continue
            if (!raw.isPrimary) continue
            val bc = raw
            if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
            val peer = bc.bePositions.second
            val r0 = WaterslideRadiusEdit.radiusAt(level, bc.bePositions.first, defRadius)
            val r1 = WaterslideRadiusEdit.radiusAt(level, peer, defRadius)
            val frames = try {
                WaterslideTubeMesh.sampleSegments(level, bc, r0, r1, origin)
            } catch (t: Throwable) {
                continue
            }
            if (frames.size < 2) continue
            val watered = be.isCurveWatered(peer)
            val water = WaterFlowSimulation.fieldFor(level, bc.bePositions.first, peer)
            val flow = if (water == null || water.segments.isEmpty()) 0f
            else water.segments.map { it.speed }.average().toFloat()
            out += CurveGeometry(peer, frames, flow, watered)
        }
        return out
    }

    private fun signature(be: WaterslideAnchorBlockEntity, level: Level): String {
        val sb = StringBuilder()
        sb.append(WaterFlowSimulation.version()).append('|')
            .append(ModClientConfig.polygonScale()).append('|')
            .append(ModClientConfig.wallThickness()).append('|')
            .append(be.radius).append('|')
        for ((peer, cfg) in be.sectorConfigs) {
            sb.append(peer.asLong()).append('=')
            for (s in cfg.sectors) {
                sb.append(s.id).append(',').append(s.material).append(',').append(s.blockId).append(';')
            }
        }
        for (e in be.anchorPeerCurvesView) {
            val raw = e.value ?: continue
            if (!raw.isPrimary) continue
            sb.append(e.key.asLong()).append('=')
                .append(raw.bePositions.first.asLong()).append(',')
                .append(raw.bePositions.second.asLong()).append(',')
                .append(raw.getSegmentCount()).append(';')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // wall emission: ring-connect between consecutive tube frames
    // ------------------------------------------------------------------

    private class RingPoint(val x: Float, val y: Float, val z: Float, val nx: Float, val ny: Float, val nz: Float)

    // cross-section point on a frame's ring: center + lateral*r*cos + up*r*sin,
    // where up = tangent x lateral (perpendicular to both). Emitted in the
    // anchor's LOCAL space (world minus `base`).
    private fun ringPoint(
        spine: Vec3, lateral: Vec3, tangent: Vec3,
        angle: Float, radius: Float, base: Vec3
    ): RingPoint {
        val c = cos(angle).toDouble()
        val s = sin(angle).toDouble()
        val up = tangent.cross(lateral).normalize()
        val r = radius.toDouble()
        val lat = lateral
        val v = spine.add(lat.scale(r * c)).add(up.scale(r * s)).subtract(base)
        val n = lat.scale(c).add(up.scale(s))
        return RingPoint(v.x.toFloat(), v.y.toFloat(), v.z.toFloat(),
            n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
    }

    private fun vertex(v: VertexConsumer, p: RingPoint, u: Float, vt: Float, light: Int) {
        v.addVertex(p.x, p.y, p.z)
            .setColor(1f, 1f, 1f, 1f)
            .setUv(u, vt)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(p.nx, p.ny, p.nz)
    }

    // frag-shader-style tiling: physical pixel coordinate -> fraction inside the
    // sprite's usable (center) region, wrapping so there is no stretching.
    private fun tileFraction(px: Float, center: Float, border: Float, tex: Float): Float {
        var m = px % center
        if (m < 0f) m += center
        return (border + m) / tex
    }

    private fun emitQuad(
        v: VertexConsumer,
        a: RingPoint, b: RingPoint, d: RingPoint, e: RingPoint,
        px0: Float, px1: Float, py0: Float, py1: Float,
        sprite: TextureAtlasSprite, flip: Boolean, light: Int,
        centerW: Float, centerH: Float, border: Float, texW: Float, texH: Float,
        su0: Float, su1: Float, sv0: Float, sv1: Float
    ) {
        fun ux(px: Float): Float = su0 + tileFraction(px, centerW, border, texW) * (su1 - su0)
        fun vy(py: Float): Float = sv0 + tileFraction(py, centerH, border, texH) * (sv1 - sv0)
        if (!flip) {
            vertex(v, a, ux(px0), vy(py0), light); vertex(v, b, ux(px1), vy(py0), light)
            vertex(v, d, ux(px1), vy(py1), light); vertex(v, e, ux(px0), vy(py1), light)
        } else {
            vertex(v, a, ux(px0), vy(py0), light); vertex(v, e, ux(px0), vy(py1), light)
            vertex(v, d, ux(px1), vy(py1), light); vertex(v, b, ux(px1), vy(py0), light)
        }
    }

    private fun emitWall(
        be: WaterslideAnchorBlockEntity,
        level: Level,
        c: CurveGeometry,
        consumer: VertexConsumer,
        light: Int,
        base: Vec3
    ) {
        val frames = c.frames
        val config = be.sectorConfigFor(c.peer)
        val placed = WaterslideSectorLayout.place(config)
        val degPerCell = 360f / WaterslideTubeMesh.crossSections()
        // cumulative real arc (world blocks) at each ring
        val prefix = FloatArray(frames.size)
        for (i in 1 until frames.size) {
            prefix[i] = prefix[i - 1] + WaterslideTubeMesh.arcLength(frames[i - 1])
        }
        for (sector in placed) {
            if (sector.sector.material == SectorMaterial.OPEN) continue
            val blockId = sector.sector.blockId ?: continue
            val registry = BuiltInRegistries.BLOCK as net.minecraft.core.Registry<net.minecraft.world.level.block.Block>
            val block = registry[blockId] ?: continue
            val sprite = Minecraft.getInstance().blockRenderer
                .getBlockModel(block.defaultBlockState())
                .getParticleIcon(ModelData.EMPTY) ?: continue
            val span = sector.endAngle - sector.startAngle
            // undistorted texture: 16 texture px per world block, sector width in
            // pixels = sectorRadians * texRadius * 16 (mirrors the vertex shader)
            val texW = sprite.contents().width().toFloat()
            val texH = sprite.contents().height().toFloat()
            val border = ModConfig.sectorBorderPx().toFloat()
            val centerW = max(texW - 2f * border, 1f)
            val centerH = max(texH - 2f * border, 1f)
            val sectorRadians = (sector.endAngle - sector.startAngle) * Math.PI.toFloat() / 180f
            val su0 = sprite.u0
            val su1 = sprite.u1
            val sv0 = sprite.v0
            val sv1 = sprite.v1
            for (i in 0 until frames.size - 1) {
                val f0 = frames[i]
                val f1 = frames[i + 1]
                // real arc at the two rings (16 px per block for py)
                val arc0Px = prefix[i] * 16f
                val arc1Px = prefix[i + 1] * 16f
                var a = WaterslideSectorLayout.normalize(sector.startAngle).coerceIn(0f, 360f)
                val endA = min(a + span, 360f)
                while (a < endA) {
                    val lo = a
                    val hi = min(endA, a + degPerCell)
                    a = hi
                    val fA = (lo - sector.startAngle) / span
                    val fB = (hi - sector.startAngle) / span
                    val a0 = (lo * Math.PI / 180.0).toFloat()
                    val a1 = (hi * Math.PI / 180.0).toFloat()
                    val p00 = ringPoint(f0.prevSpine, f0.prevLateral, f0.prevTangent, a0, f0.prevRadius, base)
                    val p01 = ringPoint(f0.prevSpine, f0.prevLateral, f0.prevTangent, a1, f0.prevRadius, base)
                    val p10 = ringPoint(f0.currSpine, f0.currLateral, f0.currTangent, a0, f0.currRadius, base)
                    val p11 = ringPoint(f0.currSpine, f0.currLateral, f0.currTangent, a1, f0.currRadius, base)
                    // px = sector fraction * sector width in px
                    val px0 = fA * sectorRadians * f0.currRadius * 16f
                    val px1 = fB * sectorRadians * f0.currRadius * 16f
                    // py = real arc in px (canvas: vertex 0..1 across the segment)
                    emitQuad(consumer, p00, p01, p11, p10, px0, px1, arc0Px, arc1Px,
                        sprite, false, light, centerW, centerH, border, texW, texH, su0, su1, sv0, sv1)
                    emitQuad(consumer, p01, p00, p10, p11, px0, px1, arc0Px, arc1Px,
                        sprite, true, light, centerW, centerH, border, texW, texH, su0, su1, sv0, sv1)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // water emission: translucent flowing band between frames
    // ------------------------------------------------------------------

    private fun waterVertex(v: VertexConsumer, p: RingPoint, u: Float, vt: Float, light: Int) {
        v.addVertex(p.x, p.y, p.z)
            .setColor(0.24f, 0.6f, 1f, 0.6f)
            .setUv(u, vt)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(p.nx, p.ny, p.nz)
    }

    private fun emitWater(c: CurveGeometry, consumer: VertexConsumer, now: Float, light: Int, base: Vec3) {
        val frames = c.frames
        val rIn = 0.35f
        val rOut = 0.6f
        val flow = c.flowSpeed
        val n = 10
        val waterSprite = Minecraft.getInstance().blockRenderer
            .getBlockModel(Blocks.WATER.defaultBlockState())
            .getParticleIcon(ModelData.EMPTY)
        if (waterSprite == null) return
        val su0 = waterSprite.u0
        val su1 = waterSprite.u1
        val sv0 = waterSprite.v0
        val sv1 = waterSprite.v1
        val prefix = FloatArray(frames.size)
        for (i in 1 until frames.size) {
            prefix[i] = prefix[i - 1] + WaterslideTubeMesh.arcLength(frames[i - 1])
        }
        for (i in 0 until frames.size - 1) {
            val f0 = frames[i]
            // one texture tile per block along the flow, phase = flow*now
            val vt0 = prefix[i] + flow * now
            val vt1 = prefix[i + 1] + flow * now
            for (j in 0 until n) {
                val a0 = (j * 360f / n) * Math.PI.toFloat() / 180f
                val a1 = ((j + 1) * 360f / n) * Math.PI.toFloat() / 180f
                val p0 = ringPoint(f0.prevSpine, f0.prevLateral, f0.prevTangent, a0, f0.prevRadius * rIn, base)
                val p1 = ringPoint(f0.prevSpine, f0.prevLateral, f0.prevTangent, a1, f0.prevRadius * rIn, base)
                val p2 = ringPoint(f0.currSpine, f0.currLateral, f0.currTangent, a1, f0.currRadius * rOut, base)
                val p3 = ringPoint(f0.currSpine, f0.currLateral, f0.currTangent, a0, f0.currRadius * rOut, base)
                var uf0 = j.toFloat() / n
                var uf1 = (j + 1).toFloat() / n
                uf0 -= Math.floor(uf0.toDouble()).toFloat()
                uf1 -= Math.floor(uf1.toDouble()).toFloat()
                val uA0 = su0 + uf0 * (su1 - su0)
                val uA1 = su0 + uf1 * (su1 - su0)
                var aV0 = vt0; var aV1 = vt0
                aV0 -= Math.floor(aV0.toDouble()).toFloat()
                aV1 -= Math.floor(aV1.toDouble()).toFloat()
                var aV2 = vt1; var aV3 = vt1
                aV2 -= Math.floor(aV2.toDouble()).toFloat()
                aV3 -= Math.floor(aV3.toDouble()).toFloat()
                val vA0 = sv0 + aV0 * (sv1 - sv0)
                val vA2 = sv0 + aV2 * (sv1 - sv0)
                waterVertex(consumer, p0, uA0, vA0, light)
                waterVertex(consumer, p1, uA1, vA0, light)
                waterVertex(consumer, p2, uA1, vA2, light)
                waterVertex(consumer, p3, uA0, vA2, light)
            }
        }
    }
}
