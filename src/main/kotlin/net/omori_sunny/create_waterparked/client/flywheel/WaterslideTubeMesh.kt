package net.omori_sunny.create_waterparked.client.flywheel

import com.simibubi.create.content.trains.track.BezierConnection
import dev.engine_room.flywheel.api.material.Material
import dev.engine_room.flywheel.api.material.MaterialShaders
import dev.engine_room.flywheel.api.material.Transparency
import dev.engine_room.flywheel.api.material.WriteMask
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.api.model.Mesh
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.material.SimpleMaterial
import dev.engine_room.flywheel.lib.material.SimpleMaterialShaders
import dev.engine_room.flywheel.lib.memory.MemoryBlock
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh
import dev.engine_room.flywheel.lib.model.SingleMeshModel
import dev.engine_room.flywheel.lib.util.ResourceUtil
import dev.engine_room.flywheel.lib.vertex.FullVertexView
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterOpenEndExtension
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.compat.IrisColorwheelCompat
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.PlacedSector
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.SpriteContents
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.model.data.ModelData
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// shared unit-circle mesh
object WaterslideTubeMesh {

    private const val LENGTH_SUBDIVISIONS = 4
    private const val MAX_FRAME_BLOCKS = 0.5f

    // keep in sync with instance/waterslide_tube.vert `BASE_WALL`: the wall is
    // drawn outward to radius + (wallThickness - BASE_WALL)
    @JvmField
    val BASE_WALL: Float = 0.1f
    // tiny radial offset so the support never sits exactly coplanar
    @JvmField
    val SUPPORT_HUG_EPSILON: Float = 0.005f

    // support strip width in sprite pixels, 4px band with 2px border fold
    const val SUPPORT_STRIP_PX: Float = 4f

    // concurrent model builds on Flywheel worker threads, getOrPut is atomic
    private val modelCache = java.util.concurrent.ConcurrentHashMap<String, TubeModels>()
    private val waterModelCache = java.util.concurrent.ConcurrentHashMap<String, Model>()

// per-fragment UV reconstruction
    private val TUBE_SHADERS: MaterialShaders = SimpleMaterialShaders(
        ResourceUtil.rl("material/default.vert"),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "material/waterslide_tube.frag")
    )

// wall is double-sided for mirrored junctions and side walls
    private val TUBE_CUTOUT_MATERIAL: Material =
        SimpleMaterial.builderOf(Materials.CUTOUT_MIPPED_BLOCK)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(false)
            .build()

    private val TUBE_CAP_CUTOUT_MATERIAL: Material =
        SimpleMaterial.builderOf(Materials.CUTOUT_MIPPED_BLOCK)
            .shaders(TUBE_SHADERS)
            .build()

    private val TUBE_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(false)
            .writeMask(WriteMask.COLOR)
            .build()

// water band renders both faces and writes real depth for iterationRP
    val WATER_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(false)
            .writeMask(WriteMask.COLOR_DEPTH)
            .build()

// thrown water is visible from both sides
    val STREAM_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(false)
            .writeMask(WriteMask.COLOR)
            .build()

// stream variant writes depth only for iterationRP, culled at other angles
    val STREAM_TRANSLUCENT_DEPTH_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(false)
            .writeMask(WriteMask.COLOR_DEPTH)
            .build()

// skeleton rings
    private val RING_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(false)
            .writeMask(WriteMask.COLOR)
            .build()

// glass sectors are order independent, single sided like vanilla glass
    val GLASS_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.ORDER_INDEPENDENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(true)
            .writeMask(WriteMask.COLOR)
            .build()

    private class V(
        var x: Float, var y: Float, var z: Float,
        var r: Float, var g: Float, var b: Float, var a: Float,
        var u: Float, var v: Float,
        var overlay: Int, var light: Int,
        var nx: Float, var ny: Float, var nz: Float
    )

// wall + caps
    data class SectorWall(
        val blockId: String,
        val model: Model,
        val translucent: Boolean
    )

    data class TubeModels(
        val wall: Model,
        val sectorWalls: List<SectorWall>,
        val startCap: Model,
        val endCap: Model,
        val wallTranslucent: Model,
        val startCapTranslucent: Model,
        val endCapTranslucent: Model,
        val ringTranslucent: Model
    )

    data class TubeSegmentFrame(
        val prevSpine: Vec3,
        val currSpine: Vec3,
        val prevTangent: Vec3,
        val currTangent: Vec3,
        val prevLateral: Vec3,
        val currLateral: Vec3,
        val prevRadius: Float,
        val currRadius: Float
    )

