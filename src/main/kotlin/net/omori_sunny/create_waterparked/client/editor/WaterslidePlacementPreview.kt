package net.omori_sunny.create_waterparked.client.editor

import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
import net.createmod.catnip.outliner.Outliner
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlock
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackItem
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.WaterslideConnectionRules
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

    private const val COLOR_OK = 0x1D9AEF
    private const val COLOR_BAD = 0xEA5A47
    private const val COLOR_GREEN = 0x1D9AEF

    private var prevSegmentCount = 0

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

        // Clear stale selection.
        val firstState = level.getBlockState(first)
        val firstBe = level.getBlockEntity(first) as? WaterslideAnchorBlockEntity
        if (firstState.block !is WaterslideAnchorBlock || firstBe == null || firstBe.legCount() >= 2) {
            WaterslideTrackPlacement.clearPendingConnection(stack)
            PacketDistributor.sendToServer(WaterslideAnchorClearPayload())
            return clearAll()
        }

        showAnchorOutline(level, first, KEY_FIRST, COLOR_GREEN)

        val hit = mc.hitResult
        if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) {
            removeHoverAndCurve()
            Outliner.getInstance().remove(KEY_INVALID)
            return
        }
        val target = hit.blockPos
        if (target == first) {
            removeHoverAndCurve()
            Outliner.getInstance().remove(KEY_INVALID)
            return
        }

        val targetState = level.getBlockState(target)
        if (targetState.block !is WaterslideAnchorBlock) {
            removeHoverAndCurve()
            showTargetOutline(level, target, KEY_INVALID, COLOR_BAD)
            // Invalid hover preview.
            val virtualSecond = target.above()
            if (virtualSecond != first) {
                val placement = CoasterAnchorBezierOptimizer.buildAnchorToVirtualPeerPlacement(
                    level, first, virtualSecond, WaterslideTrackMaterials.WATERSLIDE, false
                )
                if (placement != null) {
                    prevSegmentCount = CoasterTrackPlacement.drawCoasterCurveOutlinePreview(
                        placement.primary(), KEY_CURVE, COLOR_BAD, previewLift, prevSegmentCount
                    )
                }
            }
            player.displayClientMessage(
                Component.translatable("create_waterparked.track.must_attach_to_slide_anchors")
                    .withStyle(ChatFormatting.RED),
                true
            )
            return
        }

        Outliner.getInstance().remove(KEY_INVALID)
        val result = WaterslideConnectionRules.validate(level, first, target)
        showAnchorOutline(level, target, KEY_HOVER, if (result.valid) COLOR_GREEN else COLOR_BAD)

        // Curve preview, red when invalid.
        val bc = CoasterAnchorBezierOptimizer.buildAnchorAnchorBezier(
            level, first, target, WaterslideTrackMaterials.WATERSLIDE, false
        )
        if (bc != null) {
            val color = if (result.valid) COLOR_OK else COLOR_BAD
            prevSegmentCount = CoasterTrackPlacement.drawCoasterCurveOutlinePreview(
                bc, KEY_CURVE, color, previewLift, prevSegmentCount
            )
        } else {
            clearCurve()
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
    }

    private fun clearCurve() {
        if (prevSegmentCount > 0) {
            CoasterTrackPlacement.clearCoasterCurveOutlinePreview(KEY_CURVE, prevSegmentCount)
            prevSegmentCount = 0
        }
    }

    private fun clearAll() {
        Outliner.getInstance().remove(KEY_FIRST)
        Outliner.getInstance().remove(KEY_HOVER)
        Outliner.getInstance().remove(KEY_INVALID)
        clearCurve()
    }
}
