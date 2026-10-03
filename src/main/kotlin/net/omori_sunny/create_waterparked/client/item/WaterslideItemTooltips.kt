package net.omori_sunny.create_waterparked.client.item

import com.simibubi.create.foundation.item.ItemDescription
import com.simibubi.create.foundation.item.TooltipModifier
import net.createmod.catnip.lang.FontHelper
import net.minecraft.client.resources.language.I18n
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import net.omori_sunny.create_waterparked.content.registry.ModItems

object WaterslideItemTooltips {

    private const val KEY_PREFIX = "item.create_waterparked."

    @JvmStatic
    fun register() {
        TooltipModifier.REGISTRY.register(ModItems.WATERSLIDE_ANCHOR) { event ->
            tooltip(event, "waterslide_anchor")
        }
        TooltipModifier.REGISTRY.register(ModItems.WATERSLIDE_TRACK) { event ->
            tooltip(event, "waterslide_track")
        }
        TooltipModifier.REGISTRY.register(ModItems.SLIDE_SKETCH) { event ->
            tooltip(event, "slide_sketch")
        }
        TooltipModifier.REGISTRY.register(ModItems.SLIDE_DRAFTING_TABLE) { event ->
            tooltip(event, "slide_drafting_table")
        }
        TooltipModifier.REGISTRY.register(ModItems.BRASS_PLIER) { event ->
            tooltip(event, "brass_plier", dialHint = true)
        }
    }

    private fun tooltip(event: ItemTooltipEvent, name: String, dialHint: Boolean = false) {
        val builder = ItemDescription.Builder(FontHelper.Palette.STANDARD_CREATE)
        builder.addSummary(I18n.get("$KEY_PREFIX$name.tooltip.summary"))
        builder.addBehaviour(
            I18n.get("$KEY_PREFIX$name.tooltip.condition"),
            I18n.get("$KEY_PREFIX$name.tooltip.behaviour")
        )
        if (dialHint) {
            builder.addBehaviour(
                I18n.get("$KEY_PREFIX$name.tooltip.dial.condition"),
                I18n.get("$KEY_PREFIX$name.tooltip.dial.behaviour")
            )
        }
        event.toolTip.addAll(1, builder.build().currentLines)
    }
}
