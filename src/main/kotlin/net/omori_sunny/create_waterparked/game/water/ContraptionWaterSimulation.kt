package net.omori_sunny.create_waterparked.game.water

import com.simibubi.create.content.contraptions.AbstractContraptionEntity
import com.simibubi.create.content.trains.track.BezierConnection
import com.simibubi.create.content.contraptions.Contraption
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry
import net.omori_sunny.create_waterparked.game.physics.ContraptionSlideSpaceAccess
import net.omori_sunny.create_waterparked.game.physics.ContraptionSlideSpaces
import net.omori_sunny.create_waterparked.network.WaterslideWaterSyncPayload
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.max
import kotlin.math.sqrt

// one time water field for contraption tubes, never simulated per tick
object ContraptionWaterSimulation {

    private const val WATER_LAUNCH = 2.0 // initial block/s at the source mouth
    private const val GRAVITY = 32.0
    private const val ZERO_EPS = 1.0E-12
    private const val MIN_SEGMENT_LENGTH = 1.0E-6
    private const val MIN_SPEED = 1.0E-6

    private data class Cached(
        val sig: Int,
        val fields: Map<Pair<Long, Long>, ServerWaterSimulation.CurveField>
    )

    // contraption id to cached field, shared with the render path
    private val cache = HashMap<Int, Cached>()

    fun edgeKey(a: BlockPos, b: BlockPos): Pair<Long, Long> =
        if (a.asLong() <= b.asLong()) a.asLong() to b.asLong() else b.asLong() to a.asLong()

    fun invalidateAll() { cache.clear() }

    fun invalidate(entityId: Int) { cache.remove(entityId) }

    // recompute and resync all loaded contraption slides
    fun refresh(level: ServerLevel) {
        invalidateAll()
        for (id in ContraptionSlideSpaces.carriersIn(level)) {
            val cp = level.getEntity(id) as? AbstractContraptionEntity ?: continue
            fieldsFor(level, cp)
            syncToPlayers(level, cp)
        }
    }

    // send the computed field to all players in the dimension
    fun syncToPlayers(level: ServerLevel, entity: AbstractContraptionEntity) {
        if (level.isClientSide) return
        val fields = fieldsFor(level, entity)
        if (fields.isEmpty()) return
        val entries = fields.map { (edge, f) ->
            WaterslideWaterSyncPayload.Entry(
                edge.first, edge.second,
                f.segments.map { WaterslideWaterSyncPayload.Segment(it.arc, it.speed) },
                f.exit?.pos, f.exit?.vel
            )
        }
        val payload = WaterslideWaterSyncPayload(entries, null, entity.id)
        PacketDistributor.sendToPlayersInDimension(level, payload)
        CreateWaterparked.LOGGER.debug("[ContraptionWater] synced {} field(s) for contraption {}", entries.size, entity.id)
    }

    fun fieldsFor(level: ServerLevel, entity: AbstractContraptionEntity): Map<Pair<Long, Long>, ServerWaterSimulation.CurveField> {
        if (level.isClientSide) return emptyMap()
        val contraption = entity.contraption ?: return emptyMap()
        if (!ContraptionSlideSpaces.carriesSlides(entity)) return emptyMap()
        val sig = dataSignature(contraption)
        val cached = cache[entity.id]
        if (cached != null && cached.sig == sig) return cached.fields
        val fields = compute(level, entity)
        cache[entity.id] = Cached(sig, fields)
        if (fields.isEmpty()) {
            val watered = ContraptionSlideSpaces.decode(entity).values.count { it.hasWater() }
            CreateWaterparked.LOGGER.debug(
                "[ContraptionWater] entity={} computed EMPTY field (wateredAnchors={}) - no water to flow",
                entity.id, watered
            )
        } else {
            CreateWaterparked.LOGGER.debug(
                "[ContraptionWater] entity={} computed {} field(s)",
                entity.id, fields.size
            )
        }
        return fields
    }

    private fun dataSignature(contraption: Contraption): Int {
        var key = 0
        for (info in contraption.blocks.values) {
            val nbt = info.nbt() ?: continue
            key = key * 31 + nbt.hashCode()
        }
        return key
    }

    // water flows from every watered anchor and exits at the open mouth
    private fun compute(level: ServerLevel, entity: AbstractContraptionEntity): Map<Pair<Long, Long>, ServerWaterSimulation.CurveField> {
        val out = HashMap<Pair<Long, Long>, ServerWaterSimulation.CurveField>()
        try {
            val access = ContraptionSlideSpaceAccess(level, entity)
            for ((_, be) in ContraptionSlideSpaces.decode(entity)) {
                if (!be.hasWater()) continue
                val radius = be.radius
                for (raw in be.anchorPeerCurvesView.values) {
                    val bc = if (raw.isPrimary) raw else raw.secondary()
                    if (!WaterslideTrackMaterials.isWaterslide(bc)) continue
                    val a = bc.bePositions.getFirst()
                    val b = bc.bePositions.getSecond()
                    val edge = edgeKey(a, b)
                    if (out.containsKey(edge)) continue
                    out[edge] = flow(access, bc, radius, radius)
                }
            }
        } catch (t: Throwable) {
            CreateWaterparked.LOGGER.warn("[ContraptionWater] compute failed for entity {}: {}", entity.id, t.toString())
        }
        return out
    }

    // ballistic water along one curve, the far open end throws
    private fun flow(
        access: ContraptionSlideSpaceAccess,
        bc: BezierConnection,
        r0: Float,
        r1: Float
    ): ServerWaterSimulation.CurveField {
        val frames = SlideCurveGeometry.sampleFrames(access, bc, r0, r1, includeExtensions = true)
        if (frames.size < 2) return ServerWaterSimulation.CurveField(emptyList(), null)

        // local down, falls back to world down
        val grav = access.localGravity()
        val downLocal = if (grav.lengthSqr() > ZERO_EPS) grav.scale(-1.0).normalize()
        else Vec3(0.0, 1.0, 0.0)

        val segments = ArrayList<ServerWaterSimulation.WaterSegment>()
        var speed = WATER_LAUNCH
        var arc = 0f
        for (i in 0 until frames.size - 1) {
            val fa = frames[i]
            val fb = frames[i + 1]
            val len = fa.center.distanceTo(fb.center)
            if (len < MIN_SEGMENT_LENGTH) continue
            // gain from falling along the downward component of travel
            val along = (fb.center.subtract(fa.center)).normalize()
            val fall = max(0.0, along.dot(downLocal))
            speed = sqrt(max(MIN_SPEED, speed * speed + 2.0 * GRAVITY * fall * len))
            arc += len.toFloat()
            segments += ServerWaterSimulation.WaterSegment(arc, speed.toFloat())
        }

        // exit throw at the far mouth
        val lastFrame = frames.last()
        val exitPos = lastFrame.center
        val exitVel = lastFrame.tangent.scale(speed)
        val exit = ServerWaterSimulation.ExitInfo(exitPos, exitVel)
        return ServerWaterSimulation.CurveField(segments, exit)
    }
}