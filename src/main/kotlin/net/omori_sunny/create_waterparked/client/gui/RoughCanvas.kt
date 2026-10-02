package net.omori_sunny.create_waterparked.client.gui

import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// rough.js hand drawn strokes, ported from rough-stuff/rough src/renderer.ts (MIT)
object RoughCanvas {

    class RoughRandom(seed: Long) {
        private var s: Long = seed

        fun next(): Float {
            s = (48271L * s) % 2147483647L
            return s.toFloat() / 2147483647f
        }
    }

    class Options(seed: Long) {
        val random = RoughRandom(if (seed == 0L) 1L else seed)
        var maxRandomnessOffset = 0.6f
        var roughness = 0.7f
        var bowing = 0.4f
        var curveTightness = 0f
        var curveStepCount = 9
        var disableMultiStroke = false
        var preserveVertices = false

        fun alteredSeed(): Options {
            val o = Options(random.next().toRawBits().toLong())
            o.maxRandomnessOffset = maxRandomnessOffset
            o.roughness = roughness
            o.bowing = bowing
            o.curveTightness = curveTightness
            o.curveStepCount = curveStepCount
            o.disableMultiStroke = disableMultiStroke
            o.preserveVertices = preserveVertices
            return o
        }
    }

    sealed class Op {
        data class Move(val x: Float, val y: Float) : Op()
        data class Curve(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float, val x: Float, val y: Float) : Op()
        data class LineTo(val x: Float, val y: Float) : Op()
    }

    fun lineOps(x1: Float, y1: Float, x2: Float, y2: Float, o: Options): List<Op> =
        doubleLine(x1, y1, x2, y2, o)

    fun polygonOps(points: List<Pair<Float, Float>>, o: Options): List<Op> {
        val ops = ArrayList<Op>()
        for (i in 0 until points.size - 1) {
            ops += doubleLine(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second, o)
        }
        if (points.size > 2) {
            ops += doubleLine(points.last().first, points.last().second, points[0].first, points[0].second, o)
        }
        return ops
    }

    fun circleOps(cx: Float, cy: Float, radius: Float, o: Options): List<Op> {
        val psq = sqrt((Math.PI.toFloat() * 2f) * sqrt((radius * radius + radius * radius) / 2f))
        val stepCount = maxOf(o.curveStepCount.toFloat(), (o.curveStepCount / sqrt(200f)) * psq).toInt()
        val increment = (Math.PI.toFloat() * 2f) / stepCount
        val rx = abs(radius)
        val ry = abs(radius)
        return ellipseWithParams(cx, cy, o, increment, rx, ry)
    }

    private fun ellipseWithParams(cx: Float, cy: Float, o: Options, increment: Float, rx: Float, ry: Float): List<Op> {
        val (ap1) = computeEllipsePoints(increment, cx, cy, rx, ry, 1f, increment * offset(0.1f, 0.4f, o), o)
        var o1 = curve(ap1, null, o)
        if (!o.disableMultiStroke && o.roughness != 0f) {
            val (ap2) = computeEllipsePoints(increment, cx, cy, rx, ry, 1.5f, 0f, o)
            o1 = o1 + curve(ap2, null, o)
        }
        return o1
    }

