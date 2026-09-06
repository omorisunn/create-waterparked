package net.omori_sunny.create_waterparked.client.attachment

import com.simibubi.create.content.kinetics.base.ShaftRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity

// fallback renderer for worlds without flywheel visualization (e.g. Ponder):
// only the spinning shaft part - the attachment itself always draws from the
// level stage renderer, which fires in every world
class SlideAttachmentBlockEntityRenderer(ctx: BlockEntityRendererProvider.Context) :
    ShaftRenderer<SlideAttachmentBlockEntity>(ctx) {

    override fun renderSafe(
        be: SlideAttachmentBlockEntity,
        partialTick: Float,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        buffers: net.minecraft.client.renderer.MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val level = be.level ?: return
        if (dev.engine_room.flywheel.api.visualization.VisualizationManager.supportsVisualization(level)) {
            // the shaft spins via ShaftVisual
            return
        }
        super.renderSafe(be, partialTick, poseStack, buffers, packedLight, packedOverlay)
    }

    override fun getViewDistance(): Int = 128
}
