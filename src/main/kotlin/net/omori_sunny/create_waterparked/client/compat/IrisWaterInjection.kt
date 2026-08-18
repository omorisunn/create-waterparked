package net.omori_sunny.create_waterparked.client.compat

import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttribute
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import org.lwjgl.opengl.GL33

// Builds the mounted tube water (in-tube bands) into Sodium's compact chunk
// vertex format and draws it during Sodium's water (translucent terrain) pass,
// so the active shaderpack's own water program shades it. Geometry is gathered
// from the client anchor block entities and rebuilt a few times per second.
object IrisWaterInjection {

    private val vertexType: ChunkVertexType = ChunkMeshFormats.COMPACT

    private var vbo = 0
    private var lastVertices = 0
    private var lastBucket = -1L
    private var lastOrigin = Vec3.ZERO
    private var built = false

    @JvmStatic
    fun invalidate() {
        if (vbo != 0) {
            GL33.glDeleteBuffers(vbo)
            vbo = 0
        }
        built = false
    }

    // Called from the water-pass mixin while Sodium's water program + uniforms
    // are live; our matrix state was pushed via ChunkShaderInterface first.
    @JvmStatic
    fun renderWaterGeometry() {
        if (!IrisColorwheelCompat.waterShadingActive()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val bucket = level.gameTime / 20
        if (bucket != lastBucket || !built) {
            lastBucket = bucket
            build()
        }
        if (!built || vbo == 0 || lastVertices == 0) return

        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vbo)
        val format = vertexType.vertexFormat
        val stride = format.stride
        for (b in format.shaderBindings) {
            val attr = reflectiveAttribute(b)
            val index = b.getIndex()
            val fmt = attr.getFormat()
            val count = attr.getCount()
            val pointerOffset = attr.getPointer()
            GL33.glEnableVertexAttribArray(index)
            if (attr.isIntType) {
                vertexAttribIPointer(index, count, fmt, pointerOffset)
            } else {
                vertexAttribPointer(index, count, fmt, attr.isNormalized, stride, pointerOffset)
            }
        }
        GL33.glDrawArrays(GL33.GL_QUADS, 0, lastVertices)
        for (b in format.shaderBindings) GL33.glDisableVertexAttribArray(b.getIndex())
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, 0)
    }

    // Reflection into org.lwjgl.opengl.GL30: Kotlin's overload resolution is
    // hostile to the int vs long pointer variants; reflect to bind attributes
    // with the exact (index,size,type[,normalized,stride],offset) signatures.
    private val vap = try {
        Class.forName("org.lwjgl.opengl.GL30")
            .getMethod("glVertexAttribPointer", Integer.TYPE, Integer.TYPE, Integer.TYPE,
                java.lang.Boolean.TYPE, Integer.TYPE, java.lang.Long.TYPE)
    } catch (t: Throwable) {
        null
    }
    private val vapI = try {
        Class.forName("org.lwjgl.opengl.GL30")
            .getMethod("glVertexAttribIPointer", Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE)
    } catch (t: Throwable) {
        null
    }

    private fun vertexAttribPointer(index: Int, count: Int, fmt: Int, norm: Boolean, stride: Int, offset: Int) {
        val m = vap ?: return
        m.invoke(null, index, count, fmt, norm, stride, offset.toLong())
    }

    private fun vertexAttribIPointer(index: Int, count: Int, fmt: Int, offset: Int) {
        val m = vapI ?: return
        m.invoke(null, index, count, fmt, offset)
    }

    @JvmStatic
    fun currentRegionOffset(): Vec3 = lastOrigin

    // GlVertexAttributeBinding keeps its attribute privately; fetch it by
    // walking the class hierarchy (stable across the vendored sodium).
    private fun reflectiveAttribute(b: Any): GlVertexAttribute {
        var c: Class<*>? = b.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField("attribute")
                f.isAccessible = true
                return f.get(b) as GlVertexAttribute
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw IllegalStateException("No attribute field on GlVertexAttributeBinding")
    }

    private fun build() {
        val level = Minecraft.getInstance().level ?: return
        val quads = collectWaterBands(level)
        if (quads.isEmpty()) {
            invalidate()
            return
        }
        // fixed region origin keeps encoded coords in compact range
        val anchor = Minecraft.getInstance().player?.position() ?: Vec3.ZERO
        val origin = Vec3(
            Math.floor(anchor.x / 128.0) * 128.0,
            Math.floor(anchor.y / 128.0) * 128.0,
            Math.floor(anchor.z / 128.0) * 128.0
        )
        lastOrigin = origin

        val builder = ChunkMeshBufferBuilder(vertexType, 1 shl 20)
        builder.start(0)
        val flat = ArrayList<ChunkVertexEncoder.Vertex>(quads.size * 4)
        for (q in quads) {
            val quad = quad(q, origin)
            flat += quad
        }
        builder.push(flat.toTypedArray(), flat.size)
        val count = builder.count()
        if (count <= 0) {
            invalidate()
            builder.destroy()
            return
        }
        val slice = builder.slice()
        if (vbo == 0) vbo = GL33.glGenBuffers()
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vbo)
        GL33.glBufferData(GL33.GL_ARRAY_BUFFER, slice, GL33.GL_STATIC_DRAW)
        GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, 0)
        lastVertices = count
        built = true
        builder.destroy()
    }

    private class BandQuad(val a: Vec3, val b: Vec3, val d: Vec3, val e: Vec3)

    private fun ring(spine: Vec3, lateral: Vec3, tangent: Vec3, angle: Float, radius: Float): Vec3 {
        val c = kotlin.math.cos(angle.toDouble())
        val s = kotlin.math.sin(angle.toDouble())
        val up = tangent.cross(lateral).normalize()
        return spine.add(lateral.scale(radius * c)).add(up.scale(radius * s))
    }

    private fun collectWaterBands(level: net.minecraft.client.multiplayer.ClientLevel): List<BandQuad> {
        val out = ArrayList<BandQuad>()
        val anchors = net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer.clientAnchors()
        for (be in anchors) {
            if (be !is WaterslideAnchorBlockEntity) continue
            for (e in be.anchorPeerCurvesView) {
                val raw = e.value ?: continue
                if (!raw.isPrimary) continue
                if (!WaterslideTrackMaterials.isWaterslide(raw)) continue
                val peer = raw.bePositions.second
                if (!be.isCurveWatered(peer)) continue
                val r = be.radius
                val frames = try {
                    WaterslideTubeMesh.sampleSegments(level, raw, r, r, Vec3.atLowerCornerOf(be.blockPos))
                } catch (t: Throwable) {
                    continue
                }
                if (frames.size < 2) continue
                val n = 8
                for (i in 0 until frames.size - 1) {
                    val f0 = frames[i]
                    val f1 = frames[i + 1]
                    for (j in 0 until n) {
                        val a0 = (j * 360f / n) * Math.PI.toFloat() / 180f
                        val a1 = ((j + 1) * 360f / n) * Math.PI.toFloat() / 180f
                        val p0 = ring(f0.prevSpine, f0.prevLateral, f0.prevTangent, a0, f0.prevRadius * 0.35f)
                        val p1 = ring(f0.prevSpine, f0.prevLateral, f0.prevTangent, a1, f0.prevRadius * 0.35f)
                        val p2 = ring(f1.currSpine, f1.currLateral, f1.currTangent, a1, f1.currRadius * 0.6f)
                        val p3 = ring(f1.currSpine, f1.currLateral, f1.currTangent, a0, f1.currRadius * 0.6f)
                        out += BandQuad(p0, p1, p2, p3)
                    }
                }
            }
        }
        return out
    }

    // one quad (4 verts) in compact encoding relative to `origin`
    private fun quad(q: BandQuad, origin: Vec3): Array<ChunkVertexEncoder.Vertex> {
        val v = ChunkVertexEncoder.Vertex.uninitializedQuad()
        fill(v[0], q.a.subtract(origin), 0f, 0f)
        fill(v[1], q.b.subtract(origin), 1f, 0f)
        fill(v[2], q.d.subtract(origin), 1f, 1f)
        fill(v[3], q.e.subtract(origin), 0f, 1f)
        return v
    }

    private fun fill(vt: ChunkVertexEncoder.Vertex, p: Vec3, u: Float, vt2: Float) {
        vt.x = p.x.toFloat()
        vt.y = p.y.toFloat()
        vt.z = p.z.toFloat()
        vt.color = -1 // white
        vt.ao = 1f
        vt.u = u
        vt.v = vt2
        vt.light = 0x00F000F0
    }
}
