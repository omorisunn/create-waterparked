package net.omori_sunny.create_waterparked.game.physics

import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
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
import net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin

// Free-flight 3D physics inside the slide tube.
object PhysicsSlideTrajectoryBuilder {

    private const val GRAVITY = 32.0
    private const val DT = 1.0 / 100.0
    private const val SAMPLE_INTERVAL = 1.0 / 20.0
    // in-tube cap; the free fall keeps going until a solid block or a slide
    private const val TUBE_MAX_TIME = 120.0
    private const val MAX_TIME = 300.0
    private const val MIN_WALL_DIST = 0.05
    // cooldown (in seconds, 5 ticks) against immediately re-entering the tube
    // the player just left through its mouth
    private const val SELF_REENTRY_COOLDOWN = 0.25

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
        val curves = HashSet<BezierConnection>()
        var cursor = 0

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

    private data class ReentrySegment(val a: Vec3, val b: Vec3, val radius: Double, val curve: BezierConnection)

    private data class ReentryStart(
        val curve: BezierConnection,
        val towardSecond: Boolean,
        val startT: Float?,
        val pos: Vec3,
        val vel: Vec3,
        val center: Vec3,
        val tangent: Vec3,
        val lateral: Vec3,
        val up: Vec3,
        val radius: Float
    )

