package net.omori_sunny.create_waterparked.content.attachment.door

import net.minecraft.world.phys.AABB
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelContext
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelProvider

// mechanical door: the panels stay BoxParts (their big faces run the ghost
// CSG intersection against the tube interior) while every SIDE wall is
// generated manually - the frame is a band following the tube's inner
// polygon ring and each panel gets perimeter strips clipped in 2D against
// that same polygon. Local space: x = lateral, y = up, z = tangent.
class MechanicalDoorProvider : SlideAttachmentModelProvider() {

    companion object {
        private const val FRAME = 0.11
        private const val PANEL_THICK = 0.14
        private const val DOOR_Z = 0.08

        private const val PANEL_COLOR = 0xC0AA7A
    }

    // render-frame smoothing: prev/current open tracked per tick, lerped per frame
    private val anim = HashMap<net.minecraft.core.BlockPos, Pair<Float, Float>>()
    private var animTick = -1L

    private fun openFraction(ctx: SlideAttachmentModelContext): Double {
        val target = ctx.data.getFloat("DoorOpenF").coerceIn(0f, 1f)
        val mc = net.minecraft.client.Minecraft.getInstance()
        val now = ctx.level.gameTime
        if (animTick != now) {
            // new tick: current becomes prev; each provider call this tick
            // sees the same prev and refreshes current toward the target
            anim.entries.forEach { it.setValue(it.value.first to it.value.first) }
            animTick = now
        }
        val cur = anim.getOrPut(ctx.sabPos) { target to target }
        val updated = cur.first to target
        anim[ctx.sabPos] = updated
        val partial = try {
            mc.timer.getGameTimeDeltaPartialTick(false)
        } catch (t: Throwable) {
            net.minecraft.util.Mth.lerp(
                (mc.frameTimeNs % 50_000_000L) / 50_000_000f, 0f, 1f
            )
        }
        return (updated.first + (updated.second - updated.first) * partial).toDouble()
    }

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

        // manual frame band: front/back per edge + inner side walls + caps,
        // flush with the tube facets by construction
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
            // outer side wall hugging the slide wall (inset a hair to avoid
            // z-fighting with the tube facets), wound outward
            val wx0 = ox + (ox0 - ox) * (1.0 - 0.005 / innerR)
            val wy0 = oy + (oy0 - oy) * (1.0 - 0.005 / innerR)
            val wx1 = ox + (ox1 - ox) * (1.0 - 0.005 / innerR)
            val wy1 = oy + (oy1 - oy) * (1.0 - 0.005 / innerR)
            quads.add(quad(wx0, wy0, zB, wx1, wy1, zB, wx1, wy1, zF, wx0, wy0, zF, 0f, 0f, uf, 0f, uf, 0.2f, 0f, 0.2f, oy1 - oy0, -(ox1 - ox0), 0.0))
        }
        out.add(PolygonPart(quads))

        // panels: BoxPart through the CSG for the big faces (shrunk a hair so
        // its own side faces hide behind the manual strips), plus manual
        // perimeter side walls clipped in 2D against the wall polygon
        val slide = openFraction(ctx) * innerR
        val panelH = 2 * (innerR - FRAME) - 0.002
        val panelW = innerR - 0.002
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
            val strips = ArrayList<Quad>()
            val zP = PANEL_THICK / 2
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                val nx = b.second - a.second
                val ny = -(b.first - a.first)
                val len = kotlin.math.sqrt(nx.pow2() + ny.pow2())
                val (ux, uy) = if (len > 1.0E-9) nx / len to ny / len else 0.0 to 0.0
                val uf = len.toFloat().coerceIn(0f, 1f)
                strips.add(quad(b.first, b.second, -zP, a.first, a.second, -zP, a.first, a.second, zP, b.first, b.second, zP, uf, 0f, 0f, 0f, 0f, 0.15f, uf, 0.15f, ux, uy, 0.0))
            }
            if (strips.isNotEmpty()) out.add(PolygonPart(strips))
        }
        return out
    }

    private fun Double.pow2(): Double = this * this

    /** Sutherland-Hodgman clip of a convex subject against the CCW wall polygon */
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
