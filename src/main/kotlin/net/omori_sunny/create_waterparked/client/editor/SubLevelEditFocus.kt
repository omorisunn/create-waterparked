package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.AllItems
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager
import dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.omori_sunny.create_waterparked.CreateWaterparked

// keeps our own plot global focus anchor, Simulated Coasters loses it in sub levels
object SubLevelEditFocus {

    private var fallbackAnchor: BlockPos? = null

    @JvmStatic
    fun isActive(level: Level): Boolean = activeAnchor(level) != null

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

        val current = BezierHandleEditMode.getActiveAnchor()
        val currentCtx = current?.let { SableClientEdit.resolve(level, it) }
        val hit = mc.hitResult as? BlockHitResult
        val hitCtx = hit?.blockPos?.let { SableClientEdit.resolve(level, it) }

        if (currentCtx != null) {
            // never yank the anchor away from an in flight drag
            if (BezierHandleDragManager.isDraggingHandle() ||
                WaterslideRadiusEdit.isDragging() ||
                WaterslideSectorEdit.isDraggingControlPoint()
            ) {
                fallbackAnchor = currentCtx.globalPos
                return
            }
            // hover switching only inside Sable space, main world stays with SC
            if (currentCtx.sub != null || hitCtx?.sub != null) {
                if (hitCtx != null && hitCtx.globalPos != currentCtx.globalPos && hitCtx.be.legCount() > 0) {
                    fallbackAnchor = hitCtx.globalPos
                    CreateWaterparked.LOGGER.debug(
                        "[SubEditFocus] hover switch {} -> {} sub={}",
                        currentCtx.globalPos, hitCtx.globalPos, hitCtx.sub?.uniqueId
                    )
                    return
                }
            }
            fallbackAnchor = currentCtx.globalPos
            return
        }

        if (hitCtx != null && hitCtx.sub != null && hitCtx.be.legCount() > 0) {
            fallbackAnchor = hitCtx.globalPos
            CreateWaterparked.LOGGER.debug(
                "[SubEditFocus] activate {} sub={}",
                hitCtx.globalPos, hitCtx.sub?.uniqueId
            )
            return
        }

        // keep the last anchor while the crosshair is on empty space
        val fallback = fallbackAnchor
        if (fallback == null || SableClientEdit.resolve(level, fallback) == null) {
            clear()
        }
    }

    private fun clear() {
        fallbackAnchor = null
    }
}
