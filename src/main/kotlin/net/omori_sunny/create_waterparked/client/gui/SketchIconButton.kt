package net.omori_sunny.create_waterparked.client.gui

import net.createmod.catnip.gui.element.ScreenElement
import net.createmod.catnip.gui.widget.AbstractSimiWidget
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

class SketchIconButton(
    x: Int, y: Int,
    private var icon: ScreenElement
) : AbstractSimiWidget(x, y, 16, 16, Component.empty()) {

    var selected = false

    fun setIcon(icon: ScreenElement) {
        this.icon = icon
    }

    fun setToolTip(text: Component) {
        toolTip.clear()
        toolTip.add(text)
    }

    public override fun doRender(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (!visible) return
        isHovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height
        if (isHovered && active) {
            blit(graphics, HOVER)
        }
        if (selected) {
            blit(graphics, KNOB)
        }
        if (active) {
            icon.render(graphics, x, y)
        } else {
            val tex = icon as? TextureIcon
            if (tex != null) tex.renderDimmed(graphics, x, y, DISABLED_ALPHA)
            else icon.render(graphics, x, y)
        }
    }

    private fun blit(graphics: GuiGraphics, tex: ResourceLocation) {
        graphics.blit(tex, x - 1, y - 1, 0f, 0f, 18, 18, 18, 18)
    }

    companion object {
        val HOVER = rl("hover")
        val KNOB = rl("knob")

        private const val DISABLED_ALPHA = 0.4f

        private fun rl(name: String): ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(
                net.omori_sunny.create_waterparked.CreateWaterparked.ID, "textures/gui/sketch/$name.png"
            )
    }
}