    private fun computeEllipsePoints(
        increment: Float, cx: Float, cy: Float, rx: Float, ry: Float,
        offset: Float, overlap: Float, o: Options
    ): Pair<List<Pair<Float, Float>>, List<Pair<Float, Float>>> {
        val corePoints = ArrayList<Pair<Float, Float>>()
        val allPoints = ArrayList<Pair<Float, Float>>()
        val radOffset = offsetOpt(0.5f, o) - (Math.PI.toFloat() / 2f)
        allPoints += Pair(
            offsetOpt(offset, o) + cx + 0.9f * rx * cos(radOffset - increment),
            offsetOpt(offset, o) + cy + 0.9f * ry * sin(radOffset - increment)
        )
        val endAngle = Math.PI.toFloat() * 2f + radOffset - 0.01f
        var angle = radOffset
        while (angle < endAngle) {
            val p = Pair(
                offsetOpt(offset, o) + cx + rx * cos(angle),
                offsetOpt(offset, o) + cy + ry * sin(angle)
            )
            corePoints += p
            allPoints += p
            angle += increment
        }
        allPoints += Pair(
            offsetOpt(offset, o) + cx + rx * cos(radOffset + Math.PI.toFloat() * 2f + overlap * 0.5f),
            offsetOpt(offset, o) + cy + ry * sin(radOffset + Math.PI.toFloat() * 2f + overlap * 0.5f)
        )
        allPoints += Pair(
            offsetOpt(offset, o) + cx + 0.98f * rx * cos(radOffset + overlap),
            offsetOpt(offset, o) + cy + 0.98f * ry * sin(radOffset + overlap)
        )
        allPoints += Pair(
            offsetOpt(offset, o) + cx + 0.9f * rx * cos(radOffset + overlap * 0.5f),
            offsetOpt(offset, o) + cy + 0.9f * ry * sin(radOffset + overlap * 0.5f)
        )
        return allPoints to corePoints
    }

    private fun offset(min: Float, max: Float, o: Options, roughnessGain: Float = 1f): Float =
        o.roughness * roughnessGain * ((o.random.next() * (max - min)) + min)

    private fun offsetOpt(x: Float, o: Options, roughnessGain: Float = 1f): Float =
        offset(-x, x, o, roughnessGain)

    private fun cloneOptionsAlterSeed(o: Options): Options = o.alteredSeed()

    private fun doubleLine(x1: Float, y1: Float, x2: Float, y2: Float, o: Options): List<Op> {
        val o1 = lineOne(x1, y1, x2, y2, o, false, true)
        val o2 = if (o.disableMultiStroke) emptyList() else lineOne(x1, y1, x2, y2, cloneOptionsAlterSeed(o), true, true)
        return o1 + o2
    }

    private fun lineOne(
        x1: Float, y1: Float, x2: Float, y2: Float,
        o: Options, overlay: Boolean, move: Boolean
    ): List<Op> {
        val lengthSq = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
        val length = sqrt(lengthSq)
        val roughnessGain = when {
            length < 200f -> 1f
            length > 500f -> 0.4f
            else -> (-0.0016668f) * length + 1.233334f
        }
        var offset = o.maxRandomnessOffset
        if (offset * offset * 100f > lengthSq) {
            offset = length / 10f
        }
        val halfOffset = offset / 2f
        val divergePoint = 0.2f + o.random.next() * 0.2f
        var midDispX = o.bowing * o.maxRandomnessOffset * (y2 - y1) / 200f
        var midDispY = o.bowing * o.maxRandomnessOffset * (x1 - x2) / 200f
        midDispX = offsetOpt(midDispX, o, roughnessGain)
        midDispY = offsetOpt(midDispY, o, roughnessGain)
        val ops = ArrayList<Op>(2)
        val pv = o.preserveVertices
        if (move) {
            if (overlay) {
                ops += Op.Move(
                    x1 + (if (pv) 0f else offsetOpt(halfOffset, o, roughnessGain)),
                    y1 + (if (pv) 0f else offsetOpt(halfOffset, o, roughnessGain))
                )
            } else {
                ops += Op.Move(
                    x1 + (if (pv) 0f else offsetOpt(offset, o, roughnessGain)),
                    y1 + (if (pv) 0f else offsetOpt(offset, o, roughnessGain))
                )
            }
        }
        if (overlay) {
            ops += Op.Curve(
                midDispX + x1 + (x2 - x1) * divergePoint + offsetOpt(halfOffset, o, roughnessGain),
                midDispY + y1 + (y2 - y1) * divergePoint + offsetOpt(halfOffset, o, roughnessGain),
                midDispX + x1 + 2f * (x2 - x1) * divergePoint + offsetOpt(halfOffset, o, roughnessGain),
                midDispY + y1 + 2f * (y2 - y1) * divergePoint + offsetOpt(halfOffset, o, roughnessGain),
                x2 + (if (pv) 0f else offsetOpt(halfOffset, o, roughnessGain)),
                y2 + (if (pv) 0f else offsetOpt(halfOffset, o, roughnessGain))
            )
        } else {
            ops += Op.Curve(
                midDispX + x1 + (x2 - x1) * divergePoint + offsetOpt(offset, o, roughnessGain),
                midDispY + y1 + (y2 - y1) * divergePoint + offsetOpt(offset, o, roughnessGain),
                midDispX + x1 + 2f * (x2 - x1) * divergePoint + offsetOpt(offset, o, roughnessGain),
                midDispY + y1 + 2f * (y2 - y1) * divergePoint + offsetOpt(offset, o, roughnessGain),
                x2 + (if (pv) 0f else offsetOpt(offset, o, roughnessGain)),
                y2 + (if (pv) 0f else offsetOpt(offset, o, roughnessGain))
            )
        }
        return ops
    }

