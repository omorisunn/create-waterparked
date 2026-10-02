package net.omori_sunny.create_waterparked.client.gui

import net.createmod.catnip.gui.element.ScreenElement
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation

class TextureIcon(private val location: ResourceLocation) : ScreenElement {

    override fun render(graphics: GuiGraphics, x: Int, y: Int) {
        graphics.blit(location, x, y, 0f, 0f, 16, 16, 16, 16)
    }

    fun renderDimmed(graphics: GuiGraphics, x: Int, y: Int, alpha: Float) {
        graphics.flush()
        val pose = graphics.pose().last().pose()
        val vc = graphics.bufferSource().getBuffer(RenderType.text(location))
        val xf = x.toFloat(); val yf = y.toFloat()
        val x2 = (x + 16).toFloat(); val y2 = (y + 16).toFloat()
        val z = 0f
        vc.addVertex(pose, xf, yf, z).setColor(1f, 1f, 1f, alpha).setUv(0f, 0f).setUv2(240, 240)
        vc.addVertex(pose, xf, y2, z).setColor(1f, 1f, 1f, alpha).setUv(0f, 1f).setUv2(240, 240)
        vc.addVertex(pose, x2, y2, z).setColor(1f, 1f, 1f, alpha).setUv(1f, 1f).setUv2(240, 240)
        vc.addVertex(pose, x2, yf, z).setColor(1f, 1f, 1f, alpha).setUv(1f, 0f).setUv2(240, 240)
        graphics.flush()
    }

    companion object {
        private fun icon(name: String) = TextureIcon(
            ResourceLocation.fromNamespaceAndPath(
                net.omori_sunny.create_waterparked.CreateWaterparked.ID, "textures/gui/sketch/$name.png"
            )
        )

        val MOVE = icon("move")
        val ADD = icon("add")
        val DELETE = icon("delete")
        val SNAP = icon("snap")
        val BEZIER_NONE = icon("bezier_none")
        val BEZIER_SYMMETRIC = icon("bezier_sym")
        val BEZIER_FREE = icon("bezier_free")
        val RESET = icon("reset")
        val SAVE = icon("save")
        val UNDO = icon("undo")
        val REDO = icon("redo")
    }
}

