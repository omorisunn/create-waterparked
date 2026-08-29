package net.omori_sunny.create_waterparked.content.waterslide

import com.simibubi.create.AllDataComponents
import com.simibubi.create.content.equipment.clipboard.ClipboardContent
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides
import com.simibubi.create.content.trains.track.BezierConnection
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.silvergold.simulatedcoasters.track.CoasterTrackGauge
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

// Server half of the clipboard <-> slide config interaction.
object SlideClipboardInteraction {

    // direct hit first, then the plot-center offset mapping used by every
    // other sublevel-aware interaction in this mod
    @JvmStatic
    fun resolveAnchorBe(level: ServerLevel, pos: BlockPos): WaterslideAnchorBlockEntity? {
        (level.getBlockEntity(pos) as? WaterslideAnchorBlockEntity)?.let { return it }
        val container = SubLevelContainer.getContainer(level) ?: return null
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            (level.getBlockEntity(pos.offset(sub.getPlot().getCenterBlock())) as? WaterslideAnchorBlockEntity)
                ?.let { return it }
        }
        return null
    }

    // both uses stay in sync: the useOn mixin swallows the vanilla use when
    // the clicked block is a slide anchor (direct or plot offset)
    @JvmStatic
    fun isAnchorBe(level: Level, pos: BlockPos): Boolean {
        if (level.getBlockEntity(pos) is WaterslideAnchorBlockEntity) return true
        val serverLevel = level as? ServerLevel ?: return false
        val container = SubLevelContainer.getContainer(serverLevel) ?: return false
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            if (level.getBlockEntity(pos.offset(sub.getPlot().getCenterBlock())) is WaterslideAnchorBlockEntity) {
                return true
            }
        }
        return false
    }

    // encode the sector config of one curve and append it as a new clipboard
    // line; peer may be null (single-leg anchor, the only curve is used)
    @JvmStatic
    fun copyToClipboard(level: ServerLevel, player: ServerPlayer, anchor: BlockPos, peer: BlockPos?): Boolean {
        val be = resolveAnchorBe(level, anchor)
        if (be == null) {
            fail(player, "create_waterparked.clipboard.copy_fail")
            return false
        }
        if (!player.canInteractWithBlock(
                be.blockPos,
                CoasterTrackGauge.maxCoasterCurvePacketInteractionRangeBlocks().toDouble()
            )
        ) {
            return false
        }
        val curve = pickCurve(level, be, peer)
        if (curve == null) {
            fail(player, "create_waterparked.clipboard.copy_fail")
            return false
        }
        val peerPos = if (curve.bePositions.getFirst() == be.blockPos) {
            curve.bePositions.getSecond()
        } else {
            curve.bePositions.getFirst()
        }
        val config = be.sectorConfigFor(peerPos)
        // name fallback: the block id of the first textured sector
        val blockPath = config.sectors.firstOrNull { it.material == SectorMaterial.BLOCK }?.blockId?.path
        val line = SlideClipboardCodec.serialize(blockPath ?: "Slide", config)
        appendToClipboard(player, line)
        ok(player, "create_waterparked.clipboard.copy_ok")
        return true
    }

    // the peer curve of an anchor, optionally constrained to one target peer
    @JvmStatic
    fun pickCurve(level: ServerLevel, be: WaterslideAnchorBlockEntity, peer: BlockPos?): BezierConnection? {
        if (peer != null) {
            val target = resolveSubLevelPosLocal(level, peer)
            val raw = be.getAnchorPeerCurvesView()[target] ?: return null
            return waterslidePrimary(raw)
        }
        for ((_, raw) in be.getAnchorPeerCurvesView()) {
            val primary = waterslidePrimary(raw) ?: continue
            if (primary.bePositions.getFirst() == be.blockPos ||
                primary.bePositions.getSecond() == be.blockPos
            ) return primary
        }
        return null
    }

    private fun waterslidePrimary(raw: BezierConnection): BezierConnection? {
        val primary = if (raw.isPrimary) raw else raw.secondary()
        return if (WaterslideTrackMaterials.isWaterslide(primary)) primary else null
    }

    // mirror of the network helper without the network package dependency
    private fun resolveSubLevelPosLocal(level: ServerLevel, pos: BlockPos): BlockPos {
        if (level.getBlockEntity(pos) != null) return pos
        val subLevels = SubLevelContainer.getContainer(level)?.allSubLevels ?: return pos
        for (raw in subLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            val candidate = pos.offset(sub.getPlot().getCenterBlock())
            if (level.getBlockEntity(candidate) != null) return candidate
        }
        return pos
    }

    // append the line to the held clipboard, skip existing duplicates
    private fun appendToClipboard(player: ServerPlayer, line: String) {
        val stack = player.mainHandItem
        val clipboard = stack.getOrDefault(AllDataComponents.CLIPBOARD_CONTENT, ClipboardContent.EMPTY)
        val list = ClipboardEntry.readAll(clipboard)
        var duplicate = false
        for (page in list) {
            for (entry in page) {
                if (entry.text.getString() == line) {
                    duplicate = true
                    break
                }
            }
            if (duplicate) break
        }
        if (duplicate) return
        // a fresh page starts once every existing page holds 12 entries
        var page = list.firstOrNull { it.size <= 11 }
        if (page == null) {
            page = ArrayList()
            list.add(page)
        }
        page.add(ClipboardEntry(false, Component.literal(line)))
        val updated = clipboard.setPages(list).setType(ClipboardOverrides.ClipboardType.WRITTEN)
        // the old held clipboard is consumed: put a fresh one in the hand
        val fresh = ItemStack(stack.item)
        fresh.set(AllDataComponents.CLIPBOARD_CONTENT, updated)
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, fresh)
        // the client stack is a separate object: sync the held stack back right
        // away (the periodic inventory broadcast can lag up to 0.5s and the GUI
        // would open before it) - stateId must match the inventory container
        player.connection.send(
            net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                0, player.inventoryMenu.stateId, player.inventory.selected, fresh
            )
        )
        net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
            "[SlideClipboard] server copied, pages={}", list.size
        )
    }

    private fun ok(player: ServerPlayer, key: String) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GREEN), true)
    }

    private fun fail(player: ServerPlayer, key: String) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true)
    }

    // item stack helper kept here for payload reuse
    @JvmStatic
    fun isClipboard(stack: ItemStack): Boolean =
        stack.item is com.simibubi.create.content.equipment.clipboard.ClipboardBlockItem
}
