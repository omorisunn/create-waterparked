package net.omori_sunny.create_waterparked.content.attachment.detector

import com.simibubi.create.content.trains.track.BezierConnection
import net.minecraft.world.phys.AABB
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelContext
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentModelProvider
import net.omori_sunny.create_waterparked.content.waterslide.PlacedSector
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry

// local space: x = lateral, y = up, z = tangent
class DetectorProvider : SlideAttachmentModelProvider() {

    companion object {
        private const val FRAME = 0.11
        private const val HUG_INSET = 0.005
        private const val STATION_STEP = 0.5
        private const val MAX_STATIONS = 64
        private const val CACHE_LIMIT = 32
        private const val EDGE_EPS = 1.0E-4
        private const val EDGE_PROBE = 0.05
        private const val ARC_SAMPLES = 256

        private val DEFAULT_CONFIG: WaterslideSectorConfig = WaterslideSectorConfig.defaultConfig()

        private val cache = object : LinkedHashMap<String, List<Part>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<Part>>
            ): Boolean = size > CACHE_LIMIT
        }
    }

    private class Station(
        val axisX: Double, val axisY: Double, val axisZ: Double,
        val latX: Double, val latY: Double, val latZ: Double,
        val upX: Double, val upY: Double, val upZ: Double,
        val innerR: Double
    )

    private class Span(
        val a0: Double,
        val a1: Double,
        val capStart: Boolean,
        val capEnd: Boolean
    )

    // the drawn band itself, so picking and the placement outline always match it
    override fun boundingBox(ctx: SlideAttachmentModelContext): AABB {
        val b = doubleArrayOf(
            Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
            -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE
        )
        for (part in parts(ctx)) {
            if (part !is PolygonPart) continue
            for (q in part.quads) expand(q, b)
        }
        if (b[0] > b[3]) return hubBox(ctx)
        return AABB(
            b[0] - FRAME, b[1] - FRAME, b[2] - FRAME,
            b[3] + FRAME, b[4] + FRAME, b[5] + FRAME
        )
    }

    private fun expand(q: Quad, b: DoubleArray) {
        expand(b, q.x0, q.y0, q.z0)
        expand(b, q.x1, q.y1, q.z1)
        expand(b, q.x2, q.y2, q.z2)
        expand(b, q.x3, q.y3, q.z3)
    }

    private fun expand(b: DoubleArray, x: Double, y: Double, z: Double) {
        if (x < b[0]) b[0] = x
        if (y < b[1]) b[1] = y
        if (z < b[2]) b[2] = z
        if (x > b[3]) b[3] = x
        if (y > b[4]) b[4] = y
        if (z > b[5]) b[5] = z
    }

    // only reached while every sector is open and the band draws nothing
    private fun hubBox(ctx: SlideAttachmentModelContext): AABB {
        val outer = (ctx.radius + ctx.wallThickness - 0.1).toDouble()
        val (ox, oy) = SlideAttachmentGeometry.axisOffset(ctx, outer)
        val r = (ctx.radius - 0.1).toDouble() + FRAME
        return AABB(
            ox - r, oy - r, -DetectorAttachment.distL(ctx.data).toDouble(),
            ox + r, oy + r, DetectorAttachment.distR(ctx.data).toDouble()
        )
    }

    override fun parts(ctx: SlideAttachmentModelContext): List<Part> {
        val left = DetectorAttachment.distL(ctx.data).toDouble()
        val right = DetectorAttachment.distR(ctx.data).toDouble()
        val key = signature(ctx, left, right)
        cache[key]?.let { return it }
        val sectors = WaterslideSectorLayout.place(ctx.sectorConfig ?: DEFAULT_CONFIG)
        val quads = build(ctx, sectors, left, right)
        val built: List<Part> = if (quads.isEmpty()) emptyList() else listOf(PolygonPart(quads))
        cache[key] = built
        return built
    }

    private fun build(
        ctx: SlideAttachmentModelContext,
        sectors: List<PlacedSector>,
        left: Double,
        right: Double
    ): List<Quad> {
        val stations = stations(ctx, angleOf(ctx), left, right)
        val out = ArrayList<Quad>()
        for (span in wallSpans(sectors, WaterslideTubeMesh.crossSections())) {
            emitSpan(stations, span, out)
        }
        return out
    }

    // wall spans remaining once the open sectors are cut out of the ring
    private fun wallSpans(sectors: List<PlacedSector>, sides: Int): List<Span> {
        val step = 360.0 / sides
        val out = ArrayList<Span>(sides)
        for (k in 0 until sides) {
            val start = 90.0 + step * k
            for (piece in spansWithin(sectors, start, step)) {
                out.add(
                    Span(
                        piece.first, piece.second,
                        !isWall(sectors, piece.first - EDGE_PROBE),
                        !isWall(sectors, piece.second + EDGE_PROBE)
                    )
                )
            }
        }
        return out
    }

    private fun spansWithin(
        sectors: List<PlacedSector>,
        start: Double,
        width: Double
    ): List<Pair<Double, Double>> {
        val cuts = ArrayList<Double>()
        cuts.add(0.0)
        for (p in sectors) {
            for (b in doubleArrayOf(p.startAngle.toDouble(), p.endAngle.toDouble())) {
                var d = (b - start) % 360.0
                if (d < 0.0) d += 360.0
                if (d > EDGE_EPS && d < width - EDGE_EPS) cuts.add(d)
            }
        }
        cuts.add(width)
        cuts.sort()
        val out = ArrayList<Pair<Double, Double>>(cuts.size)
        for (i in 0 until cuts.size - 1) {
            val d0 = cuts[i]
            val d1 = cuts[i + 1]
            if (d1 - d0 < EDGE_EPS) continue
            if (!isWall(sectors, start + (d0 + d1) / 2.0)) continue
            out.add((start + d0) to (start + d1))
        }
        return out
    }

    private fun isWall(sectors: List<PlacedSector>, angleDeg: Double): Boolean {
        val placed = WaterslideSectorLayout.sectorAt(
            sectors, WaterslideSectorLayout.normalize(angleDeg.toFloat())
        ) ?: return false
        return placed.sector.material != SectorMaterial.OPEN
    }

    private fun emitSpan(stations: List<Station>, span: Span, out: MutableList<Quad>) {
        val a0 = Math.toRadians(span.a0)
        val a1 = Math.toRadians(span.a1)
        for (i in 0 until stations.size - 1) {
            val s0 = stations[i]
            val s1 = stations[i + 1]
            val r0 = s0.innerR
            val r1 = s1.innerR
            val u = (((r0 + r1) / 2.0) * (a1 - a0)).toFloat()
            val v = distance(s0, s1).toFloat()
            val o00 = ring(s0, a0, r0 - HUG_INSET)
            val o01 = ring(s0, a1, r0 - HUG_INSET)
            val o10 = ring(s1, a0, r1 - HUG_INSET)
            val o11 = ring(s1, a1, r1 - HUG_INSET)
            val i00 = ring(s0, a0, r0 - FRAME)
            val i01 = ring(s0, a1, r0 - FRAME)
            val i10 = ring(s1, a0, r1 - FRAME)
            val i11 = ring(s1, a1, r1 - FRAME)
            out.add(quad(o00, o01, o11, o10, 0f, 0f, u, 0f, u, v, 0f, v))
            out.add(quad(i00, i10, i11, i01, 0f, 0f, v, 0f, v, u, 0f, u))
            val w = FRAME.toFloat()
            if (span.capStart) {
                out.add(quad(i00, o00, o10, i10, 0f, 0f, w, 0f, w, v, 0f, v))
            }
            if (span.capEnd) {
                out.add(quad(o01, i01, i11, o11, 0f, 0f, w, 0f, w, v, 0f, v))
            }
        }
        val first = stations.first()
        val last = stations.last()
        val rf = first.innerR
        val rl = last.innerR
        val uf = (rf * (a1 - a0)).toFloat()
        val ul = (rl * (a1 - a0)).toFloat()
        val w = FRAME.toFloat()
        out.add(
            quad(
                ring(first, a0, rf - HUG_INSET), ring(first, a0, rf - FRAME),
                ring(first, a1, rf - FRAME), ring(first, a1, rf - HUG_INSET),
                0f, 0f, w, 0f, w, uf, 0f, uf
            )
        )
        out.add(
            quad(
                ring(last, a0, rl - HUG_INSET), ring(last, a1, rl - HUG_INSET),
                ring(last, a1, rl - FRAME), ring(last, a0, rl - FRAME),
                0f, 0f, ul, 0f, ul, w, 0f, w
            )
        )
    }

    private fun stations(
        ctx: SlideAttachmentModelContext,
        angle: Float,
        left: Double,
        right: Double
    ): List<Station> {
        val curve = ctx.curve
        val table = arcTable(curve)
        val centre = arcAt(table, ctx.t.toDouble())
        val total = table[table.size - 1]
        val back = (centre - left).coerceIn(0.0, total)
        val front = (centre + right).coerceIn(0.0, total)
        val span = front - back
        val count = (Math.ceil(span / STATION_STEP).toInt() + 1).coerceIn(2, MAX_STATIONS)
        val out = ArrayList<Station>(count)
        for (i in 0 until count) {
            val t = tAtArc(table, back + span * i / (count - 1))
            val at = SlideAttachmentGeometry.contextAt(
                ctx.level, ctx.sabPos, curve, t.toFloat(), angle, ctx.data,
                ctx.renderTransform, ctx.sectorConfig
            )
            out.add(stationOf(at, ctx))
        }
        return out
    }

    private fun stationOf(at: SlideAttachmentModelContext, base: SlideAttachmentModelContext): Station {
        val (lat, up, _) = SlideAttachmentGeometry.basis(at)
        val (blat, bup, btan) = SlideAttachmentGeometry.basis(base)
        val outer = (at.radius + at.wallThickness - 0.1).toDouble()
        val axis = at.position.subtract(at.radialOut.scale(outer)).subtract(base.position)
        return Station(
            axis.dot(blat), axis.dot(bup), axis.dot(btan),
            lat.dot(blat), lat.dot(bup), lat.dot(btan),
            up.dot(blat), up.dot(bup), up.dot(btan),
            (at.radius - 0.1).toDouble()
        )
    }

    private fun ring(s: Station, a: Double, r: Double): DoubleArray {
        val c = Math.cos(a)
        val sn = Math.sin(a)
        return doubleArrayOf(
            s.axisX + r * (s.latX * c + s.upX * sn),
            s.axisY + r * (s.latY * c + s.upY * sn),
            s.axisZ + r * (s.latZ * c + s.upZ * sn)
        )
    }

    private fun distance(a: Station, b: Station): Double {
        val dx = b.axisX - a.axisX
        val dy = b.axisY - a.axisY
        val dz = b.axisZ - a.axisZ
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun quad(
        a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray,
        ua: Float, va: Float, ub: Float, vb: Float,
        uc: Float, vc: Float, ud: Float, vd: Float
    ): Quad {
        val e1x = b[0] - a[0]
        val e1y = b[1] - a[1]
        val e1z = b[2] - a[2]
        val e2x = d[0] - a[0]
        val e2y = d[1] - a[1]
        val e2z = d[2] - a[2]
        return Quad(
            a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2],
            ua, va, ub, vb, uc, vc, ud, vd,
            e1y * e2z - e1z * e2y,
            e1z * e2x - e1x * e2z,
            e1x * e2y - e1y * e2x
        )
    }

    private fun arcTable(curve: BezierConnection): DoubleArray {
        val table = DoubleArray(ARC_SAMPLES + 1)
        var prev = curve.getPosition(0.0)
        for (i in 1..ARC_SAMPLES) {
            val p = curve.getPosition(i.toDouble() / ARC_SAMPLES)
            table[i] = table[i - 1] + p.distanceTo(prev)
            prev = p
        }
        return table
    }

    private fun arcAt(table: DoubleArray, t: Double): Double {
        val steps = table.size - 1
        val x = t.coerceIn(0.0, 1.0) * steps
        val i = x.toInt().coerceAtMost(steps - 1)
        return table[i] + (table[i + 1] - table[i]) * (x - i)
    }

    private fun tAtArc(table: DoubleArray, arc: Double): Double {
        val steps = table.size - 1
        val target = arc.coerceIn(0.0, table[steps])
        var lo = 0
        var hi = steps
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (table[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return 0.0
        val span = table[lo] - table[lo - 1]
        val f = if (span <= 1.0E-9) 0.0 else (target - table[lo - 1]) / span
        return (lo - 1 + f) / steps
    }

    // the wall angle is not carried on the context, so recover it from the radial direction
    private fun angleOf(ctx: SlideAttachmentModelContext): Float = Math.toDegrees(
        Math.atan2(ctx.radialOut.dot(ctx.up), ctx.radialOut.dot(ctx.lateral))
    ).toFloat()

    // everything the built geometry depends on, and nothing that moves with the plot
    private fun signature(
        ctx: SlideAttachmentModelContext,
        left: Double,
        right: Double
    ): String {
        val sections = ctx.sectorConfig ?: DEFAULT_CONFIG
        val sb = StringBuilder(192)
        sb.append(System.identityHashCode(ctx.level)).append('|')
        sb.append(curveDigest(ctx.curve)).append('|')
        sb.append(angleOf(ctx)).append('|').append(ctx.t).append('|')
        sb.append(ctx.tangent.x).append(',').append(ctx.tangent.y).append(',')
            .append(ctx.tangent.z).append('|')
        sb.append(endpointRadius(ctx, true)).append(',').append(endpointRadius(ctx, false)).append('|')
        sb.append(ctx.radius).append('|').append(ctx.wallThickness).append('|')
        sb.append(WaterslideTubeMesh.crossSections()).append('|')
        sb.append(left).append('|').append(right).append('|')
        sb.append(sections.startAngle).append('|')
        for (s in sections.sectors) {
            sb.append(s.id).append(':').append(s.material).append(':').append(s.type).append(':')
                .append(s.widthDegrees).append(';')
        }
        return sb.toString()
    }

    private fun endpointRadius(ctx: SlideAttachmentModelContext, first: Boolean): Float {
        val pos = if (first) ctx.curve.bePositions.first else ctx.curve.bePositions.second
        return SlideCurveGeometry.radiusAt(ctx.level, pos)
    }

    private fun curveDigest(curve: BezierConnection): String {
        val sb = StringBuilder(104)
        sb.append(curve.getSegmentCount()).append('|')
        for (step in 0..4) {
            val p = curve.getPosition(step / 4.0)
            sb.append(p.x).append(',').append(p.y).append(',').append(p.z).append(';')
        }
        return sb.toString()
    }
}
