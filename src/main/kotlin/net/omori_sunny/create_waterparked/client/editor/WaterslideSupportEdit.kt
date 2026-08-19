package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.AllItems
import net.createmod.catnip.outliner.Outliner
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.network.WaterslideSupportApplyPayload
import net.omori_sunny.create_waterparked.network.WaterslideSupportHoverPayload
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.network.PacketDistributor

// Client side of the copycat-style support material interaction. The support
// beam and bracket are Flywheel geometry without block hitboxes, so we ray-pick
// the same CPU-baked shapes every tick, outline the hovered part, and send an
// explicit apply payload on right-click (a canceled RightClickBlock never
// reaches the server as a block-use event).
@OnlyIn(Dist.CLIENT)
object WaterslideSupportEdit {

    private const val OUTLINE_BEAM = "create_waterparked_support_beam_hover"
    private const val OUTLINE_BRACKET = "create_waterparked_support_bracket_hover"
    private const val HOVER_COLOR = 0xFFC46A // warm amber, same family as editor highlights

    private var hovered: WaterslideTubeVisual.SupportPick? = null
    private var sentAnchor: net.minecraft.core.BlockPos? = null
    private var sentPart = -1

    @JvmStatic
    fun onClientTick() {
        val mc = Minecraft.getInstance() ?: return
        val player = mc.player ?: return
        val camera = mc.gameRenderer.mainCamera
        val rayStart = camera.position
        val rayDir = player.getViewVector(1.0f)

        val pick = WaterslideTubeVisual.pickSupport(rayStart, rayDir)
        hovered = pick

        if (pick != null) {
            val outliner = Outliner.getInstance()
            if (pick.part == 0) {
                outliner.remove(OUTLINE_BRACKET)
                outliner.showAABB(OUTLINE_BEAM, pick.outlineBox)
                    .colored(HOVER_COLOR)
                    .lineWidth(0.0625f)
            } else {
                outliner.remove(OUTLINE_BEAM)
                outliner.showAABB(OUTLINE_BRACKET, pick.outlineBox)
                    .colored(HOVER_COLOR)
                    .lineWidth(0.0625f)
            }
        } else {
            Outliner.getInstance().remove(OUTLINE_BEAM)
            Outliner.getInstance().remove(OUTLINE_BRACKET)
        }

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
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (!event.level.isClientSide) return
        val player = event.entity ?: return
        if (!canInteract(player.getItemInHand(event.hand))) return
        val pick = hovered ?: return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val blockDist = camera.position.distanceTo(event.hitVec.location)
        if (pick.distance > blockDist + 0.25) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        PacketDistributor.sendToServer(
            WaterslideSupportApplyPayload(
                pick.anchorPos,
                pick.part.toByte(),
                event.hand.ordinal.toByte(),
                (event.face?.ordinal ?: -1).toByte()
            )
        )
    }

    @JvmStatic
    fun onRightClickEmpty(event: PlayerInteractEvent.RightClickEmpty) {
        if (!event.level.isClientSide) return
        val player = event.entity ?: return
        if (!canInteract(player.getItemInHand(event.hand))) return
        val pick = hovered ?: return
        PacketDistributor.sendToServer(
            WaterslideSupportApplyPayload(
                pick.anchorPos,
                pick.part.toByte(),
                event.hand.ordinal.toByte(),
                net.minecraft.core.Direction.orderedByNearest(player)[0].ordinal.toByte()
            )
        )
    }

    private fun canInteract(stack: ItemStack): Boolean =
        !stack.isEmpty && (AllItems.WRENCH.isIn(stack) || stack.item is BlockItem)

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
