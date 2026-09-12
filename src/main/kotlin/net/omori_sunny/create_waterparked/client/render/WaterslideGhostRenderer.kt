package net.omori_sunny.create_waterparked.client.render
// Ghost block renderer: CSG seat against the tube wall, caching and crack overlay.

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.editor.SableClientEdit
import net.omori_sunny.create_waterparked.client.editor.WaterslideGhostPlacement
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.GhostBlockEntry
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.model.data.ModelData
import kotlin.math.cos
import kotlin.math.sin

@OnlyIn(Dist.CLIENT)
object WaterslideGhostRenderer {

    private const val BASE_WALL = 0.1f
    private const val MAX_DRAW_DISTANCE_SQ = 192.0 * 192.0

    private val GHOST_TRI_SOLID: RenderType = RenderType.create(
        "create_waterparked:ghost_tri_solid",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.TRIANGLES,
        262144,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_SOLID_SHADER)
            .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setCullState(RenderStateShard.CULL)
            .createCompositeState(true)
    )

    private val GHOST_TRI_CUTOUT: RenderType = RenderType.create(
        "create_waterparked:ghost_tri_cutout",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.TRIANGLES,
        262144,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
            .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setCullState(RenderStateShard.CULL)
            .createCompositeState(true)
    )

    private val GHOST_TRI_TRANSLUCENT: RenderType = RenderType.create(
        "create_waterparked:ghost_tri_translucent",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.TRIANGLES,
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

    private fun bucketOf(rt: RenderType): Int = when (rt) {
        RenderType.solid() -> 0
        RenderType.cutout() -> 1
        RenderType.cutoutMipped() -> 1
        RenderType.translucent() -> 2
        else -> -1
    }

    private fun targetTypeOf(bucket: Int): RenderType = when (bucket) {
        0 -> GHOST_TRI_SOLID
        1 -> GHOST_TRI_CUTOUT
        else -> GHOST_TRI_TRANSLUCENT
    }

    private class GhostCache(
        var signature: String,
        var worldCell: BlockPos
    ) {
        var prismSolids: List<WaterslideGhostCsg.Solid> = emptyList()
        val meshes = HashMap<Int, List<WaterslideGhostCsg.Polygon>>()
    }

    private val caches = HashMap<String, GhostCache>()

    // ghosts drawn from the block-entity renderer path (Ponder scenes); kept
    // apart so the level-render retainAll never evicts them
    private val ponderCaches = HashMap<String, GhostCache>()

    private class BlockQuadCollector : VertexConsumer {
        private class V {
            var x = 0f; var y = 0f; var z = 0f
            var r = 1f; var g = 1f; var b = 1f; var a = 1f
            var u = 0f; var v = 0f
            var overlay = 0; var light = 0
            var nx = 0f; var ny = 1f; var nz = 0f
        }

        private val verts = Array(4) { V() }
        private var count = 0
        val polygons = ArrayList<WaterslideGhostCsg.Polygon>()

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
            if (count == 4) flush()
            val d = verts[count]
            d.x = x; d.y = y; d.z = z
            count++
            return this
        }

        override fun addVertex(
            x: Float, y: Float, z: Float,
            color: Int, u: Float, v: Float,
            overlay: Int, light: Int,
            nx: Float, ny: Float, nz: Float
        ) {
            if (count == 4) flush()
            val d = verts[count]
            d.x = x; d.y = y; d.z = z
            d.r = ((color shr 16) and 255) / 255f
            d.g = ((color shr 8) and 255) / 255f
            d.b = (color and 255) / 255f
            d.a = ((color ushr 24) and 255) / 255f
            d.u = u; d.v = v
            d.overlay = overlay; d.light = light
            d.nx = nx; d.ny = ny; d.nz = nz
            count++
        }

        override fun setColor(r: Int, g: Int, b: Int, a: Int): VertexConsumer {
            val d = verts[count - 1]
            d.r = r / 255f; d.g = g / 255f; d.b = b / 255f; d.a = a / 255f
            return this
        }

        override fun setUv(u: Float, v: Float): VertexConsumer {
            val d = verts[count - 1]
            d.u = u; d.v = v
            return this
        }

        override fun setUv1(u: Int, v: Int): VertexConsumer = this

        override fun setUv2(u: Int, v: Int): VertexConsumer = this

        override fun setOverlay(overlay: Int): VertexConsumer {
            verts[count - 1].overlay = overlay
            return this
        }

        override fun setLight(light: Int): VertexConsumer {
            verts[count - 1].light = light
            return this
        }

        override fun setNormal(nx: Float, ny: Float, nz: Float): VertexConsumer {
            val d = verts[count - 1]
            d.nx = nx; d.ny = ny; d.nz = nz
            return this
        }

        fun finish() {
            if (count == 4) flush()
            count = 0
        }

        private fun flush() {
            if (count < 3) {
                count = 0
                return
            }
            val csgVerts = ArrayList<WaterslideGhostCsg.Vertex>(count)
            for (i in 0 until count) {
                val d = verts[i]
                csgVerts += WaterslideGhostCsg.Vertex(
                    d.x, d.y, d.z,
                    d.nx, d.ny, d.nz,
                    d.u, d.v,
                    ((d.a * 255).toInt() shl 24) or ((d.r * 255).toInt() shl 16) or
                        ((d.g * 255).toInt() shl 8) or (d.b * 255).toInt(),
                    d.light, d.overlay
                )
            }
            polygons += WaterslideGhostCsg.Polygon(csgVerts, fromSolid = true)
            count = 0
        }
    }

    @JvmStatic
    fun renderAllInEvent(poseStack: PoseStack, bufferSource: MultiBufferSource) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val camera = mc.gameRenderer.mainCamera.position
        val seen = HashSet<String>()
        val seenEdges = HashSet<Pair<Long, Long>>()
        for (be in anchorBEs(level)) {
            if (be.isRemoved) continue
            for ((peer, raw) in be.anchorPeerCurvesView) {
                val bc = if (raw.isPrimary) raw else raw.secondary() ?: continue
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val edge = if (a.asLong() <= b.asLong()) a.asLong() to b.asLong() else b.asLong() to a.asLong()
                if (!seenEdges.add(edge)) continue
                for (entry in be.ghostBlocksForPeer(peer)) {
                    val key = cacheKey(be, peer, entry)
                    seen += key
                    renderGhost(mc, level, be, bc, peer, entry, poseStack, bufferSource, camera, key)
                }
            }
        }
        caches.keys.retainAll(seen)
        renderCrackOverlay(mc, poseStack, bufferSource, camera)
    }

    // Ponder path: draw this anchor's ghosts inside the block-entity renderer
    // pose (already translated to the BE position). The real world keeps using
    // the RenderLevelStageEvent path - when the BE lives in the client's main
    // level we stay out of the way to avoid double drawing.
    @JvmStatic
    fun renderForBlockEntity(
        be: WaterslideAnchorBlockEntity,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource
    ) {
        val mc = Minecraft.getInstance()
        val level = be.level ?: return
        if (level === mc.level) return
        val origin = Vec3.atLowerCornerOf(be.blockPos)
        val seenEdges = HashSet<Pair<Long, Long>>()
        val seenKeys = HashSet<String>()
        for ((peer, raw) in be.anchorPeerCurvesView) {
            val bc = if (raw.isPrimary) raw else raw.secondary() ?: continue
            if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
            val a = bc.bePositions.getFirst()
            val b = bc.bePositions.getSecond()
            val edge = if (a.asLong() <= b.asLong()) a.asLong() to b.asLong() else b.asLong() to a.asLong()
            if (!seenEdges.add(edge)) continue
            for (entry in be.ghostBlocksForPeer(peer)) {
                val key = cacheKey(be, peer, entry)
                seenKeys += key
                renderGhost(mc, level, be, bc, peer, entry, poseStack, bufferSource, origin, key, ponderCaches, false)
            }
        }
        ponderCaches.keys.retainAll(seenKeys)
    }

    @JvmStatic
    fun endBatches(bufferSource: MultiBufferSource) {
        if (bufferSource is MultiBufferSource.BufferSource) {
            bufferSource.endBatch(GHOST_TRI_SOLID)
            bufferSource.endBatch(GHOST_TRI_CUTOUT)
            bufferSource.endBatch(GHOST_TRI_TRANSLUCENT)
        }
    }

    @JvmStatic
    fun clear() {
        caches.clear()
        ponderCaches.clear()
    }

    private fun deriveSurfaceCell(
        level: net.minecraft.world.level.Level,
        bc: BezierConnection,
        entry: GhostBlockEntry,
        sub: dev.ryanhcode.sable.sublevel.ClientSubLevel?
    ): Vec3? {
        val t = entry.t.toDouble()
        val center = bc.getPosition(t)
        val tangent = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames.unitTangentAt(bc, entry.t)
        val (lateral, up) = net.omori_sunny.create_waterparked.game.SlideCurveGeometry.stableFrame(tangent)
        if (lateral.lengthSqr() < 1.0E-12 || up.lengthSqr() < 1.0E-12) return null
        val r0 = radiusAt(level, bc.bePositions.getFirst())
        val r1 = radiusAt(level, bc.bePositions.getSecond())
        val radius = Mth.lerp(entry.t, r0, r1)
        val outer = radius + (net.omori_sunny.create_waterparked.config.ModConfig.wallThickness() - BASE_WALL)
        val rad = Math.toRadians(entry.angle.toDouble())
        val dirX = lateral.scale(Math.cos(rad)).add(up.scale(Math.sin(rad)))
        val surface = center.add(dirX.scale(outer.toDouble()))
        val seated = surface.subtract(dirX.scale(0.5))
        return if (sub != null)
            CoasterAnchorClientSpace.toRenderWorld(level, bc.bePositions.getFirst(), seated)
        else
            seated
    }

    @JvmStatic
    fun rayHitSurface(
        level: net.minecraft.world.level.Level,
        be: WaterslideAnchorBlockEntity,
        bc: BezierConnection,
        eye: Vec3,
        dir: Vec3,
        probeCell: BlockPos
    ): Vec3? {
        val ctx = SableClientEdit.resolve(level, bc.bePositions.getFirst()) ?: return null
        val solids = buildPrismSolids(level, be, bc, probeCell)
        var bestT = Double.MAX_VALUE
        for (s in solids) {
            val t = s.rayEntry(eye, dir) ?: continue
            if (t in 0.0..6.0 && t < bestT) bestT = t
        }
        if (bestT == Double.MAX_VALUE) return null
        return eye.add(dir.scale(bestT))
    }

    private fun anchorBEs(level: net.minecraft.world.level.Level): List<WaterslideAnchorBlockEntity> {
        val out = LinkedHashMap<WaterslideAnchorBlockEntity, Boolean>()
        for (be in WaterslideCurveRenderer.clientAnchors()) out[be] = true
        for (pos in SlideAnchorIndex.all(level)) {
            val be = level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            out[be] = true
        }
        return out.keys.toList()
    }

    private fun renderGhost(
        mc: Minecraft,
        level: net.minecraft.world.level.Level,
        be: WaterslideAnchorBlockEntity,
        bc: BezierConnection,
        peer: BlockPos,
        entry: GhostBlockEntry,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        camera: Vec3,
        key: String,
        cacheStore: MutableMap<String, GhostCache> = caches,
        cullByDistance: Boolean = true
    ) {
        val anchor = bc.bePositions.getFirst()
        val ctxResolved = SableClientEdit.resolve(level, anchor) ?: return
        val sub = ctxResolved.sub
        val derived = deriveSurfaceCell(level, bc, entry, sub)
            ?: return
        val worldLower = derived
        val worldCell = BlockPos.containing(worldLower)
        if (cullByDistance && Vec3.atCenterOf(worldCell).distanceToSqr(camera) > MAX_DRAW_DISTANCE_SQ) return

        val state = entry.state
        val model = mc.blockRenderer.getBlockModel(state)
        val renderTypes = model.getRenderTypes(state, RandomSource.create(), ModelData.EMPTY)
        val baseBucket = renderTypes.map { bucketOf(it) }.firstOrNull { it >= 0 } ?: return

        val sig = signature(level, be, bc, peer, entry)
        val cache = cacheStore.getOrPut(key) { GhostCache("", worldCell) }
        if (cache.signature != sig) {
            cache.signature = sig
            cache.worldCell = worldCell
            cache.meshes.clear()
            cache.prismSolids = buildPrismSolids(level, be, bc, worldCell)
        }

        val mesh = cache.meshes.getOrPut(baseBucket) {
            val blockPolys = captureBlockQuads(mc, level, model, state, worldCell, rtForBucket(baseBucket))
            if (ModClientConfig.ghostClipMode() && cache.prismSolids.isNotEmpty()) {
                try {
                    val ext = WaterslideGhostCsg.difference(blockPolys, cache.prismSolids)
                    val cut = WaterslideGhostCsg.cutFace(blockPolys, cache.prismSolids)
                    ext + cut
                } catch (t: Throwable) {
                    CreateWaterparked.LOGGER.warn(
                        "Ghost block wall difference failed for {}; drawing full block.", worldCell, t
                    )
                    blockPolys
                }
            } else {
                blockPolys
            }
        }
        if (mesh.isEmpty()) return
        val consumer = bufferSource.getBuffer(targetTypeOf(baseBucket))
        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)
        emitMesh(consumer, poseStack.last(), mesh)
        poseStack.popPose()
    }

    private fun rtForBucket(bucket: Int): RenderType = when (bucket) {
        0 -> RenderType.solid()
        1 -> RenderType.cutout()
        else -> RenderType.translucent()
    }

    private fun captureBlockQuads(
        mc: Minecraft,
        level: net.minecraft.world.level.Level,
        model: net.minecraft.client.resources.model.BakedModel,
        state: net.minecraft.world.level.block.state.BlockState,
        worldCell: BlockPos,
        rt: RenderType
    ): List<WaterslideGhostCsg.Polygon> {
        val collector = BlockQuadCollector()
        val pose = PoseStack()
        pose.translate(worldCell.x.toDouble(), worldCell.y.toDouble(), worldCell.z.toDouble())
        mc.blockRenderer.modelRenderer.tesselateBlock(
            level, model, state, worldCell, pose, collector,
            false, RandomSource.create(), state.getSeed(worldCell),
            OverlayTexture.NO_OVERLAY, ModelData.EMPTY, rt
        )
        collector.finish()
        return collector.polygons
    }

    private fun emitMesh(consumer: VertexConsumer, pose: PoseStack.Pose, mesh: List<WaterslideGhostCsg.Polygon>) {
        for (polygon in mesh) {
            if (polygon.vertices.size < 3) continue
            for (i in 1 until polygon.vertices.size - 1) {
                emitTri(consumer, pose, polygon.vertices[0], polygon.vertices[i], polygon.vertices[i + 1])
            }
        }
    }

    private fun emitTri(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        a: WaterslideGhostCsg.Vertex,
        b: WaterslideGhostCsg.Vertex,
        c: WaterslideGhostCsg.Vertex
    ) {
        consumer.addVertex(pose, a.x, a.y, a.z)
            .setColor(a.color)
            .setUv(a.u, a.v)
            .setOverlay(a.overlay)
            .setLight(a.light)
            .setNormal(pose, a.nx, a.ny, a.nz)
        consumer.addVertex(pose, b.x, b.y, b.z)
            .setColor(b.color)
            .setUv(b.u, b.v)
            .setOverlay(b.overlay)
            .setLight(b.light)
            .setNormal(pose, b.nx, b.ny, b.nz)
        consumer.addVertex(pose, c.x, c.y, c.z)
            .setColor(c.color)
            .setUv(c.u, c.v)
            .setOverlay(c.overlay)
            .setLight(c.light)
            .setNormal(pose, c.nx, c.ny, c.nz)
    }

    internal fun buildPrismSolids(
        level: net.minecraft.world.level.Level,
        be: WaterslideAnchorBlockEntity,
        bc: BezierConnection,
        cell: BlockPos,
        radiusBias: Double = 0.0
    ): List<WaterslideGhostCsg.Solid> {
        val a = bc.bePositions.getFirst()
        val b = bc.bePositions.getSecond()
        val r0 = radiusAt(level, a)
        val r1 = radiusAt(level, b)
        val ctx = SableClientEdit.resolve(level, a)
        val sub = ctx?.sub
        val scale = sub?.let {
            val s = it.logicalPose().scale()
            maxOf(s.x(), s.y(), s.z()).toFloat().coerceAtLeast(0.1f)
        } ?: 1f
        fun worldPos(v: Vec3): Vec3 =
            if (sub != null) CoasterAnchorClientSpace.toRenderWorld(level, a, v) else v
        fun worldDir(v: Vec3): Vec3 =
            if (sub != null) {
                val d = CoasterAnchorClientSpace.toRenderDirection(level, a, v)
                if (d.lengthSqr() < 1.0E-12) v.normalize() else d.normalize()
            } else v.normalize()

        val frames = WaterslideTubeMesh.sampleSegments(level, bc, r0, r1, Vec3.ZERO, false)
        if (frames.size < 2) return emptyList()

        val subFrames = ArrayList<WaterslideTubeMesh.TubeSegmentFrame>(frames.size * 2)
        for (f in frames) {
            val (first, second) = midSplit(f)
            subFrames += first
            subFrames += second
        }

        val crossN = WaterslideTubeMesh.crossSections()
        val degStep = 360f / crossN
        val gridAnchor = 90f
        val probeBox = AABB(cell).inflate(3.0)

        fun ringOf(f: WaterslideTubeMesh.TubeSegmentFrame): List<Vec3> {
            val c = worldPos(f.currSpine)
            val lat = worldDir(f.currLateral)
            val tangent = worldDir(f.currTangent)
            val up = tangent.cross(lat).normalize()
            val r = ((f.currRadius + net.omori_sunny.create_waterparked.config.ModConfig.wallThickness() - BASE_WALL + radiusBias).toFloat() * scale)
                .coerceAtLeast(0.05f)
            return List(crossN) { k ->
                val deg = Math.toRadians((gridAnchor + k * degStep).toDouble())
                c.add(lat.scale(cos(deg) * r)).add(up.scale(sin(deg) * r))
            }
        }

        val solids = ArrayList<WaterslideGhostCsg.Solid>()
        var prevRing: List<Vec3>? = null
        var prevCenter: Vec3? = null
        for (i in subFrames.indices) {
            val f = subFrames[i]
            val center = worldPos(f.currSpine)
            val ring = ringOf(f)
            val prev = prevRing
            if (prev != null && prevCenter != null) {
                val ring0 = prev
                val c0 = prevCenter!!
                val c1 = center
                val box = prismAabb(ring0, ring, c0, c1)
                if (box != null && box.intersects(probeBox)) {
                    solids += WaterslideGhostCsg.prismSolid(ring0, ring, c0, c1)
                }
            }
            prevRing = ring
            prevCenter = center
        }
        return solids
    }

    private fun prismAabb(ring0: List<Vec3>, ring1: List<Vec3>, c0: Vec3, c1: Vec3): AABB? {
        if (ring0.size < 3) return null
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (p in ring0 + ring1) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.z < minZ) minZ = p.z
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
            if (p.z > maxZ) maxZ = p.z
        }
        return AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.05)
    }

    private fun midSplit(f: WaterslideTubeMesh.TubeSegmentFrame): Pair<WaterslideTubeMesh.TubeSegmentFrame, WaterslideTubeMesh.TubeSegmentFrame> {
        val chord = f.currSpine.subtract(f.prevSpine)
        val handle = (chord.length() / 3.0).toFloat()
        val c0 = f.prevSpine
        val c1 = f.prevSpine.add(f.prevTangent.scale(handle.toDouble()))
        val c2 = f.currSpine.subtract(f.currTangent.scale(handle.toDouble()))
        val c3 = f.currSpine
        val t = 0.5
        val omt = 1.0 - t
        val mid = c0.scale(omt * omt * omt)
            .add(c1.scale(3.0 * omt * omt * t))
            .add(c2.scale(3.0 * omt * t * t))
            .add(c3.scale(t * t * t))
        val deriv = c1.subtract(c0).scale(3.0 * omt * omt)
            .add(c2.subtract(c1).scale(6.0 * omt * t))
            .add(c3.subtract(c2).scale(3.0 * t * t))
        val midTan = if (deriv.lengthSqr() < 1.0E-12) f.currTangent else deriv.normalize()
        val latLerp = f.prevLateral.scale(0.5).add(f.currLateral.scale(0.5))
        val midLat = latLerp.subtract(midTan.scale(latLerp.dot(midTan))).normalize()
        val midR = (f.prevRadius + f.currRadius) * 0.5f
        val first = WaterslideTubeMesh.TubeSegmentFrame(
            f.prevSpine, mid, f.prevTangent, midTan,
            f.prevLateral, midLat, f.prevRadius, midR
        )
        val second = WaterslideTubeMesh.TubeSegmentFrame(
            mid, f.currSpine, midTan, f.currTangent,
            midLat, f.currLateral, midR, f.currRadius
        )
        return first to second
    }

    private fun radiusAt(level: net.minecraft.world.level.Level, pos: BlockPos): Float =
        SableClientEdit.resolve(level, pos)?.be?.radius ?: ModConfig.defaultSlideRadius()

    private fun signature(
        level: net.minecraft.world.level.Level,
        be: WaterslideAnchorBlockEntity,
        bc: BezierConnection,
        peer: BlockPos,
        entry: GhostBlockEntry
    ): String {
        val sb = StringBuilder()
        sb.append(entry.cell).append('|').append(entry.state).append('|')
            .append(ModClientConfig.polygonScale()).append('|')
            .append(net.omori_sunny.create_waterparked.config.ModConfig.wallThickness()).append('|')
            .append(ModClientConfig.ghostClipMode()).append('|')
            .append(radiusAt(level, bc.bePositions.getFirst())).append('|')
            .append(radiusAt(level, bc.bePositions.getSecond())).append('|')
            .append(bc.bePositions.first.asLong()).append(',').append(bc.bePositions.second.asLong()).append(',')
            .append(bc.getSegmentCount()).append('|')
            .append(bc.starts.first.x).append(',').append(bc.starts.first.y).append(',').append(bc.starts.first.z).append(',')
            .append(bc.starts.second.x).append(',').append(bc.starts.second.y).append(',').append(bc.starts.second.z).append(',')
            .append(bc.axes.first.x).append(',').append(bc.axes.first.y).append(',').append(bc.axes.first.z).append(',')
            .append(bc.axes.second.x).append(',').append(bc.axes.second.y).append(',').append(bc.axes.second.z).append('|')
        val config: WaterslideSectorConfig = be.sectorConfigFor(peer)
        sb.append(config.startAngle)
        for (s in config.sectors) {
            sb.append('|').append(s.id).append(',').append(s.material).append(',').append(s.blockId)
                .append(',').append(s.type).append(',').append(s.widthDegrees)
        }
        return sb.toString()
    }

    private fun cacheKey(be: WaterslideAnchorBlockEntity, peer: BlockPos, entry: GhostBlockEntry): String {
        val space = be.level?.let { System.identityHashCode(it) } ?: 0
        return "$space:${be.blockPos.asLong()}:${peer.asLong()}:${entry.cell.asLong()}:${entry.id}"
    }

    private fun renderCrackOverlay(
        mc: Minecraft,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        camera: Vec3
    ) {
        val crack = WaterslideGhostPlacement.crackState() ?: return
        val (cell, stage) = crack
        if (stage !in 0..9) return
        val sprite = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(ResourceLocation.withDefaultNamespace("block/destroy_stage_$stage"))
        val consumer = bufferSource.getBuffer(RenderType.crumbling(TextureAtlas.LOCATION_BLOCKS))
        val center = Vec3.atCenterOf(cell)
        val forward = camera.subtract(center).normalize()
        var right = forward.cross(Vec3(0.0, 1.0, 0.0))
        if (right.lengthSqr() < 1.0E-12) right = Vec3(1.0, 0.0, 0.0)
        right = right.normalize()
        val up = right.cross(forward).normalize()
        val hw = 0.6
        val p0 = center.subtract(right.scale(hw)).subtract(up.scale(hw))
        val p1 = center.add(right.scale(hw)).subtract(up.scale(hw))
        val p2 = center.add(right.scale(hw)).add(up.scale(hw))
        val p3 = center.subtract(right.scale(hw)).add(up.scale(hw))
        poseStack.pushPose()
        poseStack.translate(-camera.x, -camera.y, -camera.z)
        val pose = poseStack.last()
        crackVertex(consumer, pose, sprite, p0, 0f, 1f)
        crackVertex(consumer, pose, sprite, p1, 1f, 1f)
        crackVertex(consumer, pose, sprite, p2, 1f, 0f)
        crackVertex(consumer, pose, sprite, p3, 0f, 0f)
        poseStack.popPose()
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
}