    private fun curveWithOffset(points: List<Pair<Float, Float>>, offset: Float, o: Options): List<Op> {
        if (points.isEmpty()) return emptyList()
        val ps = ArrayList<Pair<Float, Float>>()
        ps += Pair(points[0].first + offsetOpt(offset, o), points[0].second + offsetOpt(offset, o))
        ps += Pair(points[0].first + offsetOpt(offset, o), points[0].second + offsetOpt(offset, o))
        for (i in 1 until points.size) {
            ps += Pair(points[i].first + offsetOpt(offset, o), points[i].second + offsetOpt(offset, o))
            if (i == points.size - 1) {
                ps += Pair(points[i].first + offsetOpt(offset, o), points[i].second + offsetOpt(offset, o))
            }
        }
        return curve(ps, null, o)
    }

    private fun curve(points: List<Pair<Float, Float>>, closePoint: Pair<Float, Float>?, o: Options): List<Op> {
        val len = points.size
        val ops = ArrayList<Op>()
        if (len > 3) {
            val s = 1f - o.curveTightness
            ops += Op.Move(points[1].first, points[1].second)
            var i = 1
            while (i + 2 < len) {
                val c = points[i]
                val n = points[i + 1]
                val p = points[i - 1]
                val nn = points[i + 2]
                ops += Op.Curve(
                    c.first + (s * n.first - s * p.first) / 6f,
                    c.second + (s * n.second - s * p.second) / 6f,
                    n.first + (s * c.first - s * nn.first) / 6f,
                    n.second + (s * c.second - s * nn.second) / 6f,
                    n.first, n.second
                )
                i++
            }
            if (closePoint != null) {
                ops += Op.LineTo(closePoint.first + offsetOpt(o.maxRandomnessOffset, o), closePoint.second + offsetOpt(o.maxRandomnessOffset, o))
            }
        } else if (len == 3) {
            ops += Op.Move(points[1].first, points[1].second)
            ops += Op.Curve(points[1].first, points[1].second, points[2].first, points[2].second, points[2].first, points[2].second)
        } else if (len == 2) {
            ops += lineOne(points[0].first, points[0].second, points[1].first, points[1].second, o, true, true)
        }
        return ops
    }

    fun loopOps(points: List<Pair<Float, Float>>, o: Options): List<Op> {
        if (points.size < 3) return polygonOps(points, o)
        val closed = points + listOf(points[0], points[1], points[2])
        val o1 = curveWithOffset(closed, 1f * (1f + o.roughness * 0.2f), o)
        val o2 = if (o.disableMultiStroke) emptyList() else curveWithOffset(closed, 1.5f * (1f + o.roughness * 0.22f), cloneOptionsAlterSeed(o))
        return o1 + o2
    }

