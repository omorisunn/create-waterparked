package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.AllItems
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.BlockHitResult

// Simulated Coasters loses the active edit anchor inside Sable sub-levels
// (its hit test sees the local content position, but the anchor BE lives at
// the plot-global position). When the wrench edit mode is active and the
// player is looking at a sub-level waterslide anchor, re-point the edit mode
// at it every tick so the extra control handles stay visible.
object SubLevelEditFocus {

    fun tick(mc: Minecraft) {
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (!AllItems.WRENCH.isIn(player.mainHandItem) && !AllItems.WRENCH.isIn(player.offhandItem)) return
        if (!BezierHandleEditMode.isActive()) return

        val current = BezierHandleEditMode.getActiveAnchor()
        if (current != null && SableClientEdit.resolve(level, current) != null) return

        val hit = mc.hitResult as? BlockHitResult ?: return
        val ctx = SableClientEdit.resolve(level, hit.blockPos) ?: return
        if (ctx.be.legCount() <= 0) return
        BezierHandleEditMode.switchActiveAnchorTo(hit.blockPos, level)
    }
}
