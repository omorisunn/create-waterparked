package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.AllItems
import net.createmod.catnip.outliner.Outliner
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.network.WaterslideSupportApplyPayload
import net.omori_sunny.create_waterparked.network.WaterslideSupportHoverPayload
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor

// client side of the copycat style support interaction, ray picks Flywheel geometry
@OnlyIn(Dist.CLIENT)
object WaterslideSupportEdit {

    private const val OUTLINE_BEAM = "create_waterparked_support_beam_hover"
    private const val OUTLINE_BRACKET = "create_waterparked_support_bracket_hover"
    @JvmField
    val HOVER_COLOR = 0xFFC46A // warm amber, same family as editor highlights

    private var hovered: WaterslideTubeVisual.SupportPick? = null
    private var sentAnchor: net.minecraft.core.BlockPos? = null
    private var sentPart = -1

    // current hover pick for the render-stage outline (world space points)
    @JvmStatic
    fun hoveredPick(): WaterslideTubeVisual.SupportPick? = hovered

    // held-item visibility rules shared by hover tick and the outline draw
    @JvmStatic
    fun outlineShownForHover(
        pick: WaterslideTubeVisual.SupportPick,
        mc: Minecraft
    ): Boolean = outlineShown(pick, mc)

    @JvmStatic
    fun onClientTick() {
        val mc = Minecraft.getInstance() ?: return
        val player = mc.player ?: return
        val camera = mc.gameRenderer.mainCamera
        val rayStart = camera.position
        val rayDir = player.getViewVector(1.0f)

        val pick = WaterslideTubeVisual.pickSupport(rayStart, rayDir)
        hovered = pick

        // the outline itself is drawn at the render stage by
        // WaterslideSupportOutline (billboard strips, dye-outline style)

        if (mc.connection == null) return
        val anchor = pick?.anchorPos
        val part = pick?.part ?: -1
        if (anchor == sentAnchor && part == sentPart) return
        sentAnchor = anchor
        sentPart = part
        PacketDistributor.sendToServer(
            WaterslideSupportHoverPayload(
                anchor ?: net.minecraft.core.BlockPos.ZERO,
                part.toByte()
            )
        )
    }

    @JvmStatic
    fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
        if (!event.level.isClientSide) return
        val player = event.entity ?: return
        // the item-use stage fires on the hand that actually holds the item,
        // while RightClickEmpty only fires with an EMPTY hand
        val usedHand = usableHand(player, event.hand)
        val heldItem = player.getItemInHand(usedHand)
        if (!canInteract(heldItem)) return
        val pick = hovered ?: return
        if (!outlineShownFor(pick, Minecraft.getInstance(), heldItem)) {
            // hovered a support part but this hand combo is rejected (e.g.
            // wrench on an empty part): swallow the click so the slide editor
            // does not pick it up - the support interaction owns any click
            // aimed at support geometry
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            return
        }
        // cancel the vanilla item use so the block/axe/wrench does NOT act,
        // and let startUseItem run the swing + item-used animation
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        performSupportApply(player, pick, usedHand, net.minecraft.core.Direction.orderedByNearest(player)[0])
    }

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (!event.level.isClientSide) return
        val player = event.entity ?: return
        val usedHand = usableHand(player, event.hand)
        val heldItem = player.getItemInHand(usedHand)
        if (!canInteract(heldItem)) return
        val pick = hovered ?: return
        // the same hand logic as the outline, no hand mismatch
        if (!outlineShownFor(pick, Minecraft.getInstance(), heldItem)) {
            // swallowed: any click on support geometry belongs to the support
            // interaction, even when the held combo is rejected (wrench on an
            // empty part must not fall through to the slide sector editor)
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            return
        }
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        performSupportApply(player, pick, usedHand, event.face)
    }

    // shared: optimistic axe refresh + payload + feedback sound
    private fun performSupportApply(
        player: net.minecraft.world.entity.player.Player,
        pick: WaterslideTubeVisual.SupportPick,
        usedHand: InteractionHand,
        face: net.minecraft.core.Direction?
    ) {
        val heldItem = player.getItemInHand(usedHand)
        // optimistic air refresh: axe deletes apply instantly on empty parts
        if (heldItem.item is AxeItem) {
            val localBe = Minecraft.getInstance().level?.getBlockEntity(pick.anchorPos)
                as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
            val part = if (pick.part == 0)
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM
            else
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET
            if (localBe != null && !localBe.hasCustomSupportMaterial(part)) {
                localBe.setSupportVisible(part, false)
                net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual.refreshAnchor(pick.anchorPos)
            }
        }
        PacketDistributor.sendToServer(
            WaterslideSupportApplyPayload(
                pick.anchorPos,
                pick.part.toByte(),
                usedHand.ordinal.toByte(),
                (face?.ordinal ?: -1).toByte()
            )
        )
        WaterslideEditSounds.playCommitSuccess()
    }

    // event hand first, then the other hand, so the actual item in hand wins
    private fun usableHand(player: net.minecraft.world.entity.player.Player, eventHand: InteractionHand): InteractionHand {
        if (canInteract(player.getItemInHand(eventHand))) return eventHand
        val other = if (eventHand == InteractionHand.MAIN_HAND) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        return if (canInteract(player.getItemInHand(other))) other else eventHand
    }

    private fun canInteract(stack: ItemStack): Boolean =
        !stack.isEmpty && (AllItems.WRENCH.isIn(stack) || stack.item is BlockItem ||
            stack.item is AxeItem)

    // outline visibility by fill state and the held tool, for one specific hand
    private fun outlineShownFor(pick: WaterslideTubeVisual.SupportPick, mc: Minecraft, held: ItemStack): Boolean {
        if (held.isEmpty) return false
        val isAxe = held.item is AxeItem
        val isWrench = AllItems.WRENCH.isIn(held)
        val isBlock = held.item is BlockItem
        val be = mc.level?.getBlockEntity(pick.anchorPos)
            as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
            ?: return false
        val part = if (pick.part == 0)
            net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM
        else
            net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET
        val deleted = !be.isSupportVisible(part)
        val filled = be.hasCustomSupportMaterial(part)
        return when {
            // deleted part: only the wrench may restore it
            deleted -> isWrench
            // filled part: only the wrench may act
            filled -> isWrench
            // empty part: the wrench is rejected, axe and blocks may act
            else -> !isWrench && (isAxe || isBlock)
        }
    }

    // hover outline shows when either hand holds a valid combo
    private fun outlineShown(pick: WaterslideTubeVisual.SupportPick, mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        return outlineShownFor(pick, mc, player.mainHandItem) ||
            outlineShownFor(pick, mc, player.offhandItem)
    }

    @JvmStatic
    fun clear() {
        hovered = null
        sentAnchor = null
        sentPart = -1
        val outliner = Outliner.getInstance()
        outliner.remove(OUTLINE_BEAM)
        outliner.remove(OUTLINE_BRACKET)
    }
}