// model cache
    @JvmStatic
    fun modelsFor(config: WaterslideSectorConfig, radius: Float): TubeModels {
        val key = signature(config, radius)
        return modelCache.getOrPut(key) { build(config, radius) }
    }

    @JvmStatic
    fun modelsFor(config: WaterslideSectorConfig): TubeModels =
        modelsFor(config, ModConfig.defaultSlideRadius())

    // kept for existing world-visual call sites; level is not needed by build()
    @JvmStatic
    fun modelsFor(level: Level, config: WaterslideSectorConfig): TubeModels =
        modelsFor(config, ModConfig.defaultSlideRadius())

    @JvmStatic
    fun modelsFor(level: Level, config: WaterslideSectorConfig, radius: Float): TubeModels =
        modelsFor(config, radius)

    @JvmStatic
    fun clearModels() {
        modelCache.clear()
        waterModelCache.clear()
        bracketCache.clear()
        beamCache.clear()
    }

    @JvmStatic
    fun crossSections(): Int =
        max(2, (16 * ModClientConfig.polygonScale()).roundToInt())


    @JvmStatic
    fun bracketArcLo(): Float {
        val d = 360f / crossSections()
        return 90f + Math.round((ModClientConfig.supportArcLo() - 90f) / d) * d
    }

    @JvmStatic
    fun bracketArcHi(): Float {
        val d = 360f / crossSections()
        return 90f + Math.round((ModClientConfig.supportArcHi() - 90f) / d) * d
    }

    @JvmStatic
    fun waterCrossSections(): Int {
        val base = crossSections()
        return if (IrisColorwheelCompat.iterationRpWaterMode()) base * 10 else base
    }

    // arc length of the cubic the vertex shader reconstructs
    @JvmStatic
    fun arcLength(frame: TubeSegmentFrame): Float {
        return bezierArcLength(
            frame.prevSpine, frame.currSpine,
            frame.prevTangent, frame.currTangent
        )
    }

    @JvmStatic
    fun bezierArcLength(c0: Vec3, c1: Vec3, t0: Vec3, t1: Vec3): Float {
        val chord = c1.subtract(c0)
        val h = chord.length() / 3.0
        val p0 = c0
        val p1 = c0.add(t0.scale(h))
        val p2 = c1.subtract(t1.scale(h))
        val p3 = c1
        var sum = 0.0
        for (i in 0 until 8) {
            val a = i / 8.0
            val b = (i + 1) / 8.0
            sum += (bezierSpeed(p0, p1, p2, p3, a) + bezierSpeed(p0, p1, p2, p3, b)) * 0.5 * (b - a)
        }
        return sum.toFloat()
    }

    private fun bezierSpeed(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Double {
        val omt = 1.0 - t
        return p1.subtract(p0).scale(3.0 * omt * omt)
            .add(p2.subtract(p1).scale(6.0 * omt * t))
            .add(p3.subtract(p2).scale(3.0 * t * t))
            .length()
    }


    @JvmStatic
    fun sampleSegments(
        level: Level,
        bc: BezierConnection,
        r0: Float,
        r1: Float,
        origin: Vec3,
        useRailFrames: Boolean = false
    ): List<TubeSegmentFrame> {
        val count = bc.getSegmentCount().coerceAtLeast(1)
        val ts = FloatArray(count + 1) { i ->
            if (i == 0) 0f else if (i == count) 1f else bc.getSegmentT(i)
        }
        val centers = Array(count + 1) { bc.getPosition(ts[it].toDouble()) }
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

            var lat: Vec3
            var up: Vec3
            if (useRailFrames) {
                lat = CoasterBezierRailFrames.lateralAt(bc, ts[i], level)
                if (lat.lengthSqr() < 1.0E-12) {
                    val (fallbackLat, fallbackUp) = SlideCurveGeometry.stableFrame(tangent)
                    lat = fallbackLat
                    up = fallbackUp
                } else {
                    lat = lat.normalize()
                    up = tangent.cross(lat).normalize()
                }
            } else {
                val (stableLat, stableUp) = SlideCurveGeometry.stableFrame(tangent)
                lat = stableLat
                up = stableUp
            }
            if (prevLat != null && lat.dot(prevLat) < 0.0) {
                lat = lat.scale(-1.0)
                up = up.scale(-1.0)
            }
            tangents[i] = tangent
            lats[i] = lat
            ups[i] = up
            prevLat = lat
        }

        val ext0 = openEndExtension(level, bc, atFirst = true)
        val ext1 = openEndExtension(level, bc, atFirst = false)
        val frames = ArrayList<TubeSegmentFrame>()
        if (ext0 > 0.01f) {
            val tan = tangents[0]!!
            val steps = max(1, ceil((ext0 / MAX_FRAME_BLOCKS).toDouble()).toInt())
            for (i in 0 until steps) {
                val f0 = i.toFloat() / steps
                val f1 = (i + 1).toFloat() / steps
                frames += TubeSegmentFrame(
                    centers[0].subtract(tan.scale((ext0 * (1 - f0)).toDouble())).subtract(origin),
                    centers[0].subtract(tan.scale((ext0 * (1 - f1)).toDouble())).subtract(origin),
                    tan, tan, lats[0]!!, lats[0]!!, r0, r0
                )
            }
        }
        for (i in 0 until count) {
            frames += TubeSegmentFrame(
                centers[i].subtract(origin),
                centers[i + 1].subtract(origin),
                tangents[i]!!, tangents[i + 1]!!,
                lats[i]!!, lats[i + 1]!!,
                Mth.lerp(ts[i], r0, r1), Mth.lerp(ts[i + 1], r0, r1)
            )
        }
        if (ext1 > 0.01f) {
            val tan = tangents[count]!!
            val steps = max(1, ceil((ext1 / MAX_FRAME_BLOCKS).toDouble()).toInt())
            for (i in 0 until steps) {
                val f0 = i.toFloat() / steps
                val f1 = (i + 1).toFloat() / steps
                frames += TubeSegmentFrame(
                    centers[count].add(tan.scale((ext1 * f0).toDouble())).subtract(origin),
                    centers[count].add(tan.scale((ext1 * f1).toDouble())).subtract(origin),
                    tan, tan, lats[count]!!, lats[count]!!, r1, r1
                )
            }
        }
        return frames
    }


    @JvmStatic
    fun sampleSegments(
        bc: BezierConnection,
        r0: Float,
        r1: Float,
        origin: Vec3
    ): List<TubeSegmentFrame> {
        val count = bc.getSegmentCount().coerceAtLeast(1)
        val ts = FloatArray(count + 1) { i ->
            if (i == 0) 0f else if (i == count) 1f else bc.getSegmentT(i)
        }
        val centers = Array(count + 1) { bc.getPosition(ts[it].toDouble()) }
        val tangents = arrayOfNulls<Vec3>(count + 1)
        val lats = arrayOfNulls<Vec3>(count + 1)
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

            // no rail frames and no extensions: always the stable world-up frame
            var (lat, _) = SlideCurveGeometry.stableFrame(tangent)
            if (prevLat != null && lat.dot(prevLat) < 0.0) {
                lat = lat.scale(-1.0)
            }
            tangents[i] = tangent
            lats[i] = lat
            prevLat = lat
        }

        val frames = ArrayList<TubeSegmentFrame>(count)
        for (i in 0 until count) {
            frames += TubeSegmentFrame(
                centers[i].subtract(origin),
                centers[i + 1].subtract(origin),
                tangents[i]!!, tangents[i + 1]!!,
                lats[i]!!, lats[i + 1]!!,
                Mth.lerp(ts[i], r0, r1), Mth.lerp(ts[i + 1], r0, r1)
            )
        }
        return frames
    }

    private fun openEndExtension(level: Level, bc: BezierConnection, atFirst: Boolean): Float {
        val anchor = if (atFirst) bc.bePositions.getFirst() else bc.bePositions.getSecond()
        val be = level.getBlockEntity(anchor) as? CoasterAnchorpointBlockEntity ?: return 0f
        if (be.legCount() != 1) return 0f
        return CoasterOpenEndExtension.extensionBlocks(level, anchor)
    }

    private fun signature(config: WaterslideSectorConfig, radius: Float): String =
        buildString {
            append(ModClientConfig.polygonScale())
            append('|').append(radius)
            append('|').append(config.startAngle)
            for (s in config.sectors) {
                append('|').append(s.id)
                    .append(',').append(s.material)
                    .append(',').append(s.blockId)
                    .append(',').append(s.type)
                    .append(',').append(s.widthDegrees)
            }
        }

    private fun build(config: WaterslideSectorConfig, radius: Float): TubeModels {
        val placed = WaterslideSectorLayout.place(config)
        // low-poly cross-section, density from client config
        val crossN = crossSections()
        val degStep = 360f / crossN
        val gridAnchor = 90f
        val translucentCache = java.util.HashMap<ResourceLocation, Boolean>()

        val wallVerts = ArrayList<V>()
        val sectorBuckets = LinkedHashMap<String, ArrayList<V>>()
        val startCapVerts = ArrayList<V>()
        val endCapVerts = ArrayList<V>()

        fun add(
            dst: MutableList<V>,
            x: Float, y: Float, z: Float,
            nx: Float, ny: Float, nz: Float,
            u: Float, v: Float,
            sectorRadians: Float, texW: Float, texH: Float, border: Float,
            spriteU0: Float, spriteU1: Float, spriteV0: Float, spriteV1: Float,
            sideWall: Boolean = false,
            translucent: Boolean = false,
            capV: Boolean = false
        ) {

            val uTilesRaw = if (sideWall)
                1f
            else {
                // one tile per block at the inner radius for both walls
                (radius - BASE_WALL).coerceAtLeast(0.1f) * sectorRadians
            }
            // narrow sectors keep at least one tile for the opaque walls
            val uTiles = max(uTilesRaw, 1f)

            val centerW = max(texW - 2f * border, 1f)
            val centerH = max(texH - 2f * border, 1f)

            // per-tile position (0..texW): the fold must wrap per TILE, the old
            // % centerW wrapped per body window and dragged the texture along u
            val px = u * uTiles * texW
            val inTile = ((px % texW) + texW) % texW
            val uFrac = if (translucent) {
                when {
                    inTile < border -> max(inTile, 0.05f) / texW
                    inTile > texW - border ->
                        min(texW - border + (inTile - (texW - border)), texW - 0.05f) / texW
                    else -> (border + (inTile % centerW)) / texW
                }
            } else if (sideWall)
                (border + u * centerW) / texW
            else
                (inTile.coerceIn(border, texW - border)) / texW
            // cap radial three zone fold, walls use the plain body window fold
            val vFrac = if (translucent && capV) {
                val vPx = v * (ModClientConfig.wallThickness() * 16f)
                when {
                    vPx < border -> max(vPx, 0.05f) / texH
                    vPx > ModClientConfig.wallThickness() * 16f - border ->
                        min(texH - border + (vPx - (ModClientConfig.wallThickness() * 16f - border)), texH - 0.05f) / texH
                    else -> (border + (vPx % centerH)) / texH
                }
            } else ((border + (v * 0.5f * texH % centerH)) % texH) / texH
            val uAtlas = spriteU0 + uFrac * (spriteU1 - spriteU0)
            val vAtlas = spriteV0 + vFrac * (spriteV1 - spriteV0)

            dst += V(
                x, y, z,
                1f, 1f, 1f, 1f,
                uAtlas, vAtlas,
                0, 0x00F000F0,
                nx, ny, nz
            )
        }

        fun addSideWall(
            dst: MutableList<V>,
            angleDeg: Float,
            dir: Float,
            sectorRadians: Float, texW: Float, texH: Float, border: Float,
            su0: Float, su1: Float, sv0: Float, sv1: Float
        ) {
            val a = Math.toRadians(angleDeg.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            val nx = c
            val ny = s
            // real radial span: radius 1.0 outer, below 0.95 inner
            val innerR = 0.92f
            // u span approximates the wall thickness on the radial axis
            val sideRadians = 0.2f / 16f
            for (k in 0 until LENGTH_SUBDIVISIONS) {
                val z0 = k / (2f * LENGTH_SUBDIVISIONS)
                val z1 = (k + 1) / (2f * LENGTH_SUBDIVISIONS)
                val v0 = k / LENGTH_SUBDIVISIONS.toFloat()
                val v1 = (k + 1) / LENGTH_SUBDIVISIONS.toFloat()
                if (dir > 0f) {
                    add(dst, c, s, z0, nx, ny, 0f, 0f, v0, sideRadians, texW, texH, border, su0, su1, sv0, sv1)
                    add(dst, c * innerR, s * innerR, z0, nx, ny, 0f, 1f, v0, sideRadians, texW, texH, border, su0, su1, sv0, sv1)
                    add(dst, c * innerR, s * innerR, z1, nx, ny, 0f, 1f, v1, sideRadians, texW, texH, border, su0, su1, sv0, sv1)
                    add(dst, c, s, z1, nx, ny, 0f, 0f, v1, sideRadians, texW, texH, border, su0, su1, sv0, sv1)
                } else {
                    add(dst, c, s, z0, nx, ny, 0f, 0f, v0, sideRadians, texW, texH, border, su0, su1, sv0, sv1)
                    add(dst, c, s, z1, nx, ny, 0f, 0f, v1, sideRadians, texW, texH, border, su0, su1, sv0, sv1)
                    add(dst, c * innerR, s * innerR, z1, nx, ny, 0f, 1f, v1, sideRadians, texW, texH, border, su0, su1, sv0, sv1)
                    add(dst, c * innerR, s * innerR, z0, nx, ny, 0f, 1f, v0, sideRadians, texW, texH, border, su0, su1, sv0, sv1)
                }
            }
        }

        for (p in placed) {
            if (p.sector.material == SectorMaterial.OPEN) continue
            val blockId = p.sector.blockId ?: continue
            val sprite = spriteFor(blockId) ?: continue
            // one mesh per sector so each wall instance carries a single sprite
            val bucket = sectorBuckets.getOrPut(blockId.toString()) { ArrayList() }
            val texW = sprite.contents().width().toFloat()
            val texH = sprite.contents().height().toFloat()
            val border = ModConfig.sectorBorderPx().toFloat()
            val sectorDegrees = p.endAngle - p.startAngle
            if (sectorDegrees <= 0.001f) continue
            val sectorRadians = Math.toRadians(sectorDegrees.toDouble()).toFloat()
            val su0 = sprite.u0
            val su1 = sprite.u1
            val sv0 = sprite.v0
            val sv1 = sprite.v1
            val glass = translucentCache.getOrPut(blockId) { isTranslucent(blockId) }
            // glass sectors use their own sprite border as the fold inset
            val effBorder = if (glass) borderPxOf(sprite).toFloat() else border

            // global fixed grid, up-axis anchored, identical across tracks
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
                        val s = max(cg0, lo)
                        val e = min(cg1, hi)
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

                        for (k in 0 until LENGTH_SUBDIVISIONS) {
                            val z0 = k / (2f * LENGTH_SUBDIVISIONS)
                            val z1 = (k + 1) / (2f * LENGTH_SUBDIVISIONS)
                            val v0 = k / LENGTH_SUBDIVISIONS.toFloat()
                            val v1 = (k + 1) / LENGTH_SUBDIVISIONS.toFloat()

                            // inner wall first, translucent buckets blend in mesh order
                            add(bucket, c0, s0, z0, -cm, -sm, 0f, f0, v0, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(wallVerts, c0, s0, z0, -cm, -sm, 0f, f0, v0, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(bucket, c0, s0, z1, -cm, -sm, 0f, f0, v1, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(wallVerts, c0, s0, z1, -cm, -sm, 0f, f0, v1, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(bucket, c1, s1, z1, -cm, -sm, 0f, f1, v1, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(wallVerts, c1, s1, z1, -cm, -sm, 0f, f1, v1, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(bucket, c1, s1, z0, -cm, -sm, 0f, f1, v0, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(wallVerts, c1, s1, z0, -cm, -sm, 0f, f1, v0, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)

                            // Outer wall (drawn last)
                            add(bucket, c0, s0, z0, cm, sm, 0f, f0, v0, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(wallVerts, c0, s0, z0, cm, sm, 0f, f0, v0, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(bucket, c1, s1, z0, cm, sm, 0f, f1, v0, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(wallVerts, c1, s1, z0, cm, sm, 0f, f1, v0, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(bucket, c1, s1, z1, cm, sm, 0f, f1, v1, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(wallVerts, c1, s1, z1, cm, sm, 0f, f1, v1, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(bucket, c0, s0, z1, cm, sm, 0f, f0, v1, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                            add(wallVerts, c0, s0, z1, cm, sm, 0f, f0, v1, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass)
                        }

                        // End cap
                        add(endCapVerts, c0, s0, 0f, c0, s0, 1f, f0, 1f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass, capV = glass)
                        add(endCapVerts, c1, s1, 0f, c1, s1, 1f, f1, 1f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass, capV = glass)
                        add(endCapVerts, c1, s1, 0f, -c1, -s1, 1f, f1, 0f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass, capV = glass)
                        add(endCapVerts, c0, s0, 0f, -c0, -s0, 1f, f0, 0f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass, capV = glass)

                        // Start cap
                        add(startCapVerts, c0, s0, 0f, c0, s0, -1f, f0, 1f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass, capV = glass)
                        add(startCapVerts, c0, s0, 0f, -c0, -s0, -1f, f0, 0f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass, capV = glass)
                        add(startCapVerts, c1, s1, 0f, -c1, -s1, -1f, f1, 0f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass, capV = glass)
                        add(startCapVerts, c1, s1, 0f, c1, s1, -1f, f1, 1f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1, translucent = glass, capV = glass)
                    }
                }
            }

            // side walls next to open sectors (folded into this sector's mesh)
            val idx = placed.indexOf(p)
            val prev = placed[(idx - 1 + placed.size) % placed.size]
            val next = placed[(idx + 1) % placed.size]
            // translucent neighbours count as open, the guard keeps seams wall less
            val prevOpenLike = prev.sector.material == SectorMaterial.OPEN ||
                (!glass && prev.sector.blockId != null &&
                    translucentCache.getOrPut(prev.sector.blockId) { isTranslucent(prev.sector.blockId) })
            val nextOpenLike = next.sector.material == SectorMaterial.OPEN ||
                (!glass && next.sector.blockId != null &&
                    translucentCache.getOrPut(next.sector.blockId) { isTranslucent(next.sector.blockId) })
            if (prevOpenLike) {
                addSideWall(bucket, p.startAngle, -1f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1)
            }
            if (nextOpenLike) {
                addSideWall(bucket, p.endAngle, 1f, sectorRadians, texW, texH, effBorder, su0, su1, sv0, sv1)
            }
        }

        val sectorWalls = sectorBuckets.entries.map { (k, verts) ->
            val glass = translucentCache[ResourceLocation.tryParse(k)] == true
            // glass sectors blend instead of alpha cutting, single sided
            SectorWall(
                k,
                SingleMeshModel(
                    meshOf(verts, "waterslide_tube_wall"),
                    if (glass) GLASS_TRANSLUCENT_MATERIAL else TUBE_CUTOUT_MATERIAL
                ),
                glass
            )
        }

        val wallMesh = meshOf(wallVerts, "waterslide_tube_wall")
        val startCapMesh = meshOf(startCapVerts, "waterslide_tube_start_cap")
        val endCapMesh = meshOf(endCapVerts, "waterslide_tube_end_cap")
        return TubeModels(
            SingleMeshModel(wallMesh, TUBE_CUTOUT_MATERIAL),
            sectorWalls,
            SingleMeshModel(startCapMesh, TUBE_CAP_CUTOUT_MATERIAL),
            SingleMeshModel(endCapMesh, TUBE_CAP_CUTOUT_MATERIAL),
            SingleMeshModel(wallMesh, TUBE_TRANSLUCENT_MATERIAL),
            SingleMeshModel(startCapMesh, TUBE_TRANSLUCENT_MATERIAL),
            SingleMeshModel(endCapMesh, TUBE_TRANSLUCENT_MATERIAL),
            SingleMeshModel(endCapMesh, RING_TRANSLUCENT_MATERIAL)
        )
    }

    // dynamic water envelope model between two sections
    @JvmStatic
    fun waterModelFor(
        vertsA: List<Float>,
        vertsB: List<Float>,
        radius: Float
    ): Model {
        val shaderUpNormals = IrisColorwheelCompat.waterShadingActive()
        val key = buildString {
            append((radius * 8f).roundToInt()).append('|')
            append(waterCrossSections()).append('|')
            append(if (shaderUpNormals) "up|" else "rad|")
            for (f in vertsA) append((f * 20f).roundToInt()).append(',')
            append('|')
            for (f in vertsB) append((f * 20f).roundToInt()).append(',')
        }
        return waterModelCache.getOrPut(key) {
            val mat = if (IrisColorwheelCompat.iterationRpWaterMode())
                STREAM_TRANSLUCENT_DEPTH_MATERIAL
            else
                STREAM_TRANSLUCENT_MATERIAL
            buildWaterModel(vertsA, vertsB, radius, mat, shaderUpNormals)
        }
    }

    @JvmStatic
    fun clearWaterModels() {
        waterModelCache.clear()
    }

    private fun buildWaterModel(
        vertsA: List<Float>,
        vertsB: List<Float>,
        radius: Float,
        material: Material,
        shaderUpNormals: Boolean
    ): Model {
        val nA = vertsA.size / 2
        val nB = vertsB.size / 2
        // axial subdivisions between the two cross-sections
        val zSteps = 1
        // ring vertices are source positions only, never mesh quad data
        val ringVerts = ArrayList<V>()
        val waterVerts = ArrayList<V>()
        val waterSprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(ResourceLocation.withDefaultNamespace("block/water_still"))
        val su0 = waterSprite.u0
        val su1 = waterSprite.u1
        val sv0 = waterSprite.v0
        val sv1 = waterSprite.v1
        // bed and surface arc span a third of the circle, U tiles match the arc
        val tiles = (2f * Math.PI.toFloat() * radius * (330f - 210f) / 360f).coerceAtLeast(0.5f)

        fun addVertex(u: Float, v: Float, z: Float, uTex: Float) {
            val r = kotlin.math.sqrt(u * u + v * v).coerceAtLeast(0.001f)
            // up facing normals make pack reflection fall off like real water
            val nx = if (shaderUpNormals) 0f else u / r
            val ny = if (shaderUpNormals) 1f else v / r
            val nz = 0f
            // clean attributes, sprite rect rides the instance
            ringVerts += V(
                u, v, z,
                1f, 1f, 1f, 1f,
                uTex, if (z > 0.05f) 1f else 0f,
                0, 0x00F000F0,
                nx, ny, nz
            )
        }

        // only the bottom bed arc and top surface arc are built
        val bottomRings = zSteps + 1
        for (s in 0..zSteps) {
            val z = s.toFloat() / (2f * zSteps)
            for (i in 0 until nA) {
                addVertex(vertsA[i * 2], vertsA[i * 2 + 1], z, i.toFloat() / nA * tiles)
            }
        }
        val topBase = bottomRings * nA
        for (s in 0..zSteps) {
            val z = s.toFloat() / (2f * zSteps)
            for (i in 0 until nB) {
                addVertex(vertsB[i * 2], vertsB[i * 2 + 1], z, i.toFloat() / nB * tiles)
            }
        }
        // bottom band: water bed arc between consecutive axial rings
        for (s in 0 until zSteps) {
            for (i in 0 until nA - 1) {
                waterVerts += ringVerts[s * nA + i]
                waterVerts += ringVerts[(s + 1) * nA + i]
                waterVerts += ringVerts[(s + 1) * nA + i + 1]
                waterVerts += ringVerts[s * nA + i + 1]
            }
        }
        // top band: water surface arc between consecutive axial rings
        for (s in 0 until zSteps) {
            for (i in 0 until nB - 1) {
                waterVerts += ringVerts[topBase + s * nB + i]
                waterVerts += ringVerts[topBase + (s + 1) * nB + i]
                waterVerts += ringVerts[topBase + (s + 1) * nB + i + 1]
                waterVerts += ringVerts[topBase + s * nB + i + 1]
            }
        }
        return SingleMeshModel(meshOf(waterVerts, "waterslide_tube_water"), material)
    }

    // band ring vertices on the water grid, clipped to the bed arc range
    @JvmStatic
    fun bandVertices(rInFrac: Float, rSurfFrac: Float, mirror: Boolean): List<Float> {
        val crossN = waterCrossSections()
        val degStep = 360f / crossN
        val gridAnchor = 90f
        val bandLo = 210f
        val bandHi = 330f
        // collect the clipped grid angles inside the band, ascending
        val angles = ArrayList<Float>()
        val norm = { a: Float -> WaterslideSectorLayout.normalize(a) }
        for (k in 0 until crossN) {
            val raw0 = gridAnchor + k * degStep
            val raw1 = gridAnchor + (k + 1) * degStep
            val cells = if (raw1 <= 360f) listOf(raw0 to raw1)
            else if (raw0 >= 360f) listOf(raw0 - 360f to raw1 - 360f)
            else listOf(raw0 to 360f, 0f to raw1 - 360f)
            for ((cg0, cg1) in cells) {
                val s = max(cg0, bandLo)
                val e = min(cg1, bandHi)
                if (e <= s) continue
                if (angles.lastOrNull()?.let { abs(it - s) < 0.01f } != true) angles += s
                angles += e
            }
        }
        // normalize into the band range so the ring runs 210 -> 330 continuously
        val sorted = angles.map { a ->
            if (a < bandLo - 0.01f) a + 360f else a
        }.sorted()
        val out = ArrayList<Float>(sorted.size * 4)
        for (a in sorted) {
            val rad = Math.toRadians(a.toDouble())
            val u = (Math.cos(rad) * rInFrac).toFloat()
            val v = (Math.sin(rad) * rInFrac).toFloat()
            out += if (mirror) -u else u
            out += v
        }
        for (a in sorted.asReversed()) {
            val rad = Math.toRadians(a.toDouble())
            val u = (Math.cos(rad) * rSurfFrac).toFloat()
            val v = (Math.sin(rad) * rSurfFrac).toFloat()
            out += if (mirror) -u else u
            out += v
        }
        return out
    }


    private fun meshOf(verts: List<V>, descriptor: String): Mesh {
        if (verts.isEmpty()) {
// degenerate vertex
            val block = MemoryBlock.mallocTracked(36L)
            val empty = FullVertexView()
            empty.ptr(block.ptr())
            empty.nativeMemoryOwner(block)
            empty.vertexCount(1)
            empty.x(0, 0f)
            empty.y(0, 0f)
            empty.z(0, 0f)
            empty.r(0, 1f)
            empty.g(0, 1f)
            empty.b(0, 1f)
            empty.a(0, 1f)
            empty.u(0, 0f)
            empty.v(0, 0f)
            empty.overlay(0, 0)
            empty.light(0, 0)
            empty.normalX(0, 0f)
            empty.normalY(0, 0f)
            empty.normalZ(0, 0f)
            return SimpleQuadMesh(empty, descriptor)
        }
        val block = MemoryBlock.mallocTracked((verts.size * 36L))
        val view = FullVertexView()
        view.ptr(block.ptr())
        view.vertexCount(verts.size)
        view.nativeMemoryOwner(block)
        for ((i, v) in verts.withIndex()) {
            view.x(i, v.x)
            view.y(i, v.y)
            view.z(i, v.z)
            view.r(i, v.r)
            view.g(i, v.g)
            view.b(i, v.b)
            view.a(i, v.a)
            view.u(i, v.u)
            view.v(i, v.v)
            view.overlay(i, v.overlay)
            view.light(i, v.light)
            view.normalX(i, v.nx)
            view.normalY(i, v.ny)
            view.normalZ(i, v.nz)
        }
        return SimpleQuadMesh(view, descriptor)
    }

    private fun spriteFor(blockId: ResourceLocation): TextureAtlasSprite? {
        val block = BuiltInRegistries.BLOCK.get(blockId) ?: return null
        val state = block.defaultBlockState()
        val model = Minecraft.getInstance().blockRenderer.getBlockModel(state)
        // copycat style: dominant face sprite of the material block
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

    // glass family id split on the glass segment
    private fun isTranslucent(blockId: ResourceLocation): Boolean =
        blockId.path.split('_').any { it.contains("glass") }

    // border ring width scanned from the native image, cached per sprite
    private val borderCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

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
                // per side minimum across every row and column
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

    /** Sprite rect (u0,u1,v0,v1) for a block id — used to feed the instance. */
    @JvmStatic
    fun spriteRectFor(blockId: String): FloatArray? {
        val rl = ResourceLocation.tryParse(blockId) ?: return null
        val s = spriteFor(rl) ?: return null
        return floatArrayOf(s.u0, s.u1, s.v0, s.v1)
    }

    /** Sprite rect (u0,u1,v0,v1) of the water texture. */
    @JvmStatic
    fun waterSpriteRect(): FloatArray {
        val s = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(ResourceLocation.withDefaultNamespace("block/water_still"))
        return floatArrayOf(s.u0, s.u1, s.v0, s.v1)
    }

    // support structure, copycat style bracket shell and beam

    // shell is double sided, beam culls back faces, both use the tile fragment path
    @JvmStatic
    val SUPPORT_SHADERS: MaterialShaders = SimpleMaterialShaders(
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "material/support_material.vert"),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "material/support.frag")
    )

    @JvmStatic
    val SUPPORT_SHELL_MATERIAL: Material =
        SimpleMaterial.builderOf(Materials.CUTOUT_MIPPED_BLOCK)
            .shaders(SUPPORT_SHADERS)
            .backfaceCulling(false)
            .build()

    @JvmStatic
    val SUPPORT_BEAM_MATERIAL: Material =
        // single sided: winding keeps the outside front facing
        SimpleMaterial.builderOf(Materials.CUTOUT_MIPPED_BLOCK)
            .shaders(SUPPORT_SHADERS)
            .build()

    private val bracketCache = java.util.concurrent.ConcurrentHashMap<String, Model>()
    private val beamCache = java.util.concurrent.ConcurrentHashMap<String, Model>()

    // resolve the particle sprite exactly like Create copycat blocks
    @JvmStatic
    fun supportSprite(material: BlockState): TextureAtlasSprite? =
        runCatching {
            Minecraft.getInstance().blockRenderer.getBlockModel(material)
                .getParticleIcon(ModelData.EMPTY)
        }.getOrNull()

    // bridge style bracket, lower third arc band, CPU baked in instance space
    @JvmStatic
    fun supportBracketModelFor(
        frame: TubeSegmentFrame,
        config: WaterslideSectorConfig,
        sprite: TextureAtlasSprite,
        tStart: Float,
        tEnd: Float,
        material: BlockState
    ): Model {
        val key = buildString {
            append(ModClientConfig.polygonScale())
                .append('|').append(ModClientConfig.supportArcLo())
                .append('|').append(ModClientConfig.supportArcHi())
                .append('|').append(material)
                .append('|').append(ModClientConfig.wallThickness())
                .append('|').append(tStart).append('|').append(tEnd)
                .append('|').append(frame.prevSpine).append('|').append(frame.currSpine)
                .append('|').append(frame.prevTangent).append('|').append(frame.currTangent)
                .append('|').append(frame.prevLateral).append('|').append(frame.currLateral)
                .append('|').append(frame.prevRadius).append('|').append(frame.currRadius)
            for (s in config.sectors) {
                append('|').append(s.id)
                    .append(',').append(s.material)
                    .append(',').append(s.blockId)
                    .append(',').append(s.type)
                    .append(',').append(s.widthDegrees)
            }
        }
        return bracketCache.getOrPut(key) { buildBracket(frame, config, sprite, tStart, tEnd) }
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

    private fun bezierNormalize(v: Vec3): Vec3 {
        val l = v.length()
        return if (l > 1.0E-8) v.scale(1.0 / l) else Vec3(0.0, 1.0, 0.0)
    }

    private fun buildBracket(
        frame: TubeSegmentFrame,
        config: WaterslideSectorConfig,
        sprite: TextureAtlasSprite,
        tStart: Float,
        tEnd: Float
    ): Model {
        val placed = WaterslideSectorLayout.place(config)
        val crossN = crossSections()
        val degStep = 360f / crossN
        val gridAnchor = 90f
        // snapped to the polygon grid (nearest grid line) so the band edges
        // coincide with the tube wall's facet edges
        val arcLo = bracketArcLo()
        val arcHi = bracketArcHi()
        val su0 = sprite.u0
        val su1 = sprite.u1
        val sv0 = sprite.v0
        val sv1 = sprite.v1
        val ov = (Math.round(su0 * 32767f) shl 16) or Math.round(su1 * 32767f)
        val lt = (Math.round(sv0 * 65535f) shl 16) or Math.round(sv1 * 65535f)
        val texW = sprite.contents().width().toFloat()
        val texH = sprite.contents().height().toFloat()
        val border = ModConfig.sectorBorderPx().toFloat()
        val verts = ArrayList<V>()
        if (tEnd - tStart <= 0.001f) {
            return SingleMeshModel(
                meshOf(verts, "waterslide_tube_support_bracket"), SUPPORT_SHELL_MATERIAL
            )
        }

        val chord = frame.currSpine.subtract(frame.prevSpine)
        val handle = (chord.length() / 3.0).toFloat()
        val c0 = frame.prevSpine
        val c1 = frame.prevSpine.add(frame.prevTangent.scale(handle.toDouble()))
        val c2 = frame.currSpine.subtract(frame.currTangent.scale(handle.toDouble()))
        val c3 = frame.currSpine
        val supportThickness = ModClientConfig.supportThickness()
        // the tube wall is expanded outward to radius + (wallThickness - BASE_WALL),
        // so the bracket must hug that REAL outer surface (not the centerline
        // radius) plus a small epsilon — otherwise with the default 0.5 wall the
        // whole shell is buried inside the pipe, and at 0.1 it is exactly coplanar
        // with the wall and z-fights/flickers
        val wallOuter = ModClientConfig.wallThickness() - BASE_WALL
        // inner shell hugs the tube's OUTER wall exactly (radius = tube radius);
        // the outer shell adds the configured thickness so the bracket reads as
        // a solid saddle clamped around the tube instead of floating away from it
        val rBase0 = frame.prevRadius
        val rBase1 = frame.currRadius
        val lat0 = frame.prevLateral
        val lat1 = frame.currLateral
        val tan0 = frame.prevTangent
        val tan1 = frame.currTangent
        val arcStart = bezierArcLengthTo(c0, c1, c2, c3, tStart)

        for (p in placed) {
            if (p.sector.material == SectorMaterial.OPEN) continue
            val sectorDegrees = p.endAngle - p.startAngle
            if (sectorDegrees <= 0.001f) continue
            val sectorRadians = Math.toRadians(sectorDegrees.toDouble()).toFloat()
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
                        val f0 = (s + wrap - startNorm) / sectorDegrees
                        val f1 = (e + wrap - startNorm) / sectorDegrees
                        val a0 = Math.toRadians(s.toDouble())
                        val a1 = Math.toRadians(e.toDouble())
                        val cA0 = cos(a0).toFloat()
                        val sA0 = sin(a0).toFloat()
                        val cA1 = cos(a1).toFloat()
                        val sA1 = sin(a1).toFloat()
                        val midA = a0 + (a1 - a0) / 2.0
                        val cm = cos(midA).toFloat()
                        val sm = sin(midA).toFloat()

                        // per-corner world-space (instance-space) evaluation; radiusOffset is 0
                        // for the wall-hugging inner shell and supportThickness
                        // for the outer shell
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
                                lat = if (abs(tangent.y) < 0.9)
                                    Vec3(0.0, 1.0, 0.0)
                                else
                                    Vec3(1.0, 0.0, 0.0)
                            }
                            lat = bezierNormalize(lat)
                            val faceUp = bezierNormalize(tangent.cross(lat))
                            val radius = Mth.lerp(t, rBase0, rBase1) + radiusOffset
                            val pos = spine
                                .add(lat.scale((angleCos * radius).toDouble()))
                                .add(faceUp.scale((angleSin * radius).toDouble()))
                            return Triple(pos, lat, faceUp)
                        }

                        // emit one shell layer (inner wall-hugging, then outer)
                        fun emitLayer(radiusOffset: Float) {
                            for (k in 0 until LENGTH_SUBDIVISIONS) {
                                val tf0 = k / LENGTH_SUBDIVISIONS.toFloat()
                                val tf1 = (k + 1) / LENGTH_SUBDIVISIONS.toFloat()
                                // v = arc length in BLOCK units (tile count, not
                                // pixels) so the colorwheel/pack path samples the
                                // atlas with a repeating 0..1 coordinate; the
                                // support fragment shader scales back by texH
                                val vTile0 = bezierArcLengthTo(c0, c1, c2, c3, tStart + (tEnd - tStart) * tf0) - arcStart
                                val vTile1 = bezierArcLengthTo(c0, c1, c2, c3, tStart + (tEnd - tStart) * tf1) - arcStart
                                val (p00, lat00, up00) = cor(cA0, sA0, tf0, radiusOffset)
                                val (p10, lat10, up10) = cor(cA1, sA1, tf0, radiusOffset)
                                val (p11, _, _) = cor(cA1, sA1, tf1, radiusOffset)
                                val (p01, _, _) = cor(cA0, sA0, tf1, radiusOffset)
                                // radius at quad center for u pixel scale
                                val tC = tStart + (tEnd - tStart) * (tf0 + tf1) * 0.5f
                                val radiusC = Mth.lerp(tC, rBase0, rBase1) + radiusOffset
                                // normal = angle-mid direction in the local frame
                                val n0 = bezierNormalize(lat00.scale(cm.toDouble()).add(up00.scale(sm.toDouble())))
                                val n1 = bezierNormalize(lat10.scale(cm.toDouble()).add(up10.scale(sm.toDouble())))
                                // copycat-style full-tile mapping: each face
                                // spans the whole sprite window (border inset,
                                // same fold the walls/glass use), v repeats the
                                // sprite every block along the tube
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
                                // clean attributes (white/opaque, fullbright light,
                                // no overlay); sprite rect lives in the uv
                                verts += V(
                                    p00.x.toFloat(), p00.y.toFloat(), p00.z.toFloat(),
                                    1f, 1f, 1f, 1f, stripU(0f), vAtlas(vTile0), 0, 0x00F000F0,
                                    n0.x.toFloat(), n0.y.toFloat(), n0.z.toFloat()
                                )
                                verts += V(
                                    p10.x.toFloat(), p10.y.toFloat(), p10.z.toFloat(),
                                    1f, 1f, 1f, 1f, stripU(1f), vAtlas(vTile0), 0, 0x00F000F0,
                                    n1.x.toFloat(), n1.y.toFloat(), n1.z.toFloat()
                                )
                                verts += V(
                                    p11.x.toFloat(), p11.y.toFloat(), p11.z.toFloat(),
                                    1f, 1f, 1f, 1f, stripU(1f), vAtlas(vTile1), 0, 0x00F000F0,
                                    n1.x.toFloat(), n1.y.toFloat(), n1.z.toFloat()
                                )
                                verts += V(
                                    p01.x.toFloat(), p01.y.toFloat(), p01.z.toFloat(),
                                    1f, 1f, 1f, 1f, stripU(0f), vAtlas(vTile1), 0, 0x00F000F0,
                                    n0.x.toFloat(), n0.y.toFloat(), n0.z.toFloat()
                                )
                            }
                        }

                        // side panels closing the shell band
                        val sideRIn = wallOuter + SUPPORT_HUG_EPSILON
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
                        fun angularSidePanel(angleDeg: Float) {
                            val a = Math.toRadians(angleDeg.toDouble())
                            val cA = cos(a).toFloat()
                            val sA = sin(a).toFloat()
                            // tangential normal (side face looks along the arc)
                            val tA = a + Math.PI / 2.0
                            val ct = cos(tA).toFloat()
                            val st = sin(tA).toFloat()
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
                                verts += V(
                                    pIn0.x.toFloat(), pIn0.y.toFloat(), pIn0.z.toFloat(),
                                    1f, 1f, 1f, 1f, sideU(0f), sideV(vTile0), 0, 0x00F000F0,
                                    n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                                )
                                verts += V(
                                    pOut0.x.toFloat(), pOut0.y.toFloat(), pOut0.z.toFloat(),
                                    1f, 1f, 1f, 1f, sideU(1f), sideV(vTile0), 0, 0x00F000F0,
                                    n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                                )
                                verts += V(
                                    pOut1.x.toFloat(), pOut1.y.toFloat(), pOut1.z.toFloat(),
                                    1f, 1f, 1f, 1f, sideU(1f), sideV(vTile1), 0, 0x00F000F0,
                                    n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                                )
                                verts += V(
                                    pIn1.x.toFloat(), pIn1.y.toFloat(), pIn1.z.toFloat(),
                                    1f, 1f, 1f, 1f, sideU(0f), sideV(vTile1), 0, 0x00F000F0,
                                    n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                                )
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
                            // one panel per angular cell of the fragment
                            for (j2 in 0 until crossN) {
                                val raw0 = gridAnchor + j2 * degStep
                                val raw1 = gridAnchor + (j2 + 1) * degStep
                                val cs0 = max(raw0, s)
                                val ce1 = min(raw1, e)
                                if (ce1 <= cs0) continue
                                if (raw1 <= s || raw0 >= e) continue
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
                                // u across the cell angle, v across the thickness
                                val frac0 = (cs0 - s) / (e - s)
                                val frac1 = (ce1 - s) / (e - s)
                                fun cellU(frac: Float): Float =
                                    su0 + ((border + frac * sideStripW) / texW) * (su1 - su0)
                                verts += V(
                                    pA0.x.toFloat(), pA0.y.toFloat(), pA0.z.toFloat(),
                                    1f, 1f, 1f, 1f, cellU(frac0), sideV(0f), 0, 0x00F000F0,
                                    n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                                )
                                verts += V(
                                    pB0.x.toFloat(), pB0.y.toFloat(), pB0.z.toFloat(),
                                    1f, 1f, 1f, 1f, cellU(frac0), sideV(0.5f), 0, 0x00F000F0,
                                    n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                                )
                                verts += V(
                                    pB1.x.toFloat(), pB1.y.toFloat(), pB1.z.toFloat(),
                                    1f, 1f, 1f, 1f, cellU(frac1), sideV(0.5f), 0, 0x00F000F0,
                                    n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                                )
                                verts += V(
                                    pA1.x.toFloat(), pA1.y.toFloat(), pA1.z.toFloat(),
                                    1f, 1f, 1f, 1f, cellU(frac1), sideV(0f), 0, 0x00F000F0,
                                    n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                                )
                            }
                        }
                        fun materialNear(angleDeg: Float): SectorMaterial? {
                            var a = WaterslideSectorLayout.normalize(angleDeg)
                            for (pp in placed) {
                                val st = WaterslideSectorLayout.normalize(pp.startAngle)
                                val w = pp.endAngle - pp.startAngle
                                val inside = if (st + w <= 360f)
                                    a >= st - 0.001f && a <= st + w + 0.001f
                                else
                                    a >= st - 0.001f || a <= st + w - 360f + 0.001f
                                if (inside) return pp.sector.material
                            }
                            return null
                        }
                        fun emitShellSides(
                            sDeg: Float, eDeg: Float, loDeg: Float, hiDeg: Float,
                            cA0x: Float, sA0x: Float, cA1x: Float, sA1x: Float
                        ) {
                            // angular end faces ONLY at the shell's true ends:
                            // the support-range edges or where the neighbour is
                            // open/none - never at internal sector boundaries
                            val panelStart = sDeg <= arcLo + 0.001f ||
                                materialNear(sDeg - 1.5f) == SectorMaterial.OPEN ||
                                materialNear(sDeg - 1.5f) == null
                            val panelEnd = eDeg >= arcHi - 0.001f ||
                                materialNear(eDeg + 1.5f) == SectorMaterial.OPEN ||
                                materialNear(eDeg + 1.5f) == null
                            if (panelStart) angularSidePanel(sDeg)
                            if (panelEnd) angularSidePanel(eDeg)
                            // axial cross-section faces at both band ends
                            axialSidePanel(0f)
                            axialSidePanel(1f)
                        }
                        // shell band: inner (tube-hugging) + outer layers FIRST,
                        // then the side panels close the ends
                        emitLayer(wallOuter + SUPPORT_HUG_EPSILON)
                        if (supportThickness > 0.001f) {
                            emitLayer(wallOuter + SUPPORT_HUG_EPSILON + supportThickness)
                        }
                        emitShellSides(s, e, lo, hi, cA0, sA0, cA1, sA1)
                    }
                }
            }
        }
        return SingleMeshModel(meshOf(verts, "waterslide_tube_support_bracket"), SUPPORT_SHELL_MATERIAL)
    }

    // support beam model, square column in instance space, 4px strip UVs
    @JvmStatic
    fun supportBeamModelFor(
        base: Vec3,
        axisN: Vec3,
        len: Float,
        sprite: TextureAtlasSprite,
        material: BlockState,
        topOffsets: FloatArray? = null,
        bottomOffsets: FloatArray? = null
    ): Model {
        val key = buildString {
            append((len * 8f).roundToInt()).append('|')
            append(Math.round(base.x * 32f)).append(',').append(Math.round(base.y * 32f)).append(',').append(Math.round(base.z * 32f)).append('|')
            append(Math.round(axisN.x * 256f)).append(',').append(Math.round(axisN.y * 256f)).append(',').append(Math.round(axisN.z * 256f)).append('|')
            append(material)
            topOffsets?.let { append('|').append(it[0]).append(',').append(it[1]).append(',').append(it[2]).append(',').append(it[3]) }
            bottomOffsets?.let { append('|').append(it[0]).append(',').append(it[1]).append(',').append(it[2]).append(',').append(it[3]) }
        }
        return beamCache.getOrPut(key) { buildBeam(base, axisN, len, sprite, topOffsets, bottomOffsets) }
    }

    // must match the basis used by beamTopOffsets/beamBottomOffsets (same ref)
    private fun orthonormalBasis(axis: Vec3): Pair<Vec3, Vec3> {
        // MUST match the basis used by beamTopOffsets/beamBottomOffsets in
        // WaterslideTubeVisual (same ref): a mismatch makes the per-corner
        // top/bottom offsets land on the WRONG corners of each face, so the
        // four sides tile at different densities
        val ref = if (abs(axis.y) < 0.9f) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        var n1 = bezierNormalize(ref.cross(axis))
        if (n1.lengthSqr() < 1.0E-8) n1 = Vec3(1.0, 0.0, 0.0)
        val n2 = bezierNormalize(axis.cross(n1))
        return Pair(n1, n2)
    }

    private fun buildBeam(
        base: Vec3,
        axisN: Vec3,
        len: Float,
        sprite: TextureAtlasSprite,
        topOffsets: FloatArray? = null,
        bottomOffsets: FloatArray? = null
    ): Model {
        val size = ModClientConfig.supportBeamSize()
        val half = size / 2f
        val su0 = sprite.u0
        val su1 = sprite.u1
        val sv0 = sprite.v0
        val sv1 = sprite.v1
        val verts = ArrayList<V>()
        val (n1, n2) = orthonormalBasis(axisN)

        fun side(n: Vec3, w: Vec3) {
            // quadrant index from the corner coordinates
            fun quadrant(wSign: Float): Int {
                val n1c = (n.dot(n1) + w.dot(n1) * wSign) * half
                val n2c = (n.dot(n2) + w.dot(n2) * wSign) * half
                return (if (n1c >= 0) 2 else 0) + (if (n2c >= 0) 1 else 0)
            }

            fun topOff(wSign: Float): Float =
                topOffsets?.getOrNull(quadrant(wSign)) ?: 0f

            fun bottomOff(wSign: Float): Float =
                bottomOffsets?.getOrNull(quadrant(wSign)) ?: 0f

            fun pt(ws: Float, ts: Float, drop: Float): Vec3 =
                base.add(n.scale(half.toDouble())).add(w.scale(ws.toDouble()))
                    .add(axisN.scale(ts.toDouble())).add(0.0, -drop.toDouble(), 0.0)
            // bottom corners drop straight down onto the anchor top face
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
            // one quad per block: the tiling element, linear uv inside each
            // quad so the texture repeats per block and never reverses
            fun uV(v: Float): Float = su0 + v * (su1 - su0)
            fun vV(v: Float): Float = sv0 + v * (sv1 - sv0)
            fun vx(v: Vec3, u: Float, vv: Float): V =
                V(
                    v.x.toFloat(), v.y.toFloat(), v.z.toFloat(),
                    1f, 1f, 1f, 1f,
                    uV(u), vV(vv),
                    0, 0x00F000F0, n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                )
            val segments = max(1, ceil(totalBlocks).toInt())
            for (k in 0 until segments) {
                val fa = tMin + k
                val fb = min(fa + 1f, tMax)
                val span = fb - fa
                // lerp c0->c3 / c1->c2 across the edge run
                fun edgePos(left: Boolean, fv: Float): Vec3 {
                    val a = if (left) c0 else c1
                    val b = if (left) c3 else c2
                    val runLo = if (left) vLo0 else vLo1
                    val runHi = if (left) vHi0 else vHi1
                    val t01 = ((fv - runLo) / (runHi - runLo)).toDouble()
                    return a.add((b.subtract(a)).scale(t01.coerceIn(0.0, 1.0)))
                }
                val pa = edgePos(true, fa)
                val pb = edgePos(false, fa)
                val pc = edgePos(false, fb)
                val pd = edgePos(true, fb)
                verts += vx(pa, 0f, 0f)
                verts += vx(pd, 0f, span)
                verts += vx(pc, size, span)
                verts += vx(pb, size, 0f)
            }
        }

        // four side faces, width runs along minus the normal to stay front facing
        side(n1, n2.scale(-1.0))
        side(n1.scale(-1.0), n2)
        side(n2, n1)
        side(n2.scale(-1.0), n1.scale(-1.0))
        // no end caps, both ends sit inside other geometry

        return SingleMeshModel(meshOf(verts, "waterslide_tube_support_beam"), SUPPORT_BEAM_MATERIAL)
    }
}