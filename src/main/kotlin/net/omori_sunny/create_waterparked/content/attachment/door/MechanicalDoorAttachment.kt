package net.omori_sunny.create_waterparked.content.attachment.door

import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour
import net.createmod.catnip.lang.LangBuilder
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachment
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentEntry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType

// shaft rotation drives it: positive speed opens, negative closes
class MechanicalDoorAttachment(
    type: SlideAttachmentType,
    entry: SlideAttachmentEntry
) : SlideAttachment(type, entry),
    net.omori_sunny.create_waterparked.content.attachment.IHaveSlideAttachmentEditor {

    override fun slideEditorKey(): String? = DoorStopDistanceEditor.EDITOR_KEY

    companion object {
        const val TAG_OPEN = "DoorOpenF"

        const val TAG_MODE = "DoorMode"

        private const val PASS_THRESHOLD = 0.7f

        private const val BAR_LENGTH = 18

        private const val OPEN_PER_TICK_AT_REF = 1.0f / 37.5f
        private const val REF_SPEED = 16f
    }

    private var open = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            data.putFloat(TAG_OPEN, field)
        }

    private var lastRiderSeen = 0L

    private var lastNotifiedPercent = -1

    init {
        open = data.getFloat(TAG_OPEN).coerceIn(0f, 1f)
    }

    // mirrored into the data so the client renderer can read the mode
    private fun currentMode(sab: SlideAttachmentBlockEntity): Int =
        (sab.getBehaviour(ScrollOptionBehaviour.TYPE) as? ScrollOptionBehaviour<*>)
            ?.value?.coerceIn(0, MechanicalDoorMode.entries.size - 1) ?: 0

    // driven when any adjacent kinetic block spins
    private fun drivenSpeed(level: ServerLevel, sab: SlideAttachmentBlockEntity): Float {
        val own = sab as? com.simibubi.create.content.kinetics.base.KineticBlockEntity
        if (own != null && kotlin.math.abs(own.speed) > 1.0E-3f) return own.speed
        for (dir in net.minecraft.core.Direction.entries) {
            val n = level.getBlockEntity(sab.blockPos.relative(dir))
            if (n is com.simibubi.create.content.kinetics.base.KineticBlockEntity &&
                kotlin.math.abs(n.speed) > 1.0E-3f
            ) return n.speed
        }
        return 0f
    }

    override fun serverTick(level: ServerLevel, sab: SlideAttachmentBlockEntity) {
        val mode = currentMode(sab)
        if (data.getInt(TAG_MODE) != mode) {
            data.putInt(TAG_MODE, mode)
            sync(sab)
        }
        val speed = drivenSpeed(level, sab)
        if (speed == 0f) return
        val before = open
        val delta = (speed / REF_SPEED) * OPEN_PER_TICK_AT_REF
        open = (open + delta).coerceIn(0f, 1f)
        if (open != before) {
            sync(sab)
            val percent = (open * 100f).toInt()
            if (percent != lastNotifiedPercent) {
                lastNotifiedPercent = percent
                DisplayLinkBlock.notifyGatherers(level, sab.blockPos)
            }
        }
    }

    override fun onTrigger(level: ServerLevel, sab: SlideAttachmentBlockEntity, candidate: Entity?) {
        lastRiderSeen = level.gameTime
    }

    override fun speedScaleAt(distance: Double): Double? {
        if (open >= PASS_THRESHOLD) return null
        val stop = currentStopDistance().toDouble()
        if (distance <= stop) return 0.0
        val x = ((distance - stop) / (5.0 - stop)).coerceIn(0.0, 1.0)
        return x * x * x
    }

    override fun addToGoggleTooltip(
        sab: SlideAttachmentBlockEntity,
        tooltip: MutableList<Component>,
        isPlayerSneaking: Boolean
    ) {
        val filled = Mth.clamp(Math.round(open * BAR_LENGTH), 0, BAR_LENGTH)
        val bar = Component.literal("|".repeat(filled))
            .withStyle(if (open >= PASS_THRESHOLD) ChatFormatting.GREEN else ChatFormatting.GOLD)
            .append(
                Component.literal("|".repeat(BAR_LENGTH - filled))
                    .withStyle(ChatFormatting.DARK_GRAY)
            )
        LangBuilder(CreateWaterparked.ID)
            .translate("gui.goggles.door_open_ratio")
            .style(ChatFormatting.GRAY)
            .space()
            .add(bar)
            .forGoggles(tooltip)
    }

    var riderSide: Int = 0

    private fun currentStopDistance(): Float =
        if (riderSide < 0) DoorStopDistanceEditor.stopL(data)
        else DoorStopDistanceEditor.stopR(data)
}
