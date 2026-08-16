package net.omori_sunny.create_waterparked.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.engine_room.flywheel.api.visualization.VisualizationManager
import net.createmod.catnip.animation.AnimationTickHolder
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterOpenEndExtension
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.mixin.client.LevelRendererAccessor
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.PlacedSector
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.model.data.ModelData
import org.joml.Matrix4f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// CPU fallback renderer for waterslide curves.
class WaterslideCurveRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<WaterslideAnchorBlockEntity> {

    override fun render(
        blockEntity: WaterslideAnchorBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val bePos = blockEntity.blockPos
        poseStack.pushPose()
// world coordinates
        poseStack.translate(-bePos.x.toDouble(), -bePos.y.toDouble(), -bePos.z.toDouble())
        renderAllCurves(
            blockEntity, poseStack, bufferSource, mutableSetOf(),
            flywheelActive = false
        )
        poseStack.popPose()
    }

// off-screen rendering
    override fun shouldRenderOffScreen(blockEntity: WaterslideAnchorBlockEntity): Boolean = true

    override fun getViewDistance(): Int = 192

    companion object {
        private const val WALL_THICKNESS = 0.1f
        private const val PIXELS_PER_BLOCK = 16f
        private const val TILE_SUBDIVISION_PX = 8f
        private const val MAX_DRAW_DISTANCE_SQ = 192.0 * 192.0
        private const val WATER_FADE_BLOCKS = 2f
        // CPU fallback stream scroll rate, cycles per tick
        private const val STREAM_FLOW_SPEED = 0.05f
        private val WATER_TINT = floatArrayOf(0.3f, 0.6f, 1f)
        private val WATER_SURFACE_TINT = floatArrayOf(0.65f, 0.9f, 1f)

        // Client anchor index.
        private val CLIENT_ANCHORS: MutableSet<WaterslideAnchorBlockEntity> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

        @JvmStatic
        fun registerClientAnchor(be: WaterslideAnchorBlockEntity) {
            CLIENT_ANCHORS.add(be)
        }

        @JvmStatic
        fun unregisterClientAnchor(be: WaterslideAnchorBlockEntity) {
            CLIENT_ANCHORS.remove(be)
        }

        @JvmStatic
        fun clearClientAnchors() {
            CLIENT_ANCHORS.clear()
        }

        // Anchors for sector edit hit detection.
        @JvmStatic
        fun clientAnchors(): Iterable<WaterslideAnchorBlockEntity> = CLIENT_ANCHORS

        // Draw all loaded curves.
        @JvmStatic
        fun renderAllInEvent(poseStack: PoseStack, bufferSource: MultiBufferSource) {
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return
            val camera = mc.gameRenderer.mainCamera.position
            // Flywheel handles the pipe; this is the fallback.
            val flywheelActive = VisualizationManager.supportsVisualization(level)
// draw each curve once
            val seenEdges = mutableSetOf<Pair<Long, Long>>()
            val snapshot = CLIENT_ANCHORS.toTypedArray()
            for (be in snapshot) {
                if (be.isRemoved || be.level !== level) {
                    CLIENT_ANCHORS.remove(be)
                    continue
                }
                if (be.blockPos.distToCenterSqr(camera.x, camera.y, camera.z) > MAX_DRAW_DISTANCE_SQ) continue
                poseStack.pushPose()
                // Pose starts at the world origin.
                poseStack.translate(-camera.x, -camera.y, -camera.z)
                renderAllCurves(be, poseStack, bufferSource, seenEdges, flywheelActive)
                poseStack.popPose()
            }
            renderDebugTrajectories(poseStack, bufferSource)
// flush pipe batches
            endBatches(bufferSource)
        }

        // Sub-level thrown water, drawn in main-world coordinates after every
        // level/sub-level geometry has written depth. The Flywheel visual owns
        // the prediction; this pass only triangulates the cached world polylines
        // so the sheet is depth-tested against sub-levels in the correct order.
        @JvmStatic
        fun renderWorldStreams(
            poseStack: PoseStack,
            bufferSource: MultiBufferSource,
            cameraPos: Vec3,
            cameraRotation: Matrix4f
        ) {
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return
            val sheets = WaterslideTubeVisual.worldStreamSheets()
            if (sheets.isEmpty()) return
            val sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(ResourceLocation.withDefaultNamespace("block/water_still"))
            val consumer = bufferSource.getBuffer(TUBE_STREAM_TRANSLUCENT)
            // AFTER_LEVEL hands us an identity PoseStack and a camera-rotation
            // matrix (no translation). Convert every world point to eye space
            // ourselves, exactly like the editor outlines do; then the vertex
            // pose stays identity.
            val pose = poseStack.last()
            val flow = -AnimationTickHolder.getRenderTime(level) * STREAM_FLOW_SPEED
            for ((outer, inner) in sheets) {
                val outerEye = outer.map { ray ->
                    ray.map { toEye(cameraPos, cameraRotation, it) }
                }
                val innerEye = inner.map { ray ->
                    ray.map { toEye(cameraPos, cameraRotation, it) }
                }
                renderWorldSheet(level, outerEye, innerEye, pose, consumer, sprite, flow)
            }
            if (bufferSource is MultiBufferSource.BufferSource) {
                bufferSource.endBatch(TUBE_STREAM_TRANSLUCENT)
            }
        }

        private fun toEye(cameraPos: Vec3, cameraRotation: Matrix4f, world: Vec3): Vec3 {
            val eye = WaterslideEditorRenderTypes.worldToEye(cameraPos, cameraRotation, world)
            return Vec3(eye.x().toDouble(), eye.y().toDouble(), eye.z().toDouble())
        }

        private fun renderWorldSheet(
            level: Level,
            outer: List<List<Vec3>>,
            inner: List<List<Vec3>>,
            pose: PoseStack.Pose,
            consumer: VertexConsumer,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            flow: Float
        ) {
            if (outer.size < 2 || inner.size != outer.size) return
            val samples = minOf(
                outer.minOfOrNull { it.size } ?: return,
                inner.minOfOrNull { it.size } ?: return
            )
            if (samples < 2) return
            val cum = FloatArray(samples)
            for (k in 1 until samples) {
                cum[k] = cum[k - 1] + outer[0][k - 1].distanceTo(outer[0][k]).toFloat()
            }
            val maxSegments = max(4, (48 * ModClientConfig.polygonScale()).roundToInt())
            val stride = max(1, (samples - 1) / maxSegments)

            fun vAt(k: Int): Float = mod(
                cum[k] * WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK + flow, 1f
            )

            fun sheet(
                a: List<Vec3>,
                b: List<Vec3>,
                u0: Float,
                u1: Float,
                tint: FloatArray,
                alpha: Float,
                flip: Boolean
            ) {
                for (k in 0 until samples - 1 step stride) {
                    val k1 = minOf(k + stride, samples - 1)
                    val a0 = a[k]
                    val a1 = a[k1]
                    val b1 = b[k1]
                    val b0 = b[k]
                    val edge1 = a1.subtract(a0)
                    val edge2 = b0.subtract(a0)
                    var normal = edge1.cross(edge2).normalize()
                    if (flip) normal = normal.scale(-1.0)
                    // actual block/sky light instead of FULL_BRIGHT; the old
                    // full-bright version made the sheet glow
                    val light = LevelRenderer.getLightColor(level, BlockPos.containing(a0))
                    vertex(consumer, pose, sprite, a0, normal, u0 to vAt(k), light, 0.7f, alpha, tint)
                    vertex(consumer, pose, sprite, a1, normal, u0 to vAt(k1), light, 0.7f, alpha, tint)
                    vertex(consumer, pose, sprite, b1, normal, u1 to vAt(k1), light, 0.7f, alpha, tint)
                    vertex(consumer, pose, sprite, b0, normal, u1 to vAt(k), light, 0.7f, alpha, tint)
                }
            }

            val n = outer.size
            val colArc = FloatArray(n)
            for (i in 1 until n) {
                colArc[i] = colArc[i - 1] + outer[i - 1][0].distanceTo(outer[i][0]).toFloat()
            }
            for (i in 0 until n - 1) {
                val u0 = mod(colArc[i], 1f)
                val u1 = mod(colArc[i + 1], 1f)
                sheet(outer[i], outer[i + 1], u0, u1, WATER_TINT, 0.75f, false)
                sheet(inner[i], inner[i + 1], u0, u1, WATER_SURFACE_TINT, 0.95f, true)
            }
        }

        // water simulation trajectory debug overlay
        private fun renderDebugTrajectories(poseStack: PoseStack, bufferSource: MultiBufferSource) {
            val polylines = WaterFlowSimulation.debugPolylines()
            if (polylines.isEmpty()) return
            val mc = Minecraft.getInstance()
            val camera = mc.gameRenderer.mainCamera.position
            val lines = bufferSource.getBuffer(RenderType.lines())
            poseStack.pushPose()
            poseStack.translate(-camera.x, -camera.y, -camera.z)
            val pose = poseStack.last()
            var colorIdx = 0
            for (poly in polylines) {
                if (poly.size < 2) continue
                colorIdx++
                val r = if (colorIdx % 2 == 0) 0.2f else 1f
                val g = if (colorIdx % 3 == 0) 0.2f else 1f
                val b = if (colorIdx % 2 == 1) 0.2f else 1f
                for (i in 1 until poly.size) {
                    val a = poly[i - 1]
                    val c = poly[i]
                    lines.addVertex(pose, a.x.toFloat(), a.y.toFloat(), a.z.toFloat())
                        .setColor(r, g, b, 1f)
                        .setNormal(pose, 0f, 1f, 0f)
                    lines.addVertex(pose, c.x.toFloat(), c.y.toFloat(), c.z.toFloat())
                        .setColor(r, g, b, 1f)
                        .setNormal(pose, 0f, 1f, 0f)
                }
            }
            poseStack.popPose()
            if (bufferSource is MultiBufferSource.BufferSource) {
                bufferSource.endBatch(RenderType.lines())
            }
        }

        @JvmStatic
        fun endBatches(bufferSource: MultiBufferSource) {
            if (bufferSource is MultiBufferSource.BufferSource) {
                bufferSource.endBatch(TUBE_SOLID)
                bufferSource.endBatch(TUBE_CUTOUT)
                bufferSource.endBatch(TUBE_TRANSLUCENT)
                bufferSource.endBatch(TUBE_WATER_TRANSLUCENT)
                bufferSource.endBatch(TUBE_STREAM_TRANSLUCENT)
            }
        }

        private fun renderAllCurves(
            be: WaterslideAnchorBlockEntity,
            poseStack: PoseStack,
            bufferSource: MultiBufferSource,
            seenEdges: MutableSet<Pair<Long, Long>>,
            flywheelActive: Boolean
        ) {
            val level = be.level ?: return
            for ((peer, raw) in be.anchorPeerCurvesView) {
                val primary = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(primary)) continue
                if (!seenEdges.add(edgeKey(primary))) continue
                val config = WaterslideSectorEdit.previewConfigFor(
                    primary.bePositions.getFirst(), primary.bePositions.getSecond()
                ) ?: be.sectorConfigFor(peer)
                val water = WaterFlowSimulation.resultFor(level, primary)
                // Flywheel owns pipes and streams when active.
                if (flywheelActive) continue
                if (water != null) renderStreams(water, poseStack, bufferSource)
                renderCurve(level, primary, config, poseStack, bufferSource, water)
            }
        }

