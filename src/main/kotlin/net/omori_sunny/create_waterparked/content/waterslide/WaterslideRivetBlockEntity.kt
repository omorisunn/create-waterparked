package net.omori_sunny.create_waterparked.content.waterslide
// extended CCS rivet block entity carrying the slide curve binding; the
// world frame (position + wall-hugging orientation) is recomputed from the
// LIVE curve every time, so edits to the slide move the rivet along

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.rivet.RivetBlockEntity
import dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.game.SlideCurveGeometry

class WaterslideRivetBlockEntity(pos: BlockPos, state: BlockState) :
    RivetBlockEntity(pos, state) {

    // parent constructor hardcodes the CCS rivet type; ours must match the
    // extended block or the BE validity check kills it on placement
    override fun getType(): net.minecraft.world.level.block.entity.BlockEntityType<*> =
        net.omori_sunny.create_waterparked.content.registry.ModBlockEntities.WATERSLIDE_RIVET_BE

    var curvePeer: BlockPos = BlockPos.ZERO
        private set

    // body-local centre of mass captured right after placement; block edits
    // shift the live COM, and the hold compensates so content stays put
    var comBaseX: Double = 0.5
    var comBaseY: Double = 0.5
    var comBaseZ: Double = 0.5
    var curveT: Float = 0f
        private set
    var wallAngle: Float = 0f
        private set

    fun bindCurve(peer: BlockPos, t: Float, angle: Float) {
        curvePeer = peer.immutable()
        curveT = t
        wallAngle = angle
        setChanged()
    }

    // world frame hugging the tube OUTER wall at (T, angle)
    fun curveWorldFrame(level: net.minecraft.world.level.Level): Pair<Vec3, Vec3>? {
        val hostLevel = level as? net.minecraft.server.level.ServerLevel ?: return null
        val anchor = hostLevel.getBlockEntity(hostPos()) as? WaterslideAnchorBlockEntity
            ?: return null
        val raw = anchor.anchorPeerCurvesView[curvePeer] ?: return null
        val curve = (if (raw.isPrimary) raw else raw.secondary()) as BezierConnection
        if (!WaterslideTrackMaterials.isWaterslide(curve)) return null
        val spine = curve.getPosition(curveT.toDouble())
        val tangent = CoasterBezierRailFrames.unitTangentAt(curve, curveT).normalize()
        val (lateral, up) = SlideCurveGeometry.stableFrame(tangent)
        val rad = Math.toRadians(wallAngle.toDouble())
        val radial = lateral.scale(Math.cos(rad)).add(up.scale(Math.sin(rad)))
        val r0 = SlideCurveGeometry.radiusAt(hostLevel, curve.bePositions.getFirst())
        val r1 = SlideCurveGeometry.radiusAt(hostLevel, curve.bePositions.getSecond())
        val radius = Mth.lerp(curveT, r0, r1).toDouble()
        // anchor point just outside the wall, normal = radial
        return spine.add(radial.scale(radius + 0.1)) to radial
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putLong("CurvePeer", curvePeer.asLong())
        tag.putFloat("CurveT", curveT)
        tag.putFloat("WallAngle", wallAngle)
        tag.putDouble("ComBaseX", comBaseX)
        tag.putDouble("ComBaseY", comBaseY)
        tag.putDouble("ComBaseZ", comBaseZ)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains("CurvePeer", 4)) curvePeer = BlockPos.of(tag.getLong("CurvePeer"))
        if (tag.contains("CurveT", 5)) curveT = tag.getFloat("CurveT")
        if (tag.contains("WallAngle", 5)) wallAngle = tag.getFloat("WallAngle")
        if (tag.contains("ComBaseX", 6)) comBaseX = tag.getDouble("ComBaseX")
        if (tag.contains("ComBaseY", 6)) comBaseY = tag.getDouble("ComBaseY")
        if (tag.contains("ComBaseZ", 6)) comBaseZ = tag.getDouble("ComBaseZ")
    }
}
