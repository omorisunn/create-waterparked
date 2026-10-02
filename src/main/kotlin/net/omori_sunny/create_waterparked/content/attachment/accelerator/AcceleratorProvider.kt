package net.omori_sunny.create_waterparked.content.attachment.accelerator

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentKinetics
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelContext
import net.omori_sunny.create_waterparked.content.attachment.SlideBandProvider

class AcceleratorProvider : SlideBandProvider() {

    companion object {
        private const val ARROW_INSET = 0.03
        private const val SCROLL_PER_SECOND = 1.5
        private const val SCROLL_REFERENCE_RPM = 16.0
        private const val SCROLL_LIMIT = 3.0
        private const val ARROW_CELL = 0.4
        private const val TILE = 1.0
        private const val SEAM_EPS = 1.0E-3

        private val ARROW_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
            "create_waterparked", "textures/ui/accelerator_arrow.png"
        )
    }

    override fun backDistance(data: CompoundTag): Double = AcceleratorAttachment.distL(data).toDouble()

    override fun frontDistance(data: CompoundTag): Double = AcceleratorAttachment.distR(data).toDouble()

    override fun signatureExtra(ctx: SlideAttachmentModelContext): String =
        AcceleratorAttachment.directionDegrees(ctx.data).toString() + ":" + scrollSpeed(ctx)

    private fun scrollSpeed(ctx: SlideAttachmentModelContext): Float =
        ctx.data.getFloat(AcceleratorAttachment.TAG_RATE)

    override fun extraParts(
        ctx: SlideAttachmentModelContext,
        stations: List<Station>,
        spans: List<Span>
    ): List<Part> {
        if (stations.size < 2 || spans.isEmpty()) return emptyList()
        val phi = AcceleratorAttachment.directionRadians(ctx.data)
        val cosPhi = Math.cos(phi)
        val sinPhi = Math.sin(phi)
        val arc = DoubleArray(stations.size)
        for (i in 1 until stations.size) arc[i] = arc[i - 1] + distance(stations[i - 1], stations[i])
        val origins = spanOrigins(spans)
        val quads = ArrayList<Quad>()
        for ((index, span) in spans.withIndex()) {
            val a0 = Math.toRadians(span.a0)
            val a1 = Math.toRadians(span.a1)
            val origin = Math.toRadians(origins[index])
            val reach = 0.0
            val cells = 1
            for (c in 0 until cells) {
                val b0 = a0 + (a1 - a0) * c / cells
                val b1 = a0 + (a1 - a0) * (c + 1) / cells
                var dx = cosPhi
                var dy = -Math.sin((b0 + b1) / 2.0) * sinPhi
                val reachDir = Math.sqrt(dx * dx + dy * dy)
                if (reachDir < 0.05) {
                    dx = 1.0
                    dy = 0.0
                } else {
                    dx /= reachDir
                    dy /= reachDir
                }
                for (i in 0 until stations.size - 1) {
                    val s0 = stations[i]
                    val s1 = stations[i + 1]
                    val r0a = shapedR(s0, b0, FRAME + ARROW_INSET)
                    val r0b = shapedR(s0, b1, FRAME + ARROW_INSET)
                    val r1a = shapedR(s1, b0, FRAME + ARROW_INSET)
                    val r1b = shapedR(s1, b1, FRAME + ARROW_INSET)
                    val sa = arc[i]
                    val sb = arc[i + 1]
                    val qa0 = r0a * (b0 - origin)
                    val qa1 = r0b * (b1 - origin)
                    val qb0 = r1a * (b0 - origin)
                    val qb1 = r1b * (b1 - origin)
                    quads.add(
                        quad(
                            ring(s0, b0, r0a), ring(s1, b0, r1a), ring(s1, b1, r1b), ring(s0, b1, r0b),
                            uvU(sa, qa0, dx, dy), uvV(sa, qa0, dx, dy),
                            uvU(sb, qb0, dx, dy), uvV(sb, qb0, dx, dy),
                            uvU(sb, qb1, dx, dy), uvV(sb, qb1, dx, dy),
                            uvU(sa, qa1, dx, dy), uvV(sa, qa1, dx, dy)
                        )
                    )
                }
            }
        }
        return listOf(TiledPart(quads, ARROW_TEXTURE, -scrollSpeed(ctx)))
    }

    private fun uvU(along: Double, around: Double, dx: Double, dy: Double): Float =
        ((along * dx + around * dy) / TILE).toFloat()

    private fun uvV(along: Double, around: Double, dx: Double, dy: Double): Float =
        ((-along * dy + around * dx) / TILE).toFloat()

    private fun spanOrigins(spans: List<Span>): DoubleArray {
        val n = spans.size
        val out = DoubleArray(n)
        var start = 0
        for (i in 0 until n) {
            val prevEnd = if (i == 0) spans[n - 1].a1 - 360.0 else spans[i - 1].a1
            if (spans[i].a0 - prevEnd > SEAM_EPS) {
                start = i
                break
            }
        }
        var origin = spans[start].a0
        var prevEnd = spans[start].a0
        for (step in 0 until n) {
            val index = (start + step) % n
            if (spans[index].a0 - prevEnd > SEAM_EPS) origin = spans[index].a0
            out[index] = origin
            prevEnd = spans[index].a1
        }
        return out
    }
}
