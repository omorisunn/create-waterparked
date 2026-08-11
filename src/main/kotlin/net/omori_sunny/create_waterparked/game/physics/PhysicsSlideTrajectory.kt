package net.omori_sunny.create_waterparked.game.physics

import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.waterslide.PlacedSector
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin

// Free-flight 3D physics inside the slide tube.
object PhysicsSlideTrajectoryBuilder {

    private const val GRAVITY = 32.0
    private const val DT = 1.0 / 100.0
    private const val SAMPLE_INTERVAL = 1.0 / 20.0
    private const val MAX_TIME = 120.0
    private const val MAX_SAMPLES = 4096
    private const val MIN_WALL_DIST = 0.05

    private data class TubeFrame(
        val center: Vec3,
        val tangent: Vec3,
        val lateral: Vec3,
        val up: Vec3,
        val radius: Float,
        val config: WaterslideSectorConfig,
        val watered: Boolean
    )

    private class Tube {
        val frames = ArrayList<TubeFrame>()
        var cursor = 0
        var endIsOpen = false

        fun hit(pos: Vec3): TubeHit = tubeHit(this, pos)
    }

    private data class TubeHit(
        val center: Vec3,
        val tangent: Vec3,
        val lateral: Vec3,
        val up: Vec3,
        val radius: Float,
        val config: WaterslideSectorConfig,
        val watered: Boolean,
        val idx: Int,
        val t: Double,
        val atEnd: Boolean
    )

    private data class SegClosest(val t: Double, val distSq: Double)

    fun build(
        level: ServerLevel,
        entryCurve: BezierConnection,
        towardSecond: Boolean,
        startT: Float?,
        startPos: Vec3,
        startVel: Vec3,
        poseWidth: Double,
        poseHeight: Double
    ): SlideTrajectory? {
        val entryWatered = isCurveWatered(
            level, entryCurve.bePositions.getFirst(), entryCurve.bePositions.getSecond()
        )
        val tube = buildTube(level, entryCurve, towardSecond, startT) ?: return null
        if (tube.frames.size < 2) return null
        tube.cursor = nearestIndex(tube.frames, startPos)

        var pos = startPos
        var vel = startVel
        val samples = ArrayList<SlideSample>(256)
        var time = 0.0
        var lastSampleTime = 0.0
        var lastTraceTime = -1.0
        var lastProgress = 0.0
        var lastProgressTime = 0.0
        var endApproachTime = Double.MAX_VALUE
        var leftTube = false
        var landedOnSlide = false
        var slideCheckCounter = 0
        var endReason = if (tube.endIsOpen) SlideEndReason.EXITED else SlideEndReason.STOPPED
        var hit = tube.hit(pos)
        samples += SlideSample(
            0.0, pos, hit.center, safeTangent(vel, hit.tangent), hit.up, hit.radius, vel.length()
        )

        while (time < MAX_TIME && samples.size < MAX_SAMPLES) {
            vel = vel.add(0.0, -GRAVITY * DT, 0.0)
            val newPos = pos.add(vel.scale(DT))
            hit = tube.hit(newPos)

            val radial = newPos.subtract(hit.center)
            val axisDist = radial.length()
            val inner = max(0.1, hit.radius - SLIDE_WALL_THICKNESS)
            val wallDist = max(MIN_WALL_DIST, inner - poseWidth / 2.0)

            if (axisDist > wallDist) {
                val angle = angleDeg(radial, hit.lateral, hit.up)
                val sector = sectorAt(hit.config, angle)
                if (sector == null || sector.sector.material == SectorMaterial.OPEN || hit.atEnd) {
                    endReason = SlideEndReason.EXITED
                    if (hit.atEnd) vel = hit.tangent.scale(vel.length())
                    leftTube = true
                    break
                }
                val dir = radial.normalize()
                val radialVel = vel.dot(dir)
                if (radialVel > 0.0) vel = vel.subtract(dir.scale(radialVel))
                pos = hit.center.add(dir.scale(wallDist))
                val friction = if (hit.watered) ModConfig.slideWaterFriction() else sectorFriction(sector)
                val decay = (1.0 - friction * DT).coerceAtLeast(0.0)
                vel = vel.scale(decay)
            } else {
                pos = newPos
            }

            if (hit.atEnd) {
                endReason = SlideEndReason.EXITED
                vel = hit.tangent.scale(vel.length())
                leftTube = true
                break
            }

            time += DT
            val progress = hit.idx + hit.t
            if (progress > lastProgress + 0.05) {
                lastProgress = progress
                lastProgressTime = time
            } else if (time - lastProgressTime >= 2.0 && time > 1.0) {
                CreateWaterparked.LOGGER.info(
                    "SlideTrace stalled t={} pos={} vel={} speed={} idx={}",
                    time, pos, vel, vel.length(), hit.idx
                )
                endReason = SlideEndReason.EXITED
                if (hit.idx >= tube.frames.size - 3) {
                    val last = tube.frames.last()
                    vel = last.tangent.scale(vel.length())
                }
                leftTube = true
                break
            }
            if (time - lastTraceTime >= 1.0) {
                lastTraceTime = time
                CreateWaterparked.LOGGER.info(
                    "SlideTrace t={} pos={} vel={} speed={} idx={} atEnd={} frames={}",
                    time, pos, vel, vel.length(), hit.idx, hit.atEnd, tube.frames.size
                )
            }
            if (hit.idx >= tube.frames.size - 3) {
                if (endApproachTime == Double.MAX_VALUE) endApproachTime = time
                if (time - endApproachTime >= 3.0) {
                    CreateWaterparked.LOGGER.info(
                        "SlideTrace end-timeout t={} pos={} vel={} idx={}",
                        time, pos, vel, hit.idx
                    )
                    endReason = SlideEndReason.EXITED
                    vel = hit.tangent.scale(vel.length())
                    leftTube = true
                    break
                }
            } else {
                endApproachTime = Double.MAX_VALUE
            }
            if (time - lastSampleTime >= SAMPLE_INTERVAL - 1.0E-9) {
                val th = tube.hit(pos)
                samples += SlideSample(
                    time, pos, th.center, safeTangent(vel, th.tangent), th.up, th.radius, vel.length()
                )
                lastSampleTime = time
            }
        }

        val lastTubeCenter = hit.center
        val lastUp = hit.up
        val lastRadius = hit.radius
        if (leftTube) {
            while (time < MAX_TIME && samples.size < MAX_SAMPLES) {
                vel = vel.add(0.0, -GRAVITY * DT, 0.0)
                pos = pos.add(vel.scale(DT))
                time += DT
                if (++slideCheckCounter % 5 == 0 && hitsSlide(level, pos, tube)) {
                    landedOnSlide = true
                    break
                }
                if (hitsGround(level, pos, poseHeight)) {
                    val surfaceY = groundSurfaceY(level, pos)
                    if (surfaceY != null) pos = Vec3(pos.x, surfaceY, pos.z)
                    break
                }
                if (time - lastSampleTime >= SAMPLE_INTERVAL - 1.0E-9) {
                    samples += SlideSample(
                        time, pos, lastTubeCenter, safeTangent(vel, lastUp),
                        lastUp, lastRadius, vel.length(), false
                    )
                    lastSampleTime = time
                }
            }
        }

        if (samples.size < 2) return null
        val last = samples.last()
        if (last.time < time - 1.0E-9) {
            samples += SlideSample(
                time, pos, lastTubeCenter, safeTangent(vel, lastUp),
                lastUp, lastRadius, vel.length(), !leftTube
            )
        }
        CreateWaterparked.LOGGER.info(
            "SlideTrace done reason={} endIsOpen={} landed={} watered={} samples={} exitPos={} exitVel={}",
            endReason, tube.endIsOpen, landedOnSlide, entryWatered, samples.size, pos, vel
        )
        return SlideTrajectory(samples, endReason, tube.endIsOpen, vel, landedOnSlide)
    }

