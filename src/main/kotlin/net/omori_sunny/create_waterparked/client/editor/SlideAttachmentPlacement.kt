package net.omori_sunny.create_waterparked.client.editor

import net.createmod.catnip.outliner.Outliner
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockItem
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentGeometry
import net.omori_sunny.create_waterparked.client.attachment.SlideAttachmentRenderer
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentSite
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
import net.omori_sunny.create_waterparked.network.SlideAttachmentSelectPayload

// phase 1 of SAB placement: holding the binding-block item and hovering the
// slide shows a green preview box (the type provider's bounds); right click
// stores the position on the item (glint) for the ground placement phase 2.
@OnlyIn(Dist.CLIENT)
object SlideAttachmentPlacement {

    private const val OUTLINE_KEY = "waterparked:slide_attachment_preview"
    private const val GREEN = 0x30FF60
    private const val RED = 0xE04040

    private var lastOutlineShown = false

    /** main-hand SAB item + its type, or null */
    private fun heldSab(mc: Minecraft): Pair<ItemStack, net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType>? {
        val player = mc.player ?: return null
        val stack = player.getMainHandItem()
        val item = stack.item as? SlideAttachmentBlockItem ?: return null
        return stack to item.type()
    }

    /** wall pick gated by the type's site rules; invalid sites get feedback */
    private fun pick(mc: Minecraft, type: net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType
    ): WaterslideSectorEdit.WallHit? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        if (player.isShiftKeyDown) return null
        val wall = WaterslideSectorEdit.marchWallHit(level, player.eyePosition, player.getViewVector(1f))
            ?: return null
        if (!siteValid(type, wall)) {
            // red flash + hint, frogport style rejection
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("create_waterparked.attachment.wrong_site"), true
            )
            level.addParticle(
                net.minecraft.core.particles.ParticleTypes.CRIT,
                wall.surfacePlot?.x ?: player.x,
                (wall.surfacePlot?.y ?: player.eyeY),
                wall.surfacePlot?.z ?: player.z,
                0.0, 0.1, 0.0
            )
            return null
        }
        return wall
    }

    private fun sendSelection(
        type: net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType,
        wall: WaterslideSectorEdit.WallHit
    ) {
        val curveA = wall.curve.bePositions.getFirst()
        val curveB = wall.curve.bePositions.getSecond()
        val t = when (type.site) {
            SlideAttachmentSite.ENDPOINT -> if (wall.t < 0.5f) 0f else 1f
            SlideAttachmentSite.INTERIOR -> wall.t.coerceIn(0.02f, 0.98f)
        }
        PacketDistributor.sendToServer(
            SlideAttachmentSelectPayload(
                type.id.toString(), curveA, curveB, type.site, t, wall.angle
            )
        )
    }

    // ---- event entries (same pattern as the ghost placement) ----

    @JvmStatic
    fun onUseItemKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) return
        val mc = Minecraft.getInstance()
        val held = heldSab(mc) ?: return
        val wall = pick(mc, held.second) ?: return
        if (event.isCanceled) return
        event.setCanceled(true)
        event.setSwingHand(true)
        sendSelection(held.second, wall)
    }

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val mc = Minecraft.getInstance()
        val held = heldSab(mc) ?: return
        val wall = pick(mc, held.second) ?: return
        if (event.isCanceled) return
        event.isCanceled = true
        event.cancellationResult = net.minecraft.world.InteractionResult.SUCCESS
        mc.player?.swing(InteractionHand.MAIN_HAND)
        sendSelection(held.second, wall)
    }

    @JvmStatic
    fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
        val mc = Minecraft.getInstance()
        val held = heldSab(mc) ?: return
        val wall = pick(mc, held.second) ?: return
        if (event.isCanceled) return
        event.isCanceled = true
        event.cancellationResult = net.minecraft.world.InteractionResult.SUCCESS
        mc.player?.swing(InteractionHand.MAIN_HAND)
        sendSelection(held.second, wall)
    }

    // ---- per-tick hover preview ----

    /** site gate result for a wall hit */
    private fun siteValid(
        type: net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType,
        wall: WaterslideSectorEdit.WallHit
    ): Boolean = when (type.site) {
        SlideAttachmentSite.INTERIOR -> wall.t in 0.02f..0.98f
        SlideAttachmentSite.ENDPOINT -> wall.t < 0.2f || wall.t > 0.8f
    }

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val held = heldSab(mc)
        val player = mc.player
        val level = mc.level ?: return
        var hover: Pair<net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType, WaterslideSectorEdit.WallHit>? = null
        if (held != null && player != null && level != null && !player.isShiftKeyDown) {
            WaterslideSectorEdit.marchWallHit(level, player.eyePosition, player.getViewVector(1f))
                ?.let { hover = held.second to it }
        }
        if (hover == null) {
            if (lastOutlineShown) {
                Outliner.getInstance().remove(OUTLINE_KEY)
                lastOutlineShown = false
            }
            return
        }
        val (type, wall) = hover!!
        val valid = siteValid(type, wall)
        val ctx = SlideAttachmentGeometry.contextAt(
            level, BlockPos.ZERO, wall.curve,
            if (type.site == SlideAttachmentSite.ENDPOINT) (if (wall.t < 0.5f) 0f else 1f) else wall.t,
            wall.angle,
            CompoundTag(),
            SlideAttachmentRenderer.renderTransform(level, wall.curve.bePositions.getFirst())
        )
        val provider = type.providerFactory()
        val worldBox = SlideAttachmentGeometry.worldBounds(ctx, provider.boundingBox(ctx))
        // frogport style: green when mountable, red when the site is wrong
        Outliner.getInstance()
            .showAABB(OUTLINE_KEY, worldBox.inflate(0.02))
            .colored(if (valid) GREEN else RED)
            .lineWidth(0.0625f)
        lastOutlineShown = true
        if (valid && level.gameTime % 6L == 0L) {
            val c = worldBox.center
            level.addParticle(
                net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                c.x + (Math.random() - 0.5) * worldBox.xsize,
                c.y + (Math.random() - 0.5) * worldBox.ysize,
                c.z + (Math.random() - 0.5) * worldBox.zsize,
                0.0, 0.05, 0.0
            )
        }
    }
}
