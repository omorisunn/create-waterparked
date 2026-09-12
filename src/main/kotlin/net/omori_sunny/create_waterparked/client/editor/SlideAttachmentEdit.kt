package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.AllItems
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentClientIndex
import net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentRenderer
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
import net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideControlPointEditor

// SA edit mode: wrench right-click on the attachment's world bounds opens
// the attachment's control point editor (declared via
// IHaveSlideAttachmentEditor); the model turns translucent while editing.
// Clicks that miss the bounds fall through untouched - CCS's anchor editor
// and the SAB block's wrench-reset keep working.
@OnlyIn(Dist.CLIENT)
object SlideAttachmentEdit {

    private const val EXIT_MISS_TICKS = 40L

    private var lastHitTick = 0L

    /** the open attachment, owned by the shared edit state machine */
    private val editingBePos: BlockPos?
        get() = SlideEditState.editingAttachmentPos()

    @JvmStatic
    fun isEditing(): Boolean = SlideEditState.isEditingAttachment()

    @JvmStatic
    fun isEditing(be: SlideAttachmentBlockEntity): Boolean = editingBePos == be.blockPos

    /** lightweight check used by the CCS mixin gate (no state change) */
    @JvmStatic
    fun pickHitsAttachment(): Boolean {
        val mc = Minecraft.getInstance()
        if (!holdingWrench(mc)) return false
        if (mc.player?.isShiftKeyDown == true) return false
        return pickAttachment(mc) != null
    }

    fun editingBe(): SlideAttachmentBlockEntity? {
        val pos = editingBePos ?: return null
        return SlideAttachmentClientIndex.all().firstOrNull { it.blockPos == pos }
    }

