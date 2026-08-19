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
import net.minecraft.client.renderer.block.model.BakedQuad
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
    // tiny radial offset so the support shell/beam never sit exactly coplanar
    // with the tube wall — CPU-baked vs GPU-evaluated depths would z-fight
    @JvmField
    val SUPPORT_HUG_EPSILON: Float = 0.005f

    // Support beam/bracket texture strip width in sprite pixels. Both pieces
    // sample only this 4px-wide strip across their width and stack it along
    // their length, using the same 2px border fold as the slide walls.
    const val SUPPORT_STRIP_PX: Float = 4f

    // Concurrent: the world visual AND the contraption actor visual can both
    // build models on Flywheel worker threads. Kotlin's ConcurrentMap.getOrPut
    // is atomic, so first-build races cannot lose models.
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

// water is single-sided; cull backfaces between instances.
// WriteMask.COLOR_DEPTH: the colorwheel pass must write the real water-surface
// depth into depthtex0 - iterationRP's composite computes waterDeep from
// (opaqueDepth - waterDepth); without the depth write waterDepth == the far
// wall, waterDeep == 0 and the whole water fog/scattering never runs (the
// water then reads as the refracted background + sky reflection = white).
    val WATER_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(true)
            .writeMask(WriteMask.COLOR_DEPTH)
            .build()

// thrown water is visible from both sides
    val STREAM_TRANSLUCENT_MATERIAL: Material =
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
        val model: Model
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

    // Water-only cross-section density. iterationRP renders the water band and
    // thrown stream through the pack's refraction/reflection path, where the
    // low-poly band silhouette is far more visible than under vanilla lighting,
    // so the water rings are subdivided 10x relative to the tube wall. The tube
    // wall, caps and supports keep using crossSections() unchanged, and every
    // other shaderpack keeps the base water density.
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