    private fun buildTube(
        level: ServerLevel,
        entryCurve: BezierConnection,
        towardSecond: Boolean,
        startT: Float?
    ): Tube? {
        val tube = Tube()
        var bc = entryCurve
        var atFirst = towardSecond
        var midStart = startT != null
        var first = true
        var guard = 0

        while (guard++ < 64) {
            val a = bc.bePositions.getFirst()
            val b = bc.bePositions.getSecond()
            val r0 = SlideCurveGeometry.radiusAt(level, a)
            val r1 = SlideCurveGeometry.radiusAt(level, b)
            val base = SlideCurveGeometry.sampleFrames(level, bc, r0, r1)
            if (base.isEmpty()) break

            val walkFrames: List<SlideCurveGeometry.Frame>
            if (first && midStart) {
                val startIdx = nearestFrameIndex(base, bc.getPosition(startT!!.toDouble()))
                walkFrames = if (atFirst) base.subList(startIdx, base.size)
                else SlideCurveGeometry.reversed(base.subList(0, startIdx + 1))
            } else {
                val ordered = if (atFirst) base else SlideCurveGeometry.reversed(base)
                val startAnchorOpenEnd = first &&
                    (level.getBlockEntity(if (atFirst) a else b) as? CoasterAnchorpointBlockEntity)?.legCount() == 1
                val startIdx = if (startAnchorOpenEnd && ordered.size > 1) 1 else 0
                walkFrames = ordered.subList(startIdx, ordered.size)
            }
            if (walkFrames.isEmpty()) break

            val config = SlideCurveGeometry.sectorConfig(level, a, b)
                ?: WaterslideSectorConfig.defaultConfig()
            val watered = isCurveWatered(level, a, b)
            for (f in walkFrames) pushFrame(tube.frames, f, config, watered)

            first = false
            midStart = false

            val exitPos = if (atFirst) b else a
            val exitBe = level.getBlockEntity(exitPos) as? WaterslideAnchorBlockEntity ?: break
            if (exitBe.legCount() <= 1) {
                tube.endIsOpen = true
                break
            }

            val next = exitBe.anchorPeerCurvesView.values
                .asSequence()
                .mapNotNull { if (it.isPrimary) it else it.secondary() }
                .firstOrNull { !sameEdge(it, bc) && touches(it, exitPos) }
                ?: break
            bc = next
            atFirst = next.bePositions.getFirst() == exitPos
        }

        return if (tube.frames.isEmpty()) null else tube
    }