    fun bezierLoopOps(
        anchors: List<Pair<Float, Float>>,
        handles: List<Pair<Float, Float>>,
        inHandles: List<Pair<Float, Float>>,
        modes: List<Boolean>,
        o: Options
    ): List<Op> {
        if (anchors.size < 3 || handles.size < anchors.size) return polygonOps(anchors, o)

        fun sampleLoop(offset: Float, opts: Options): List<Op> {
            val sampled = ArrayList<Pair<Float, Float>>()
            val n = anchors.size
            for (i in 0 until n) {
                val j = (i + 1) % n
                val p0 = anchors[i]
                val p1 = anchors[j]
                val outH = handles[i]
                val inH = if (j < inHandles.size) inHandles[j] else 0f to 0f
                val iCurved = i < modes.size && modes[i]
                val jCurved = j < modes.size && modes[j]

                if (!iCurved && !jCurved) {
                    sampled += p0
                    sampled += p1
                    continue
                }

                val c1 = if (iCurved) Pair(
                    p0.first + outH.first + offsetOpt(offset, opts),
                    p0.second + outH.second + offsetOpt(offset, opts)
                ) else p0
                val c2 = if (jCurved) Pair(
                    p1.first + inH.first + offsetOpt(offset, opts),
                    p1.second + inH.second + offsetOpt(offset, opts)
                ) else p1

                val steps = 6
                for (s in 0 until steps) {
                    val t = s.toFloat() / steps
                    val u = 1f - t
                    sampled += Pair(
                        u * u * u * p0.first + 3f * u * u * t * c1.first + 3f * u * t * t * c2.first + t * t * t * p1.first,
                        u * u * u * p0.second + 3f * u * u * t * c1.second + 3f * u * t * t * c2.second + t * t * t * p1.second
                    )
                }
            }
            if (sampled.size >= 3) {
                sampled += sampled[0]
                sampled += sampled[1]
                sampled += sampled[2]
            }
            return curve(sampled, null, opts)
        }

        val o1 = sampleLoop(1f * (1f + o.roughness * 0.2f), o)
        val o2 = if (o.disableMultiStroke) emptyList()
        else sampleLoop(1.5f * (1f + o.roughness * 0.22f), cloneOptionsAlterSeed(o))
        return o1 + o2
    }

    class PixelBuffer {
        private val rows = HashMap<Int, IntArrayList>()

        private class IntArrayList {
            var items = IntArray(16)
            var size = 0

            fun add(v: Int) {
                if (size == items.size) items = items.copyOf(items.size * 2)
                items[size++] = v
            }
        }

        fun pixel(x: Int, y: Int) {
            rows.getOrPut(y) { IntArrayList() }.add(x)
        }

        fun line(ax: Float, ay: Float, bx: Float, by: Float) {
            var x0 = ax.toInt()
            var y0 = ay.toInt()
            val x1 = bx.toInt()
            val y1 = by.toInt()
            val dx = abs(x1 - x0)
            val dy = -abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1
            val sy = if (y0 < y1) 1 else -1
            var err = dx + dy
            while (true) {
                pixel(x0, y0)
                if (x0 == x1 && y0 == y1) break
                val e2 = 2 * err
                if (e2 >= dy) {
                    err += dy
                    x0 += sx
                }
                if (e2 <= dx) {
                    err += dx
                    y0 += sy
                }
            }
        }

        fun bezier(x0: Float, y0: Float, c: Op.Curve) {
            val chordLen = sqrt((c.x - x0) * (c.x - x0) + (c.y - y0) * (c.y - y0))
            val controlSpread = sqrt(
                (c.c1x - x0) * (c.c1x - x0) + (c.c1y - y0) * (c.c1y - y0)
            ) + sqrt(
                (c.c2x - c.c1x) * (c.c2x - c.c1x) + (c.c2y - c.c1y) * (c.c2y - c.c1y)
            ) + sqrt(
                (c.x - c.c2x) * (c.x - c.c2x) + (c.y - c.c2y) * (c.y - c.c2y)
            )
            val steps = (maxOf(chordLen, controlSpread).toInt().coerceAtLeast(2)).coerceAtMost(128)
            var px = x0
            var py = y0
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val u = 1f - t
                val x = u * u * u * x0 + 3f * u * u * t * c.c1x + 3f * u * t * t * c.c2x + t * t * t * c.x
                val y = u * u * u * y0 + 3f * u * u * t * c.c1y + 3f * u * t * t * c.c2y + t * t * t * c.y
                line(px, py, x, y)
                px = x
                py = y
            }
        }