        private fun edgeKey(bc: com.simibubi.create.content.trains.track.BezierConnection): Pair<Long, Long> {
            val a = bc.bePositions.getFirst().asLong()
            val b = bc.bePositions.getSecond().asLong()
            return if (a <= b) a to b else b to a
        }

        // Cull back faces.
        private val TUBE_SOLID: RenderType = RenderType.create(
            "create_waterparked:waterslide_tube_solid",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            262144,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_SOLID_SHADER)
                .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setCullState(RenderStateShard.CULL)
                .createCompositeState(true)
        )

        private val TUBE_CUTOUT: RenderType = RenderType.create(
            "create_waterparked:waterslide_tube_cutout",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            262144,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
                .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setCullState(RenderStateShard.CULL)
                .createCompositeState(true)
        )

        private val TUBE_TRANSLUCENT: RenderType = RenderType.create(
            "create_waterparked:waterslide_tube_translucent",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            262144,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
// no depth write
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.CULL)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .createCompositeState(false)
        )

        // water is single-sided; cull backfaces between instances
        private val TUBE_WATER_TRANSLUCENT: RenderType = RenderType.create(
            "create_waterparked:waterslide_water_translucent",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            131072,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.CULL)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .createCompositeState(false)
        )

// thrown water is visible from both sides
        private val TUBE_STREAM_TRANSLUCENT: RenderType = RenderType.create(
            "create_waterparked:waterslide_stream_translucent",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            131072,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.NO_CULL)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .createCompositeState(false)
        )

