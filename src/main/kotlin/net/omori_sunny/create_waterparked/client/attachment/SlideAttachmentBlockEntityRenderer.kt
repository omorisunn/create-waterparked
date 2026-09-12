package net.omori_sunny.create_waterparked.client.attachment

import com.simibubi.create.content.kinetics.base.ShaftRenderer
import net.createmod.ponder.api.level.PonderLevel
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity

// runs only without Flywheel visualization (e.g. Ponder)
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
            return
        }
        super.renderSafe(be, partialTick, poseStack, buffers, packedLight, packedOverlay)
        if (level is PonderLevel) {
            SlideAttachmentRenderer.renderOne(be, poseStack, buffers, Vec3.atLowerCornerOf(be.blockPos), partialTick)
        }
    }

    override fun getViewDistance(): Int = 128
}
