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

@OnlyIn(Dist.CLIENT)
object SlideAttachmentEdit {

    private const val EXIT_MISS_TICKS = 40L

    private var lastHitTick = 0L

    private val editingBePos: BlockPos?
        get() = SlideEditState.editingAttachmentPos()

    @JvmStatic
    fun isEditing(): Boolean = SlideEditState.isEditingAttachment()

    @JvmStatic
    fun isEditing(be: SlideAttachmentBlockEntity): Boolean = editingBePos == be.blockPos

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
                level, be.blockPos, resolveCurve(level, entry.curveA, entry.curveB) ?: continue,
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

    internal fun resolveCurve(
        level: net.minecraft.world.level.Level,
        curveA: BlockPos,
        curveB: BlockPos
    ): com.simibubi.create.content.trains.track.BezierConnection? {
        val anchor = level.getBlockEntity(curveA)
            as? net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
            ?: return null
        val raw = anchor.anchorPeerCurvesView[curveB.immutable()] ?: return null
        return if (raw.isPrimary) raw else raw.secondary()
    }

    private fun holdingWrench(mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        return AllItems.WRENCH.isIn(player.mainHandItem) || AllItems.WRENCH.isIn(player.offhandItem)
    }

    private fun tryEnter(mc: Minecraft) {
        val be = pickAttachment(mc)
        if (be == null) {
            return
        }
        val editor = net.omori_sunny.create_waterparked.client.editor.controlpoint
            .SlideAttachmentEditorRegistry.editorFor(be)
        if (editor == null) {
            return
        }
        SlideEditState.enterAttachment(be.blockPos)
        lastHitTick = mc.level?.gameTime ?: 0L
        net.omori_sunny.create_waterparked.client.editor.WaterslideEditSounds.playCommitSuccess()
    }

    // enter only, sneak-right-click exits
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
        if (!SlideEditState.isEditingAttachment()) return
        if (!holdingWrench(mc) || editingBe() == null) {
            exit()
            return
        }
        if (SlideControlPointEditor.anyDragging()) {
            lastHitTick = level.gameTime
            return
        }
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
            event.setCanceled(true)
            event.setSwingHand(false)
            return
        }
        if (pickAttachment(mc) == null) return
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
        if (pickAttachment(mc) == null) return
        event.isCanceled = true
        event.cancellationResult = net.minecraft.world.InteractionResult.SUCCESS
        mc.player?.swing(net.minecraft.world.InteractionHand.MAIN_HAND)
        toggle(mc)
    }
}
