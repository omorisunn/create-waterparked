package net.omori_sunny.create_waterparked.client.editor
// Rivet tool: aiming at a waterslide tube wall with the Coasters rivet item
// routes the placement to the virtual tube rivets (server computes the
// position); aiming anywhere else falls through to the Coasters track
// placement untouched. HIGHEST priority so we run before Coasters' own
// RivetPlacementOutlineClient interception.

import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.omori_sunny.create_waterparked.network.WaterslideRivetPayload

object WaterslideRivetEdit {

    @JvmStatic
    fun onUseItemKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) return
        if (pickAndSend()) {
            event.setCanceled(true)
            event.setSwingHand(true)
        }
    }

    @JvmStatic
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.isCanceled) return
        if (!event.entity.level().isClientSide) return
        if (pickAndSend()) {
            event.isCanceled = true
            event.cancellationResult = net.minecraft.world.InteractionResult.SUCCESS
        }
    }

    // placement flows through the CCS pipeline (RivetPlacement mixin); we only
    // handle removal

    // wall pick along the view ray (same march as the ghost placement);
    // returns true when a tube wall was hit and the payload was sent
    private fun pickAndSend(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        var held = false
        for (hand in net.minecraft.world.InteractionHand.entries) {
            if (player.getItemInHand(hand).`is`(dev.silvergold.simulatedcoasters.SimulatedCoasters.RIVET.get())) {
                held = true
                break
            }
        }
        if (!held) return false
        if (!player.isShiftKeyDown) return false

        val eye = player.eyePosition
        val view = player.getViewVector(1f)
        var best: WaterslideSectorEdit.WallHit? = null
        var bestD = Double.MAX_VALUE
        var d = 0.0
        while (d <= 6.0) {
            val hit = WaterslideSectorEdit.resolveWallHit(level, eye.add(view.scale(d)))
            if (hit != null && d < bestD) {
                bestD = d
                best = hit
            }
            d += 0.075
        }
        val wall = best ?: return false

        // send the hit point in the curve's own (plot/local) space; the
        // server derives the authoritative (t, angle)
        val surface = wall.surfacePlot ?: return false
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            WaterslideRivetPayload(
                wall.curve.bePositions.getFirst(),
                wall.curve.bePositions.getSecond(),
                player.isShiftKeyDown,
                surface.x.toFloat(), surface.y.toFloat(), surface.z.toFloat()
            )
        )
        return true
    }
}
