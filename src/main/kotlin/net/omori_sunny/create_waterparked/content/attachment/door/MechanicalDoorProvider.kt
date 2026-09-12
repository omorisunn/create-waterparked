package net.omori_sunny.create_waterparked.content.attachment.door

import net.minecraft.world.phys.AABB
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelContext
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelProvider

// local space: x = lateral, y = up, z = tangent
class MechanicalDoorProvider : SlideAttachmentModelProvider() {

    companion object {
        private val shownOpen = HashMap<net.minecraft.core.BlockPos, Float>()

        // quantized for cheap cache signatures
        @JvmStatic
        fun smoothedOpen(pos: net.minecraft.core.BlockPos, target: Float): Float {
            var shown = shownOpen.getOrDefault(pos, target)
            shown += (target - shown) * 0.25f
            shownOpen[pos] = shown
            return (kotlin.math.floor(shown * 200f) / 200f).coerceIn(0f, 1f)
        }

        @JvmStatic
        fun clearAnim(pos: net.minecraft.core.BlockPos) {
            shownOpen.remove(pos)
        }

        private const val FRAME = 0.11
        private const val PANEL_THICK = 0.14
        private const val DOOR_Z = 0.08
        private const val PANEL_COLOR = 0xC0AA7A
        private const val PANEL_INSET = 0.002
        private const val PANEL_STRIP_V = 0.15f
    }

    private fun openFraction(ctx: SlideAttachmentModelContext): Double =
        smoothedOpen(ctx.sabPos, ctx.data.getFloat(MechanicalDoorAttachment.TAG_OPEN)).toDouble()

    override fun boundingBox(ctx: SlideAttachmentModelContext): AABB {
        val outer = ctx.radius + ctx.wallThickness - 0.1
        val (ox, oy) = centreOffset(ctx, outer)
        val r = ctx.radius - 0.1
        return AABB(ox - r - FRAME, oy - r - FRAME, -0.25, ox + r + FRAME, oy + r + FRAME, 0.25)
    }

    private fun centreOffset(ctx: SlideAttachmentModelContext, r: Double): Pair<Double, Double> {
        val cosA = ctx.radialOut.dot(ctx.lateral)
        val sinA = ctx.radialOut.dot(ctx.up)
        return -cosA * r to -sinA * r
    }

    private fun ring(cx: Double, cy: Double, radius: Double, sides: Int): List<Pair<Double, Double>> =
        (0 until sides).map { k ->
            val a = Math.toRadians(90.0 + 360.0 * k / sides)
            (cx + radius * kotlin.math.cos(a)) to (cy + radius * kotlin.math.sin(a))
        }

    override fun parts(ctx: SlideAttachmentModelContext): List<Part> {
        val sides = net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh.crossSections()
        val innerR = (ctx.radius - 0.1).toDouble()
        val (ox, oy) = centreOffset(ctx, ctx.radius + ctx.wallThickness - 0.1)
        val outerRing = ring(ox, oy, innerR, sides)
        val innerRing = ring(ox, oy, innerR - FRAME, sides)

        val out = ArrayList<Part>()
        val quads = ArrayList<Quad>()

        val zF = DOOR_Z
        val zB = -DOOR_Z
        for (k in 0 until sides) {
            val j = (k + 1) % sides
            val (ox0, oy0) = outerRing[k]
            val (ox1, oy1) = outerRing[j]
            val (ix0, iy0) = innerRing[k]
            val (ix1, iy1) = innerRing[j]
            val uf = kotlin.math.sqrt((ox1 - ox0).pow2() + (oy1 - oy0).pow2()).toFloat().coerceIn(0f, 1f)
            val vf = (FRAME / innerR).toFloat().coerceIn(0f, 1f)
            quads.add(quad(ox0, oy0, zF, ox1, oy1, zF, ix1, iy1, zF, ix0, iy0, zF, 0f, 0f, uf, 0f, uf, vf, 0f, vf, 0.0, 0.0, 1.0))
            quads.add(quad(ix0, iy0, zB, ix1, iy1, zB, ox1, oy1, zB, ox0, oy0, zB, 0f, 0f, uf, 0f, uf, vf, 0f, vf, 0.0, 0.0, -1.0))
            quads.add(quad(ix1, iy1, zB, ix0, iy0, zB, ix0, iy0, zF, ix1, iy1, zF, uf, 0f, 0f, 0f, 0f, 0.2f, uf, 0.2f, ix1 - ix0, iy1 - iy0, 0.0))
            quads.add(quad(ix0, iy0, zB, ox0, oy0, zB, ox0, oy0, zF, ix0, iy0, zF, 0.2f, 0f, 0f, 0f, 0f, 0.2f, 0.2f, 0.2f, -(oy1 - oy0), ox1 - ox0, 0.0))
            val wx0 = ox + (ox0 - ox) * (1.0 - 0.005 / innerR)
            val wy0 = oy + (oy0 - oy) * (1.0 - 0.005 / innerR)
            val wx1 = ox + (ox1 - ox) * (1.0 - 0.005 / innerR)
            val wy1 = oy + (oy1 - oy) * (1.0 - 0.005 / innerR)
            quads.add(quad(wx0, wy0, zB, wx1, wy1, zB, wx1, wy1, zF, wx0, wy0, zF, 0f, 0f, uf, 0f, uf, 0.2f, 0f, 0.2f, oy1 - oy0, -(ox1 - ox0), 0.0))
        }
        out.add(PolygonPart(quads))

        val open = openFraction(ctx)
        val mode = MechanicalDoorMode.entries.getOrElse(
            ctx.data.getInt(MechanicalDoorAttachment.TAG_MODE)
        ) { MechanicalDoorMode.SLIDE }
        out.addAll(
            when (mode) {
                MechanicalDoorMode.LIFT -> liftParts(ox, oy, innerR, outerRing, open)
                MechanicalDoorMode.APERTURE ->
                    apertureParts(ox, oy, innerR, innerRing, open, ctx.wallThickness)
                MechanicalDoorMode.SLIDE -> slideParts(ox, oy, innerR, outerRing, open)
            }
        )
        return out
    }

