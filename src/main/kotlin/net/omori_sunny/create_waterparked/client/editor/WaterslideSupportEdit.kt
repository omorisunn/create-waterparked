package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.AllItems
import net.createmod.catnip.outliner.Outliner
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeMesh
import net.omori_sunny.create_waterparked.config.ModClientConfig
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.network.WaterslideSupportApplyPayload
import net.omori_sunny.create_waterparked.network.WaterslideSupportHoverPayload
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.abs
import kotlin.math.max

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
        if (mc.player == null) return
        val pick = freshPick()
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

    // Original flywheel/geometry pick: visual instances first, the BER CPU
    // fallback second. Mirror of the rendered support geometry.
    @JvmStatic
    fun freshPick(): WaterslideTubeVisual.SupportPick? {
        return try {
            val mc = Minecraft.getInstance() ?: return null
            val player = mc.player ?: return null
            val camera = mc.gameRenderer.mainCamera
            val rayStart = camera.position
            val rayDir = player.getViewVector(1.0f)
            WaterslideTubeVisual.pickSupport(rayStart, rayDir)
                ?: net.omori_sunny.create_waterparked.client.renderer.WaterslideTubeBlockEntityRenderer
                    .pickSupport(mc.level ?: return null, rayStart, rayDir)
        } catch (t: Throwable) {
            // a pick failure must never crash the click path; the editor gate
            // keeps its own cache fallback
            null
        }
    }

    // use-key path: the most reliable hook for the support click - the input
    // stage runs BEFORE every interaction event, so the vanilla wrench use and
    // the coaster's click handling never see a click aimed at support geometry
    // (independent of event priority/order; mirrors WaterslideSectorEdit).
    // The click uses the SAME hovered cache that drives the Outline render:
    // what you see highlighted is exactly what the click acts on.
    @JvmStatic
    fun onUseItemKey(event: net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        // slide-native pick first; fall back to a fresh ray march at click time
        // (the hover cache lags one tick behind handleKeybinds)
        val pick = hovered ?: freshPick() ?: return
        val mainOk = outlineShownFor(pick, mc, player.mainHandItem)
        val offOk = outlineShownFor(pick, mc, player.offhandItem)
        if (!mainOk && !offOk) {
            diagnoseUnresolvedBe(pick)
            event.setCanceled(true)
            event.setSwingHand(false)
            return
        }
        val usedHand = if (mainOk) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
        event.setCanceled(true)
        event.setSwingHand(true)
        performSupportApply(player, pick, usedHand, net.minecraft.core.Direction.orderedByNearest(player)[0])
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
        val mc = Minecraft.getInstance()
        val pick = hovered ?: freshPick()
        if (!outlineShownFor(pick ?: return, mc, heldItem)) {
            // hovered a support part but this hand combo is rejected (e.g.
            // wrench on an empty part): swallow the click so the slide editor
            // does not pick it up - the support interaction owns any click
            // aimed at support geometry
            diagnoseUnresolvedBe(pick)
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
        val mc = Minecraft.getInstance()
        val pick = hovered ?: freshPick()
        // the same hand logic as the outline, no hand mismatch
        if (!outlineShownFor(pick ?: return, mc, heldItem)) {
            // swallowed: any click on support geometry belongs to the support
            // interaction, even when the held combo is rejected (wrench on an
            // empty part must not fall through to the slide sector editor)
            diagnoseUnresolvedBe(pick)
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            return
        }
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        performSupportApply(player, pick, usedHand, event.face)
    }

    // shared: optimistic wrench refresh + payload + feedback sound
    private fun performSupportApply(
        player: net.minecraft.world.entity.player.Player,
        pick: WaterslideTubeVisual.SupportPick,
        usedHand: InteractionHand,
        face: net.minecraft.core.Direction?
    ) {
        val heldItem = player.getItemInHand(usedHand)
        val partEnum = if (pick.part == 0)
            net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM
        else
            net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET
        // optimistic client refresh: the server apply is authoritative, but the
        // flywheel visual only rebuilds when its data signature changes and the
        // signature does NOT track support visibility - so visibility-only
        // applies (wrench restore) need the unconditional rebuild
        // (WaterslideTubeVisual itself stays untouched). Wrench optimism is
        // safe: a rejected restore is a no-op (part already visible) and a
        // stale material view cannot diverge (both sides agree on the default).
        val level = Minecraft.getInstance().level ?: return
        val localBe = resolveBe(level, pick.anchorPos)
        var localChanged = false
        if (localBe != null && AllItems.WRENCH.isIn(heldItem)) {
            if (localBe.hasCustomSupportMaterial(partEnum)) {
                localBe.resetSupportMaterial(partEnum)
                localChanged = true
            }
            if (!localBe.isSupportVisible(partEnum)) {
                localBe.setSupportVisible(partEnum, true)
                localChanged = true
            }
        }
        // KNOWN BOUNDARY (documented per t7 contract): the axe delete does NOT
        // run an optimistic hide. An optimistic hide could diverge when the
        // server rejects the delete (client BE momentarily stale) and no client
        // correction exists: the apply payload is one-way, so a rejected
        // delete would leave the part hidden forever. The authoritative BE NBT
        // sync instead delivers visible=false and the read() visibility gate
        // rebuilds the visuals within 1-2 ticks.
        if (localChanged) {
            net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual.refreshAll()
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

    // main world first, then the sublevel editor focus (Sable plots store their
    // anchors in a separate world - mc.level.getBlockEntity alone misses them)
    private fun resolveBe(
        level: net.minecraft.world.level.Level,
        anchor: net.minecraft.core.BlockPos
    ): net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity? {
        (level.getBlockEntity(anchor) as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity)?.let { return it }
        return try {
            SableClientEdit.resolve(level, anchor)?.be as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
        } catch (t: Throwable) {
            null
        }
    }

    // one-shot diagnostic (per client session): a support-geometry click was
    // swallowed because the local BE could not be resolved - no payload was
    // sent and the server never heard about it (chunk flicker / sublevel refs)
    private var unresolvedDiagDone = false

    private fun diagnoseUnresolvedBe(pick: WaterslideTubeVisual.SupportPick) {
        if (unresolvedDiagDone) return
        val mc = Minecraft.getInstance() ?: return
        if (resolveBe(mc.level ?: return, pick.anchorPos) != null) return
        unresolvedDiagDone = true
        net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.warn(
            "[SupportApply] client BE unresolved anchor={} part={} - click swallowed without payload",
            pick.anchorPos, pick.part
        )
    }

    // outline visibility by fill state and the held tool, for one specific hand
    private fun outlineShownFor(pick: WaterslideTubeVisual.SupportPick, mc: Minecraft, held: ItemStack): Boolean {
        if (held.isEmpty) return false
        val isAxe = held.item is AxeItem
        val isWrench = AllItems.WRENCH.isIn(held)
        val isBlock = held.item is BlockItem
        // the wrench always owns support clicks: the server resolves the anchor
        // BE and decides what applies (a rejected combo still belongs to the
        // support interaction) - a momentarily unresolvable client BE must never
        // eat the click, that is how support clicks silently got lost
        if (isWrench) return true
        val be = resolveBe(mc.level ?: return false, pick.anchorPos) ?: return false
        val part = if (pick.part == 0)
            net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM
        else
            net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET
        val deleted = !be.isSupportVisible(part)
        val filled = be.hasCustomSupportMaterial(part)
        return when {
            // deleted part: only the wrench may restore it
            deleted -> false
            // filled part: only the wrench may act
            filled -> false
            // empty part: axe and blocks may act
            else -> isAxe || isBlock
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
