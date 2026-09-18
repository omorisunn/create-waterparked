package net.omori_sunny.create_waterparked.content.attachment.grab_bar

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity

// cumulative grab count of the grab bar the link sits on
class GrabBarDisplaySource : NumericSingleLineDisplaySource() {

    override fun getTranslationKey(): String = "grab_bar_grabs"

    override fun provideLine(
        context: DisplayLinkContext,
        stats: DisplayTargetStats
    ): MutableComponent {
        val zero = Component.literal("0")
        val sab = context.getSourceBlockEntity() as? SlideAttachmentBlockEntity ?: return zero
        val entry = sab.entry ?: return zero
        if (entry.typeId != ModSlideAttachments.GRAB_BAR.id.toString()) return zero
        return Component.literal(GrabBarAttachment.grabs(entry.data).toString())
    }

    override fun allowsLabeling(context: DisplayLinkContext): Boolean = true

    override fun getPassiveRefreshTicks(): Int = 200
}