    /** nearest attachment whose world bounds the eye ray hits */
    fun pickAttachment(mc: Minecraft): SlideAttachmentBlockEntity? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val eye = player.eyePosition
        val rayEnd = eye.add(player.getViewVector(1f).scale(6.0))
        var best: SlideAttachmentBlockEntity? = null
        var bestD = Double.MAX_VALUE
        for (be in SlideAttachmentClientIndex.all()) {
            if (be.isRemoved) continue
            val entry = be.entry ?: continue
            val type = SlideAttachmentTypes.byTypeIdString(entry.typeId) ?: continue
            val ctx = SlideAttachmentGeometry.contextAt(
                level, be.blockPos, resolveCurve(level, entry) ?: continue,
                entry.t, entry.angle, entry.data,
                SlideAttachmentRenderer.renderTransform(level, entry.curveA)
            )
            val box = SlideAttachmentGeometry.worldBounds(ctx, type.providerFactory().boundingBox(ctx))
            val hit = box.inflate(0.05).clip(eye, rayEnd).orElse(null) ?: continue
            val d = eye.distanceToSqr(hit)
            if (d < bestD) {
                bestD = d
                best = be
            }
        }
        return best
    }

    private fun resolveCurve(
        level: net.minecraft.world.level.Level,
        entry: net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentEntry
    ): com.simibubi.create.content.trains.track.BezierConnection? {
        val anchor = level.getBlockEntity(entry.curveA)
            as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
            ?: return null
        val raw = anchor.anchorPeerCurvesView[entry.curveB.immutable()] ?: return null
        return if (raw.isPrimary) raw else raw.secondary()
    }

    /** passive chain probe: reports index/type/resolution without any click */
    private fun probeOncePerSecond(mc: Minecraft) {
        val level = mc.level ?: return
        if (level.gameTime % 40L != 0L) return
        val all = SlideAttachmentClientIndex.all()
        if (all.isEmpty()) return
        for (be in all) {
            val entry = be.entry
            val type = entry?.let {
                net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
                    .byTypeIdString(it.typeId)
            }
            val editor = net.omori_sunny.create_waterparked.client.editor.controlpoint
                .SlideAttachmentEditorRegistry.editorFor(be)
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "[SADebug] be={} entry={} type={} typeOk={} editorOk={} points={} mode={}",
                be.blockPos, entry?.typeId, type?.id, type != null, editor != null,
                editor?.debugResolveOk(), SlideEditState.mode()
            )
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "[SADebug] registered={} renderAllCalls={}",
                net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideControlPointEditor
                    .registeredCount(),
                net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideControlPointEditor
                    .renderAllCalls
            )
        }
    }

    private fun holdingWrench(mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        // main OR offhand, matching the control point framework - a mismatch
        // made the editor exit every tick when the wrench sat in the offhand
        return AllItems.WRENCH.isIn(player.mainHandItem) || AllItems.WRENCH.isIn(player.offhandItem)
    }

    private fun tryEnter(mc: Minecraft) {
        val be = pickAttachment(mc)
        if (be == null) {
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info("[SlideEdit] enter failed: no attachment under the crosshair")
            return
        }
        val editor = net.omori_sunny.create_waterparked.client.editor.controlpoint
            .SlideAttachmentEditorRegistry.editorFor(be)
        if (editor == null) {
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "[SlideEdit] enter failed: no editor for {} at {} (attachment={} key={})",
                be.entry?.typeId, be.blockPos, be.attachment()?.javaClass?.simpleName,
                (be.attachment() as? net.omori_sunny.create_waterparked.content.attachment
                    .IHaveSlideAttachmentEditor)?.slideEditorKey()
            )
            return
        }
        SlideEditState.enterAttachment(be.blockPos)
        lastHitTick = mc.level?.gameTime ?: 0L
        net.omori_sunny.create_waterparked.client.editor.WaterslideEditSounds.playCommitSuccess()
    }

    /**
     * Enter-only: while an editor is open the same click must reach the
     * control points (dragging), otherwise grabbing a handle would close the
     * editor and the UI would flicker. Exit via sneak-right-click.
     */
    fun toggle(mc: Minecraft) {
        if (editingBePos == null) tryEnter(mc)
    }

    fun exit() {
        SlideEditState.exitAttachment()
    }

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: run { exit(); return }
        probeOncePerSecond(mc)
        if (!SlideEditState.isEditingAttachment()) return
        // wrench left the hand or the BE vanished -> leave
        if (!holdingWrench(mc) || editingBe() == null) {
            exit()
            return
        }
        // never leave while a control point is being dragged
        if (SlideControlPointEditor.anyDragging()) {
            lastHitTick = level.gameTime
            return
        }
        // cursor stays near the attachment -> refresh the grace timer
        if (pickAttachment(mc) != null) {
            lastHitTick = level.gameTime
        } else if (level.gameTime - lastHitTick > EXIT_MISS_TICKS) {
            exit()
        }
    }

    @JvmStatic
    fun onUseItemKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) return
        val mc = Minecraft.getInstance()
        if (!holdingWrench(mc)) return
        if (mc.player?.isShiftKeyDown == true) return
        // let the two-phase placement and other combos act first
        if (net.omori_sunny.create_waterparked.client.editor.WaterslideGhostPlacement.ghostPlacementStack(mc.player ?: return) != null) return
        if (SlideControlPointEditor.anyDragging()) {
            event.setCanceled(true)
            event.setSwingHand(false)
            return
        }
        if (editingBePos != null) {
            if (mc.player?.isShiftKeyDown == true) {
                event.setCanceled(true)
                event.setSwingHand(true)
                exit()
                return
            }
            // keep the click for the control points; no swing animation while
            // the wrench is driving handles (CCS convention)
            event.setCanceled(true)
            event.setSwingHand(false)
            return
        }
        if (pickAttachment(mc) == null) return
        // receiveCanceled=true: CCS may have canceled first (its activation is
        // blocked by our mixin, but the event flag stays set) - we claim it
        event.setCanceled(true)
        event.setSwingHand(true)
        toggle(mc)
    }

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val mc = Minecraft.getInstance()
        if (!holdingWrench(mc)) return
        if (mc.player?.isShiftKeyDown == true) return
        if (SlideControlPointEditor.anyDragging()) {
            event.isCanceled = true
            return
        }
        if (editingBePos != null) {
            event.isCanceled = true
            return
        }
        // the SAB block itself handles wrench (material reset); the AABB is
        // air so a block hit here means the player aimed at something else
        if (pickAttachment(mc) == null) return
        event.isCanceled = true
        event.cancellationResult = net.minecraft.world.InteractionResult.SUCCESS
        mc.player?.swing(net.minecraft.world.InteractionHand.MAIN_HAND)
        toggle(mc)
    }
}