    private fun pushFrame(
        frames: MutableList<TubeFrame>,
        f: SlideCurveGeometry.Frame,
        config: WaterslideSectorConfig,
        watered: Boolean
    ) {
        val last = frames.lastOrNull()
        if (last != null && last.center.distanceToSqr(f.center) < 1.0E-6) {
            val tan = last.tangent.add(f.tangent)
            val tanN = if (tan.lengthSqr() < 1.0E-9) last.tangent else tan.normalize()
            val up = last.up.add(f.up)
            val upN = if (up.lengthSqr() < 1.0E-9) last.up else up.normalize()
            val lat = upN.cross(tanN).normalize()
            frames[frames.size - 1] = TubeFrame(
                last.center, tanN, lat, upN, max(last.radius, f.radius), config, watered
            )
        } else {
            frames += TubeFrame(f.center, f.tangent, f.lateral, f.up, f.radius, config, watered)
        }
    }

    private fun tubeHit(tube: Tube, pos: Vec3): TubeHit {
        val frames = tube.frames
        var idx = tube.cursor.coerceIn(0, frames.size - 2)

        while (idx < frames.size - 2) {
            val cur = closestOnSegment(frames[idx], frames[idx + 1], pos)
            val nxt = closestOnSegment(frames[idx + 1], frames[idx + 2], pos)
            if (nxt.distSq < cur.distSq) idx++ else break
        }
        if (idx > 0) {
            val cur = closestOnSegment(frames[idx], frames[idx + 1], pos)
            val prev = closestOnSegment(frames[idx - 1], frames[idx], pos)
            if (prev.distSq < cur.distSq) idx--
        }
        tube.cursor = idx
        if (idx >= frames.size - 3 && pos.distanceToSqr(frames.last().center) <= 2.25) {
            idx = frames.size - 2
        }

        val fa = frames[idx]
        val fb = frames[idx + 1]
        val seg = closestOnSegment(fa, fb, pos)
        val t = seg.t
        val center = fa.center.lerp(fb.center, t)
        val tan = slerpUnit(fa.tangent, fb.tangent, t)
        val lat = slerpUnit(fa.lateral, fb.lateral, t)
        val upB = if (fa.up.dot(fb.up) < 0.0) fb.up.scale(-1.0) else fb.up
        val up = slerpUnit(fa.up, upB, t)
        val radius = fa.radius + (fb.radius - fa.radius) * t.toFloat()
        val cfg = if (t < 0.5) fa.config else fb.config
        val atEnd = idx >= frames.size - 2 && t > 0.5
        return TubeHit(center, tan, lat, up, radius, cfg, fa.watered, idx, t, atEnd)
    }