// CCS renderer fallback
        @JvmStatic
        fun renderFromParent(
            anchor: CoasterAnchorpointBlockEntity,
            poseStack: PoseStack,
            bufferSource: MultiBufferSource
        ) {
            val level = anchor.level ?: return
// flywheel owns the pipe when active
            if (VisualizationManager.supportsVisualization(level)) return
            val bePos = anchor.blockPos
            poseStack.pushPose()
            poseStack.translate(-bePos.x.toDouble(), -bePos.y.toDouble(), -bePos.z.toDouble())

            val self = anchor as? WaterslideAnchorBlockEntity
            for ((peer, raw) in anchor.anchorPeerCurvesView) {
                val primary = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(primary)) continue
                val config = self?.sectorConfigFor(peer) ?: WaterslideSectorConfig.defaultConfig()
                val water = WaterFlowSimulation.resultFor(level, primary)
                renderCurve(level, primary, config, poseStack, bufferSource, water)
            }

            poseStack.popPose()
        }

        private fun renderCurve(
            level: Level,
            bc: com.simibubi.create.content.trains.track.BezierConnection,
            config: WaterslideSectorConfig,
            poseStack: PoseStack,
            bufferSource: MultiBufferSource,
            water: WaterFlowSimulation.CurveWater?
        ) {
            val placed = WaterslideSectorLayout.place(config)
            val r0 = radiusAt(level, bc.bePositions.getFirst())
            val r1 = radiusAt(level, bc.bePositions.getSecond())
            val count = bc.getSegmentCount().coerceAtLeast(1)

// translucent on edited anchor
            val editMode = BezierHandleEditMode.isActive()
            val editAnchor = BezierHandleEditMode.getActiveAnchor()
            val translucent = editMode &&
                (editAnchor == bc.bePositions.getFirst() || editAnchor == bc.bePositions.getSecond())
            val alpha = if (translucent) 0.35f else 1f
            val forcedRenderType = if (translucent) RenderType.translucent() else null
// skip pipe while dragging
            if (translucent && BezierHandleDragManager.isDraggingTangentHandle()) return

// cache texture/render type
            val materialCache = HashMap<ResourceLocation, Pair<
                net.minecraft.client.renderer.texture.TextureAtlasSprite, RenderType>?>()

// one cross-section subdivision
            // low-poly cross-section, density from client config
            val crossN = WaterslideTubeMesh.crossSections()

// stable frames
            val ts = FloatArray(count + 1) { i ->
                if (i == 0) 0f else if (i == count) 1f else bc.getSegmentT(i)
            }
            val centers = Array(count + 1) { worldPoint(bc, ts[it]) }
            val tangents = arrayOfNulls<Vec3>(count + 1)
            val lats = arrayOfNulls<Vec3>(count + 1)
            val ups = arrayOfNulls<Vec3>(count + 1)
            var prevLat: Vec3? = null
            for (i in 0..count) {
                var tangent = CoasterBezierRailFrames.unitTangentAt(bc, ts[i])
                if (tangent.lengthSqr() < 1.0E-12) {
                    val prevIdx = if (i > 0) i - 1 else i
                    val nextIdx = if (i < count) i + 1 else i
                    tangent = if (prevIdx != nextIdx) centers[nextIdx].subtract(centers[prevIdx])
                    else Vec3(0.0, 1.0, 0.0)
                }
                tangent = tangent.normalize()

// stable world-up frame
                var (lat, up) = SlideCurveGeometry.stableFrame(tangent)

// continuous lateral
                if (prevLat != null && lat.dot(prevLat) < 0.0) {
                    lat = lat.scale(-1.0)
                    up = up.scale(-1.0)
                }
                tangents[i] = tangent
                lats[i] = lat
                ups[i] = up
                prevLat = lat
            }

// open-end extensions
            val ext0 = openEndExtensionBlocks(level, bc, atFirst = true)
            val ext1 = openEndExtensionBlocks(level, bc, atFirst = false)
            val extCenters = ArrayList<Vec3>()
            val extTangents = ArrayList<Vec3>()
            val extLats = ArrayList<Vec3>()
            val extUps = ArrayList<Vec3>()
            val extRads = ArrayList<Float>()
            if (ext0 > 0.01f) {
                val tan = tangents[0]!!
                extCenters += centers[0].subtract(tan.scale(ext0.toDouble()))
                extTangents += tan
                extLats += lats[0]!!
                extUps += ups[0]!!
                extRads += r0
            }
            for (i in 0..count) {
                extCenters += centers[i]
                extTangents += tangents[i]!!
                extLats += lats[i]!!
                extUps += ups[i]!!
                extRads += Mth.lerp(ts[i], r0, r1)
            }
            if (ext1 > 0.01f) {
                val tan = tangents[count]!!
                extCenters += centers[count].add(tan.scale(ext1.toDouble()))
                extTangents += tan
                extLats += lats[count]!!
                extUps += ups[count]!!
                extRads += r1
            }

            var totalLen = 0f
            for (i in 0 until extCenters.size - 1) {
                totalLen += extCenters[i].distanceTo(extCenters[i + 1]).toFloat()
            }
            var arcBase = 0f
            for (i in 0 until extCenters.size - 1) {
                val center0 = extCenters[i]
                val center1 = extCenters[i + 1]
                val tan0 = extTangents[i]
                val tan1 = extTangents[i + 1]
                val lat0 = extLats[i]
                val lat1 = extLats[i + 1]
                val up0 = extUps[i]
                val up1 = extUps[i + 1]
                val rad0 = extRads[i]
                val rad1 = extRads[i + 1]
                val segLight = lightAt(level, center0.add(center1).scale(0.5))

                for (p in placed) {
                    if (p.sector.material == SectorMaterial.OPEN) continue
                    val blockId = p.sector.blockId ?: continue
                    val (sprite, renderType) = tubeMaterial(materialCache, blockId, forcedRenderType) ?: continue
                    val consumer = bufferSource.getBuffer(renderType)
                    renderSectorSegment(
                        center0, center1, lat0, lat1, up0, up1, rad0, rad1,
                        tan0, tan1, p, sprite, poseStack, consumer, crossN, alpha, segLight
                    )

                    // side walls next to open sectors
                    val idx = placed.indexOf(p)
                    val prev = placed[(idx - 1 + placed.size) % placed.size]
                    val next = placed[(idx + 1) % placed.size]
                    if (prev.sector.material == SectorMaterial.OPEN) {
                        renderSideWall(
                            center0, center1, lat0, lat1, up0, up1, rad0, rad1,
                            tan0, tan1, p.startAngle, -1f, sprite, poseStack, consumer, segLight, alpha
                        )
                    }
                    if (next.sector.material == SectorMaterial.OPEN) {
                        renderSideWall(
                            center0, center1, lat0, lat1, up0, up1, rad0, rad1,
                            tan0, tan1, p.endAngle, 1f, sprite, poseStack, consumer, segLight, alpha
                        )
                    }
                }

                // water envelope from the server-simulated sections
                if (water != null && water.exists && water.segments.isNotEmpty() && i == 0) {
                    val waterSprite = Minecraft.getInstance()
                        .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                        .apply(ResourceLocation.withDefaultNamespace("block/water_still"))
                    val waterConsumer = bufferSource.getBuffer(TUBE_WATER_TRANSLUCENT)
                    renderWaterEnvelope(
                        level, extCenters, extLats, extUps, extRads, water,
                        waterSprite, poseStack, waterConsumer
                    )
                }
                arcBase += center0.distanceTo(center1).toFloat()
            }

// end caps
            val lastIdx = extCenters.size - 1
            for (endIdx in intArrayOf(0, lastIdx)) {
                val center = extCenters[endIdx]
                val lat = extLats[endIdx]
                val up = extUps[endIdx]
                val radius = extRads[endIdx]
                val tangent = extTangents[endIdx]
                val capNormal = if (endIdx == 0) tangent.scale(-1.0) else tangent
                val capLight = lightAt(level, center)
                for (p in placed) {
                    if (p.sector.material == SectorMaterial.OPEN) continue
                    val blockId = p.sector.blockId ?: continue
                    val (sprite, renderType) = tubeMaterial(materialCache, blockId, forcedRenderType) ?: continue
                    val consumer = bufferSource.getBuffer(renderType)
                    renderEndCap(
                        center, lat, up, radius, capNormal, p, sprite,
                        poseStack, consumer, crossN, alpha, capLight,
                        reverse = endIdx == 0
                    )
                }
            }

// skeleton rings
            if (translucent && ModClientConfig.showSkeletonWhenTranslucent()) {
                for (i in 1 until lastIdx) {
                    val center = extCenters[i]
                    val lat = extLats[i]
                    val up = extUps[i]
                    val radius = extRads[i]
                    val tangent = extTangents[i]
                    val junctionLight = lightAt(level, center)
                    for (p in placed) {
                        if (p.sector.material == SectorMaterial.OPEN) continue
                        val blockId = p.sector.blockId ?: continue
                        val (sprite, renderType) = tubeMaterial(materialCache, blockId, forcedRenderType) ?: continue
                        val consumer = bufferSource.getBuffer(renderType)
                        renderEndCap(
                            center, lat, up, radius, tangent, p, sprite,
                            poseStack, consumer, crossN, alpha, junctionLight,
                            reverse = false
                        )
                        renderEndCap(
                            center, lat, up, radius, tangent, p, sprite,
                            poseStack, consumer, crossN, alpha, junctionLight,
                            reverse = true
                        )
                    }
                }
            }

            renderCrackOverlays(level, centers, lats, ups, poseStack, bufferSource)
        }

        private fun tubeMaterial(
            cache: MutableMap<ResourceLocation, Pair<
                net.minecraft.client.renderer.texture.TextureAtlasSprite, RenderType>?>,
            blockId: ResourceLocation,
            forcedRenderType: RenderType?
        ): Pair<net.minecraft.client.renderer.texture.TextureAtlasSprite, RenderType>? {
            return cache.getOrPut(blockId) {
                val block = BuiltInRegistries.BLOCK.get(blockId) ?: return@getOrPut null
                val state = block.defaultBlockState()
                val model = Minecraft.getInstance().blockRenderer.getBlockModel(state)
                val sprite = model.getParticleIcon(ModelData.EMPTY)
                val renderTypes = model.getRenderTypes(state, RandomSource.create(), ModelData.EMPTY)
                val renderType = tubeRenderType(
                    forcedRenderType,
                    renderTypes.firstOrNull() ?: RenderType.solid()
                )
                sprite to renderType
            }
        }

        private fun renderCrackOverlays(
            level: Level,
            centers: Array<Vec3>,
            lats: Array<Vec3?>,
            ups: Array<Vec3?>,
            poseStack: PoseStack,
            bufferSource: MultiBufferSource
        ) {
            val mc = Minecraft.getInstance()
            val levelRenderer = mc.levelRenderer
            if (levelRenderer !is LevelRendererAccessor) return
            val map = levelRenderer.getWaterslideDestructionProgress() ?: return
            if (map.isEmpty()) return
            val pose = poseStack.last()
            for (entry in map.long2ObjectEntrySet()) {
                val pos = BlockPos.of(entry.getLongKey())
                val set = entry.value
                val bp = set.lastOrNull() ?: continue
                val stage = bp.getProgress()
                if (stage !in 0..9) continue

                val target = Vec3.atCenterOf(pos)
                var best = -1
                var bestD = Double.MAX_VALUE
                for (i in centers.indices) {
                    val d = centers[i].distanceToSqr(target)
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                if (best < 0 || bestD > 2.25) continue

                val center = centers[best]
                val lat = lats[best] ?: continue
                val up = ups[best] ?: continue
                val tangent = when {
                    best < centers.size - 1 -> centers[best + 1].subtract(center).normalize()
                    best > 0 -> center.subtract(centers[best - 1]).normalize()
                    else -> Vec3(0.0, 1.0, 0.0)
                }
// crack overlay
                val surface = center.add(lat.scale(0.5))
                val consumer = bufferSource.getBuffer(
                    RenderType.crumbling(TextureAtlas.LOCATION_BLOCKS)
                )
                val destroySprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.withDefaultNamespace("block/destroy_stage_$stage"))
                val hw = 0.45
                val hh = 0.45
                val tS = tangent.scale(hw)
                val uS = up.scale(hh)
                val p0 = surface.subtract(tS).subtract(uS)
                val p1 = surface.add(tS).subtract(uS)
                val p2 = surface.add(tS).add(uS)
                val p3 = surface.subtract(tS).add(uS)
                crackVertex(consumer, pose, destroySprite, p0, 0f, 1f)
                crackVertex(consumer, pose, destroySprite, p1, 1f, 1f)
                crackVertex(consumer, pose, destroySprite, p2, 1f, 0f)
                crackVertex(consumer, pose, destroySprite, p3, 0f, 0f)
            }
        }

        private fun crackVertex(
            consumer: VertexConsumer,
            pose: PoseStack.Pose,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            p: Vec3,
            u: Float,
            v: Float
        ) {
            consumer.addVertex(pose, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                .setColor(1f, 1f, 1f, 1f)
                .setUv(sprite.getU(u), sprite.getV(v))
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0f, 1f, 0f)
        }

        private fun renderEndCap(
            center: Vec3,
            lat: Vec3,
            up: Vec3,
            radius: Float,
            normal: Vec3,
            placed: PlacedSector,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            poseStack: PoseStack,
            consumer: VertexConsumer,
            crossN: Int,
            alpha: Float,
            light: Int,
            reverse: Boolean
        ) {
            val inner = (radius - WALL_THICKNESS).coerceAtLeast(0.001f)
            val outer = radius + (ModClientConfig.wallThickness() - WALL_THICKNESS)
            val pose = poseStack.last()
            val width = placed.sectorWidthDegrees()
            if (width <= 0.001f) return
            val texW = sprite.contents().width()
            val texH = sprite.contents().height()
            val border = ModConfig.sectorBorderPx().toFloat()
            val midRadius = (outer + inner) / 2f
            val capTargetW = Math.toRadians(width.toDouble()).toFloat() * midRadius * PIXELS_PER_BLOCK
            val capTargetH = (outer - inner) * PIXELS_PER_BLOCK
            fun capUv(u: Float, v: Float): Pair<Float, Float> =
                nineSliceUv(u, v, capTargetW, capTargetH, texW, texH, border)
            val startNorm = WaterslideSectorLayout.normalize(placed.startAngle)
            val intervals = if (startNorm + width <= 360f)
                listOf(startNorm to startNorm + width)
            else
                listOf(startNorm to 360f, 0f to startNorm + width - 360f)
            val degStep = 360f / crossN
            val gridAnchor = 90f
            for ((lo, hi) in intervals) {
                val wrap = if (lo == startNorm) 0f else 360f
                for (j in 0 until crossN) {
                    val raw0 = gridAnchor + j * degStep
                    val raw1 = gridAnchor + (j + 1) * degStep
                    val cells = if (raw1 <= 360f) listOf(raw0 to raw1)
                    else if (raw0 >= 360f) listOf(raw0 - 360f to raw1 - 360f)
                    else listOf(raw0 to 360f, 0f to raw1 - 360f)
                    for ((cg0, cg1) in cells) {
                        val s = max(cg0, lo)
                        val e = min(cg1, hi)
                        if (e <= s) continue
                        val u0 = (s + wrap - startNorm) / width
                        val u1 = (e + wrap - startNorm) / width
                        val o0 = tubePoint(center, lat, up, outer, s)
                        val o1 = tubePoint(center, lat, up, outer, e)
                        val i0 = tubePoint(center, lat, up, inner, s)
                        val i1 = tubePoint(center, lat, up, inner, e)
                        if (reverse) {
// reverse winding
                            quad(
                                consumer, pose, sprite,
                                o0, i0, i1, o1,
                                normal, normal, normal, normal,
                                capUv(u0, 1f), capUv(u0, 0f), capUv(u1, 0f), capUv(u1, 1f),
                                light, light, light, light,
                                shadeFor(normal, 0.8f), shadeFor(normal, 0.8f),
                                shadeFor(normal, 0.8f), shadeFor(normal, 0.8f),
                                alpha
                            )
                        } else {
                            quad(
                                consumer, pose, sprite,
                                o0, o1, i1, i0,
                                normal, normal, normal, normal,
                                capUv(u0, 1f), capUv(u1, 1f), capUv(u1, 0f), capUv(u0, 0f),
                                light, light, light, light,
                                shadeFor(normal, 0.8f), shadeFor(normal, 0.8f),
                                shadeFor(normal, 0.8f), shadeFor(normal, 0.8f),
                                alpha
                            )
                        }
                    }
                }
            }
        }

        private fun renderSectorSegment(
            center0: Vec3,
            center1: Vec3,
            lat0: Vec3,
            lat1: Vec3,
            up0: Vec3,
            up1: Vec3,
            rad0: Float,
            rad1: Float,
            tan0: Vec3,
            tan1: Vec3,
            placed: PlacedSector,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            poseStack: PoseStack,
            consumer: VertexConsumer,
            crossN: Int,
            alpha: Float,
            segLight: Int
        ) {
            val texW = sprite.contents().width()
            val texH = sprite.contents().height()
            val border = ModConfig.sectorBorderPx().toFloat()
            val wallExtra = ModClientConfig.wallThickness() - WALL_THICKNESS
            val avgRadius = (rad0 + rad1) / 2f + wallExtra
            val arcLength = placed.sectorWidthRadians() * avgRadius
            val targetW = arcLength * PIXELS_PER_BLOCK
            val targetH = WaterslideTubeMesh.bezierArcLength(center0, center1, tan0, tan1) * PIXELS_PER_BLOCK
            val m = max(1, Math.ceil((targetH / TILE_SUBDIVISION_PX).toDouble()).toInt())
            val pose = poseStack.last()

            val width = placed.sectorWidthDegrees()
            if (width <= 0.001f) return
            val startNorm = WaterslideSectorLayout.normalize(placed.startAngle)
            val intervals = if (startNorm + width <= 360f)
                listOf(startNorm to startNorm + width)
            else
                listOf(startNorm to 360f, 0f to startNorm + width - 360f)
            val degStep = 360f / crossN
            val gridAnchor = 90f
            for ((lo, hi) in intervals) {
                val wrap = if (lo == startNorm) 0f else 360f
                for (j in 0 until crossN) {
                    val raw0 = gridAnchor + j * degStep
                    val raw1 = gridAnchor + (j + 1) * degStep
                    val cells = if (raw1 <= 360f) listOf(raw0 to raw1)
                    else if (raw0 >= 360f) listOf(raw0 - 360f to raw1 - 360f)
                    else listOf(raw0 to 360f, 0f to raw1 - 360f)
                    for ((cg0, cg1) in cells) {
                        val s = max(cg0, lo)
                        val e = min(cg1, hi)
                        if (e <= s) continue
                        val u0 = (s + wrap - startNorm) / width
                        val u1 = (e + wrap - startNorm) / width
                        for (k in 0 until m) {
                        val v0 = k.toFloat() / m.toFloat()
                        val v1 = (k + 1).toFloat() / m.toFloat()

                        val deg0 = s
                        val deg1 = e
                        val p00 = tubePoint(center0, lat0, up0, rad0 + wallExtra, deg0)
                        val p10 = tubePoint(center1, lat1, up1, rad1 + wallExtra, deg0)
                        val p11 = tubePoint(center1, lat1, up1, rad1 + wallExtra, deg1)
                        val p01 = tubePoint(center0, lat0, up0, rad0 + wallExtra, deg1)

                        val uv00 = nineSliceUv(u0, v0, targetW, targetH, texW, texH, border)
                        val uv10 = nineSliceUv(u0, v1, targetW, targetH, texW, texH, border)
                        val uv11 = nineSliceUv(u1, v1, targetW, targetH, texW, texH, border)
                        val uv01 = nineSliceUv(u1, v0, targetW, targetH, texW, texH, border)

                        val n00 = radialNormal(p00, center0)
                        val n01 = radialNormal(p01, center0)
                        val n11 = radialNormal(p11, center1)
                        val n10 = radialNormal(p10, center1)
                        quad(
                            consumer, pose, sprite,
                            p00, p01, p11, p10,
                            n00, n01, n11, n10,
                            uv00, uv01, uv11, uv10,
                            segLight, segLight, segLight, segLight,
                            shadeFor(n00, 1f), shadeFor(n01, 1f),
                            shadeFor(n11, 1f), shadeFor(n10, 1f),
                            alpha
                        )

                        val rIn0 = rad0 - WALL_THICKNESS
                        val rIn1 = rad1 - WALL_THICKNESS
                        val i00 = tubePoint(center0, lat0, up0, rIn0, deg0)
                        val i10 = tubePoint(center1, lat1, up1, rIn1, deg0)
                        val i11 = tubePoint(center1, lat1, up1, rIn1, deg1)
                        val i01 = tubePoint(center0, lat0, up0, rIn0, deg1)
                        val in00 = radialNormal(i00, center0).scale(-1.0)
                        val in10 = radialNormal(i10, center1).scale(-1.0)
                        val in11 = radialNormal(i11, center1).scale(-1.0)
                        val in01 = radialNormal(i01, center0).scale(-1.0)
                        quad(
                            consumer, pose, sprite,
                            i00, i10, i11, i01,
                            in00, in10, in11, in01,
                            uv00, uv10, uv11, uv01,
                            segLight, segLight, segLight, segLight,
                            shadeFor(in00, 0.55f), shadeFor(in10, 0.55f),
                            shadeFor(in11, 0.55f), shadeFor(in01, 0.55f),
                            alpha
                        )
                        }
                    }
                }
            }
        }

        private fun renderWaterEnvelope(
            level: Level,
            centers: List<Vec3>,
            lats: List<Vec3>,
            ups: List<Vec3>,
            rads: List<Float>,
            water: WaterFlowSimulation.CurveWater,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            poseStack: PoseStack,
            consumer: VertexConsumer
        ) {
            val pose = poseStack.last()
            val light = LightTexture.FULL_BRIGHT
            val flowScale = ModClientConfig.waterFlowScale()
            val time = AnimationTickHolder.getRenderTime(level)
            // fixed fallback length; the server segment length is a server config
            val segLen = 0.5f
            val frameArc = FloatArray(centers.size)
            for (i in 1 until centers.size) {
                frameArc[i] = frameArc[i - 1] + centers[i - 1].distanceTo(centers[i]).toFloat()
            }
            val totalLen = frameArc.lastOrNull() ?: return

            fun frameAt(arc: Float): Int {
                for (i in 1 until centers.size) {
                    if (frameArc[i] >= arc) return i - 1
                }
                return centers.size - 2
            }

            // band ring angles on the same grid as the tube wall (90 + k*degStep)
            val crossN = WaterslideTubeMesh.crossSections()
            val degStep = 360f / crossN
            val gridAnchor = 90f
            val angles = ArrayList<Float>()
            for (k in 0 until crossN) {
                val raw0 = gridAnchor + k * degStep
                val raw1 = gridAnchor + (k + 1) * degStep
                val cells = if (raw1 <= 360f) listOf(raw0 to raw1)
                else if (raw0 >= 360f) listOf(raw0 - 360f to raw1 - 360f)
                else listOf(raw0 to 360f, 0f to raw1 - 360f)
                for ((cg0, cg1) in cells) {
                    val s = max(cg0, 210f)
                    val e = min(cg1, 330f)
                    if (e <= s) continue
                    if (angles.lastOrNull()?.let { abs(it - s) < 0.01f } != true) angles += s
                    angles += e
                }
            }
            val sorted = angles.map { a -> if (a < 210f - 0.01f) a + 360f else a }.sorted()
            val ring = ArrayList<Float>(sorted.size * 2)
            for (a in sorted) ring += a
            for (a in sorted.asReversed()) ring += a
            val half = ring.size / 2
            for (seg in water.segments) {
                // each segment flows along its own sampled direction
                val segForward = seg.speed >= 0f
                // fixed depth like the thrown water sheet
                val depth = 0.25f
                val arc0 = (if (segForward) seg.arc else totalLen - seg.arc).coerceIn(0f, totalLen)
                val arc1 = (arc0 + segLen).coerceAtMost(totalLen)
                val fi0 = frameAt(arc0)
                val fi1 = frameAt(arc1)
                val c0 = centers[fi0]
                val c1 = centers[fi1]
                val la0 = lats[fi0]
                val la1 = lats[fi1]
                val up0 = ups[fi0]
                val up1 = ups[fi1]
                val rIn0 = (rads[fi0] - WALL_THICKNESS).coerceAtLeast(0.05f)
                val rIn1 = (rads[fi1] - WALL_THICKNESS).coerceAtLeast(0.05f)
                val rSurf0 = (rIn0 - depth).coerceAtLeast(0.01f)
                val rSurf1 = (rIn1 - depth).coerceAtLeast(0.01f)
                val speed = abs(seg.speed) * WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK * flowScale
                val flow = -time * speed
                val tiles = (2.0 * Math.PI * rIn0).toFloat().coerceAtLeast(0.5f)
                fun drawRingQuad(k: Int, j: Int) {
                    val aK = ring[k]
                    val aJ = ring[j]
                    val rK0 = if (k < half) rIn0 else rSurf0
                    val rK1 = if (k < half) rIn1 else rSurf1
                    val rJ0 = if (j < half) rIn0 else rSurf0
                    val rJ1 = if (j < half) rIn1 else rSurf1
                    val p0 = tubePoint(c0, la0, up0, rK0, aK)
                    val p1 = tubePoint(c1, la1, up1, rK1, aK)
                    val p2 = tubePoint(c1, la1, up1, rJ1, aJ)
                    val p3 = tubePoint(c0, la0, up0, rJ0, aJ)
                    val uK = k.toFloat() / ring.size * tiles
                    val uJ = j.toFloat() / ring.size * tiles
                    val v0 = mod(arc0 * WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK + flow, 1f)
                    val v1 = mod(arc1 * WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK + flow, 1f)
                    quad(
                        consumer, pose, sprite,
                        p0, p1, p2, p3,
                        p0, p1, p2, p3,
                        uK to v0, uK to v1, uJ to v1, uJ to v0,
                        light, light, light, light,
                        0.55f, 0.55f, 0.55f, 0.55f,
                        0.75f, if (k >= half) WATER_SURFACE_TINT else WATER_TINT
                    )
                }
                // band strips: bottom arc and top arc, no closing end walls
                for (k in 0 until half - 1) {
                    drawRingQuad(k, k + 1)
                }
                for (k in half until ring.size - 1) {
                    drawRingQuad(k, k + 1)
                }
            }
        }

        // Falling water sheet after the slide outlet (CPU fallback).
        private fun renderStreams(
            water: WaterFlowSimulation.CurveWater,
            poseStack: PoseStack,
            bufferSource: MultiBufferSource
        ) {
            val exit = water.exit ?: return
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return
            val sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(ResourceLocation.withDefaultNamespace("block/water_still"))
            val consumer = bufferSource.getBuffer(TUBE_STREAM_TRANSLUCENT)
            val pose = poseStack.last()
            val light = LightTexture.FULL_BRIGHT
            val (lat, up) = SlideCurveGeometry.stableFrame(exit.vel.normalize())
            val count = 8
            val outer = ArrayList<List<Vec3>>(count)
            val inner = ArrayList<List<Vec3>>(count)
            for (i in 0 until count) {
                val theta = Math.toRadians((i + 0.5) / count * 120.0 + 210.0)
                val c = Math.cos(theta)
                val s = Math.sin(theta)
                val pOut = exit.pos.add(lat.scale(c * 0.9)).add(up.scale(s * 0.9))
                val pIn = exit.pos.add(lat.scale(c * 0.6)).add(up.scale(s * 0.6))
                outer += traceFall(level, pOut, exit.vel)
                inner += traceFall(level, pIn, exit.vel)
            }
            val maxSamples = minOf(
                outer.minOfOrNull { it.size } ?: return,
                inner.minOfOrNull { it.size } ?: return
            )
            if (maxSamples < 2) return
            val samples = maxSamples
            val cum = FloatArray(samples)
            for (k in 1 until samples) {
                cum[k] = cum[k - 1] + outer[0][k - 1].distanceTo(outer[0][k]).toFloat()
            }
            val flow = -AnimationTickHolder.getRenderTime(level) * STREAM_FLOW_SPEED
            fun vAt(k: Int): Float = mod(
                cum[k] * WaterFlowSimulation.WATER_V_CYCLES_PER_BLOCK + flow, 1f
            )
            val maxSegments = max(4, (48 * ModClientConfig.polygonScale()).roundToInt())
            val stride = max(1, (samples - 1) / maxSegments)

            fun sheet(
                a: List<Vec3>,
                b: List<Vec3>,
                u0: Float,
                u1: Float,
                tint: FloatArray,
                alpha: Float,
                flip: Boolean
            ) {
                for (k in 0 until samples - 1 step stride) {
                    val k1 = minOf(k + stride, samples - 1)
                    val a0 = a[k]
                    val a1 = a[k1]
                    val b1 = b[k1]
                    val b0 = b[k]
                    val edge1 = a1.subtract(a0)
                    val edge2 = b0.subtract(a0)
                    var normal = edge1.cross(edge2).normalize()
                    if (flip) normal = normal.scale(-1.0)
                    vertex(consumer, pose, sprite, a0, normal, u0 to vAt(k), light, 0.7f, alpha, tint)
                    vertex(consumer, pose, sprite, a1, normal, u0 to vAt(k1), light, 0.7f, alpha, tint)
                    vertex(consumer, pose, sprite, b1, normal, u1 to vAt(k1), light, 0.7f, alpha, tint)
                    vertex(consumer, pose, sprite, b0, normal, u1 to vAt(k), light, 0.7f, alpha, tint)
                }
            }

            val n = outer.size
            val colArc = FloatArray(n)
            for (i in 1 until n) {
                colArc[i] = colArc[i - 1] + outer[i - 1][0].distanceTo(outer[i][0]).toFloat()
            }
            for (i in 0 until n - 1) {
                val u0 = mod(colArc[i], 1f)
                val u1 = mod(colArc[i + 1], 1f)
                sheet(outer[i], outer[i + 1], u0, u1, WATER_TINT, 0.75f, false)
                sheet(inner[i], inner[i + 1], u0, u1, WATER_SURFACE_TINT, 0.95f, true)
            }
        }

        private fun traceFall(level: Level, pos: Vec3, vel: Vec3): List<Vec3> {
            val dt = 0.5
            val poly = ArrayList<Vec3>()
            var p = pos
            var v = vel
            poly += p
            for (step in 0 until 400) {
                p = p.add(v.scale(dt / 20.0))
                v = v.add(0.0, -32.0 * dt / 20.0, 0.0)
                val bp = BlockPos.containing(p)
                if (level.getBlockState(bp).isSolid || p.y < level.minBuildHeight) break
                if (step % 2 == 0) poly += p
            }
            return poly
        }

        private fun renderSideWall(
            center0: Vec3,
            center1: Vec3,
            lat0: Vec3,
            lat1: Vec3,
            up0: Vec3,
            up1: Vec3,
            rad0: Float,
            rad1: Float,
            tan0: Vec3,
            tan1: Vec3,
            angleDeg: Float,
            dir: Float,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            poseStack: PoseStack,
            consumer: VertexConsumer,
            light: Int,
            alpha: Float
        ) {
            val pose = poseStack.last()
            val a = Math.toRadians(angleDeg.toDouble())
            val sin = Math.sin(a).toFloat()
            val cos = Math.cos(a).toFloat()
            val texW = sprite.contents().width()
            val texH = sprite.contents().height()
            val border = ModConfig.sectorBorderPx().toFloat()
            val sideTargetW = ModClientConfig.wallThickness() * PIXELS_PER_BLOCK
            val sideTargetH = WaterslideTubeMesh.bezierArcLength(center0, center1, tan0, tan1) * PIXELS_PER_BLOCK
            fun sideUv(u: Float, v: Float): Pair<Float, Float> =
                nineSliceUv(u, v, sideTargetW, sideTargetH, texW, texH, border)
            val inner0 = rad0 - WALL_THICKNESS
            val inner1 = rad1 - WALL_THICKNESS
            val wallExtra = ModClientConfig.wallThickness() - WALL_THICKNESS
            val o0 = tubePoint(center0, lat0, up0, rad0 + wallExtra, angleDeg)
            val i0 = tubePoint(center0, lat0, up0, inner0, angleDeg)
            val o1 = tubePoint(center1, lat1, up1, rad1 + wallExtra, angleDeg)
            val i1 = tubePoint(center1, lat1, up1, inner1, angleDeg)
            val n0 = lat0.scale((-sin * dir).toDouble())
                .add(up0.scale((cos * dir).toDouble()))
                .normalize()
            val n1 = lat1.scale((-sin * dir).toDouble())
                .add(up1.scale((cos * dir).toDouble()))
                .normalize()
            if (dir > 0f) {
                quad(
                    consumer, pose, sprite,
                    o0, i0, i1, o1,
                    n0, n0, n1, n1,
                    sideUv(0f, 0f), sideUv(1f, 0f), sideUv(1f, 1f), sideUv(0f, 1f),
                    light, light, light, light,
                    0.7f, 0.7f, 0.7f, 0.7f,
                    alpha
                )
            } else {
                quad(
                    consumer, pose, sprite,
                    o0, o1, i1, i0,
                    n0, n0, n1, n1,
                    sideUv(0f, 0f), sideUv(0f, 1f), sideUv(1f, 1f), sideUv(1f, 0f),
                    light, light, light, light,
                    0.7f, 0.7f, 0.7f, 0.7f,
                    alpha
                )
            }
        }

        private fun lightAt(level: Level, p: Vec3): Int =
            LevelRenderer.getLightColor(level, BlockPos.containing(p))

        private fun tubeRenderType(forced: RenderType?, base: RenderType): RenderType =
            if (forced != null) TUBE_TRANSLUCENT
            else when (base) {
                RenderType.translucent() -> TUBE_TRANSLUCENT
                RenderType.cutout(), RenderType.cutoutMipped() -> TUBE_CUTOUT
                else -> TUBE_SOLID
            }

