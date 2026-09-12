package net.omori_sunny.create_waterparked.content.attachment.door

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext
import com.simibubi.create.content.redstone.displayLink.source.PercentOrProgressBarDisplaySource
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder
import net.createmod.catnip.lang.LangBuilder
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.Mth
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity

// bound to the door's binding block, mirroring Create's percent/progress sources
class MechanicalDoorDisplaySource : PercentOrProgressBarDisplaySource() {

    override fun getTranslationKey(): String = "mechanical_door"

    // null = no door on that block; the base class then outputs nothing
    override fun getProgress(context: DisplayLinkContext): Float? {
        val sab = context.getSourceBlockEntity() as? SlideAttachmentBlockEntity ?: return null
        val entry = sab.entry ?: return null
        if (entry.typeId != ModSlideAttachments.MECHANICAL_DOOR.id.toString()) return null
        return Mth.clamp(entry.data.getFloat(MechanicalDoorAttachment.TAG_OPEN), 0f, 1f)
    }

    // 0 = percentage, 1 = progress bar
    override fun progressBarActive(context: DisplayLinkContext): Boolean =
        context.sourceConfig().getInt("Mode") != 0

    override fun allowsLabeling(context: DisplayLinkContext): Boolean = true

    @OnlyIn(Dist.CLIENT)
    override fun initConfigurationWidgets(
        context: DisplayLinkContext,
        builder: ModularGuiLineBuilder,
        isFirstLine: Boolean
    ) {
        super.initConfigurationWidgets(context, builder, isFirstLine)
        if (isFirstLine) return
        builder.addSelectionScrollInput(0, 120, { si, _ ->
            si.forOptions(
                listOf(
                    option("percent"),
                    option("progress_bar")
                )
            ).titled(option("display"))
        }, "Mode")
    }

    private companion object {
        fun option(key: String): MutableComponent =
            LangBuilder(CreateWaterparked.ID)
                .translate("display_source.mechanical_door.$key")
                .component()
    }
}
