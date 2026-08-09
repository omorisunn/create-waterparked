package net.omori_sunny.create_waterparked.client.flywheel

import com.simibubi.create.content.trains.track.BezierConnection
import dev.engine_room.flywheel.api.material.Material
import dev.engine_room.flywheel.api.material.Transparency
import dev.engine_room.flywheel.api.material.WriteMask
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.api.model.Mesh
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.material.SimpleMaterial
import dev.engine_room.flywheel.lib.memory.MemoryBlock
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh
import dev.engine_room.flywheel.lib.model.SingleMeshModel
import dev.engine_room.flywheel.lib.vertex.FullVertexView
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import dev.silvergold.simulatedcoasters.track.CoasterOpenEndExtension
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.minecraft.client.Minecraft
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
import kotlin.math.sin

// shared unit-circle mesh
object WaterslideTubeMesh {

    private const val WALL_THICKNESS = 0.1f
    private const val TILE_SUBDIVISION_PX = 8f
    private const val PIXELS_PER_BLOCK = 16f

    private val modelCache = HashMap<String, TubeModels>()

    private val TUBE_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
            .backfaceCulling(true)
            .writeMask(WriteMask.COLOR)
            .build()

// skeleton rings
    private val RING_TRANSLUCENT_MATERIAL: Material =
        SimpleMaterial.builder()
            .transparency(Transparency.TRANSLUCENT)
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
    fun modelsFor(level: Level, config: WaterslideSectorConfig, maxRadius: Float): TubeModels {
        val key = signature(config, maxRadius)
        return modelCache.getOrPut(key) { build(level, config, maxRadius) }
    }

// frames (with extensions)
    @JvmStatic
    fun sampleSegments(
        level: Level,
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

            var lat = CoasterBezierRailFrames.lateralAt(bc, ts[i], tangent, level)
            var up = tangent.cross(lat)
            val valid = lat.lengthSqr() > 1.0E-12 &&
                up.lengthSqr() > 1.0E-12 &&
                !lat.x.isNaN() && !lat.y.isNaN() && !lat.z.isNaN() &&
                !up.x.isNaN() && !up.y.isNaN() && !up.z.isNaN()
            if (!valid) {
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
            frames += TubeSegmentFrame(
                centers[0].subtract(tan.scale(ext0.toDouble())).subtract(origin),
                centers[0].subtract(origin),
                tan, tan, lats[0]!!, lats[0]!!, r0, r0
            )
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
            frames += TubeSegmentFrame(
                centers[count].subtract(origin),
                centers[count].add(tan.scale(ext1.toDouble())).subtract(origin),
                tan, tan, lats[count]!!, lats[count]!!, r1, r1
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

    private fun signature(config: WaterslideSectorConfig, maxRadius: Float): String =
        buildString {
            append(maxRadius)
            for (s in config.sectors) {
                append('|').append(s.id)
                    .append(',').append(s.material)
                    .append(',').append(s.blockId)
                    .append(',').append(s.type)
                    .append(',').append(s.widthDegrees)
            }
        }

    private fun build(level: Level, config: WaterslideSectorConfig, maxRadius: Float): TubeModels {
        val placed = WaterslideSectorLayout.place(config.sectors)
        val crossN = max(
            8,
            ceil((2.0 * Math.PI * maxRadius * PIXELS_PER_BLOCK / TILE_SUBDIVISION_PX)).toInt()
        )

        val wallVerts = ArrayList<V>()
        val startCapVerts = ArrayList<V>()
        val endCapVerts = ArrayList<V>()

        fun add(
            dst: MutableList<V>,
            x: Float, y: Float, z: Float,
            nx: Float, ny: Float, nz: Float,
            u: Float, v: Float,
            sectorRadians: Float, texW: Float, texH: Float, border: Float,
            spriteU0: Float, spriteU1: Float, spriteV0: Float, spriteV1: Float
        ) {
            dst += V(
                x, y, z,
                sectorRadians / (2.0 * Math.PI).toFloat(),
                texW / 64f, texH / 64f, border / 16f,
                u, v,
                (Math.round(spriteU0 * 32767f) shl 16) or Math.round(spriteU1 * 32767f),
                (Math.round(spriteV0 * 65535f) shl 16) or Math.round(spriteV1 * 65535f),
                nx, ny, nz
            )
        }

        for (p in placed) {
            if (p.sector.material == SectorMaterial.OPEN) continue
            val blockId = p.sector.blockId ?: continue
            val sprite = spriteFor(blockId) ?: continue
            val texW = sprite.contents().width().toFloat()
            val texH = sprite.contents().height().toFloat()
            val border = ModConfig.sectorBorderPx().toFloat()
            val sectorDegrees = p.endAngle - p.startAngle
            val sectorRadians = Math.toRadians(sectorDegrees.toDouble()).toFloat()
            val steps = max(1, ceil(sectorDegrees / 360f * crossN).toInt())
            val su0 = sprite.u0
            val su1 = sprite.u1
            val sv0 = sprite.v0
            val sv1 = sprite.v1

            for (j in 0 until steps) {
                val f0 = j.toFloat() / steps
                val f1 = (j + 1).toFloat() / steps
                val a0 = Math.toRadians((p.startAngle + sectorDegrees * f0).toDouble())
                val a1 = Math.toRadians((p.startAngle + sectorDegrees * f1).toDouble())
                val c0 = cos(a0).toFloat()
                val s0 = sin(a0).toFloat()
                val c1 = cos(a1).toFloat()
                val s1 = sin(a1).toFloat()

                // Outer wall
                add(wallVerts, c0, s0, 0f, c0, s0, 0f, f0, 0f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                add(wallVerts, c1, s1, 0f, c1, s1, 0f, f1, 0f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                add(wallVerts, c1, s1, 0.5f, c1, s1, 0f, f1, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                add(wallVerts, c0, s0, 0.5f, c0, s0, 0f, f0, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)

                // Inner wall
                add(wallVerts, c0, s0, 0f, -c0, -s0, 0f, f0, 0f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                add(wallVerts, c0, s0, 0.5f, -c0, -s0, 0f, f0, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                add(wallVerts, c1, s1, 0.5f, -c1, -s1, 0f, f1, 1f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)
                add(wallVerts, c1, s1, 0f, -c1, -s1, 0f, f1, 0f, sectorRadians, texW, texH, border, su0, su1, sv0, sv1)

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

        val wallMesh = meshOf(wallVerts, "waterslide_tube_wall")
        val startCapMesh = meshOf(startCapVerts, "waterslide_tube_start_cap")
        val endCapMesh = meshOf(endCapVerts, "waterslide_tube_end_cap")
        return TubeModels(
            SingleMeshModel(wallMesh, Materials.CUTOUT_MIPPED_BLOCK),
            SingleMeshModel(startCapMesh, Materials.CUTOUT_MIPPED_BLOCK),
            SingleMeshModel(endCapMesh, Materials.CUTOUT_MIPPED_BLOCK),
            SingleMeshModel(wallMesh, TUBE_TRANSLUCENT_MATERIAL),
            SingleMeshModel(startCapMesh, TUBE_TRANSLUCENT_MATERIAL),
            SingleMeshModel(endCapMesh, TUBE_TRANSLUCENT_MATERIAL),
            SingleMeshModel(endCapMesh, RING_TRANSLUCENT_MATERIAL)
        )
    }

    private fun meshOf(verts: List<V>, descriptor: String): Mesh {
        if (verts.isEmpty()) {
// degenerate vertex
            val empty = FullVertexView()
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
