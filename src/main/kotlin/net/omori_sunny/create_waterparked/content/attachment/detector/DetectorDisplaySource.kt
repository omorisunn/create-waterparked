package net.omori_sunny.create_waterparked.content.attachment.detector

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity

// cumulative rider count of the detector the link sits on
class DetectorDisplaySource : NumericSingleLineDisplaySource() {

    override fun getTranslationKey(): String = "detector_riders"

    override fun provideLine(
        context: DisplayLinkContext,
        stats: DisplayTargetStats
    ): MutableComponent {
        val zero = Component.literal("0")
        val sab = context.getSourceBlockEntity() as? SlideAttachmentBlockEntity ?: return zero
        val entry = sab.entry ?: return zero
        if (entry.typeId != ModSlideAttachments.SLIDE_DETECTOR.id.toString()) return zero
        return Component.literal(DetectorAttachment.riders(entry.data).toString())
    }

    override fun allowsLabeling(context: DisplayLinkContext): Boolean = true

    override fun getPassiveRefreshTicks(): Int = 200
}
