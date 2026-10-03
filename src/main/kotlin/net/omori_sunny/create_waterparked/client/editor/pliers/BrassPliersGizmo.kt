package net.omori_sunny.create_waterparked.client.editor.pliers

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.silvergold.simulatedcoasters.client.track.CoasterAnchorClientSpace
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.client.editor.WaterslideEditorRenderTypes
import org.joml.Matrix4f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private class HoverTriple(val origin: Vec3, val e1: Vec3, val e2: Vec3)

object BrassPliersGizmo {

    private const val MASK_RADIUS = 5.0
    private const val MASK_FULL = 1.5
    private const val POINT_FADE_BLOCKS = 30.0
    private const val GRID_STEP = 0.5
    private const val CIRCLE_SEGMENTS = 48

    private var pointMaskRadius = MASK_RADIUS
    private var pointMaskTarget = MASK_RADIUS

    private val gridColor = floatArrayOf(0.35f, 0.75f, 0.95f)
    private val circleColor = floatArrayOf(0.2f, 0.9f, 1.0f)
    private val snapColor = floatArrayOf(1.0f, 0.85f, 0.3f)

    @JvmStatic
    fun render(
        mc: Minecraft,
        poseStack: PoseStack,
        consumer: VertexConsumer,
        cameraPos: Vec3,
        cameraRotation: Matrix4f
    ) {
        val level = mc.level ?: return
        val player = mc.player ?: return

        fun maskAt(distToProj: Double): Float {
            if (distToProj >= MASK_RADIUS) return 0f
            if (distToProj <= MASK_FULL) return 1f
            val f = ((distToProj - MASK_FULL) / (MASK_RADIUS - MASK_FULL)).toFloat()
            return 1f - f * f * (3f - 2f * f)
        }

        fun pointFade(distToPoint: Double, radius: Double): Float {
            val t = ((radius - distToPoint) / radius).toFloat().coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun blendFade(p: Vec3, points: List<Vec3>, pr: Double): Float {
            var best = 0f
            for (c in points) {
                val f = pointFade(p.distanceTo(c), pr)
                if (f > best) best = f
            }
            return best
        }

        fun lineR(a: Vec3, b: Vec3, color: FloatArray, halfWidth: Float, proj: Vec3, points: List<Vec3>, pr: Double) {
            val steps = kotlin.math.ceil(a.distanceTo(b) / 0.5).toInt().coerceAtLeast(1)
            for (i in 0 until steps) {
                val p0 = a.add(b.subtract(a).scale(i.toDouble() / steps))
                val p1 = a.add(b.subtract(a).scale((i + 1).toDouble() / steps))
                val alpha = (
                    maskAt(p0.distanceTo(proj)) * blendFade(p0, points, pr) +
                        maskAt(p1.distanceTo(proj)) * blendFade(p1, points, pr)
                    ) * 0.5f * 0.85f
                if (alpha < 0.02f) continue
                WaterslideEditorRenderTypes.billboardStrip(
                    poseStack, consumer, cameraPos, cameraRotation,
                    p0, p1, halfWidth,
                    color[0], color[1], color[2], alpha
                )
            }
        }

        fun circleR(
            origin: Vec3, e1: Vec3, e2: Vec3, radius: Double,
            color: FloatArray, halfWidth: Float, proj: Vec3, points: List<Vec3>, pr: Double
        ) {
            var prev = origin.add(e1.scale(radius))
            for (i in 1..CIRCLE_SEGMENTS) {
                val ang = 2 * Math.PI * i / CIRCLE_SEGMENTS
                val p = origin.add(e1.scale(cos(ang) * radius).add(e2.scale(sin(ang) * radius)))
                lineR(prev, p, color, halfWidth, proj, points, pr)
                prev = p
            }
        }

        fun grid(
            origin: Vec3, e1: Vec3, e2: Vec3, polar: Boolean,
            proj: Vec3, points: List<Vec3>, currentRadius: Double?, pr: Double
        ) {
            val rel = proj.subtract(origin)
            val q1 = rel.dot(e1)
            val q2 = rel.dot(e2)
            val reach = MASK_RADIUS + GRID_STEP

            if (!polar) {
                var k = floor((q2 - reach) / GRID_STEP).toInt()
                while (k * GRID_STEP <= q2 + reach) {
                    val off = k * GRID_STEP
                    val isAxis = k == 0
                    val snapped = abs(off - q2) < GRID_STEP * 0.5f
                    val a = origin.add(e2.scale(off)).add(e1.scale(q1 - reach))
                    val b = origin.add(e2.scale(off)).add(e1.scale(q1 + reach))
                    lineR(a, b, gridColor, if (isAxis || snapped) 0.045f else 0.025f, proj, points, pr)
                    k++
                }
                k = floor((q1 - reach) / GRID_STEP).toInt()
                while (k * GRID_STEP <= q1 + reach) {
                    val off = k * GRID_STEP
                    val isAxis = k == 0
                    val snapped = abs(off - q1) < GRID_STEP * 0.5f
                    val a = origin.add(e1.scale(off)).add(e2.scale(q2 - reach))
                    val b = origin.add(e1.scale(off)).add(e2.scale(q2 + reach))
                    lineR(a, b, gridColor, if (isAxis || snapped) 0.045f else 0.025f, proj, points, pr)
                    k++
                }
            } else {
                val rProj = rel.length()
                var r = (floor((rProj - reach) / GRID_STEP) * GRID_STEP).coerceAtLeast(GRID_STEP)
                while (r <= rProj + reach) {
                    circleR(origin, e1, e2, r, gridColor, 0.025f, proj, points, pr)
                    r += GRID_STEP
                }
                val rays = 12
                val sector = 2 * Math.PI / rays
                val projAng = atan2(rel.dot(e2), rel.dot(e1))
                val first = floor((projAng - 1.2) / sector).toInt()
                for (i in first..first + rays) {
                    val ang = sector * i
                    val dir = e1.scale(cos(ang)).add(e2.scale(sin(ang)))
                    val r0 = (rProj - reach).coerceAtLeast(0.0)
                    lineR(
                        origin.add(dir.scale(r0)), origin.add(dir.scale(rProj + reach)),
                        gridColor, 0.02f, proj, points, pr
                    )
                }
                if (currentRadius != null && currentRadius > 0.05) {
                    circleR(origin, e1, e2, currentRadius, circleColor, 0.045f, proj, points, pr)
                }
            }
        }

        fun pointR(p: Vec3, color: FloatArray, point: Vec3, pr: Double) {
            val alpha = pointFade(p.distanceTo(point), pr) * 0.95f
            if (alpha < 0.02f) return
            WaterslideEditorRenderTypes.billboardStrip(
                poseStack, consumer, cameraPos, cameraRotation,
                p.subtract(Vec3(0.08, 0.0, 0.0)), p.add(Vec3(0.08, 0.0, 0.0)),
                0.08f, color[0], color[1], color[2], alpha
            )
            WaterslideEditorRenderTypes.billboardStrip(
                poseStack, consumer, cameraPos, cameraRotation,
                p.subtract(Vec3(0.0, 0.08, 0.0)), p.add(Vec3(0.0, 0.08, 0.0)),
                0.08f, color[0], color[1], color[2], alpha
            )
        }

        val eye = player.eyePosition
        val look = player.getViewVector(1f).normalize()

        fun projectionOn(planePoint: Vec3, normal: Vec3): Vec3? {
            val denom = look.dot(normal)
            if (abs(denom) < 1.0E-4) return null
            val t = planePoint.subtract(eye).dot(normal) / denom
            if (t < 0.0) return null
            val q = eye.add(look.scale(t))
            return if (q.distanceTo(planePoint) <= POINT_FADE_BLOCKS + MASK_RADIUS) q else null
        }

        pointMaskTarget = if (BrassPliersEditor.currentDrag() != null) POINT_FADE_BLOCKS else MASK_RADIUS
        pointMaskRadius += (pointMaskTarget - pointMaskRadius) * 0.06f

        val d = BrassPliersEditor.currentDrag()
        if (d != null) {
            val spaceAnchor = d.spaceAnchorRender()
            fun toRender(p: Vec3): Vec3 = CoasterAnchorClientSpace.toRenderWorld(level, spaceAnchor, p)
            val scale = subLevelScale(level, spaceAnchor)

            val e1p = basis1(d.axis)
            val e2p = d.axis.cross(e1p).normalize()
            val e1 = CoasterAnchorClientSpace.toRenderDirection(level, spaceAnchor, e1p).scale(scale)
            val e2 = CoasterAnchorClientSpace.toRenderDirection(level, spaceAnchor, e2p).scale(scale)
            val point = toRender(d.constrained)
            val axisR = CoasterAnchorClientSpace.toRenderDirection(level, spaceAnchor, d.axis)
            val proj = projectionOn(point, axisR) ?: toRender(d.constrained)
            val origin = toRender(d.origin)

            val currentR = if (d.polar) d.constrained.subtract(d.origin).length() * scale else null
            grid(origin, e1, e2, d.polar, proj, listOf(point), currentR, pointMaskRadius)

            for (n in d.snapPoints) pointR(toRender(n), snapColor, point, pointMaskRadius)
            d.activeSnap?.let { pointR(toRender(it), snapColor, point, pointMaskRadius) }
            pointR(toRender(d.constrained), circleColor, point, pointMaskRadius)
        } else {
            val polar = BrassPliersModes.align == PlierAlign.POLAR
            val hovers = BrassPliersEditor.currentHovers()
            val drawn = ArrayList<Pair<HoverTriple, Vec3>>()
            val tips = ArrayList<Vec3>()
            for (hover in hovers) {
                val anchor = hover.anchor
                val scale = subLevelScale(level, anchor)
                val axisP = BrassPliersModes.planeNormalPlot(level, anchor, hover.frame)
                val e1p = basis1(axisP)
                val e2p = axisP.cross(e1p).normalize()
                val e1 = CoasterAnchorClientSpace.toRenderDirection(level, anchor, e1p).scale(scale)
                val e2 = CoasterAnchorClientSpace.toRenderDirection(level, anchor, e2p).scale(scale)
                val axisR = CoasterAnchorClientSpace.toRenderDirection(level, anchor, axisP)
                val point = hover.point
                val proj = projectionOn(point, axisR) ?: continue
                drawn += (HoverTriple(point, e1, e2) to proj)
                tips += point
            }
            for ((t, proj) in drawn) {
                grid(t.origin, t.e1, t.e2, polar, proj, tips, null, pointMaskRadius)
            }
        }
    }

    private fun subLevelScale(level: net.minecraft.world.level.Level, anchor: net.minecraft.core.BlockPos): Double {
        val ctx = net.omori_sunny.create_waterparked.client.editor.SableClientEdit.resolve(level, anchor)
        val sub = ctx?.sub ?: return 1.0
        val s = sub.logicalPose().scale()
        return maxOf(s.x(), s.y(), s.z()).coerceAtLeast(0.1)
    }

    private fun basis1(axis: Vec3): Vec3 {
        val ref = if (abs(axis.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        return axis.cross(ref).normalize()
    }
}

private fun BrassPliersEditor.DragInfo.spaceAnchorRender(): net.minecraft.core.BlockPos =
    if (kind == BrassPliersEditor.Kind.TANGENT) endpoint else anchor
