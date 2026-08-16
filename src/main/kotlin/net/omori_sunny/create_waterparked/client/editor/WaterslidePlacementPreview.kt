package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
import net.createmod.catnip.outliner.Outliner
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackItem
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.WaterslideConnectionRules
import net.omori_sunny.create_waterparked.game.WaterslideNeighborSmoothing
import net.omori_sunny.create_waterparked.game.WaterslideTrackPlacement
import net.omori_sunny.create_waterparked.network.WaterslideAnchorClearPayload
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor

// Slide connection UI, mirroring CCS.
@OnlyIn(Dist.CLIENT)
object WaterslidePlacementPreview {

    private const val KEY_CURVE = "waterslide_placement_preview"
    private const val KEY_FIRST = "waterslide_anchor_first"
    private const val KEY_HOVER = "waterslide_anchor_hover"
    private const val KEY_INVALID = "waterslide_anchor_invalid"
    private const val KEY_NEIGHBOR = "waterslide_placement_preview_neighbor_"
    private const val NEIGHBOR_SLOTS = 4

    private const val COLOR_OK = 0x1D9AEF
    private const val COLOR_BAD = 0xEA5A47
    private const val COLOR_GREEN = 0x1D9AEF

    private var prevSegmentCount = 0
    private var neighborCounts = IntArray(NEIGHBOR_SLOTS)

    private val previewLift: Double
        get() = CoasterTrackGauge.coasterPreviewSpineVerticalBumpBlocks().toDouble()

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return clearAll()
        val level = mc.level ?: return clearAll()
        val stack = player.mainHandItem
        if (stack.item !is WaterslideTrackItem) return clearAll()
        val first = WaterslideTrackPlacement.readAnchorFirstSelection(stack) ?: return clearAll()

        // Never touch the far sub-level anchor or build preview geometry when the hovered
        // block lives in a different space (main world vs. sub-level): that is what froze
        // the line-preview phase before.
        val hit = mc.hitResult
        val hovered = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos
        if (hovered != null && hovered != first && WaterslideConnectionRules.acrossSubLevels(level, first, hovered)) {
            clearAll()
            player.displayClientMessage(
                Component.translatable("create_waterparked.connect.cross_sublevel")
                    .withStyle(ChatFormatting.RED),
                true
            )
            return
        }

        // Also avoid querying the far plot-global anchor when the player has left its space
        // (e.g. walked back into the main world while a sub-level selection is still active).
        if (hovered == null && WaterslideConnectionRules.acrossSubLevels(level, first, player.blockPosition())) {
            clearAll()
            return
        }

        // Clear stale selection.
        val firstState = level.getBlockState(first)
        val firstBe = level.getBlockEntity(first) as? WaterslideAnchorBlockEntity
        if (firstState.block !is WaterslideAnchorBlock || firstBe == null || firstBe.legCount() >= 2) {
            WaterslideTrackPlacement.clearPendingConnection(stack)
            PacketDistributor.sendToServer(WaterslideAnchorClearPayload())
            return clearAll()
        }

        showAnchorOutline(level, first, KEY_FIRST, COLOR_GREEN)

        if (hovered == null) {
            removeHoverAndCurve()
            Outliner.getInstance().remove(KEY_INVALID)
            return
        }
        val target = hovered
        if (target == first) {
            removeHoverAndCurve()
            Outliner.getInstance().remove(KEY_INVALID)
            return
        }

        val targetState = level.getBlockState(target)
        if (targetState.block !is WaterslideAnchorBlock) {
            removeHoverAndCurve()
            clearNeighborPreviews()
            showTargetOutline(level, target, KEY_INVALID, COLOR_BAD)
            // Invalid hover preview. Skip geometry entirely across spaces / beyond max span;
            // building a sub-level -> main-world preview used to hang the client.
            val virtualSecond = target.above()
            if (virtualSecond != first) {
                if (WaterslideConnectionRules.shouldSkipPreview(level, first, virtualSecond)) {
                    clearCurve()
                    clearNeighborPreviews()
                    player.displayClientMessage(
                        Component.translatable("create_waterparked.connect.cross_sublevel")
                            .withStyle(ChatFormatting.RED),
                        true
                    )
                } else {
                    val placement = CoasterAnchorBezierOptimizer.buildAnchorToVirtualPeerPlacement(
                        level, first, virtualSecond, WaterslideTrackMaterials.WATERSLIDE, false
                    )
                    if (placement != null) {
                        prevSegmentCount = CoasterTrackPlacement.drawCoasterCurveOutlinePreview(
                            placement.primary(), KEY_CURVE, COLOR_BAD, previewLift, prevSegmentCount
                        )
                    }
                    player.displayClientMessage(
                        Component.translatable("create_waterparked.track.must_attach_to_slide_anchors")
                            .withStyle(ChatFormatting.RED),
                        true
                    )
                }
            }
            return
        }

