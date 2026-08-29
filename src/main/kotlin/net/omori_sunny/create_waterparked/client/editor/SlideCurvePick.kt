package net.omori_sunny.create_waterparked.client.editor

import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn

// Client side of the clipboard copy targeting.
@OnlyIn(Dist.CLIENT)
object SlideCurvePick {

    // resolved copy target for a right click on an anchor block
    data class CopyTarget(
        val anchor: BlockPos,
        // null when the server picks the only curve (single-leg anchor)
        val peer: BlockPos?,
        val noCurve: Boolean,
        val miss: Boolean
    )

    // null when the click is not on a slide anchor; otherwise the target state
    @JvmStatic
    fun resolveCopyTarget(mc: Minecraft, clickedPos: BlockPos): CopyTarget? {
        val level = mc.level ?: return null
        val be = anchorBe(level, clickedPos) ?: return null
        val peers = be.anchorPeerCurvesView.keys.filter { peer ->
            val raw = be.anchorPeerCurvesView[peer] ?: return@filter false
            val primary = if (raw.isPrimary) raw else raw.secondary()
            WaterslideTrackMaterials.isWaterslide(primary)
        }
        if (peers.isEmpty()) {
            // an anchor without any slide curve cannot be copied
            return CopyTarget(be.blockPos, null, noCurve = true, miss = false)
        }
        if (peers.size == 1) {
            // single curve: the server resolves the peer
            return CopyTarget(be.blockPos, null, noCurve = false, miss = false)
        }
        // multi-leg anchor: the curve under the cursor wins
        val hit = WaterslideSectorEdit.pickWallAtCursor(mc) ?: return CopyTarget(be.blockPos, null, noCurve = false, miss = true)
        val raw = hit.curve
        val primary = if (raw.isPrimary) raw else raw.secondary()
        if (primary.bePositions.getFirst() != be.blockPos && primary.bePositions.getSecond() != be.blockPos) {
            return CopyTarget(be.blockPos, null, noCurve = false, miss = true)
        }
        val peer = if (primary.bePositions.getFirst() == be.blockPos) {
            primary.bePositions.getSecond()
        } else {
            primary.bePositions.getFirst()
        }
        return CopyTarget(be.blockPos, peer, noCurve = false, miss = false)
    }

    // direct hit first, then the Sable plot-local resolution
    @JvmStatic
    fun anchorBe(level: Level, pos: BlockPos): WaterslideAnchorBlockEntity? {
        (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)?.let { return it }
        return try {
            SableClientEdit.resolve(level, pos)?.be
        } catch (t: Throwable) {
            null
        }
    }
}