    private fun closestOnSegment(a: TubeFrame, b: TubeFrame, pos: Vec3): SegClosest {
        val ab = b.center.subtract(a.center)
        val lenSq = ab.lengthSqr()
        val t = if (lenSq < 1.0E-12) 0.0
        else ((pos.subtract(a.center)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
        val point = a.center.add(ab.scale(t))
        return SegClosest(t, pos.distanceToSqr(point))
    }

    private fun nearestIndex(frames: List<TubeFrame>, pos: Vec3): Int {
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (i in frames.indices) {
            val d = frames[i].center.distanceToSqr(pos)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best.coerceAtMost(frames.size - 2)
    }

    private fun nearestFrameIndex(frames: List<SlideCurveGeometry.Frame>, target: Vec3): Int {
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (i in frames.indices) {
            val d = frames[i].center.distanceToSqr(target)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun isCurveWatered(level: ServerLevel, a: BlockPos, b: BlockPos): Boolean {
        val beA = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity
        val beB = level.getBlockEntity(b) as? WaterslideAnchorBlockEntity
        return (beA != null && beA.isCurveWatered(b)) || (beB != null && beB.isCurveWatered(a))
    }

    private fun sameEdge(x: BezierConnection, y: BezierConnection): Boolean {
        val xa = x.bePositions.getFirst().asLong()
        val xb = x.bePositions.getSecond().asLong()
        val ya = y.bePositions.getFirst().asLong()
        val yb = y.bePositions.getSecond().asLong()
        return (xa == ya && xb == yb) || (xa == yb && xb == ya)
    }

    private fun touches(bc: BezierConnection, pos: BlockPos): Boolean =
        bc.bePositions.getFirst() == pos || bc.bePositions.getSecond() == pos

    private fun safeTangent(vel: Vec3, fallback: Vec3): Vec3 {
        if (vel.lengthSqr() < 1.0E-12) return fallback.normalize()
        return vel.normalize()
    }

    private fun angleDeg(radial: Vec3, lat: Vec3, up: Vec3): Float =
        Math.toDegrees(atan2(radial.dot(up), radial.dot(lat))).toFloat()

    private fun sectorAt(config: WaterslideSectorConfig, angle: Float): PlacedSector? =
        WaterslideSectorLayout.sectorAt(WaterslideSectorLayout.place(config), angle)

    private fun sectorFriction(sector: PlacedSector): Double {
        val blockId = sector.sector.blockId ?: return 0.0
        val block = BuiltInRegistries.BLOCK.get(blockId) ?: return 0.0
        return PhysicsBlockPropertyHelper.getFriction(block.defaultBlockState())
    }

    private fun hitsGround(level: ServerLevel, pos: Vec3, height: Double): Boolean {
        val feet = BlockPos.containing(pos.x, pos.y - 0.01, pos.z)
        val body = BlockPos.containing(pos.x, pos.y + height * 0.7, pos.z)
        return solidBlock(level, feet) || solidBlock(level, body)
    }

    private fun solidBlock(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !state.getCollisionShape(level, pos).isEmpty()
    }

    private fun groundSurfaceY(level: ServerLevel, pos: Vec3): Double? {
        val blockX = Mth.floor(pos.x)
        val blockZ = Mth.floor(pos.z)
        for (y in Mth.floor(pos.y - 0.01) downTo Mth.floor(pos.y - 3.0)) {
            val blockPos = BlockPos(blockX, y, blockZ)
            val state = level.getBlockState(blockPos)
            val shape = state.getCollisionShape(level, blockPos)
            if (shape.isEmpty()) continue
            return y + shape.max(Direction.Axis.Y) + 0.01
        }
        return null
    }

    private fun hitsSlide(level: ServerLevel, pos: Vec3, currentTube: Tube): Boolean {
        val exitFrames = currentTube.frames.takeLast(4)
        for (frame in exitFrames) {
            val radius = frame.radius.toDouble() + 1.0
            if (pos.distanceToSqr(frame.center) < radius * radius) return false
        }
        for (anchorPos in SlideAnchorIndex.all(level)) {
            val be = level.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity ?: continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                if (!bc.getBounds().inflate(3.0).contains(pos)) continue
                val r0 = SlideCurveGeometry.radiusAt(level, bc.bePositions.getFirst())
                val r1 = SlideCurveGeometry.radiusAt(level, bc.bePositions.getSecond())
                val frames = SlideCurveGeometry.sampleFrames(level, bc, r0, r1)
                for (i in 0 until frames.size - 1) {
                    val a = frames[i].center
                    val b = frames[i + 1].center
                    val t = segmentT(a, b, pos)
                    val closest = a.lerp(b, t)
                    val radius = max(frames[i].radius, frames[i + 1].radius).toDouble() - 0.25
                    if (pos.distanceToSqr(closest) < radius * radius) return true
                }
            }
        }
        return false
    }

    private fun segmentT(a: Vec3, b: Vec3, pos: Vec3): Double {
        val ab = b.subtract(a)
        val lenSq = ab.lengthSqr()
        if (lenSq < 1.0E-12) return 0.0
        return ((pos.subtract(a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
    }

    private fun slerpUnit(a: Vec3, b: Vec3, f: Double): Vec3 {
        if (a.lengthSqr() < 1.0E-12) return if (b.lengthSqr() < 1.0E-12) Vec3(0.0, 1.0, 0.0) else b.normalize()
        if (b.lengthSqr() < 1.0E-12) return a.normalize()
        val dot = (a.dot(b) / (a.length() * b.length())).coerceIn(-1.0, 1.0)
        val omega = kotlin.math.acos(dot)
        if (omega < 1.0E-6) return a.normalize()
        val sinOmega = sin(omega)
        if (sinOmega < 1.0E-6) return a.normalize()
        val wa = sin((1.0 - f) * omega) / sinOmega
        val wb = sin(f * omega) / sinOmega
        return a.scale(wa).add(b.scale(wb)).normalize()
    }
}
