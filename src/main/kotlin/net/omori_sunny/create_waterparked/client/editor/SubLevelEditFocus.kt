package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.AllItems
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult

// Simulated Coasters loses the active edit anchor inside Sable sub-levels
// (its hit test sees the local content position, but the anchor BE lives at
// the plot-global position). Keep our own fallback anchor and feed it back to
// the edit mode every tick so the extra control handles stay visible.
object SubLevelEditFocus {

    private var fallbackAnchor: BlockPos? = null

    fun activeAnchor(level: Level): BlockPos? {
        val current = BezierHandleEditMode.getActiveAnchor()
        if (current != null && SableClientEdit.resolve(level, current) != null) return current
        val fallback = fallbackAnchor
        if (fallback != null && SableClientEdit.resolve(level, fallback) != null) return fallback
        return null
    }

    fun tick(mc: Minecraft) {
        val level = mc.level ?: return clear()
        val player = mc.player ?: return clear()
        if (!AllItems.WRENCH.isIn(player.mainHandItem) && !AllItems.WRENCH.isIn(player.offhandItem)) return clear()
        if (!BezierHandleEditMode.isActive()) return clear()

        val current = BezierHandleEditMode.getActiveAnchor()
        if (current != null && SableClientEdit.resolve(level, current) != null) {
            fallbackAnchor = current
            return
        }

        val hit = mc.hitResult as? BlockHitResult
        val anchor = hit?.blockPos ?: fallbackAnchor ?: return
        val ctx = SableClientEdit.resolve(level, anchor) ?: return
        if (ctx.be.legCount() <= 0) return
        fallbackAnchor = anchor
        BezierHandleEditMode.switchActiveAnchorTo(anchor, level)
    }

    private fun clear() {
        fallbackAnchor = null
    }
}
