package net.omori_sunny.create_waterparked.client.item

import com.simibubi.create.foundation.item.ItemDescription
import com.simibubi.create.foundation.item.TooltipModifier
import net.createmod.catnip.lang.FontHelper
import net.minecraft.client.resources.language.I18n
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import net.omori_sunny.create_waterparked.content.registry.ModItems

// Create-style hover tooltips for the slide anchor and the slide track item.
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
    }

    private fun tooltip(event: ItemTooltipEvent, name: String) {
        val builder = ItemDescription.Builder(FontHelper.Palette.STANDARD_CREATE)
        builder.addSummary(I18n.get("$KEY_PREFIX$name.tooltip.summary"))
        builder.addBehaviour(
            I18n.get("$KEY_PREFIX$name.tooltip.condition"),
            I18n.get("$KEY_PREFIX$name.tooltip.behaviour")
        )
        event.toolTip.addAll(1, builder.build().currentLines)
    }
}
