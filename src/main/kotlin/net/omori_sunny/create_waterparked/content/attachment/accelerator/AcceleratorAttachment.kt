package net.omori_sunny.create_waterparked.content.attachment.accelerator

import net.createmod.catnip.lang.LangBuilder
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.attachment.IHaveSlideAttachmentEditor
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachment
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentEntry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentKinetics
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType
import net.omori_sunny.create_waterparked.content.attachment.SlideHandleDistance
import kotlin.math.abs

// adds a one shot velocity pulse along its own 2D direction when a rider crosses the band
class AcceleratorAttachment(
    type: SlideAttachmentType,
    entry: SlideAttachmentEntry
) : SlideAttachment(type, entry), IHaveSlideAttachmentEditor {

    override fun slideEditorKey(): String? = AcceleratorDistanceEditor.EDITOR_KEY

    companion object {
        const val TAG_DIST_L = "AccelDistL"

        const val TAG_DIST_R = "AccelDistR"

        const val TAG_DIR = "AccelDir"

        const val TAG_RATE = "AccelRate"

        private const val BAR_LENGTH = 18
        private const val BAR_FULL_RPM = 64.0
        private const val RATE_EPSILON = 0.05f

        fun distL(data: CompoundTag): Float = SlideHandleDistance.read(data, TAG_DIST_L)

        fun distR(data: CompoundTag): Float = SlideHandleDistance.read(data, TAG_DIST_R)

        // degrees from the slide tangent toward the tube lateral axis
        fun directionDegrees(data: CompoundTag): Float = data.getFloat(TAG_DIR)

        fun directionRadians(data: CompoundTag): Double =
            Math.toRadians(directionDegrees(data).toDouble())

        // blocks per second added at the given rpm, signed by the rotation direction
        fun pulseSpeed(rpm: Float): Double =
            ModConfig.acceleratorBaseSpeed() * (rpm / ModConfig.acceleratorReferenceRpm())
    }

    // the client cannot read the server config, so the rate travels in the synced data
    override fun serverTick(level: net.minecraft.server.level.ServerLevel, sab: SlideAttachmentBlockEntity) {
        val rpm = SlideAttachmentKinetics.drivenSpeed(level, sab)
        val rate = pulseSpeed(rpm).toFloat()
        if (kotlin.math.abs(rate - data.getFloat(TAG_RATE)) < RATE_EPSILON) return
        data.putFloat(TAG_RATE, rate)
        sync(sab)
    }

    override fun addToGoggleTooltip(
        sab: SlideAttachmentBlockEntity,
        tooltip: MutableList<Component>,
        isPlayerSneaking: Boolean
    ) {
        val rateValue = data.getFloat(TAG_RATE).toDouble()
        val rate = abs(rateValue)
        val filled = Mth.clamp(Math.round(rate / pulseSpeed(BAR_FULL_RPM.toFloat()) * BAR_LENGTH).toInt(), 0, BAR_LENGTH)
        val colour = when {
            rateValue == 0.0 -> ChatFormatting.DARK_GRAY
            rateValue > 0.0 -> ChatFormatting.GREEN
            else -> ChatFormatting.RED
        }
        val bar = Component.literal("|".repeat(filled))
            .withStyle(colour)
            .append(
                Component.literal("|".repeat(BAR_LENGTH - filled)).withStyle(ChatFormatting.DARK_GRAY)
            )
        LangBuilder(CreateWaterparked.ID)
            .translate(
                if (rateValue < 0.0) "gui.goggles.accelerator_reverse"
                else "gui.goggles.accelerator_forward"
            )
            .style(colour)
            .space()
            .add(bar)
            .space()
            .add(Component.literal("%.1f".format(java.util.Locale.ROOT, rate) + "m/s"))
            .forGoggles(tooltip)
    }
}
