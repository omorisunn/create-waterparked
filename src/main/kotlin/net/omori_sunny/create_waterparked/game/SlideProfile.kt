package net.omori_sunny.create_waterparked.game

import net.minecraft.nbt.ByteArrayTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.util.Mth
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// closed bezier cross-section for one slide curve, consumed as a per-angle radius multiplier
class SlideProfile private constructor(
    val anchors: FloatArray,
    val handles: FloatArray,
    val transition: Float,
    val inHandles: FloatArray?,
    val modes: ByteArray?
) {

    val radii: FloatArray
    val arcFractions: FloatArray
    val bottomY: Float
    val minMultiplier: Float
    val maxMultiplier: Float

    init {
        val polygon = samplePolygon()
        radii = FloatArray(SAMPLES)
        arcFractions = FloatArray(SAMPLES)
        var bottom = Float.MAX_VALUE
        val binStep = Math.toRadians(360.0 / SAMPLES).toFloat()
        for (j in 0 until SAMPLES) {
            val angle = (j + 0.5f) * binStep
            radii[j] = rayDistance(polygon, cos(angle), sin(angle))
            val next = (j + 1.5f) * binStep
            val rNext = rayDistance(polygon, cos(next), sin(next))
            arcFractions[j] = Mth.sqrt(
                radii[j] * radii[j] + rNext * rNext - 2f * radii[j] * rNext * cos(binStep)
            )
        }
        for (p in polygon) bottom = min(bottom, p[1])
        bottomY = bottom
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (r in radii) {
            lo = min(lo, r)
            hi = max(hi, r)
        }
        minMultiplier = lo
        maxMultiplier = hi
    }

    fun samplePolygon(stepsPerSegment: Int = 10): List<FloatArray> {
        val count = anchors.size / 2
        val out = ArrayList<FloatArray>(count * stepsPerSegment + 1)
        for (i in 0 until count) {
            val j = (i + 1) % count
            val ax = anchors[i * 2]; val ay = anchors[i * 2 + 1]
            val bx = anchors[j * 2]; val by = anchors[j * 2 + 1]
            val iCurved = modeAt(i) != MODE_NONE
            val jCurved = modeAt(j) != MODE_NONE
            val inX: Float; val inY: Float
            if (inHandles != null && j * 2 + 1 < inHandles.size) {
                inX = inHandles[j * 2]; inY = inHandles[j * 2 + 1]
            } else {
                inX = -handles[j * 2]; inY = -handles[j * 2 + 1]
            }
            val c1x = if (iCurved) ax + handles[i * 2] else ax
            val c1y = if (iCurved) ay + handles[i * 2 + 1] else ay
            val c2x = if (jCurved) bx + inX else bx
            val c2y = if (jCurved) by + inY else by
            for (s in 0 until stepsPerSegment) {
                val t = s.toFloat() / stepsPerSegment
                val u = 1f - t
                out += floatArrayOf(
                    u * u * u * ax + 3f * u * u * t * c1x + 3f * u * t * t * c2x + t * t * t * bx,
                    u * u * u * ay + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * by
                )
            }
        }
        out += out[0].copyOf()
        return out
    }

    private fun modeAt(i: Int): Byte =
        if (modes != null && i < modes.size) modes[i] else MODE_SYMMETRIC

    private fun rayDistance(polygon: List<FloatArray>, dx: Float, dy: Float): Float {
        var best = -1f
        var prev = polygon[polygon.size - 1]
        for (cur in polygon) {
            val ex = cur[0] - prev[0]; val ey = cur[1] - prev[1]
            val denom = dx * ey - dy * ex
            if (abs(denom) > 1.0E-6f) {
                val s = (prev[0] * dy - prev[1] * dx) / denom
                val t = (prev[0] * ey - prev[1] * ex) / denom
                if (s >= 0f && s <= 1f && t > 0f) best = max(best, t)
            }
            prev = cur
        }
        return best.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    fun write(tag: CompoundTag) {
        tag.put("Anchors", floatList(anchors))
        tag.put("Handles", floatList(handles))
        tag.putFloat("Transition", transition)
        inHandles?.let { tag.put("InHandles", floatList(it)) }
        modes?.let { tag.put("Modes", ByteArrayTag(it)) }
    }

    fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(anchors.size)
        for (v in anchors) buf.writeFloat(v)
        for (v in handles) buf.writeFloat(v)
        buf.writeFloat(transition)
        buf.writeBoolean(inHandles != null)
        inHandles?.let { for (v in it) buf.writeFloat(v) }
        buf.writeBoolean(modes != null)
        modes?.let { buf.writeByteArray(it) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SlideProfile) return false
        return transition == other.transition &&
            anchors.contentEquals(other.anchors) && handles.contentEquals(other.handles) &&
            inHandles.contentEquals(other.inHandles) && modes.contentEquals(other.modes)
    }

    override fun hashCode(): Int {
        var h = transition.toRawBits()
        h = 31 * h + anchors.contentHashCode()
        h = 31 * h + handles.contentHashCode()
        h = 31 * h + inHandles.contentHashCode()
        h = 31 * h + modes.contentHashCode()
        return h
    }

    fun signature(): String {
        val sb = StringBuilder(16 + anchors.size * 2)
        sb.append(transition.toRawBits()).append('|')
        for (v in anchors) sb.append(v.toRawBits()).append(',')
        sb.append('|')
        for (v in handles) sb.append(v.toRawBits()).append(',')
        sb.append('|')
        for (v in inHandles ?: return sb.toString()) sb.append(v.toRawBits()).append(',')
        sb.append('|')
        for (v in modes ?: return sb.toString()) sb.append(v.toInt()).append(',')
        return sb.toString()
    }

    companion object {
        const val SAMPLES = 64
        const val MIN_MULTIPLIER = 0.3f
        const val MAX_MULTIPLIER = 3.0f
        const val DEFAULT_TRANSITION = 1.5f

        const val MODE_NONE: Byte = 0
        const val MODE_SYMMETRIC: Byte = 1
        const val MODE_FREE: Byte = 2

        fun of(anchors: FloatArray, handles: FloatArray, transition: Float): SlideProfile? =
            of(anchors, handles, null, null, transition)

        fun of(
            anchors: FloatArray,
            handles: FloatArray,
            inHandles: FloatArray?,
            modes: ByteArray?,
            transition: Float
        ): SlideProfile? {
            if (anchors.size < 6 || handles.size != anchors.size) return null
            val count = anchors.size / 2
            val cleanIn = if (inHandles != null && inHandles.size == anchors.size) inHandles.copyOf() else null
            val cleanModes = if (modes != null && modes.size == count) {
                ByteArray(count) { i -> if (modes[i] in MODE_NONE..MODE_FREE) modes[i] else MODE_SYMMETRIC }
            } else null
            val clamped = transition.coerceIn(0f, 16f)
            return SlideProfile(anchors.copyOf(), handles.copyOf(), clamped, cleanIn, cleanModes)
        }

        fun read(tag: CompoundTag): SlideProfile? {
            if (!tag.contains("Anchors", 9) || !tag.contains("Handles", 9)) return null
            val anchors = readFloatList(tag.getList("Anchors", 5)) ?: return null
            val handles = readFloatList(tag.getList("Handles", 5)) ?: return null
            val inHandles = if (tag.contains("InHandles", 9)) readFloatList(tag.getList("InHandles", 5)) else null
            val modes = if (tag.contains("Modes", 7)) tag.getByteArray("Modes") else null
            val transition = if (tag.contains("Transition", 5)) tag.getFloat("Transition") else DEFAULT_TRANSITION
            return of(anchors, handles, inHandles, modes, transition)
        }

        fun read(buf: FriendlyByteBuf): SlideProfile? {
            val size = buf.readVarInt()
            if (size < 6 || size > 1024 || size % 2 != 0) return null
            val anchors = FloatArray(size)
            for (i in anchors.indices) anchors[i] = buf.readFloat()
            val handles = FloatArray(size)
            for (i in handles.indices) handles[i] = buf.readFloat()
            val transition = buf.readFloat()
            val inHandles = if (buf.readBoolean()) FloatArray(size) { buf.readFloat() } else null
            val modes = if (buf.readBoolean()) buf.readByteArray() else null
            return of(anchors, handles, inHandles, modes, transition)
        }

        private fun floatList(values: FloatArray): ListTag {
            val list = ListTag()
            for (v in values) list.add(FloatTag.valueOf(v))
            return list
        }

        private fun readFloatList(list: ListTag): FloatArray? {
            val out = FloatArray(list.size)
            for (i in 0 until list.size) {
                val tag = list[i] as? FloatTag ?: return null
                out[i] = tag.asFloat
            }
            return out
        }

        fun blend(a: FloatArray?, b: FloatArray?, f: Float): FloatArray? {
            if (a == null && b == null) return null
            if (a == null) return b!!.copyOf()
            if (b == null) return a.copyOf()
            val out = FloatArray(SAMPLES)
            for (j in 0 until SAMPLES) out[j] = Mth.lerp(f, a[j], b[j])
            return out
        }

        fun blendShared(a: FloatArray?, b: FloatArray?, f: Float): FloatArray? = when {
            a == null && b == null -> null
            a == null -> b
            b == null -> a
            f <= 0f -> a
            f >= 1f -> b
            else -> blend(a, b, f)
        }

        fun multiplierAt(radii: FloatArray, angleDeg: Float): Float {
            val a = ((angleDeg % 360f) + 360f) % 360f
            val x = a * SAMPLES / 360f
            val j = x.toInt().mod(SAMPLES)
            val k = (j + 1).mod(SAMPLES)
            return Mth.lerp(x - x.toInt(), radii[j], radii[k])
        }

        fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

        fun circleFallback(): SlideProfile = of(
            floatArrayOf(1f, 0f, 0f, 1f, -1f, 0f, 0f, -1f),
            FloatArray(8),
            DEFAULT_TRANSITION
        )!!

        fun contentSignature(radii: FloatArray): String {
            val sb = StringBuilder(radii.size * 2)
            for (r in radii) sb.append((r * 512f).toInt()).append(',')
            return sb.toString()
        }
    }
}