    private fun liftParts(
        ox: Double,
        oy: Double,
        innerR: Double,
        outerRing: List<Pair<Double, Double>>,
        open: Double
    ): List<Part> {
        val slide = open * innerR
        val out = ArrayList<Part>()
        val panelW = 2 * (innerR - FRAME) - PANEL_INSET
        val panelH = innerR - PANEL_INSET
        for (dir in intArrayOf(-1, 1)) {
            val cy = oy + dir * (innerR / 2 + slide)
            out.add(BoxPart(ox, cy, 0.0, panelW, panelH, PANEL_THICK, color = PANEL_COLOR))
            val rect = listOf(
                ox - (innerR - FRAME) to cy - innerR / 2,
                ox + (innerR - FRAME) to cy - innerR / 2,
                ox + (innerR - FRAME) to cy + innerR / 2,
                ox - (innerR - FRAME) to cy + innerR / 2
            )
            val poly = clipToPolygon(rect, outerRing)
            val strips = panelStrips(poly, -PANEL_THICK / 2, PANEL_THICK / 2, PANEL_STRIP_V)
            if (strips.isNotEmpty()) out.add(PolygonPart(strips))
        }
        return out
    }

    // closed blades must cover the whole disc, hence the 1.1 width factor
    private fun apertureParts(
        ox: Double,
        oy: Double,
        innerR: Double,
        clipRing: List<Pair<Double, Double>>,
        open: Double,
        wallThickness: Float
    ): List<Part> {
        val out = ArrayList<Part>()
        val blades = 6
        val theta = open * Math.PI
        val pivotR = kotlin.math.min(innerR * 1.1, innerR + wallThickness * 0.6)
        val bladeLen = pivotR + innerR * 0.25
        val halfW = innerR * kotlin.math.sin(Math.PI / blades) * 1.1
        val taper = pivotR + innerR * 0.1
        val noseW = halfW * 0.2
        val slab = PANEL_THICK / blades
        for (i in 0 until blades) {
            val zBack = -PANEL_THICK / 2 + i * slab
            val zFront = zBack + slab
            val a = 2.0 * Math.PI * i / blades
            val nx = kotlin.math.cos(a)
            val ny = kotlin.math.sin(a)
            val px = ox + pivotR * nx
            val py = oy + pivotR * ny
            val dx = -nx * kotlin.math.cos(theta) + ny * kotlin.math.sin(theta)
            val dy = -ny * kotlin.math.cos(theta) - nx * kotlin.math.sin(theta)
            val tx = -dy
            val ty = dx
            val leaf = listOf(
                0.0 to -halfW,
                taper to -halfW,
                bladeLen to -noseW,
                bladeLen to noseW,
                taper to halfW,
                0.0 to halfW
            ).map { (u, v) -> px + u * dx + v * tx to py + u * dy + v * ty }
            val poly = clipToPolygon(leaf, clipRing)
            if (poly.size < 3) continue
            fun localU(p: Pair<Double, Double>): Double =
                (p.first - px) * dx + (p.second - py) * dy

            fun localV(p: Pair<Double, Double>): Double =
                (p.first - px) * tx + (p.second - py) * ty

            val blade = ArrayList<Quad>()
            var cu = 0.0
            while (cu < bladeLen) {
                val stepU = kotlin.math.min(1.0, bladeLen - cu)
                var cv = -halfW
                while (cv < halfW) {
                    val stepV = kotlin.math.min(1.0, halfW - cv)
                    val cell = listOf(
                        px + cu * dx + cv * tx to py + cu * dy + cv * ty,
                        px + (cu + stepU) * dx + cv * tx to py + (cu + stepU) * dy + cv * ty,
                        px + (cu + stepU) * dx + (cv + stepV) * tx to py + (cu + stepU) * dy + (cv + stepV) * ty,
                        px + cu * dx + (cv + stepV) * tx to py + cu * dy + (cv + stepV) * ty
                    )
                    val cutLeaf = clipToPolygon(cell, leaf)
                    val cellPoly = if (cutLeaf.size >= 3) clipToPolygon(cutLeaf, clipRing) else cutLeaf
                    if (cellPoly.size >= 3) {
                        val uvs = cellPoly.map {
                            (localU(it) - cu).toFloat() to (localV(it) - cv).toFloat()
                        }
                        for (k in 1 until cellPoly.size - 1) {
                            val p0 = cellPoly[0]
                            val p1 = cellPoly[k]
                            val p2 = cellPoly[k + 1]
                            val (u0, v0) = uvs[0]
                            val (u1, v1) = uvs[k]
                            val (u2, v2) = uvs[k + 1]
                            blade.add(quad(p0.first, p0.second, zFront, p1.first, p1.second, zFront, p2.first, p2.second, zFront, p2.first, p2.second, zFront, u0, v0, u1, v1, u2, v2, u2, v2, 0.0, 0.0, 1.0))
                            blade.add(quad(p2.first, p2.second, zBack, p1.first, p1.second, zBack, p0.first, p0.second, zBack, p0.first, p0.second, zBack, u2, v2, u1, v1, u0, v0, u0, v0, 0.0, 0.0, -1.0))
                        }
                    }
                    cv += stepV
                }
                cu += stepU
            }
            blade.addAll(panelStrips(poly, zBack, zFront, PANEL_STRIP_V))
            out.add(PolygonPart(blade))
        }
        return out
    }

