package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.content.trains.track.BezierConnection
import net.createmod.ponder.api.level.PonderLevel
import net.createmod.ponder.foundation.PonderScene
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.client.editor.WaterslideRadiusEdit
import net.omori_sunny.create_waterparked.client.water.WaterFlowSimulation
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.SectorType
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorLayout
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.config.ModConfig

/**
 * Ponder storyboard toolkit for the waterslide (mirrors Create's PonderHilo):
 *  - edit UI control points (query + soft driving)
 *  - precise curve control with smooth easing
 *  - sector create / delete / resize / move
 *  - support beam / bracket hide / material assign / material clear
 *  - outlines show / hide (dye, delete, support)
 *  - water flow display + visibility toggle
 *  - capture entity and auto ride
 *  - position at arbitrary progress percentage
 *
 * Every operation writes straight into the Ponder level's block entity data
 * (the Ponder world ticks BlockEntityTickers and renders from BE data), so a
 * storyboard just calls these helpers and idles.
 */
object PonderSlideHelper {

    // ------------------------------------------------------------------
    // 1. Edit UI: control points
    // ------------------------------------------------------------------

    /** editor frame data (endpoints + opening normal/radius) for a primary curve */
    @JvmStatic
    fun editUiFrames(be: WaterslideAnchorBlockEntity): List<PonderSlideEditUiElement.Frame>? {
        val bc = be.primaryCurve() ?: return null
        val level = be.level ?: return null
        val def = ModConfig.defaultSlideRadius()
        val r0 = WaterslideRadiusEdit.radiusAt(level, bc.bePositions.first, def)
        val r1 = WaterslideRadiusEdit.radiusAt(level, bc.bePositions.second, def)
        return try {
            val frames = net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
                .sampleSegments(bc, r0, r1, Vec3.ZERO)
            if (frames.size < 2) return null
            val f0 = frames.first()
            val f1 = frames.last()
            val up0 = f0.prevTangent.cross(f0.prevLateral).normalize()
            val up1 = f1.currTangent.cross(f1.currLateral).normalize()
            listOf(
                PonderSlideEditUiElement.Frame(f0.prevSpine, f0.prevLateral, up0, r0, 0f),
                PonderSlideEditUiElement.Frame(f1.currSpine, f1.currLateral, up1, r1, 1f)
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** world position of the edit handle (endpoint + opening lift) at progress */
    @JvmStatic
    fun handlePosition(
        level: Level,
        bc: BezierConnection,
        progress: Float,
        radiusAt: Float
    ): Vec3 {
        val t = progress.toDouble().coerceIn(0.0, 1.0)
        val tan = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
            .unitTangentAt(bc, progress).normalize()
        val lat = lateralOf(bc, t)
        val up = tan.cross(lat)
        val upN = if (up.lengthSqr() > 1.0E-8) up.normalize() else Vec3(0.0, 1.0, 0.0)
        return bc.getPosition(t).add(upN.scale((radiusAt * 1.35).toDouble()))    }

    /** raw endpoints + handle pull points of the bezier (world space) */
    @JvmStatic
    fun controlPoints(bc: BezierConnection): List<Vec3> {
        val p0 = bc.getPosition(0.0)
        val p1 = bc.getPosition(1.0)
        val t0 = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
            .unitTangentAt(bc, 0f).normalize()
        val t1 = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
            .unitTangentAt(bc, 1f).normalize()
        val h0 = p0.add(t0.scale((p1.subtract(p0).length() / 3.0)))
        val h1 = p1.subtract(t1.scale((p1.subtract(p0).length() / 3.0)))
        return listOf(p0, h0, p1, h1)
    }

    private fun lateralOf(bc: BezierConnection, t: Double): Vec3 {
        val starts = bc.starts
        val lat = starts.first
        return if (lat.lengthSqr() > 1.0E-8) lat.normalize() else Vec3(0.0, 1.0, 0.0)
    }

    // ------------------------------------------------------------------
    // 2. Smooth-ease moving control points (spline shape)
    // ------------------------------------------------------------------

    // Smoothly translates the slide spline by (dx,dy,dz) over ticks (smoothstep, no accumulation).
    @JvmStatic
    fun easeMove(
        scene: PonderScene,
        be: WaterslideAnchorBlockEntity,
        dx: Double, dy: Double, dz: Double,
        ticks: Int,
        startTicks: Int = 0
    ) {
        scene.builder().addInstruction(PonderSlideEaseInstruction(be, dx, dy, dz, ticks, startTicks))
    }

    // ------------------------------------------------------------------
    // 3. Sectors
    // ------------------------------------------------------------------

    @JvmStatic
    fun createSector(
        be: WaterslideAnchorBlockEntity,
        peer: BlockPos,
        widthDegrees: Float,
        material: SectorMaterial = SectorMaterial.BLOCK,
        blockId: String? = null,
        fixed: Boolean = false
    ) {
        val config = be.sectorConfigFor(peer).copyOf()
        val id = config.newId()
        config.sectors += net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector(
            id = id,
            material = material,
            blockId = blockId?.let { net.minecraft.resources.ResourceLocation.parse(it) },
            type = if (fixed) SectorType.FIXED else SectorType.AUTO,
            widthDegrees = if (fixed) widthDegrees else 0f
        )
        be.setSectorConfig(peer, config)
    }

    @JvmStatic
    fun deleteSector(be: WaterslideAnchorBlockEntity, peer: BlockPos, sectorId: Int) {
        val config = be.sectorConfigFor(peer).copyOf()
        if (config.sectors.removeAll { it.id == sectorId }) {
            be.setSectorConfig(peer, config)
        }
    }

    /** resize the boundary between two sectors (segment create/delete sized by width) */
    @JvmStatic
    fun resizeSectorBoundary(
        be: WaterslideAnchorBlockEntity,
        peer: BlockPos,
        sectorId: Int,
        newBoundaryAngle: Float
    ) {
        val config = be.sectorConfigFor(peer).copyOf()
        WaterslideSectorLayout.applyBoundaryResize(config, sectorId, newBoundaryAngle)
        be.setSectorConfig(peer, config)
    }

    /** move a sector by dragging its center to a new angle */
    @JvmStatic
    fun moveSector(
        be: WaterslideAnchorBlockEntity,
        peer: BlockPos,
        sectorId: Int,
        newCenterAngle: Float
    ) {
        val config = be.sectorConfigFor(peer).copyOf()
        WaterslideSectorLayout.applyMove(config, sectorId, newCenterAngle)
        be.setSectorConfig(peer, config)
    }

    // ------------------------------------------------------------------
    // 4. Support beam / bracket
    // ------------------------------------------------------------------

    @JvmStatic
    fun setSupportVisible(be: WaterslideAnchorBlockEntity, part: WaterslideSupportPart, visible: Boolean) =
        be.setSupportVisible(part, visible)

    @JvmStatic
    fun setSupportMaterial(
        be: WaterslideAnchorBlockEntity,
        part: WaterslideSupportPart,
        state: net.minecraft.world.level.block.state.BlockState
    ) {
        be.setSupportMaterial(part, state, ItemStack.EMPTY)
        be.setSupportVisible(part, true)
    }

    @JvmStatic
    fun clearSupportMaterial(be: WaterslideAnchorBlockEntity, part: WaterslideSupportPart) {
        be.resetSupportMaterial(part)
    }

    @JvmStatic
    fun supportMaterial(be: WaterslideAnchorBlockEntity, part: WaterslideSupportPart) =
        be.supportMaterial(part)

    @JvmStatic
    fun isSupportVisible(be: WaterslideAnchorBlockEntity, part: WaterslideSupportPart) =
        be.isSupportVisible(part)

    // ------------------------------------------------------------------
    // 5. Outlines (dye / delete / support) - show & hide against the helper cache
    // ------------------------------------------------------------------

    @JvmStatic
    fun outlineShow(be: WaterslideAnchorBlockEntity, color: Int, thickness: Float) {
        val bc = be.primaryCurve() ?: return
        outlineCache[be.blockPos.asLong()] = OutlineData(curveSamples(be.level ?: return, bc, 48), color, thickness)
    }

    @JvmStatic
    fun outlineHide(be: WaterslideAnchorBlockEntity) {
        outlineCache.remove(be.blockPos.asLong())
    }

    // ------------------------------------------------------------------
    // 6. Water flow display + toggle
    // ------------------------------------------------------------------

    @JvmStatic
    fun waterFlowShow(be: WaterslideAnchorBlockEntity, speed: Float = 1.0f) {
        val level = be.level ?: return
        val bc = be.primaryCurve() ?: return
        for (peer in be.anchorPeerCurvesView.keys) be.setCurveWatered(peer, true)
        WaterFlowSimulation.injectPonderField(level, bc, speed)
    }

    @JvmStatic
    fun waterFlowHide(be: WaterslideAnchorBlockEntity) {
        val bc = be.primaryCurve() ?: return
        WaterFlowSimulation.clearPonderField(bc)
        for (peer in be.anchorPeerCurvesView.keys) be.setCurveWatered(peer, false)
    }

    // ------------------------------------------------------------------
    // 7. Capture + auto ride
    // ------------------------------------------------------------------

    @JvmStatic
    fun startRide(
        scene: PonderScene,
        entity: Entity,
        bc: BezierConnection,
        startProgress: Float,
        speedBlocksPerSecond: Float = 2.0f
    ) {
        scene.builder().addInstruction(PonderSlideRideInstruction(entity, bc, startProgress, speedBlocksPerSecond))
    }

    // ------------------------------------------------------------------
    // 8. Position at progress (percent)
    // ------------------------------------------------------------------

    @JvmStatic
    fun positionAt(bc: BezierConnection, progress: Float, lift: Float = 0f): Vec3 {
        val p = progress.coerceIn(0f, 1f)
        val pos = bc.getPosition(p.toDouble())
        val tan = dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames
            .unitTangentAt(bc, p).normalize()
        val lat = lateralOf(bc, p.toDouble())
        val up = tan.cross(lat)
        val upN = if (up.lengthSqr() > 1.0E-8) up.normalize() else Vec3(0.0, 1.0, 0.0)
        return pos.add(upN.scale(lift.toDouble()))
    }

    @JvmStatic
    fun curveSamples(level: Level, bc: BezierConnection, n: Int): List<Vec3> {
        val out = ArrayList<Vec3>(n + 1)
        for (i in 0..n) {
            val t = i.toDouble() / n
            out.add(bc.getPosition(t))
        }
        return out
    }

    // ------------------------------------------------------------------

    internal fun WaterslideAnchorBlockEntity.primaryCurve(): BezierConnection? {
        for (raw in anchorPeerCurvesView.values) {
            if (raw == null) continue
            val bc = if (raw.isPrimary) raw else raw.secondary()
            if (WaterslideTrackMaterials.isWaterslide(bc)) return bc
        }
        return null
    }

    data class OutlineData(val points: List<Vec3>, val color: Int, val thickness: Float)

    internal val outlineCache = java.util.concurrent.ConcurrentHashMap<Long, OutlineData>()
}