// frames (with extensions). `useRailFrames` makes the cross-section follow
// Coaster's actual rail banking; Sable sub-levels use this so a rotated plot
// pose cannot twist the wall between neighbouring curve segments.
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

    // level-free overload for contraption-local tube meshes: no rail frames (the
    // mounted preview uses the stable world-up frame) and no open-end extensions
    // (extension length needs a live CoasterAnchorpointBlockEntity in a real level).
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
            sideWall: Boolean = false
        ) {
            // Clean attributes (white/opaque, fullbright light, no overlay):
            // Colorwheel forwards the raw mesh attributes verbatim to the
            // shaderpack, which samples texture() with the vertex uv AS an
            // atlas coordinate. So the uv must be tile coordinates folded into
            // the sprite's atlas rect (u0..u1 / v0..v1): mod keeps one sprite
            // per tile so the pack never bleeds into other atlas regions.
            // Tile widths: walls ~1 tile per block of arc length, side walls
            // 1 tile per wallThickness of radial span, V 2 tiles per frame
            // (frames sample every 0.5 blocks) = 1 tile per block.
            val uTiles = if (sideWall)
                1f // side wall: one full sprite across its thickness (no mod
                   // wrap so the adjacent sector edge never bleeds atlas colors)
            else {
                // 0.1.5-faithful tiling: px = u * sectorRadians * texRadius * 16
                // with the per-vertex inner/outer wall radius (inner wall =
                // radius - BASE_WALL, outer = radius + wallThickness - BASE_WALL).
                // The old fixed *2 hardcode only matched radius 2 and stretched
                // the texture on every other radius.
                val innerWall = nx * x + ny * y < 0f
                val texRadius = if (innerWall)
                    (radius - BASE_WALL).coerceAtLeast(0.1f)
                else
                    radius + (ModClientConfig.wallThickness() - BASE_WALL)
                (sectorRadians * texRadius).coerceAtLeast(1f)
            }
            // 0.1.5-style pixel-domain fold with the 2px border inset restored:
            // sampling at exactly su0/su1 (the sprite rect edges) blends with
            // the neighbouring atlas sprite under mipmapping/linear filtering —
            // the green face at the OPEN/BLOCK sector boundary columns and the
            // shimmering support edges. The fold keeps every sample >= border px
            // inside the sprite, exactly like the old fragment shader did.
            // Side walls span a single tile (u in 0..1 across the wall
            // thickness), so they use the non-periodic inset mapping instead:
            // a periodic fold would wrap at u=1 and slice the sprite mid-tile,
            // which reads as a stretched/offset texture on the side wall.
            val centerW = max(texW - 2f * border, 1f)
            val centerH = max(texH - 2f * border, 1f)
            val uFrac = if (sideWall)
                (border + u * centerW) / texW
            else
                ((border + (u * uTiles * texW % centerW)) % texW) / texW
            val vFrac = ((border + (v * 0.5f * texH % centerH)) % texH) / texH
            val uAtlas = spriteU0 + uFrac * (spriteU1 - spriteU0)
            val vAtlas = spriteV0 + vFrac * (spriteV1 - spriteV0)
            // Side walls keep a POSITIVE atlas u (no negative flag): the vertex
            // shader derives inner/outer from lp.xy length + normal orientation,
            // so the old negative-u channel (used as a radial mix() fraction) is
            // what extruded the side wall ~1 block OUTSIDE the tube at OPEN
            // sector boundaries - the wrong wall width between sectors.
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
            // real radial span; the plain wall branch maps radius 1.0 to the outer
            // wall and radius < 0.95 to the inner wall
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
            // one mesh per sector so every wall instance carries a single
            // sprite through the instance buffer (Colorwheel floods mesh
            // attributes to packs, so per-vertex sprite encoding is forbidden)
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

                            // Outer wall (dup into the sector bucket and the
                            // composite wallVerts used by the translucent pass)
                            add(bucket, c0, s0, z0, cm, sm, 0f, f0, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c0, s0, z0, cm, sm, 0f, f0, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(bucket, c1, s1, z0, cm, sm, 0f, f1, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c1, s1, z0, cm, sm, 0f, f1, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(bucket, c1, s1, z1, cm, sm, 0f, f1, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c1, s1, z1, cm, sm, 0f, f1, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(bucket, c0, s0, z1, cm, sm, 0f, f0, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c0, s0, z1, cm, sm, 0f, f0, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)

                            // Inner wall
                            add(bucket, c0, s0, z0, -cm, -sm, 0f, f0, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c0, s0, z0, -cm, -sm, 0f, f0, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(bucket, c0, s0, z1, -cm, -sm, 0f, f0, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c0, s0, z1, -cm, -sm, 0f, f0, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(bucket, c1, s1, z1, -cm, -sm, 0f, f1, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c1, s1, z1, -cm, -sm, 0f, f1, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(bucket, c1, s1, z0, -cm, -sm, 0f, f1, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c1, s1, z0, -cm, -sm, 0f, f1, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                        }

                        // End cap
                        add(endCapVerts, c0, s0, 0f, c0, s0, 1f, f0, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                        add(endCapVerts, c1, s1, 0f, c1, s1, 1f, f1, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                        add(endCapVerts, c1, s1, 0f, -c1, -s1, 1f, f1, 0f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                        add(endCapVerts, c0, s0, 0f, -c0, -s0, 1f, f0, 0f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)

                        // Start cap
                        add(startCapVerts, c0, s0, 0f, c0, s0, -1f, f0, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                        add(startCapVerts, c0, s0, 0f, -c0, -s0, -1f, f0, 0f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                        add(startCapVerts, c1, s1, 0f, -c1, -s1, -1f, f1, 0f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                        add(startCapVerts, c1, s1, 0f, c1, s1, -1f, f1, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                    }
                }
            }

            // side walls next to open sectors (folded into this sector's mesh)
            val idx = placed.indexOf(p)
            val prev = placed[(idx - 1 + placed.size) % placed.size]
            val next = placed[(idx + 1) % placed.size]
            if (prev.sector.material == SectorMaterial.OPEN) {
                addSideWall(bucket, p.startAngle, -1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
            }
            if (next.sector.material == SectorMaterial.OPEN) {
                addSideWall(bucket, p.endAngle, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
            }
        }

        val sectorWalls = sectorBuckets.entries.map { (k, verts) ->
            SectorWall(k, SingleMeshModel(meshOf(verts, "waterslide_tube_wall"), TUBE_CUTOUT_MATERIAL))
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
            buildWaterModel(vertsA, vertsB, radius, STREAM_TRANSLUCENT_MATERIAL, shaderUpNormals)
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
        // ring vertices are source positions only; they must not be in the mesh
        // (SimpleQuadMesh would treat every 4 of them as a quad and emit garbage
        // cross-section faces perpendicular to the tube)
        val ringVerts = ArrayList<V>()
        val waterVerts = ArrayList<V>()
        val waterSprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(ResourceLocation.withDefaultNamespace("block/water_still"))
        val su0 = waterSprite.u0
        val su1 = waterSprite.u1
        val sv0 = waterSprite.v0
        val sv1 = waterSprite.v1
        // bed/surface arc only spans 210°→330° (120° = 1/3 of the circle); scale
        // the U tile count to that arc so U and V both use ~1 tile per block
        val tiles = (2f * Math.PI.toFloat() * radius * (330f - 210f) / 360f).coerceAtLeast(0.5f)

        fun addVertex(u: Float, v: Float, z: Float, uTex: Float) {
            val r = kotlin.math.sqrt(u * u + v * v).coerceAtLeast(0.001f)
            // Normals: without shaders a radial normal shades the curved water
            // surface like the wall; under a shaderpack (stamped water id) a
            // radial normal makes the fresnel reflection read as grazing from
            // every angle and the water looks like a mirror. Up-facing normals
            // make the pack's reflection fall off with view angle like real
            // water. Models are cached per render state, so switching packs
            // clears the cache and rebuilds with the other normal.
            val nx = if (shaderUpNormals) 0f else u / r
            val ny = if (shaderUpNormals) 1f else v / r
            val nz = 0f
            // clean attributes: white/opaque, fullbright light, no overlay
            // (Colorwheel forwards these to packs; sprite rect rides instances)
            ringVerts += V(
                u, v, z,
                1f, 1f, 1f, 1f,
                uTex, if (z > 0.25f) 1f else 0f,
                0, 0x00F000F0,
                nx, ny, nz
            )
        }

        // only the bottom arc ring (water bed) and the top arc ring (water
        // surface) are built, with axial rings at every step; the sloped walls
        // connecting the two arcs are intentionally not built
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

    // band ring vertices on the water grid (10x the tube wall grid under
    // iterationRP): bottom arc at rInFrac, top arc back at rSurfFrac, clipped
    // to 210-330 degrees
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
        // copycat-style: pick the model's dominant face sprite so the support
        // structure looks exactly like the material block's main texture (not
        // a small accent face and not the decorative particle icon)
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

    // ------------------------------------------------------------------
    // support structure (copycat-style): bracket shell + beam
    // ------------------------------------------------------------------

    // shell is double-sided (readable from inside the tube); beam culls back
    // faces normally. Both use the same tile-in-sprite fragment path.
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
        // Single-sided: a double-sided face is the SAME geometry twice, which
        // z-fights and flickers regardless of transparency. Culling is
        // winding-based, so the mesh uses a winding that keeps the outside
        // front-facing.
        SimpleMaterial.builderOf(Materials.CUTOUT_MIPPED_BLOCK)
            .shaders(SUPPORT_SHADERS)
            .build()

    private val bracketCache = java.util.concurrent.ConcurrentHashMap<String, Model>()
    private val beamCache = java.util.concurrent.ConcurrentHashMap<String, Model>()

    // Copycat-style material: resolve the particle sprite of the stored
    // BlockState exactly like Create's copycat blocks present their material.
    @JvmStatic
    fun supportSprite(material: BlockState): TextureAtlasSprite? =
        runCatching {
            Minecraft.getInstance().blockRenderer.getBlockModel(material)
                .getParticleIcon(ModelData.EMPTY)
        }.getOrNull()

    // Bridge-style bracket model for one anchor: a short arc band hugging the
    // tube's lower 1/3 arc, spanning curve parameter [tStart, tEnd] (anchor at
    // the curve end). Blank (OPEN) sectors are masked out. Arc bounds come from
    // the client config (supportArcLo/Hi). Positions are baked on the CPU in
    // instance space (frame spines are already origin-relative), so the support
    // visual is a pure-translation instance and textures are never sheared by
    // a GPU bezier/radius transform. UVs are pixel space (16 px per block).
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
        val arcLo = ModClientConfig.supportArcLo()
        val arcHi = ModClientConfig.supportArcHi()
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
                                // stacked 4px strip material: each angular cell
                                // repeats the same 4px strip across its width,
                                // and v repeats the sprite every block along the
                                // tube. Same 2px border fold as the slide walls,
                                // so pack/vanilla sampling never hits the sprite
                                // edge (mip bleed into neighbouring atlas tiles).
                                val centerH = max(texH - 2f * border, 1f)
                                val stripW = min(SUPPORT_STRIP_PX, max(texW - 2f * border, 1f))
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
                        emitLayer(wallOuter + SUPPORT_HUG_EPSILON)
                        if (supportThickness > 0.001f) emitLayer(wallOuter + SUPPORT_HUG_EPSILON + supportThickness)
                    }
                }
            }
        }
        return SingleMeshModel(meshOf(verts, "waterslide_tube_support_bracket"), SUPPORT_SHELL_MATERIAL)
    }

    // Support beam model (girder-style, like CCS's anchor girder): a square
    // column from `base` along unit direction `axisN` for `len` blocks, all in
    // INSTANCE space (CPU-baked, so the visual is a pure translation). Square
    // profile edge = config supportBeamSize. UVs: each face maps the 4px-wide
    // strip of the sprite across its width, v repeats per block along the beam.
    @JvmStatic
    fun supportBeamModelFor(
        base: Vec3,
        axisN: Vec3,
        len: Float,
        sprite: TextureAtlasSprite,
        material: BlockState
    ): Model {
        val key = buildString {
            append((len * 8f).roundToInt()).append('|')
            append(Math.round(base.x * 32f)).append(',').append(Math.round(base.y * 32f)).append(',').append(Math.round(base.z * 32f)).append('|')
            append(Math.round(axisN.x * 256f)).append(',').append(Math.round(axisN.y * 256f)).append(',').append(Math.round(axisN.z * 256f)).append('|')
            append(material)
        }
        return beamCache.getOrPut(key) { buildBeam(base, axisN, len, sprite) }
    }

    private fun orthonormalBasis(axis: Vec3): Pair<Vec3, Vec3> {
        val ref = if (abs(axis.y) < 0.9f) Vec3(0.0, 1.0, 0.0) else Vec3(0.0, 0.0, 1.0)
        var n1 = bezierNormalize(ref.cross(axis))
        if (n1.lengthSqr() < 1.0E-8) n1 = Vec3(1.0, 0.0, 0.0)
        val n2 = bezierNormalize(axis.cross(n1))
        return Pair(n1, n2)
    }

    private fun buildBeam(base: Vec3, axisN: Vec3, len: Float, sprite: TextureAtlasSprite): Model {
        val size = ModClientConfig.supportBeamSize()
        val half = size / 2f
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
        val cg = texW / 64f
        val cb = texH / 64f
        val ca = border / 16f
        // stacked 4px strip material: u spans only the 4px strip inside the
        // sprite (with the 2px border inset), v repeats the sprite every block
        // along the beam. This matches the slide wall's border-fold tiling and
        // the bracket shell below.
        val (n1, n2) = orthonormalBasis(axisN)
        val lenD = len.toDouble()
        val beamStripW = min(SUPPORT_STRIP_PX, max(texW - 2f * border, 1f))
        val beamCenterH = max(texH - 2f * border, 1f)

        fun side(n: Vec3, w: Vec3, u0f: Float, u1f: Float, v0f: Float, v1f: Float) {
            fun pt(ws: Float, ts: Float): Vec3 =
                base.add(n.scale(half.toDouble())).add(w.scale(ws.toDouble())).add(axisN.scale(ts.toDouble()))
            val c0 = pt(-half, 0f)
            val c1 = pt(half, 0f)
            val c2 = pt(half, len)
            val c3 = pt(-half, len)
            // 0.1.5-style border fold: u = 0..1 across the face maps into the
            // 4px strip; v tiles per block with a border inset at every seam.
            fun vx(v: Vec3, u: Float, vv: Float): V {
                val uF = (border + u * beamStripW) / texW
                val vF = ((border + ((vv * texH) % beamCenterH)) % texH) / texH
                return V(
                    v.x.toFloat(), v.y.toFloat(), v.z.toFloat(),
                    1f, 1f, 1f, 1f,
                    su0 + uF * (su1 - su0),
                    sv0 + vF * (sv1 - sv0),
                    0, 0x00F000F0, n.x.toFloat(), n.y.toFloat(), n.z.toFloat()
                )
            }
            // winding c0,c3,c2,c1 keeps the OUTSIDE (towards +n) front-facing:
            // culling is winding-based, so the same order determines what a
            // single-sided material shows
            verts += vx(c0, u0f, v0f)
            verts += vx(c3, u0f, v1f)
            verts += vx(c2, u1f, v1f)
            verts += vx(c1, u1f, v0f)
        }

        // four side faces, u 0..1 across each face width, v 0..len along the axis.
        // Winding: the CCW front normal of the c0,c3,c2,c1 quad is (axis x w). So
        // for the +n1/-n1 faces the width must run along -n2/+n2 (NOT +n2/-n2) —
        // otherwise their outside is back-facing and single-sided culling hides
        // two of the four sides.
        side(n1, n2.scale(-1.0), 0f, 1f, 0f, lenD.toFloat())
        side(n1.scale(-1.0), n2, 0f, 1f, 0f, lenD.toFloat())
        side(n2, n1, 0f, 1f, 0f, lenD.toFloat())
        side(n2.scale(-1.0), n1.scale(-1.0), 0f, 1f, 0f, lenD.toFloat())
        // no end caps: the top sits inside the bracket shell against the tube
        // underside and the bottom against the anchor top face, so the end
        // faces are invisible — and skipping them avoids border-tiling artifacts

        return SingleMeshModel(meshOf(verts, "waterslide_tube_support_beam"), SUPPORT_BEAM_MATERIAL)
    }
}
