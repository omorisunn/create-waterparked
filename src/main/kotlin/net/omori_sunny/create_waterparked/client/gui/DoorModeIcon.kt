package net.omori_sunny.create_waterparked.client.gui

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.simibubi.create.foundation.gui.AllIcons
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.omori_sunny.create_waterparked.CreateWaterparked
import org.joml.Matrix4f

// both render overloads must be overridden: world value box and settings screen differ
class DoorModeIcon(name: String) : AllIcons(0, 0) {

    private val texture: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "textures/gui/" + name + ".png")

    override fun render(graphics: GuiGraphics, x: Int, y: Int) {
        graphics.blit(texture, x, y, 0f, 0f, 16, 16, 16, 16)
    }

    override fun render(ms: PoseStack, buffer: MultiBufferSource, color: Int) {
        val builder = buffer.getBuffer(RenderType.text(texture))
        val matrix = ms.last().pose()
        val r = color shr 16 and 0xFF
        val g = color shr 8 and 0xFF
        val b = color and 0xFF
        vertex(builder, matrix, 0f, 0f, r, g, b, 0f, 0f)
        vertex(builder, matrix, 0f, 1f, r, g, b, 0f, 1f)
        vertex(builder, matrix, 1f, 1f, r, g, b, 1f, 1f)
        vertex(builder, matrix, 1f, 0f, r, g, b, 1f, 0f)
    }

    private fun vertex(
        builder: VertexConsumer, matrix: Matrix4f,
        x: Float, y: Float, r: Int, g: Int, b: Int, u: Float, v: Float
    ) {
        builder.addVertex(matrix, x, y, 0f)
            .setColor(r, g, b, 255)
            .setUv(u, v)
            .setLight(LightTexture.FULL_BRIGHT)
    }
}
