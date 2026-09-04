package net.omori_sunny.create_waterparked.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.DyedItemColor
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.raft.InflatableBoat1x2Entity
import net.omori_sunny.create_waterparked.content.registry.ModItems

// Renders the inflatable_boat through the vanilla item pipeline: the shared block/block item
// model is drawn by ItemRenderer with the model's tintindex, so uv, culling and
// dyeing all follow the standard model baker.
class InflatableBoat1x2Renderer(ctx: EntityRendererProvider.Context) : EntityRenderer<InflatableBoat1x2Entity>(ctx) {

    override fun getTextureLocation(entity: InflatableBoat1x2Entity): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "textures/block/inflatable_boat_1x2.png")

    override fun render(
        entity: InflatableBoat1x2Entity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int
    ) {
        val stack = ItemStack(ModItems.INFLATABLE_BOAT_1X2)
        stack.set(DataComponents.DYED_COLOR, DyedItemColor(entity.color, true))
        poseStack.pushPose()
        // since the item uses a custom renderer, the BEWLR path additionally
        // centres the draw space with translate(0.5,0.5,0.5) before our quads.
        // our model spans x 0..16, y 0..3, z 0..32 (centre (8, ?, 16)), so the
        // combined counter-translate must put the model centre at the entity
        // origin and the hull bottom ~0.02 above y=0:
        //   x: 8/16 + 0.5 + Tx = 0.5  -> Tx = -0.5
        //   y:    0 + 0.5 + Ty = 0.52 -> Ty =  0.02
        //   z: 16/16 + 0.5 + Tz = 0.5 -> Tz = -1.0
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.yRot))
        poseStack.translate(-0.5, 0.02, -1.0)
        // NONE applies no display transform: model units are already block units
        Minecraft.getInstance().itemRenderer.renderStatic(
            stack, ItemDisplayContext.NONE, packedLight, OverlayTexture.NO_OVERLAY,
            poseStack, buffers, entity.level(), 0
        )
        poseStack.popPose()
    }
}