        Outliner.getInstance().remove(KEY_INVALID)
        val result = WaterslideConnectionRules.validate(level, first, target)
        showAnchorOutline(level, target, KEY_HOVER, if (result.valid) COLOR_GREEN else COLOR_BAD)

        // Curve preview, red when invalid. Skip entirely across spaces / beyond max span.
        if (WaterslideConnectionRules.shouldSkipPreview(level, first, target)) {
            clearCurve()
            clearNeighborPreviews()
        } else {
            val placement = CoasterAnchorBezierOptimizer.buildAnchorAnchorPlacement(
                level, first, target, WaterslideTrackMaterials.WATERSLIDE, false
            )
            if (placement != null) {
                val color = if (result.valid) COLOR_OK else COLOR_BAD
                val smoothing = WaterslideNeighborSmoothing.build(
                    level, first, target, placement.primary(), placement
                )
                val previewCurve = smoothing?.primary ?: placement.primary()
                prevSegmentCount = CoasterTrackPlacement.drawCoasterCurveOutlinePreview(
                    previewCurve, KEY_CURVE, color, previewLift, prevSegmentCount
                )
                drawNeighborPreviews(level, smoothing?.neighbors ?: emptyList<Any>(), color)
            } else {
                clearCurve()
                clearNeighborPreviews()
            }
        }

        if (result.valid) {
            player.displayClientMessage(
                Component.translatable("create.track.valid_connection").withStyle(ChatFormatting.WHITE),
                true
            )
        } else {
            player.displayClientMessage(
                Component.translatable(result.messageKey ?: "create.track.too_sharp")
                    .withStyle(ChatFormatting.RED),
                true
            )
        }
    }

    private fun showAnchorOutline(level: Level, pos: BlockPos, key: String, color: Int) {
        val state = level.getBlockState(pos)
        if (state.block !is WaterslideAnchorBlock) {
            Outliner.getInstance().remove(key)
            return
        }
        val shape = state.getShape(level, pos)
        if (shape.isEmpty) {
            Outliner.getInstance().remove(key)
        } else {
            Outliner.getInstance().showAABB(key, shape.bounds().move(pos)).colored(color).lineWidth(0.0625f)
        }
    }

    private fun showTargetOutline(level: Level, pos: BlockPos, key: String, color: Int) {
        val shape = level.getBlockState(pos).getShape(level, pos)
        val box = if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
        Outliner.getInstance().showAABB(key, box).colored(color).lineWidth(0.0625f)
    }

    private fun removeHoverAndCurve() {
        Outliner.getInstance().remove(KEY_HOVER)
        clearCurve()
        clearNeighborPreviews()
    }

    private fun clearCurve() {
        if (prevSegmentCount > 0) {
            CoasterTrackPlacement.clearCoasterCurveOutlinePreview(KEY_CURVE, prevSegmentCount)
            prevSegmentCount = 0
        }
    }

// smoothed neighbor previews
    private fun drawNeighborPreviews(level: Level, neighbors: List<*>, color: Int) {
        val counts = IntArray(NEIGHBOR_SLOTS)
        for ((i, n) in neighbors.withIndex()) {
            if (i >= NEIGHBOR_SLOTS) break
            val bc = n as? BezierConnection ?: continue
            counts[i] = CoasterTrackPlacement.drawCoasterCurveOutlinePreview(
                bc, KEY_NEIGHBOR + i, color, previewLift, neighborCounts[i]
            )
        }
        for (i in neighbors.size until NEIGHBOR_SLOTS) {
            if (neighborCounts[i] > 0) {
                CoasterTrackPlacement.clearCoasterCurveOutlinePreview(KEY_NEIGHBOR + i, neighborCounts[i])
            }
            counts[i] = 0
        }
        neighborCounts = counts
    }

    private fun clearNeighborPreviews() {
        for (i in 0 until NEIGHBOR_SLOTS) {
            if (neighborCounts[i] > 0) {
                CoasterTrackPlacement.clearCoasterCurveOutlinePreview(KEY_NEIGHBOR + i, neighborCounts[i])
            }
        }
        neighborCounts = IntArray(NEIGHBOR_SLOTS)
    }

    private fun clearAll() {
        Outliner.getInstance().remove(KEY_FIRST)
        Outliner.getInstance().remove(KEY_HOVER)
        Outliner.getInstance().remove(KEY_INVALID)
        clearCurve()
        clearNeighborPreviews()
    }
}