// diffuse shading
        private fun shadeFor(normal: Vec3, surfaceFactor: Float): Float {
            val top = max(0f, normal.y.toFloat())
            return surfaceFactor * (0.7f + 0.3f * top)
        }

        private fun PlacedSector.sectorWidthDegrees(): Float = endAngle - startAngle

        private fun PlacedSector.sectorWidthRadians(): Float =
            Math.toRadians(sectorWidthDegrees().toDouble()).toFloat()

        private fun tubePoint(center: Vec3, lat: Vec3, up: Vec3, radius: Float, degrees: Float): Vec3 {
            val rad = Math.toRadians(degrees.toDouble())
            val r = radius.toDouble()
            return center
                .add(lat.scale(Math.cos(rad) * r))
                .add(up.scale(Math.sin(rad) * r))
        }

        private fun radialNormal(p: Vec3, center: Vec3): Vec3 =
            p.subtract(center).normalize()

// tiled UV
        private fun nineSliceUv(
            u01: Float,
            v01: Float,
            targetW: Float,
            targetH: Float,
            texW: Int,
            texH: Int,
            border: Float
        ): Pair<Float, Float> {
            val px = u01 * targetW
            val py = v01 * targetH
            val centerW = (texW.toFloat() - border * 2f).coerceAtLeast(1f)
            val centerH = (texH.toFloat() - border * 2f).coerceAtLeast(1f)
            val uf = mod(border + mod(px, centerW), texW.toFloat()) / texW.toFloat()
            val vf = mod(border + mod(py, centerH), texH.toFloat()) / texH.toFloat()
            return uf to vf
        }

        private fun smoothstep01(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun mod(v: Float, m: Float): Float = ((v % m) + m) % m

        private fun quad(
            consumer: VertexConsumer,
            pose: PoseStack.Pose,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            a: Vec3,
            b: Vec3,
            c: Vec3,
            d: Vec3,
            na: Vec3,
            nb: Vec3,
            nc: Vec3,
            nd: Vec3,
            uva: Pair<Float, Float>,
            uvb: Pair<Float, Float>,
            uvc: Pair<Float, Float>,
            uvd: Pair<Float, Float>,
            lightA: Int,
            lightB: Int,
            lightC: Int,
            lightD: Int,
            shadeA: Float,
            shadeB: Float,
            shadeC: Float,
            shadeD: Float,
            alpha: Float,
            tint: FloatArray? = null
        ) {
            // flat face: one normal and one shade for the whole quad
            val fn = b.subtract(a).cross(d.subtract(a)).normalize()
            val shade = (shadeA + shadeB + shadeC + shadeD) / 4f
            vertex(consumer, pose, sprite, a, fn, uva, lightA, shade, alpha, tint)
            vertex(consumer, pose, sprite, b, fn, uvb, lightB, shade, alpha, tint)
            vertex(consumer, pose, sprite, c, fn, uvc, lightC, shade, alpha, tint)
            vertex(consumer, pose, sprite, d, fn, uvd, lightD, shade, alpha, tint)
        }

        private fun vertex(
            consumer: VertexConsumer,
            pose: PoseStack.Pose,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            p: Vec3,
            normal: Vec3,
            uv: Pair<Float, Float>,
            light: Int,
            shade: Float,
            alpha: Float,
            tint: FloatArray? = null
        ) {
            val r = tint?.get(0) ?: shade
            val g = tint?.get(1) ?: shade
            val b = tint?.get(2) ?: shade
            consumer.addVertex(pose, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                .setColor(r, g, b, alpha)
                .setUv(sprite.getU(uv.first), sprite.getV(uv.second))
                .setLight(light)
                .setNormal(pose, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
        }

        private fun radiusAt(level: Level, pos: BlockPos): Float =
            WaterslideRadiusEdit.radiusAt(level, pos, ModConfig.defaultSlideRadius())

// open-end extension length
        private fun openEndExtensionBlocks(
            level: Level,
            bc: com.simibubi.create.content.trains.track.BezierConnection,
            atFirst: Boolean
        ): Float {
            val anchor = if (atFirst) bc.bePositions.getFirst() else bc.bePositions.getSecond()
            val be = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return 0f
            if (be.legCount() != 1) return 0f
            return CoasterOpenEndExtension.extensionBlocks(level, anchor)
        }

        private fun worldPoint(
            bc: com.simibubi.create.content.trains.track.BezierConnection,
            t: Float
        ): Vec3 = bc.getPosition(t.toDouble())

// selection outline

        private data class OutlineSample(val center: Vec3, val lat: Vec3, val up: Vec3, val half: Float)

// CCS outline mixin
        @JvmStatic
        fun renderSelectionOutline(
            poseStack: PoseStack,
            bufferSource: MultiBufferSource,
            camera: Vec3,
            level: Level,
            bc: com.simibubi.create.content.trains.track.BezierConnection,
            partialTick: Float,
            r: Float,
            g: Float,
            b: Float,
            a: Float
        ) {
            val primary = if (bc.isPrimary) bc else bc.secondary()
            if (!WaterslideTrackMaterials.isWaterslide(primary)) return
            val r0 = radiusAt(level, primary.bePositions.getFirst())
            val r1 = radiusAt(level, primary.bePositions.getSecond())
            val samples = outlineSamples(level, primary, r0, r1)
            if (samples.size < 2) return

            val lines = bufferSource.getBuffer(RenderType.lines())
            poseStack.pushPose()
            poseStack.translate(-camera.x, -camera.y, -camera.z)
            val pose = poseStack.last()
            for (i in 1 until samples.size) {
                val prev = samples[i - 1]
                val curr = samples[i]
                for (c in 0 until 4) {
                    val (u0, v0) = outlineCorner(c, prev.half)
                    val (u1, v1) = outlineCorner(c, curr.half)
                    outlineSegment(
                        lines, pose,
                        prev.center.add(prev.lat.scale(u0.toDouble())).add(prev.up.scale(v0.toDouble())),
                        curr.center.add(curr.lat.scale(u1.toDouble())).add(curr.up.scale(v1.toDouble())),
                        r, g, b, a
                    )
                }
            }
            outlineEndRing(lines, pose, samples.first(), r, g, b, a)
            outlineEndRing(lines, pose, samples.last(), r, g, b, a)
            poseStack.popPose()
        }

        private fun outlineSamples(
            level: Level,
            bc: com.simibubi.create.content.trains.track.BezierConnection,
            r0: Float,
            r1: Float
        ): List<OutlineSample> {
            val count = bc.getSegmentCount().coerceAtLeast(1)
            val out = ArrayList<OutlineSample>(count + 3)
            var prevLat: Vec3? = null

            fun addSample(center: Vec3, t: Float) {
                var lat = CoasterBezierRailFrames.lateralAt(bc, t, level)
                var up = CoasterBezierRailFrames.faceUpAt(bc, t, level)
                val valid = lat.lengthSqr() > 1.0E-12 &&
                    up.lengthSqr() > 1.0E-12 &&
                    !lat.x.isNaN() && !lat.y.isNaN() && !lat.z.isNaN() &&
                    !up.x.isNaN() && !up.y.isNaN() && !up.z.isNaN()
                if (!valid) {
                    var tangent = CoasterBezierRailFrames.unitTangentAt(bc, t)
                    if (tangent.lengthSqr() < 1.0E-12) tangent = Vec3(0.0, 1.0, 0.0)
                    tangent = tangent.normalize()
                    var fallbackUp = Vec3(0.0, 1.0, 0.0)
                    if (abs(tangent.y) > 0.999) fallbackUp = Vec3(1.0, 0.0, 0.0)
                    fallbackUp = fallbackUp.subtract(tangent.scale(fallbackUp.dot(tangent)))
                    if (fallbackUp.lengthSqr() < 1.0E-12) {
                        fallbackUp = Vec3(0.0, 0.0, 1.0).subtract(tangent.scale(tangent.z))
                    }
                    fallbackUp = fallbackUp.normalize()
                    lat = fallbackUp.cross(tangent)
                    if (lat.lengthSqr() < 1.0E-12) {
                        lat = Vec3(0.0, 0.0, 1.0).cross(tangent)
                    }
                    lat = lat.normalize()
                    up = tangent.cross(lat).normalize()
                } else {
                    lat = lat.normalize()
                    up = up.normalize()
                }
                if (prevLat != null && lat.dot(prevLat!!) < 0.0) {
                    lat = lat.scale(-1.0)
                    up = up.scale(-1.0)
                }
                prevLat = lat
                out += OutlineSample(center, lat, up, Mth.lerp(t, r0, r1) + 0.45f)
            }

            val ext0 = openEndExtensionBlocks(level, bc, atFirst = true)
            val ext1 = openEndExtensionBlocks(level, bc, atFirst = false)
            if (ext0 > 0.01f) {
                val tan = CoasterBezierRailFrames.unitTangentAt(bc, 0f).normalize()
                addSample(worldPoint(bc, 0f).subtract(tan.scale(ext0.toDouble())), 0f)
            }
            for (i in 0..count) {
                val t = if (i == 0) 0f else if (i == count) 1f else bc.getSegmentT(i)
                addSample(worldPoint(bc, t), t)
            }
            if (ext1 > 0.01f) {
                val tan = CoasterBezierRailFrames.unitTangentAt(bc, 1f).normalize()
                addSample(worldPoint(bc, 1f).add(tan.scale(ext1.toDouble())), 1f)
            }
            return out
        }

        private fun outlineCorner(corner: Int, half: Float): Pair<Float, Float> = when (corner) {
            0 -> -half to -half
            1 -> half to -half
            2 -> half to half
            else -> -half to half
        }

        private fun outlineEndRing(
            lines: VertexConsumer,
            pose: PoseStack.Pose,
            sample: OutlineSample,
            r: Float,
            g: Float,
            b: Float,
            a: Float
        ) {
            for (c in 0 until 4) {
                val (u0, v0) = outlineCorner(c, sample.half)
                val (u1, v1) = outlineCorner((c + 1) % 4, sample.half)
                outlineSegment(
                    lines, pose,
                    sample.center.add(sample.lat.scale(u0.toDouble())).add(sample.up.scale(v0.toDouble())),
                    sample.center.add(sample.lat.scale(u1.toDouble())).add(sample.up.scale(v1.toDouble())),
                    r, g, b, a
                )
            }
        }

        private fun outlineSegment(
            lines: VertexConsumer,
            pose: PoseStack.Pose,
            p0: Vec3,
            p1: Vec3,
            r: Float,
            g: Float,
            b: Float,
            alpha: Float
        ) {
            val dx = (p1.x - p0.x).toFloat()
            val dy = (p1.y - p0.y).toFloat()
            val dz = (p1.z - p0.z).toFloat()
            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1.0E-6f) return
            val nx = dx / len
            val ny = dy / len
            val nz = dz / len
            lines.addVertex(pose, p0.x.toFloat(), p0.y.toFloat(), p0.z.toFloat())
                .setColor(r, g, b, alpha)
                .setNormal(pose, nx, ny, nz)
            lines.addVertex(pose, p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat())
                .setColor(r, g, b, alpha)
                .setNormal(pose, nx, ny, nz)
        }
    }
}
