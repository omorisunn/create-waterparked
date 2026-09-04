package net.omori_sunny.create_waterparked.client.renderer

import com.mojang.blaze3d.vertex.PoseStack
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

// Package-style item renderer, like Create's cardboard boxes on belts/depots.
// The belt/depot renderers treat the boat as a package (see the isPackage
// redirect mixins) and apply the package lift/scale themselves; this BEWLR
// only recentres the long hull on the item slot in FIXED and leaves every
// other context pixel-identical to the plain model render.
class InflatableBoatItemRenderer : CustomRenderedItemModelRenderer() {

    override fun render(
        stack: ItemStack,
        model: CustomRenderedItemModel,
        renderer: PartialItemModelRenderer,
        transformType: ItemDisplayContext,
        ms: PoseStack,
        buffer: MultiBufferSource,
        light: Int,
        overlay: Int
    ) {
        val itemRenderer = Minecraft.getInstance().itemRenderer
        val consumer = ItemRenderer.getFoilBufferDirect(
            buffer, RenderType.cutout(), true, stack.hasFoil()
        )
        // undo the BEWLR +0.5 centring so quads land exactly where the plain
        // model would (json display transforms are already applied by the
        // model wrapper); tints (DYED_COLOR via tintindex 0) are applied
        // internally from the registered item colors
        if (transformType == ItemDisplayContext.FIXED) {
            // package-parity placement: package boxes end up with their bottom
            // on the belt item origin; -1/4 calibrated from playtesting
            // (inner translations are amplified 1.5x by the package scale)
            ms.translate(-0.5f, -0.25f, -1.0f)
        }
        // other contexts: NO extra translate - ItemRenderer's implicit
        // (-0.5,-0.5,-0.5) grid centring and the BEWLR base (+0.5,+0.5,+0.5)
        // cancel out, matching the plain first-commit model render exactly
        itemRenderer.renderModelLists(
            model.originalModel, stack, light, overlay, ms, consumer
        )
    }
}
