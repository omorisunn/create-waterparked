package net.omori_sunny.create_waterparked.content.sketch

import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.omori_sunny.create_waterparked.game.SlideSketchData

class SlideSketchItem(properties: Properties) : Item(properties) {

    override fun getName(stack: ItemStack): Component {
        val data = SlideSketchData.of(stack)
        return if (data != null && data.name == net.omori_sunny.create_waterparked.network.SlideSketchSavePayload.EDITED_NAME) {
            Component.translatable("item.create_waterparked.slide_sketch.edited")
        } else {
            Component.translatable(descriptionId)
        }
    }

    override fun getTooltipImage(stack: ItemStack): java.util.Optional<net.minecraft.world.inventory.tooltip.TooltipComponent> {
        val marker = SlideSketchData.of(stack)?.let { SlideSketchPreviewTooltip(it.profile) }
        return java.util.Optional.ofNullable(marker)
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag
    ) {
        super.appendHoverText(stack, context, tooltip, flag)
        val data = SlideSketchData.of(stack) ?: return
        if (data.name == net.omori_sunny.create_waterparked.network.SlideSketchSavePayload.EDITED_NAME) {
            tooltip.add(
                Component.translatable("create_waterparked.sketch.tooltip.edited")
                    .withStyle(net.minecraft.ChatFormatting.GRAY)
            )
        }
    }
}