    fun build(
        access: SlideSpaceAccess,
        entryCurve: BezierConnection,
        towardSecond: Boolean,
        startT: Float?,
        startPos: Vec3,
        startVel: Vec3,
        poseWidth: Double,
        poseHeight: Double
    ): SlideTrajectory? {
        val maxSamples = ModConfig.slideMaxTrajectorySamples()
        val maxLength = ModConfig.slideMaxTrajectoryBlocks()
        var tube = buildTube(access, entryCurve, towardSecond, startT) ?: return null
        if (tube.frames.size < 2) return null
        tube.cursor = nearestIndex(tube.frames, startPos)

        var pos = startPos
        var vel = startVel
        val samples = ArrayList<SlideSample>(256)
        var time = 0.0
        var totalLength = 0.0
        var lastSampleTime = 0.0
        var limitHit = false
        var hardStop = false
        var inTubeState = true
        var tubeCenter = tube.frames.first().center
        var tubeUp = tube.frames.first().up
        var tubeRadius = tube.frames.first().radius
        // slides from every OTHER coordinate space, in world coordinates;
        // contact with one of these ends the trajectory (re-entering across
        // Sable poses would require per-sample spaces in the payloads)
        val worldSlideGrid = buildWorldSlideGrid(access)

        var hit = tube.hit(pos)
        fun clampBody(p: Vec3, h: TubeHit): Vec3 {
            val inner = max(0.1, h.radius - SLIDE_WALL_THICKNESS)
            val head = p.add(h.up.scale(poseHeight))
            val headRadial = head.subtract(h.center)
            val headDist = headRadial.length()
            val headLimit = max(MIN_WALL_DIST, inner - poseWidth / 2.0 - 0.02)
            if (headDist > headLimit) {
                return p.subtract(headRadial.normalize().scale(headDist - headLimit))
            }
            return p
        }
        pos = clampBody(pos, hit)
        samples += SlideSample(
            0.0, pos, hit.center, safeTangent(vel, hit.tangent), hit.up, hit.radius, vel.length(),
            true, hit.watered
        )

        outer@ while (true) {
            // ================= in-tube segment =================
            val segStart = time
            var leftTube = false
            var lastTraceTime = -1.0
            var lastProgress = 0.0
            // Start the stall clock at THIS segment's start. It was 0.0, so any
            // mid-ride re-entry (overall time > 2s) could be judged "stalled"
            // on the very first physics step - before the wall reflection had
            // any chance to turn the incoming fall velocity along the tube.
            // The rider was then ejected again, immediately re-entered, and the
            // camera roll chattered through the whole throw-and-catch sequence.
            var lastProgressTime = segStart
            var endApproachTime = Double.MAX_VALUE
            var prevPos = pos
            var step = 0
            while (time - segStart < TUBE_MAX_TIME && samples.size < maxSamples && !limitHit) {
                vel = vel.add(access.localGravity().scale(DT))
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
                        if (hit.atEnd) vel = hit.tangent.scale(vel.length())
                        leftTube = true
                        break
                    }
                    val dir = radial.normalize()
                    val radialVel = vel.dot(dir)
                    if (radialVel > 0.0) {
                        // reflect the outward velocity off the wall, keep the full speed
                        val speed = vel.length()
                        vel = vel.subtract(dir.scale(radialVel))
                        val after = vel.length()
                        if (after > 1.0E-9) {
                            vel = vel.scale(speed / after)
                        } else {
                            // fully radial hit: slide along the tube tangent instead
                            // of zeroing the velocity (keeps the horizontal component)
                            vel = hit.tangent.scale(speed)
                        }
                    }
                    pos = hit.center.add(dir.scale(wallDist))
                    // watered tubes apply water friction on every wall contact
                    if (hit.watered) {
                        vel = vel.scale((1.0 - ModConfig.slideWaterFriction()).coerceAtLeast(0.0))
                    }
                } else {
                    pos = newPos
                }

                // keep the head inside the tube so the camera never leaves the wall
                pos = clampBody(pos, hit)
                totalLength += pos.distanceTo(prevPos)
                prevPos = pos
                if (totalLength > maxLength) {
                    limitHit = true
                    break
                }

                // a real block (in any Sable space) overlapping the rider is
                // an instant hard stop; check every 5 physics steps
                if (++step % 5 == 0 &&
                    worldBlocksCollide(access.level, access.toWorld(pos), poseWidth, poseHeight)
                ) {
                    hardStop = true
                    CreateWaterparked.LOGGER.info(
                        "[FallDiag] in-tube block hard stop t={} local={} world={}",
                        time, pos, access.toWorld(pos)
                    )
                    break
                }

                if (hit.atEnd) {
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
                        time, pos, th.center, safeTangent(vel, th.tangent), th.up, th.radius, vel.length(),
                        true, th.watered
                    )
                    lastSampleTime = time
                }
            }
            if (samples.size >= maxSamples) break
            if (hardStop) break

            tubeCenter = hit.center
            tubeUp = hit.up
            tubeRadius = hit.radius
            inTubeState = false

            if (!leftTube) {
                // stalled inside the tube (or the length limit cut us off): push
                // out through the wall and fall until a real block or another slide
                val radial = pos.subtract(hit.center)
                val dir = if (radial.lengthSqr() < 1.0E-9) hit.lateral else radial.normalize()
                pos = hit.center.add(dir.scale(hit.radius + 0.5))
                vel = hit.tangent.scale(max(vel.length(), 1.0))
            }

            // short cooldown against re-entering the pipe we just left, but only
            // when leaving through the mouth; the player must actually fly off
            // the tube axis before the same tube can catch them again
            val exitAtMouth = leftTube && hit.atEnd
            val selfCurves = if (exitAtMouth) tube.curves.toSet() else null
            val noSelfUntil = if (selfCurves != null) time + SELF_REENTRY_COOLDOWN
            else Double.NEGATIVE_INFINITY

            // ================= free fall segment =================
            val fallStart = time
            val grid = ReentryGrid()
            for (s in allReentrySegments(access)) grid.add(s)
            val fallSampleInterval = SAMPLE_INTERVAL * 4
            var check = 0
            // a reentry into the pipe we just left is only accepted after the
            // player has fully left every slide grid, so a slow exit cannot
            // bounce straight back into the mouth they flew out of; other
            // slides catch the player immediately
            var wasClear = false
            var prevLocal = pos
            var lastWorldLog = -1.0
            while (time - fallStart < MAX_TIME && samples.size < maxSamples) {
                prevLocal = pos
                vel = vel.add(access.localGravity().scale(DT))
                pos = pos.add(vel.scale(DT))
                totalLength += vel.length() * DT
                if (totalLength > maxLength) {
                    limitHit = true
                    break
                }
                time += DT
                // real blocks and slides from other Sable spaces are checked
                // in world coordinates
                val worldPos = access.toWorld(pos)
                val collided = worldBlocksCollide(access.level, worldPos, poseWidth, poseHeight)
                if (time - lastWorldLog >= 0.5) {
                    lastWorldLog = time
                    val feet = BlockPos.containing(
                        worldPos.x, worldPos.y - poseHeight / 2.0 - 0.01, worldPos.z
                    )
                    CreateWaterparked.LOGGER.info(
                        "[FallDiag] t={} local={} world={} collide={} feet={} state={} localGround={}",
                        time, pos, worldPos, collided, feet, access.level.getBlockState(feet),
                        hitsGround(access, pos, poseHeight)
                    )
                }
                if (collided) {
                    pos = prevLocal
                    hardStop = true
                    CreateWaterparked.LOGGER.info(
                        "[FallDiag] main-world block hard stop t={} world={} prevLocal={}",
                        time, worldPos, prevLocal
                    )
                    break
                }
                if (worldSlideGrid != null && worldSlideGrid.hit(worldPos) != null) {
                    pos = prevLocal
                    hardStop = true
                    CreateWaterparked.LOGGER.info(
                        "[FallDiag] other-space slide hard stop t={} world={}",
                        time, worldPos
                    )
                    break
                }
                // landing wins over reentry so a pipe mouth sitting on the
                // ground cannot pull the player back after they touched down
                if (hitsGround(access, pos, poseHeight)) {
                    val surfaceY = groundSurfaceY(access, pos, poseHeight)
                    if (surfaceY != null) pos = Vec3(pos.x, surfaceY, pos.z)
                    CreateWaterparked.LOGGER.info(
                        "[FallDiag] local-space ground end t={} local={} world={} surfaceY={}",
                        time, pos, access.toWorld(pos), surfaceY
                    )
                    break
                }
                if (pos.y < access.level.minBuildHeight - 10) {
                    // fell out of the world: end like a normal block contact
                    break
                }
                if (++check % 2 == 0) {
                    val seg = grid.hit(pos)
                    if (seg != null) {
                        val isSelf = selfCurves != null && seg.curve in selfCurves
                        val selfBlocked = isSelf && (time < noSelfUntil || !wasClear)
                        if (!selfBlocked) {
                            val start = reentryStart(access, seg, pos, vel, poseWidth)
                            if (start != null) {
                                samples += SlideSample(
                                    time, start.pos, start.center, safeTangent(start.vel, start.tangent),
                                    start.up, start.radius, start.vel.length(), true,
                                    ServerWaterSimulation.field(
                                        access.level,
                                        start.curve.bePositions.getFirst(),
                                        start.curve.bePositions.getSecond()
                                    )?.segments?.isNotEmpty() == true
                                )
                                val nextTube = buildTube(access, start.curve, start.towardSecond, start.startT)
                                if (nextTube == null || nextTube.frames.size < 2) break
                                nextTube.cursor = nearestIndex(nextTube.frames, start.pos)
                                tube = nextTube
                                pos = start.pos
                                vel = start.vel
                                inTubeState = true
                                continue@outer
                            }
                        }
                    } else {
                        wasClear = true
                    }
                }
                if (time - lastSampleTime >= fallSampleInterval - 1.0E-9) {
                    samples += SlideSample(
                        time, pos, tubeCenter, safeTangent(vel, tubeUp),
                        tubeUp, tubeRadius, vel.length(), false, false
                    )
                    lastSampleTime = time
                }
            }
            break
        }

        if (samples.size < 2) return null
        val last = samples.last()
        if (last.time < time - 1.0E-9) {
            samples += SlideSample(
                time, pos, tubeCenter, safeTangent(vel, tubeUp),
                tubeUp, tubeRadius, vel.length(), inTubeState, inTubeState && hit.watered
            )
        }
        CreateWaterparked.LOGGER.info(
            "SlideTrace done samples={} length={} exitPos={} exitVel={} limitHit={}",
            samples.size, totalLength, pos, vel, limitHit
        )
        return SlideTrajectory(samples, SlideEndReason.EXITED, false, vel, false)
    }

    private fun buildTube(
        access: SlideSpaceAccess,
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
            tube.curves.add(bc)
            val r0 = SlideCurveGeometry.radiusAt(access.level, a)
            val r1 = SlideCurveGeometry.radiusAt(access.level, b)
            val base = cachedFrames(access, bc, r0, r1)
            if (base.isEmpty()) break

            val walkFrames: List<SlideCurveGeometry.Frame>
            if (first && midStart) {
                val startIdx = nearestFrameIndex(base, bc.getPosition(startT!!.toDouble()))
                walkFrames = if (atFirst) base.subList(startIdx, base.size)
                else SlideCurveGeometry.reversed(base.subList(0, startIdx + 1))
            } else {
                val ordered = if (atFirst) base else SlideCurveGeometry.reversed(base)
                val startAnchorOpenEnd = first &&
                    (access.getBlockEntity(if (atFirst) a else b) as? CoasterAnchorpointBlockEntity)?.legCount() == 1
                val startIdx = if (startAnchorOpenEnd && ordered.size > 1) 1 else 0
                walkFrames = ordered.subList(startIdx, ordered.size)
            }
            if (walkFrames.isEmpty()) break

            val config = SlideCurveGeometry.sectorConfig(access.level, a, b)
                ?: WaterslideSectorConfig.defaultConfig()
            val watered = isCurveWatered(access, a, b)
            for (f in walkFrames) pushFrame(tube.frames, f, config, watered)

            first = false
            midStart = false

            val exitPos = if (atFirst) b else a
            val exitBe = access.getBlockEntity(exitPos) as? WaterslideAnchorBlockEntity ?: break
            if (exitBe.legCount() <= 1) break

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

    private fun isCurveWatered(access: SlideSpaceAccess, a: BlockPos, b: BlockPos): Boolean =
        !ServerWaterSimulation.field(access.level, a, b)?.segments.isNullOrEmpty()

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

    private fun hitsGround(access: SlideSpaceAccess, pos: Vec3, height: Double): Boolean {
        val feet = BlockPos.containing(pos.x, pos.y - height / 2.0 - 0.01, pos.z)
        return solidBlock(access, feet)
    }

    private fun solidBlock(access: SlideSpaceAccess, pos: BlockPos): Boolean {
        val state = access.getBlockState(pos)
        return !state.getCollisionShape(access.level, pos).isEmpty()
    }

    private fun groundSurfaceY(access: SlideSpaceAccess, pos: Vec3, height: Double): Double? {
        val blockX = Mth.floor(pos.x)
        val blockZ = Mth.floor(pos.z)
        val feetY = pos.y - height / 2.0
        for (y in Mth.floor(feetY - 0.01) downTo Mth.floor(feetY - 3.0)) {
            val blockPos = BlockPos(blockX, y, blockZ)
            val state = access.getBlockState(blockPos)
            val shape = state.getCollisionShape(access.level, blockPos)
            if (shape.isEmpty()) continue
            return y + shape.max(Direction.Axis.Y) + 0.01 + height / 2.0
        }
        return null
    }

    // AABB collision against the parent level's real block shapes at a world
    // position. Works for trajectories in main space AND for trajectories
    // computed inside a Sable sub-level, because the sub-level pose projects
    // the local position back into world space first.
    // Trajectory `pos` is the body CENTER (hitsGround/groundSurfaceY use
    // pos.y - height/2), so the box must span [pos.y - height/2, pos.y + height/2].
    fun worldBlocksCollide(level: ServerLevel, pos: Vec3, width: Double, height: Double): Boolean {
        val halfH = height / 2.0
        val box = AABB(
            pos.x - width / 2.0, pos.y - halfH, pos.z - width / 2.0,
            pos.x + width / 2.0, pos.y + halfH, pos.z + width / 2.0
        ).inflate(0.05)
        val minX = Mth.floor(box.minX)
        val minY = Mth.floor(box.minY)
        val minZ = Mth.floor(box.minZ)
        val maxX = Mth.floor(box.maxX)
        val maxY = Mth.floor(box.maxY)
        val maxZ = Mth.floor(box.maxZ)
        // precomputed trajectories can travel far ahead of the player, so make
        // sure every chunk along the world path is actually loaded before we
        // ask for block states (otherwise an unloaded chunk reads as air and
        // the rider falls through main-world terrain)
        for (cx in (minX shr 4)..(maxX shr 4)) {
            for (cz in (minZ shr 4)..(maxZ shr 4)) {
                level.getChunk(cx, cz)
            }
        }
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val bp = BlockPos(x, y, z)
                    val state = level.getBlockState(bp)
                    if (state.isAir) continue
                    val shape = state.getCollisionShape(level, bp)
                    if (shape.isEmpty) continue
                    // toAabbs() returns LOCAL [0..1] boxes; move them to the
                    // block position before testing against the world AABB
                    for (aabb in shape.toAabbs()) {
                        if (aabb.move(bp).intersects(box)) return true
                    }
                }
            }
        }
        return false
    }

    // World-space tube segments of every slide space other than the one the
    // trajectory currently runs in. Used to detect "ran into another slide"
    // during free fall.
    private data class WorldTubeSeg(val a: Vec3, val b: Vec3, val radius: Double)

    private fun buildWorldSlideGrid(access: SlideSpaceAccess): WorldSlideGrid? {
        val level = access.level
        val out = ArrayList<WorldTubeSeg>()

        fun addSpace(spaceAccess: SlideSpaceAccess, toWorld: (Vec3) -> Vec3, radiusScale: Double) {
            for (anchorPos in SlideAnchorIndex.all(level, spaceAccess.space)) {
                val be = spaceAccess.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity ?: continue
                for (raw in be.anchorPeerCurvesView.values) {
                    val bc = if (raw.isPrimary) raw else raw.secondary()
                    if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                    val a = bc.bePositions.getFirst()
                    val b = bc.bePositions.getSecond()
                    val r0 = SlideCurveGeometry.radiusAt(level, a)
                    val r1 = SlideCurveGeometry.radiusAt(level, b)
                    val frames = cachedFrames(spaceAccess, bc, r0, r1)
                    for (i in 0 until frames.size - 1) {
                        val fa = frames[i]
                        val fb = frames[i + 1]
                        out += WorldTubeSeg(
                            toWorld(fa.center), toWorld(fb.center),
                            (max(fa.radius, fb.radius).toDouble() + 0.25) * radiusScale
                        )
                    }
                }
            }
        }

        if (access.space != SlideSpace.Main) {
            val main = MainSlideSpaceAccess(level)
            addSpace(main, { it }, 1.0)
        }
        val container = SubLevelContainer.getContainer(level)
        container?.allSubLevels?.forEach { raw ->
            val sub = raw as? ServerSubLevel ?: return@forEach
            if (access.space == SlideSpace.SubLevel(sub.uniqueId)) return@forEach
            val subAccess = SubSlideSpaceAccess(level, sub)
            val scale = sub.logicalPose().scale()
            val s = maxOf(scale.x(), scale.y(), scale.z()).coerceAtLeast(0.1)
            addSpace(subAccess, { subAccess.toWorld(it) }, s.toDouble())
        }
        if (out.isEmpty()) return null
        val grid = WorldSlideGrid()
        for (seg in out) grid.add(seg)
        return grid
    }

    private class WorldSlideGrid {
        private val buckets = HashMap<Long, MutableList<WorldTubeSeg>>()

        fun add(seg: WorldTubeSeg) {
            val r = seg.radius + 0.5
            val minX = Mth.floor((minOf(seg.a.x, seg.b.x) - r) / GRID)
            val maxX = Mth.floor((maxOf(seg.a.x, seg.b.x) + r) / GRID)
            val minY = Mth.floor((minOf(seg.a.y, seg.b.y) - r) / GRID)
            val maxY = Mth.floor((maxOf(seg.a.y, seg.b.y) + r) / GRID)
            val minZ = Mth.floor((minOf(seg.a.z, seg.b.z) - r) / GRID)
            val maxZ = Mth.floor((maxOf(seg.a.z, seg.b.z) + r) / GRID)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        buckets.getOrPut(key(x, y, z)) { ArrayList() } += seg
                    }
                }
            }
        }

        fun hit(pos: Vec3): WorldTubeSeg? {
            val cx = Mth.floor(pos.x / GRID)
            val cy = Mth.floor(pos.y / GRID)
            val cz = Mth.floor(pos.z / GRID)
            var best: WorldTubeSeg? = null
            var bestDist = Double.MAX_VALUE
            for (x in cx - 1..cx + 1) {
                for (y in cy - 1..cy + 1) {
                    for (z in cz - 1..cz + 1) {
                        val list = buckets[key(x, y, z)] ?: continue
                        for (s in list) {
                            val ab = s.b.subtract(s.a)
                            val lenSq = ab.lengthSqr()
                            if (lenSq < 1.0E-12) continue
                            val t = ((pos.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                            val closest = s.a.lerp(s.b, t)
                            val d = pos.distanceToSqr(closest)
                            if (d < s.radius * s.radius && d < bestDist) {
                                bestDist = d
                                best = s
                            }
                        }
                    }
                }
            }
            return best
        }

        companion object {
            const val GRID = 8.0

            fun key(x: Int, y: Int, z: Int): Long =
                ((x.toLong() and 0x1FFFFF) shl 42) or
                    ((y.toLong() and 0x1FFFFF) shl 21) or
                    (z.toLong() and 0x1FFFFF)
        }
    }

    // Keyed per coordinate space: sub-level and main-world trajectories share
    // the same ServerLevel dimension but must never share segment lists.
    private val reentryCache =
        HashMap<Pair<ResourceKey<Level>, SlideSpace>, Pair<String, List<ReentrySegment>>>()

    private fun allReentrySegments(access: SlideSpaceAccess): List<ReentrySegment> {
        val cacheKey = access.level.dimension() to access.space
        val sig = structureSignature(access)
        reentryCache[cacheKey]?.let { if (it.first == sig) return it.second }
        val out = ArrayList<ReentrySegment>()
        for (anchorPos in SlideAnchorIndex.all(access.level, access.space)) {
            val be = access.getBlockEntity(anchorPos) as? WaterslideAnchorBlockEntity ?: continue
            for (raw in be.anchorPeerCurvesView.values) {
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                val r0 = SlideCurveGeometry.radiusAt(access.level, a)
                val r1 = SlideCurveGeometry.radiusAt(access.level, b)
                val frames = cachedFrames(access, bc, r0, r1)
                for (i in 0 until frames.size - 1) {
                    val fa = frames[i]
                    val fb = frames[i + 1]
                    out += ReentrySegment(
                        fa.center, fb.center,
                        max(fa.radius, fb.radius).toDouble() + 0.25,
                        bc
                    )
                }
            }
        }
        reentryCache[cacheKey] = sig to out
        return out
    }

    private fun structureSignature(access: SlideSpaceAccess): String {
        val sb = StringBuilder()
        sb.append(access.space.cacheKey(access.level)).append('|')
        for (pos in SlideAnchorIndex.all(access.level).sortedBy { it.asLong() }) {
            val be = access.getBlockEntity(pos) as? WaterslideAnchorBlockEntity ?: continue
            sb.append(pos.asLong()).append('|')
            for (e in be.anchorPeerCurvesView.entries.sortedBy { it.key.asLong() }) {
                val raw = e.value
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                val a = bc.bePositions.getFirst()
                val b = bc.bePositions.getSecond()
                sb.append(a.asLong()).append(',').append(b.asLong()).append(',')
                    .append(bc.getSegmentCount()).append(',')
                    .append(bc.starts.getFirst().x).append(',')
                    .append(bc.starts.getFirst().y).append(',')
                    .append(bc.starts.getFirst().z).append(',')
                    .append(bc.starts.getSecond().x).append(',')
                    .append(bc.starts.getSecond().y).append(',')
                    .append(bc.starts.getSecond().z).append(',')
                    .append(SlideCurveGeometry.radiusAt(access.level, a)).append(',')
                    .append(SlideCurveGeometry.radiusAt(access.level, b)).append(';')
            }
        }
        return sb.toString()
    }

    private val frameCache = HashMap<Pair<Long, Long>, Pair<String, List<SlideCurveGeometry.Frame>>>()

    private fun cachedFrames(
        access: SlideSpaceAccess,
        bc: BezierConnection,
        r0: Float,
        r1: Float
    ): List<SlideCurveGeometry.Frame> {
        val a = bc.bePositions.getFirst()
        val b = bc.bePositions.getSecond()
        val key = if (a.asLong() <= b.asLong()) a.asLong() to b.asLong()
        else b.asLong() to a.asLong()
        val h0 = bc.starts.getFirst()
        val h1 = bc.starts.getSecond()
        val sig = "${access.space.cacheKey(access.level)}|$r0,$r1,${h0.x},${h0.y},${h0.z},${h1.x},${h1.y},${h1.z},${bc.getSegmentCount()}"
        frameCache[key]?.let { if (it.first == sig) return it.second }
        val frames = SlideCurveGeometry.sampleFrames(access.level, bc, r0, r1)
        if (frameCache.size > 1024) frameCache.clear()
        frameCache[key] = sig to frames
        return frames
    }

    // Turn a free-fall contact with another slide tube into an entry state:
    // locate the curve, pick the slide direction from the fall velocity, and
    // project the player back inside the tube.
    private fun reentryStart(
        access: SlideSpaceAccess,
        seg: ReentrySegment,
        pos: Vec3,
        vel: Vec3,
        poseWidth: Double
    ): ReentryStart? {
        val bc = seg.curve
        val a = bc.bePositions.getFirst()
        val b = bc.bePositions.getSecond()
        val frames = cachedFrames(
            access, bc, SlideCurveGeometry.radiusAt(access.level, a), SlideCurveGeometry.radiusAt(access.level, b)
        )
        if (frames.size < 2) return null
        var bestI = 0
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0 until frames.size - 1) {
            val fa = frames[i]
            val fb = frames[i + 1]
            val ab = fb.center.subtract(fa.center)
            val lenSq = ab.lengthSqr()
            val t = if (lenSq < 1.0E-12) 0.0
            else ((pos.subtract(fa.center)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
            val d = pos.distanceToSqr(fa.center.add(ab.scale(t)))
            if (d < bestD) {
                bestD = d
                bestI = i
                bestT = t
            }
        }
        val fa = frames[bestI]
        val fb = frames[bestI + 1]
        val startT = fa.t + (fb.t - fa.t) * bestT.toFloat()
        val tan = fa.tangent.lerp(fb.tangent, bestT).normalize()
        val lat = fa.lateral.lerp(fb.lateral, bestT).normalize()
        val upB = if (fa.up.dot(fb.up) < 0.0) fb.up.scale(-1.0) else fb.up
        val up = fa.up.lerp(upB, bestT).normalize()
        val radius = fa.radius + (fb.radius - fa.radius) * bestT.toFloat()
        val proj = vel.dot(tan)
        val towardSecond = when {
            abs(proj) > 0.5 -> proj >= 0
            tan.y < -0.02 -> true
            tan.y > 0.02 -> false
            else -> b.y <= a.y
        }
        val entryTan = if (towardSecond) tan else tan.scale(-1.0)
        val center = fa.center.lerp(fb.center, bestT)
        // keep the player's actual contact position; the in-tube physics slides
        // them back inside the wall gradually instead of snapping radially
        // (no "air wall" jump)
        val entryPos = pos
        // preserve BOTH the incoming direction and speed when re-entering
        // another slide; wall collisions are handled by the in-tube physics
        val entryVel = vel
        return ReentryStart(
            bc, towardSecond, startT, entryPos, entryVel, center, entryTan, lat, up, radius
        )
    }

    private class ReentryGrid {
        private val buckets = HashMap<Long, MutableList<ReentrySegment>>()

        fun add(seg: ReentrySegment) {
            val r = seg.radius + 0.5
            val minX = Mth.floor((minOf(seg.a.x, seg.b.x) - r) / GRID)
            val maxX = Mth.floor((maxOf(seg.a.x, seg.b.x) + r) / GRID)
            val minY = Mth.floor((minOf(seg.a.y, seg.b.y) - r) / GRID)
            val maxY = Mth.floor((maxOf(seg.a.y, seg.b.y) + r) / GRID)
            val minZ = Mth.floor((minOf(seg.a.z, seg.b.z) - r) / GRID)
            val maxZ = Mth.floor((maxOf(seg.a.z, seg.b.z) + r) / GRID)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        buckets.getOrPut(key(x, y, z)) { ArrayList() } += seg
                    }
                }
            }
        }

        fun hit(pos: Vec3): ReentrySegment? {
            val cx = Mth.floor(pos.x / GRID)
            val cy = Mth.floor(pos.y / GRID)
            val cz = Mth.floor(pos.z / GRID)
            var best: ReentrySegment? = null
            var bestDist = Double.MAX_VALUE
            for (x in cx - 1..cx + 1) {
                for (y in cy - 1..cy + 1) {
                    for (z in cz - 1..cz + 1) {
                        val list = buckets[key(x, y, z)] ?: continue
                        for (s in list) {
                            val ab = s.b.subtract(s.a)
                            val lenSq = ab.lengthSqr()
                            if (lenSq < 1.0E-12) continue
                            val t = ((pos.subtract(s.a)).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                            val closest = s.a.lerp(s.b, t)
                            val d = pos.distanceToSqr(closest)
                            if (d < s.radius * s.radius && d < bestDist) {
                                bestDist = d
                                best = s
                            }
                        }
                    }
                }
            }
            return best
        }

        companion object {
            const val GRID = 8.0

            fun key(x: Int, y: Int, z: Int): Long =
                ((x.toLong() and 0x1FFFFF) shl 42) or
                    ((y.toLong() and 0x1FFFFF) shl 21) or
                    (z.toLong() and 0x1FFFFF)
        }
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
