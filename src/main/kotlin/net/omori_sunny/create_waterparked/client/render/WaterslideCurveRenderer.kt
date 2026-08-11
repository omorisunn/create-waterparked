package net.omori_sunny.create_waterparked.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.engine_room.flywheel.api.visualization.VisualizationManager
import net.createmod.catnip.animation.AnimationTickHolder
import net.omori_sunny.create_waterparked.CreateWaterparked
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterOpenEndExtension
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.mixin.client.LevelRendererAccessor
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideSectorEdit
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.PlacedSector
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        private const val WATER_DEPTH = 0.12f
        private const val WATER_FLOW_SPEED = 0.025f
        private const val WATER_BAND_START = 210f
        private const val WATER_BAND_END = 330f

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
// flush pipe batches
            endBatches(bufferSource)
        }

        @JvmStatic
        fun endBatches(bufferSource: MultiBufferSource) {
            if (bufferSource is MultiBufferSource.BufferSource) {
                bufferSource.endBatch(TUBE_SOLID)
                bufferSource.endBatch(TUBE_CUTOUT)
                bufferSource.endBatch(TUBE_TRANSLUCENT)
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
                // Flywheel owns rendering when active.
                if (flywheelActive) continue
                val config = WaterslideSectorEdit.previewConfigFor(
                    primary.bePositions.getFirst(), primary.bePositions.getSecond()
                ) ?: be.sectorConfigFor(peer)
                renderCurve(level, primary, config, poseStack, bufferSource, be.isCurveWatered(peer))
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
                renderCurve(level, primary, config, poseStack, bufferSource, self?.isCurveWatered(peer) ?: false)
            }

            poseStack.popPose()
        }

        private fun renderCurve(
            level: Level,
            bc: com.simibubi.create.content.trains.track.BezierConnection,
            config: WaterslideSectorConfig,
            poseStack: PoseStack,
            bufferSource: MultiBufferSource,
            watered: Boolean
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
            val maxR = max(r0, r1)
            val crossN = max(
                8,
                Math.ceil((2.0 * Math.PI * maxR * PIXELS_PER_BLOCK / TILE_SUBDIVISION_PX)).toInt()
            )

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

// prefer CCS frame
                var lat = CoasterBezierRailFrames.lateralAt(bc, ts[i], tangent, level)
                var up = tangent.cross(lat)
                val ccsValid = lat.lengthSqr() > 1.0E-12 &&
                    up.lengthSqr() > 1.0E-12 &&
                    !lat.x.isNaN() && !lat.y.isNaN() && !lat.z.isNaN() &&
                    !up.x.isNaN() && !up.y.isNaN() && !up.z.isNaN()
                if (!ccsValid) {
                    var fallbackUp = Vec3(0.0, 1.0, 0.0)
                    if (abs(tangent.y) > 0.999) {
                        fallbackUp = Vec3(1.0, 0.0, 0.0)
                    }
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

            var waterVBase = 0f
            for (i in 0 until extCenters.size - 1) {
                val center0 = extCenters[i]
                val center1 = extCenters[i + 1]
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
                        p, sprite, poseStack, consumer, crossN, alpha, segLight
                    )

                    // side walls next to open sectors
                    val idx = placed.indexOf(p)
                    val prev = placed[(idx - 1 + placed.size) % placed.size]
                    val next = placed[(idx + 1) % placed.size]
                    if (prev.sector.material == SectorMaterial.OPEN) {
                        renderSideWall(
                            center0, center1, lat0, lat1, up0, up1, rad0, rad1,
                            p.startAngle, -1f, sprite, poseStack, consumer, segLight, alpha
                        )
                    }
                    if (next.sector.material == SectorMaterial.OPEN) {
                        renderSideWall(
                            center0, center1, lat0, lat1, up0, up1, rad0, rad1,
                            p.endAngle, 1f, sprite, poseStack, consumer, segLight, alpha
                        )
                    }
                }

                // water band: lower 120 degrees, inner surface
                if (watered) {
                    val waterSprite = Minecraft.getInstance()
                        .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                        .apply(ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "block/water_slide_water"))
                    val waterConsumer = bufferSource.getBuffer(TUBE_TRANSLUCENT)
                    renderWaterSegment(
                        level, center0, center1, lat0, lat1, up0, up1, rad0, rad1,
                        placed, waterSprite, poseStack, waterConsumer, crossN, segLight, waterVBase
                    )
                }
                waterVBase += center0.distanceTo(center1).toFloat()
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
            val pose = poseStack.last()
            val start = placed.startAngle
            val width = placed.sectorWidthDegrees()
            for (j in 0 until crossN) {
                val u0 = j.toFloat() / crossN.toFloat()
                val u1 = (j + 1).toFloat() / crossN.toFloat()
                val a0 = start + width * u0
                val a1 = start + width * u1
                val o0 = tubePoint(center, lat, up, radius, a0)
                val o1 = tubePoint(center, lat, up, radius, a1)
                val i0 = tubePoint(center, lat, up, inner, a0)
                val i1 = tubePoint(center, lat, up, inner, a1)
                if (reverse) {
// reverse winding
                    quad(
                        consumer, pose, sprite,
                        o0, i0, i1, o1,
                        normal, normal, normal, normal,
                        u0 to 1f, u0 to 0f, u1 to 0f, u1 to 1f,
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
                        u0 to 1f, u1 to 1f, u1 to 0f, u0 to 0f,
                        light, light, light, light,
                        shadeFor(normal, 0.8f), shadeFor(normal, 0.8f),
                        shadeFor(normal, 0.8f), shadeFor(normal, 0.8f),
                        alpha
                    )
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
            val avgRadius = (rad0 + rad1) / 2f
            val arcLength = placed.sectorWidthRadians() * avgRadius
            val targetW = arcLength * PIXELS_PER_BLOCK
            val targetH = center0.distanceTo(center1).toFloat() * PIXELS_PER_BLOCK
            val m = max(1, Math.ceil((targetH / TILE_SUBDIVISION_PX).toDouble()).toInt())
            val pose = poseStack.last()

            for (j in 0 until crossN) {
                val u0 = j.toFloat() / crossN.toFloat()
                val u1 = (j + 1).toFloat() / crossN.toFloat()
                for (k in 0 until m) {
                    val v0 = k.toFloat() / m.toFloat()
                    val v1 = (k + 1).toFloat() / m.toFloat()

                    val deg0 = placed.startAngle + placed.sectorWidthDegrees() * u0
                    val deg1 = placed.startAngle + placed.sectorWidthDegrees() * u1
                    val p00 = tubePoint(center0, lat0, up0, rad0, deg0)
                    val p10 = tubePoint(center1, lat1, up1, rad1, deg0)
                    val p11 = tubePoint(center1, lat1, up1, rad1, deg1)
                    val p01 = tubePoint(center0, lat0, up0, rad0, deg1)

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

        private fun renderWaterSegment(
            level: Level,
            center0: Vec3,
            center1: Vec3,
            lat0: Vec3,
            lat1: Vec3,
            up0: Vec3,
            up1: Vec3,
            rad0: Float,
            rad1: Float,
            placed: List<PlacedSector>,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            poseStack: PoseStack,
            consumer: VertexConsumer,
            crossN: Int,
            light: Int,
            vBase: Float
        ) {
            val pose = poseStack.last()
            val chordLen = center0.distanceTo(center1).toFloat()
            val flowSign = if (center1.y < center0.y) 1f else -1f
            val flow = AnimationTickHolder.getRenderTime(level) * WATER_FLOW_SPEED * flowSign
            val rIn0 = (rad0 - WALL_THICKNESS).coerceAtLeast(0.001f)
            val rIn1 = (rad1 - WALL_THICKNESS).coerceAtLeast(0.001f)
            val rSurf0 = (rIn0 - WATER_DEPTH).coerceAtLeast(0.001f)
            val rSurf1 = (rIn1 - WATER_DEPTH).coerceAtLeast(0.001f)

            // U stays within one 16px tile across the band
            fun arcUv(u: Float, v: Float): Pair<Float, Float> =
                u to mod(vBase + v * chordLen + flow, 1f)

            for (p in placed) {
                if (p.sector.material == SectorMaterial.OPEN) continue
                val start = max(p.startAngle, WATER_BAND_START)
                val end = min(p.endAngle, WATER_BAND_END)
                if (end <= start) continue
                val bandSpan = WATER_BAND_END - WATER_BAND_START
                val steps = max(1, Math.ceil(((end - start) / 360f * crossN).toDouble()).toInt())
                for (j in 0 until steps) {
                    val u0 = j.toFloat() / steps
                    val u1 = (j + 1).toFloat() / steps
                    val a0 = start + (end - start) * u0
                    val a1 = start + (end - start) * u1
                    val uG0 = (a0 - WATER_BAND_START) / bandSpan
                    val uG1 = (a1 - WATER_BAND_START) / bandSpan

                    // bottom arc at inner radius
                    val b00 = tubePoint(center0, lat0, up0, rIn0, a0)
                    val b10 = tubePoint(center1, lat1, up1, rIn1, a0)
                    val b11 = tubePoint(center1, lat1, up1, rIn1, a1)
                    val b01 = tubePoint(center0, lat0, up0, rIn0, a1)
                    val nb00 = radialNormal(b00, center0).scale(-1.0)
                    val nb10 = radialNormal(b10, center1).scale(-1.0)
                    val nb11 = radialNormal(b11, center1).scale(-1.0)
                    val nb01 = radialNormal(b01, center0).scale(-1.0)
                    quad(
                        consumer, pose, sprite,
                        b00, b10, b11, b01,
                        nb00, nb10, nb11, nb01,
                        arcUv(uG0, 0f), arcUv(uG0, 1f),
                        arcUv(uG1, 1f), arcUv(uG1, 0f),
                        light, light, light, light,
                        0.55f, 0.55f, 0.55f, 0.55f,
                        0.65f
                    )

                    // top free surface at inner radius - depth
                    val t00 = tubePoint(center0, lat0, up0, rSurf0, a0)
                    val t10 = tubePoint(center1, lat1, up1, rSurf1, a0)
                    val t11 = tubePoint(center1, lat1, up1, rSurf1, a1)
                    val t01 = tubePoint(center0, lat0, up0, rSurf0, a1)
                    val nt00 = radialNormal(t00, center0).scale(-1.0)
                    val nt10 = radialNormal(t10, center1).scale(-1.0)
                    val nt11 = radialNormal(t11, center1).scale(-1.0)
                    val nt01 = radialNormal(t01, center0).scale(-1.0)
                    quad(
                        consumer, pose, sprite,
                        t00, t10, t11, t01,
                        nt00, nt10, nt11, nt01,
                        arcUv(uG0, 0f), arcUv(uG0, 1f),
                        arcUv(uG1, 1f), arcUv(uG1, 0f),
                        light, light, light, light,
                        0.55f, 0.55f, 0.55f, 0.55f,
                        0.65f
                    )
                }

            }

            // side walls at band edges
            val leftSector = placed.firstOrNull {
                WATER_BAND_START >= it.startAngle - 0.001f &&
                    WATER_BAND_START <= it.endAngle + 0.001f
            }
            if (leftSector != null && leftSector.sector.material != SectorMaterial.OPEN) {
                renderWaterWall(
                    center0, center1, lat0, lat1, up0, up1,
                    WATER_BAND_START, rIn0, rIn1, rSurf0, rSurf1,
                    chordLen, flow, vBase, sprite, pose, consumer, light, left = true
                )
            }
            val rightSector = placed.firstOrNull {
                WATER_BAND_END >= it.startAngle - 0.001f &&
                    WATER_BAND_END <= it.endAngle + 0.001f
            }
            if (rightSector != null && rightSector.sector.material != SectorMaterial.OPEN) {
                renderWaterWall(
                    center0, center1, lat0, lat1, up0, up1,
                    WATER_BAND_END, rIn0, rIn1, rSurf0, rSurf1,
                    chordLen, flow, vBase, sprite, pose, consumer, light, left = false
                )
            }
        }

        private fun renderWaterWall(
            center0: Vec3,
            center1: Vec3,
            lat0: Vec3,
            lat1: Vec3,
            up0: Vec3,
            up1: Vec3,
            angleDeg: Float,
            rIn0: Float,
            rIn1: Float,
            rSurf0: Float,
            rSurf1: Float,
            chordLen: Float,
            flow: Float,
            vBase: Float,
            sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
            pose: PoseStack.Pose,
            consumer: VertexConsumer,
            light: Int,
            left: Boolean
        ) {
            val o0 = tubePoint(center0, lat0, up0, rIn0, angleDeg)
            val i0 = tubePoint(center0, lat0, up0, rSurf0, angleDeg)
            val o1 = tubePoint(center1, lat1, up1, rIn1, angleDeg)
            val i1 = tubePoint(center1, lat1, up1, rSurf1, angleDeg)
            val lateral = if (left) 0.5 else -0.5
            val n0 = lat0.scale(lateral.toDouble()).add(up0.scale(-0.866))
            val n1 = lat1.scale(lateral.toDouble()).add(up1.scale(-0.866))
            fun uv(u: Float, v: Float): Pair<Float, Float> =
                u to mod(vBase + v * chordLen + flow, 1f)
            if (left) {
                quad(
                    consumer, pose, sprite,
                    o0, i0, i1, o1,
                    n0, n0, n1, n1,
                    uv(0f, 0f), uv(1f, 0f), uv(1f, 1f), uv(0f, 1f),
                    light, light, light, light,
                    0.55f, 0.55f, 0.55f, 0.55f,
                    0.65f
                )
            } else {
                quad(
                    consumer, pose, sprite,
                    o0, o1, i1, i0,
                    n0, n1, n1, n0,
                    uv(0f, 0f), uv(0f, 1f), uv(1f, 1f), uv(1f, 0f),
                    light, light, light, light,
                    0.55f, 0.55f, 0.55f, 0.55f,
                    0.65f
                )
            }
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
            val inner0 = rad0 - WALL_THICKNESS
            val inner1 = rad1 - WALL_THICKNESS
            val o0 = tubePoint(center0, lat0, up0, rad0, angleDeg)
            val i0 = tubePoint(center0, lat0, up0, inner0, angleDeg)
            val o1 = tubePoint(center1, lat1, up1, rad1, angleDeg)
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
                    0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f,
                    light, light, light, light,
                    0.7f, 0.7f, 0.7f, 0.7f,
                    alpha
                )
            } else {
                quad(
                    consumer, pose, sprite,
                    o0, o1, i1, i0,
                    n0, n0, n1, n1,
                    0f to 0f, 0f to 1f, 1f to 1f, 1f to 0f,
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

// 9-slice UV
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
            val w = texW.toFloat()
            val h = texH.toFloat()
            val cx = (w - 2f * border).coerceAtLeast(1f)
            val cy = (h - 2f * border).coerceAtLeast(1f)
            val right = (targetW - border).coerceAtLeast(border)
            val top = (targetH - border).coerceAtLeast(border)

            val uf = when {
                px < border -> px / w
                px >= right -> (w - (targetW - px)) / w
                else -> (border + mod(px - border, cx)) / w
            }
            val vf = when {
                py < border -> py / h
                py >= top -> (h - (targetH - py)) / h
                else -> (border + mod(py - border, cy)) / h
            }
            return uf to vf
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
            alpha: Float
        ) {
            vertex(consumer, pose, sprite, a, na, uva, lightA, shadeA, alpha)
            vertex(consumer, pose, sprite, b, nb, uvb, lightB, shadeB, alpha)
            vertex(consumer, pose, sprite, c, nc, uvc, lightC, shadeC, alpha)
            vertex(consumer, pose, sprite, d, nd, uvd, lightD, shadeD, alpha)
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
            alpha: Float
        ) {
            consumer.addVertex(pose, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                .setColor(shade, shade, shade, alpha)
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