    private fun slideParts(
        ox: Double,
        oy: Double,
        innerR: Double,
        outerRing: List<Pair<Double, Double>>,
        open: Double
    ): List<Part> {
        val slide = open * innerR
        val out = ArrayList<Part>()
        val panelH = 2 * (innerR - FRAME) - PANEL_INSET
        val panelW = innerR - PANEL_INSET
        for (dir in intArrayOf(-1, 1)) {
            val cx = ox + dir * (innerR / 2 + slide)
            out.add(BoxPart(cx, oy, 0.0, panelW, panelH, PANEL_THICK, color = PANEL_COLOR))
            val rect = listOf(
                cx - innerR / 2 to oy - (innerR - FRAME),
                cx + innerR / 2 to oy - (innerR - FRAME),
                cx + innerR / 2 to oy + (innerR - FRAME),
                cx - innerR / 2 to oy + (innerR - FRAME)
            )
            val poly = clipToPolygon(rect, outerRing)
            val strips = panelStrips(poly, -PANEL_THICK / 2, PANEL_THICK / 2, PANEL_STRIP_V)
            if (strips.isNotEmpty()) out.add(PolygonPart(strips))
        }
        return out
    }

    // edges are split per unit so the texture is never stretched
    private fun panelStrips(
        poly: List<Pair<Double, Double>>,
        zBack: Double,
        zFront: Double,
        vf: Float
    ): List<Quad> {
        val strips = ArrayList<Quad>()
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val ex = b.first - a.first
            val ey = b.second - a.second
            val len = kotlin.math.sqrt(ex.pow2() + ey.pow2())
            if (len <= 1.0E-9) continue
            val nx = ey / len
            val ny = -ex / len
            var done = 0.0
            while (done < len - 1.0E-6) {
                val step = kotlin.math.min(1.0, len - done)
                val ax = a.first + ex * (done / len)
                val ay = a.second + ey * (done / len)
                val bx = a.first + ex * ((done + step) / len)
                val by = a.second + ey * ((done + step) / len)
                strips.add(
                    quad(
                        ax, ay, zBack, bx, by, zBack, bx, by, zFront, ax, ay, zFront,
                        0f, 0f, step.toFloat(), 0f, step.toFloat(), vf, 0f, vf,
                        nx, ny, 0.0
                    )
                )
                done += step
            }
        }
        return strips
    }

    private fun Double.pow2(): Double = this * this

    // subject must be convex; ring must be counter-clockwise
    private fun clipToPolygon(
        subject: List<Pair<Double, Double>>,
        ringPts: List<Pair<Double, Double>>
    ): List<Pair<Double, Double>> {
        var cur = subject
        val n = ringPts.size
        for (k in 0 until n) {
            if (cur.isEmpty()) break
            val a = ringPts[k]
            val b = ringPts[(k + 1) % n]
            fun side(p: Pair<Double, Double>): Double =
                (b.first - a.first) * (p.second - a.second) -
                    (b.second - a.second) * (p.first - a.first)
            val next = ArrayList<Pair<Double, Double>>()
            for (i in cur.indices) {
                val p = cur[i]
                val q = cur[(i + 1) % cur.size]
                val sp = side(p)
                val sq = side(q)
                if (sp >= 0) next.add(p)
                if ((sp >= 0 && sq < 0) || (sp < 0 && sq >= 0)) {
                    val t = sp / (sp - sq)
                    next.add(p.first + (q.first - p.first) * t to p.second + (q.second - p.second) * t)
                }
            }
            cur = next
        }
        return cur
    }

    private fun quad(
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        x3: Double, y3: Double, z3: Double,
        u0: Float, v0: Float, u1: Float, v1: Float,
        u2: Float, v2: Float, u3: Float, v3: Float,
        nx: Double, ny: Double, nz: Double
    ): Quad = Quad(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, u0, v0, u1, v1, u2, v2, u3, v3, nx, ny, nz)
}
