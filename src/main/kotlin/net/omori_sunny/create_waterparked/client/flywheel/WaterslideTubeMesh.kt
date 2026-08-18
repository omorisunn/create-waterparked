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
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.PlacedSector
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
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

// water is single-sided; cull backfaces between instances
    val WATER_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(true)
            .writeMask(WriteMask.COLOR)
            .build()

// thrown water is visible from both sides
    val STREAM_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .shaders(TUBE_SHADERS)
            .backfaceCulling(false)
            .writeMask(WriteMask.COLOR)
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
    data class TubeModels(
        val wall: Model,
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
    fun modelsFor(config: WaterslideSectorConfig): TubeModels {
        val key = signature(config)
        return modelCache.getOrPut(key) { build(config) }
    }

    // kept for existing world-visual call sites; level is not needed by build()
    @JvmStatic
    fun modelsFor(level: Level, config: WaterslideSectorConfig): TubeModels = modelsFor(config)

    @JvmStatic
    fun clearModels() {
        modelCache.clear()
        waterModelCache.clear()
    }

    @JvmStatic
    fun crossSections(): Int =
        max(2, (16 * ModClientConfig.polygonScale()).roundToInt())

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

    private fun signature(config: WaterslideSectorConfig): String =
        buildString {
            append(ModClientConfig.polygonScale())
            append('|').append(config.startAngle)
            for (s in config.sectors) {
                append('|').append(s.id)
                    .append(',').append(s.material)
                    .append(',').append(s.blockId)
                    .append(',').append(s.type)
                    .append(',').append(s.widthDegrees)
            }
        }

    private fun build(config: WaterslideSectorConfig): TubeModels {
        val placed = WaterslideSectorLayout.place(config)
        // low-poly cross-section, density from client config
        val crossN = crossSections()
        val degStep = 360f / crossN
        val gridAnchor = 90f

        val wallVerts = ArrayList<V>()
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
            val ovY = Math.round(spriteU1 * 32767f)
            dst += V(
                x, y, z,
                sectorRadians / (2.0 * Math.PI).toFloat(),
                texW / 64f, texH / 64f, border / 16f,
                u, v,
                (Math.round(spriteU0 * 32767f) shl 16) or
                    if (sideWall) (ovY and 0x7FFF) or 0x8000 else ovY,
                (Math.round(spriteV0 * 65535f) shl 16) or Math.round(spriteV1 * 65535f),
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

                            // Outer wall
                            add(wallVerts, c0, s0, z0, cm, sm, 0f, f0, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c1, s1, z0, cm, sm, 0f, f1, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c1, s1, z1, cm, sm, 0f, f1, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c0, s0, z1, cm, sm, 0f, f0, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)

                            // Inner wall
                            add(wallVerts, c0, s0, z0, -cm, -sm, 0f, f0, v0, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c0, s0, z1, -cm, -sm, 0f, f0, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                            add(wallVerts, c1, s1, z1, -cm, -sm, 0f, f1, v1, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
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

            // side walls next to open sectors
            val idx = placed.indexOf(p)
            val prev = placed[(idx - 1 + placed.size) % placed.size]
            val next = placed[(idx + 1) % placed.size]
            if (prev.sector.material == SectorMaterial.OPEN) {
                addSideWall(wallVerts, p.startAngle, -1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
            }
            if (next.sector.material == SectorMaterial.OPEN) {
                addSideWall(wallVerts, p.endAngle, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
            }
        }

        val wallMesh = meshOf(wallVerts, "waterslide_tube_wall")
        val startCapMesh = meshOf(startCapVerts, "waterslide_tube_start_cap")
        val endCapMesh = meshOf(endCapVerts, "waterslide_tube_end_cap")
        return TubeModels(
            SingleMeshModel(wallMesh, TUBE_CUTOUT_MATERIAL),
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
        val key = buildString {
            append((radius * 8f).roundToInt()).append('|')
            for (f in vertsA) append((f * 20f).roundToInt()).append(',')
            append('|')
            for (f in vertsB) append((f * 20f).roundToInt()).append(',')
        }
        return waterModelCache.getOrPut(key) {
            buildWaterModel(vertsA, vertsB, radius, STREAM_TRANSLUCENT_MATERIAL)
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
        material: Material
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
            // boundary factor: 0 at the tube-bottom midline (270°), 1 at the
            // left/right wall edges (210°/330°) — vertices nearer the wall jitter
            // harder. Baked into color.a (water vertices' color is otherwise 0).
            // Use -|u| instead of u so mirrored ring vertices (backward-flow
            // segments) bake the SAME factor as their unmirrored seam partners.
            val angleDeg = Math.toDegrees(
                kotlin.math.atan2(v.toDouble(), -kotlin.math.abs(u.toDouble()))
            ).toFloat()
            val boundary = (Math.abs(angleDeg + 90f) / 60f).coerceIn(0f, 1f)
            ringVerts += V(
                u, v, z,
                0f, 0f, 0f, boundary,
                uTex, if (z > 0.25f) 1f else 0f,
                (Math.round(su0 * 32767f) shl 16) or Math.round(su1 * 32767f),
                (Math.round(sv0 * 65535f) shl 16) or Math.round(sv1 * 65535f),
                // Up-facing normals (model-space +y): the shaderpack's water
                // shading assumes a level surface, so a radial normal makes
                // the fresnel reflection read as grazing from every angle and
                // the water looks like a mirror. Up normals make reflection
                // fall off with view angle like real water.
                0f, 1f, 0f
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

    // band ring vertices on the same angular grid as the tube wall:
    // bottom arc at rInFrac, top arc back at rSurfFrac, clipped to 210-330 degrees
    @JvmStatic
    fun bandVertices(rInFrac: Float, rSurfFrac: Float, mirror: Boolean): List<Float> {
        val crossN = crossSections()
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
        val model = Minecraft.getInstance().blockRenderer.getBlockModel(block.defaultBlockState())
        return model.getParticleIcon(ModelData.EMPTY)
    }
}
