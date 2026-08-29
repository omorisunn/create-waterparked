package net.omori_sunny.create_waterparked.client.editor

import com.simibubi.create.content.equipment.clipboard.ClipboardBlockItem
import com.simibubi.create.content.trains.track.BezierConnection
import dev.silvergold.simulatedcoasters.track.CoasterTrackPlacement
import net.createmod.catnip.outliner.Outliner
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents
import net.omori_sunny.create_waterparked.network.WaterslideSlidePastePayload
import net.omori_sunny.create_waterparked.network.WaterslideSlidePasteStatePayload
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.AABB
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.network.PacketDistributor

// Client paste mode for the clipboard slide config.
@OnlyIn(Dist.CLIENT)
object WaterslideClipboardPaste {

    private const val KEY_A = "waterslide_clipboard_a"
    private const val KEY_B = "waterslide_clipboard_b"
    private const val KEY_CURVE = "waterslide_clipboard_curve"
    private const val HINT_INTERVAL = 20L

    private var active = false
    private var line: String? = null
    private var prevSegments = 0
    private var lastHintTick = -1L

    @JvmStatic
    fun isActive(): Boolean = active

    // enter paste mode from the clipboard GUI click
    @JvmStatic
    fun enter(newLine: String) {
        val mc = Minecraft.getInstance() ?: return
        val player = mc.player ?: return
        val stack = player.mainHandItem
        if (stack.item !is ClipboardBlockItem) return
        // local first: zero-latency foil, the server echo pushes the same value
        stack.set(ModDataComponents.SLIDE_PASTE_LINE, newLine)
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        run {
            val content = stack.getOrDefault(
                com.simibubi.create.AllDataComponents.CLIPBOARD_CONTENT,
                com.simibubi.create.content.equipment.clipboard.ClipboardContent.EMPTY
            )
            val lines = com.simibubi.create.content.equipment.clipboard.ClipboardEntry.readAll(content)
                .flatMap { it.toList() }
            val first = lines.firstOrNull()?.text?.getString()?.take(48) ?: "<empty>"
            net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                "[SlidePaste] enter lines={} first=\"{}\" glint={}",
                lines.size, first, stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) != null
            )
        }
        PacketDistributor.sendToServer(WaterslideSlidePasteStatePayload(newLine))
        active = true
        line = newLine
        prevSegments = 0
        lastHintTick = -1L
        player.displayClientMessage(
            Component.translatable("create_waterparked.clipboard.paste_enter")
                .withStyle(ChatFormatting.GREEN),
            true
        )
    }

    // leave paste mode; the foil and the outline disappear
    @JvmStatic
    fun exit(silent: Boolean = false) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player != null) {
            val stack = player.mainHandItem
            if (stack.item is ClipboardBlockItem && stack.has(ModDataComponents.SLIDE_PASTE_LINE)) {
                stack.remove(ModDataComponents.SLIDE_PASTE_LINE)
                stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)
                player.setItemInHand(InteractionHand.MAIN_HAND, stack)
            }
        }
        if (mc.connection != null) {
            PacketDistributor.sendToServer(WaterslideSlidePasteStatePayload(null))
        }
        if (active && !silent) {
            player?.displayClientMessage(
                Component.translatable("create_waterparked.clipboard.paste_exit")
                    .withStyle(ChatFormatting.YELLOW),
                true
            )
        }
        active = false
        line = null
        clearOutlines()
    }

    @JvmStatic
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return exit(silent = true)
        val stack = player.mainHandItem
        // any item switch or a missing component clears the state client side
        if (stack.item !is ClipboardBlockItem) return exit(silent = true)
        val comp = stack.get(ModDataComponents.SLIDE_PASTE_LINE)
        if (comp == null) {
            if (active) exit(silent = true)
            return
        }
        // self heal: after a relog the component syncs back but the static
        // state was reset - re-derive the paste mode from the component
        if (!active) {
            active = true
            line = comp
            prevSegments = 0
            lastHintTick = -1L
        }
        val level = mc.level ?: return exit(silent = true)
        val hit = WaterslideSectorEdit.pickWallAtCursor(mc)
        if (hit == null) {
            clearOutlines()
            maybeHint(player, level)
            return
        }
        val raw = hit.curve
        val primary = if (raw.isPrimary) raw else raw.secondary()
        showCurveOutline(primary)
    }

    @JvmStatic
    fun onUseItemKey(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem) return
        if (!active) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val stack = player.mainHandItem
        if (stack.item !is ClipboardBlockItem) return
        // the paste mode owns every use key until it is explicitly exited
        event.setCanceled(true)
        if (player.isShiftKeyDown) {
            event.setSwingHand(false)
            exit(silent = false)
            return
        }
        val pasteLine = line ?: return exit(silent = true)
        val hit = WaterslideSectorEdit.pickWallAtCursor(mc)
        if (hit == null) {
            event.setSwingHand(false)
            player.displayClientMessage(
                Component.translatable("create_waterparked.clipboard.paste_hint")
                    .withStyle(ChatFormatting.YELLOW),
                true
            )
            return
        }
        event.setSwingHand(true)
        PacketDistributor.sendToServer(
            WaterslideSlidePastePayload(
                hit.curve.bePositions.getFirst(),
                hit.curve.bePositions.getSecond(),
                pasteLine
            )
        )
        player.displayClientMessage(
            Component.translatable("create_waterparked.clipboard.paste_ok")
                .withStyle(ChatFormatting.GREEN),
            true
        )
    }

    private fun showCurveOutline(primary: BezierConnection) {
        val first = primary.bePositions.getFirst()
        val second = primary.bePositions.getSecond()
        Outliner.getInstance().showAABB(KEY_A, AABB(first).inflate(0.05))
            .colored(CoasterTrackPlacement.CONNECTION_VALID_GREEN)
            .lineWidth(0.0625f)
        Outliner.getInstance().showAABB(KEY_B, AABB(second).inflate(0.05))
            .colored(CoasterTrackPlacement.CONNECTION_VALID_GREEN)
            .lineWidth(0.0625f)
        // the draw call replaces the previous segments via the count pairing
        prevSegments = CoasterTrackPlacement.drawCoasterCurveOutlinePreview(
            primary, KEY_CURVE, CoasterTrackPlacement.CONNECTION_VALID_GREEN, 0.0, prevSegments
        )
    }

    private fun clearOutlines() {
        val outliner = Outliner.getInstance()
        outliner.remove(KEY_A)
        outliner.remove(KEY_B)
        if (prevSegments > 0) {
            CoasterTrackPlacement.clearCoasterCurveOutlinePreview(KEY_CURVE, prevSegments)
            prevSegments = 0
        }
    }

    private fun maybeHint(player: net.minecraft.world.entity.player.Player, level: net.minecraft.world.level.Level) {
        if (level.gameTime - lastHintTick < HINT_INTERVAL) return
        lastHintTick = level.gameTime
        player.displayClientMessage(
            Component.translatable("create_waterparked.clipboard.paste_hint")
                .withStyle(ChatFormatting.YELLOW),
            true
        )
    }
}