        fun ops(opList: List<Op>) {
            var curX = 0f
            var curY = 0f
            for (op in opList) {
                when (op) {
                    is Op.Move -> {
                        curX = op.x
                        curY = op.y
                    }
                    is Op.LineTo -> {
                        line(curX, curY, op.x, op.y)
                        curX = op.x
                        curY = op.y
                    }
                    is Op.Curve -> {
                        bezier(curX, curY, op)
                        curX = op.x
                        curY = op.y
                    }
                }
            }
        }

        fun isEmpty() = rows.isEmpty()

        fun flush(graphics: GuiGraphics, color: Int, z: Int = 1) {
            if (rows.isEmpty()) return
            val builder = graphics.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui())
            val pose = graphics.pose().last().pose()
            val a = ((color shr 24) and 255).toFloat() / 255f
            val r = ((color shr 16) and 255).toFloat() / 255f
            val g = ((color shr 8) and 255).toFloat() / 255f
            val b = (color and 255).toFloat() / 255f
            val zf = z.toFloat()

            for ((y, list) in rows) {
                if (list.size == 0) continue
                val xs = list.items.copyOf(list.size)
                xs.sort()
                var runStart = xs[0]
                var prev = xs[0]
                for (i in 1 until xs.size) {
                    val x = xs[i]
                    if (x > prev + 1) {
                        emitQuad(builder, pose, runStart, y, prev + 1, y + 1, zf, r, g, b, a)
                        runStart = x
                    }
                    prev = x
                }
                emitQuad(builder, pose, runStart, y, prev + 1, y + 1, zf, r, g, b, a)
            }
        }

        private fun emitQuad(
            builder: com.mojang.blaze3d.vertex.VertexConsumer,
            pose: org.joml.Matrix4f,
            x1: Int, y1: Int, x2: Int, y2: Int, z: Float,
            r: Float, g: Float, b: Float, a: Float
        ) {
            builder.addVertex(pose, x1.toFloat(), y1.toFloat(), z).setColor(r, g, b, a)
            builder.addVertex(pose, x1.toFloat(), y2.toFloat(), z).setColor(r, g, b, a)
            builder.addVertex(pose, x2.toFloat(), y2.toFloat(), z).setColor(r, g, b, a)
            builder.addVertex(pose, x2.toFloat(), y1.toFloat(), z).setColor(r, g, b, a)
        }
    }

    fun drawOps(graphics: GuiGraphics, ops: List<Op>, color: Int) {
        val buf = PixelBuffer()
        buf.ops(ops)
        buf.flush(graphics, color)
    }

    fun line(graphics: GuiGraphics, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, seed: Long = 1L) {
        val o = Options(seed)
        drawOps(graphics, lineOps(x1, y1, x2, y2, o), color)
    }

    fun circle(graphics: GuiGraphics, cx: Float, cy: Float, radius: Float, color: Int, seed: Long = 1L) {
        val o = Options(seed)
        drawOps(graphics, circleOps(cx, cy, radius, o), color)
    }

    fun loop(graphics: GuiGraphics, points: List<Pair<Float, Float>>, color: Int, seed: Long = 1L) {
        val o = Options(seed)
        drawOps(graphics, loopOps(points, o), color)
    }

    fun gridDot(graphics: GuiGraphics, cx: Float, cy: Float, size: Float, color: Int, seed: Long = 1L) {
        val s = size / 2
        line(graphics, cx - s, cy - s, cx + s, cy - s, color, seed)
        line(graphics, cx + s, cy - s, cx + s, cy + s, color, seed + 1)
        line(graphics, cx + s, cy + s, cx - s, cy + s, color, seed + 2)
        line(graphics, cx - s, cy + s, cx - s, cy - s, color, seed + 3)
    }
}
