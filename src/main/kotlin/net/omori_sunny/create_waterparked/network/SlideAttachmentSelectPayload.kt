package net.omori_sunny.create_waterparked.network

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockItem
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentPos
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentSite
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity

// C2S: the player confirmed a slide position for a held SAB item (phase 1).
// The server validates the position against the live curve and stores the
// component on the held stack; the component push carries the glint back.
class SlideAttachmentSelectPayload(
    val typeId: String,
    val curveA: BlockPos,
    val curveB: BlockPos,
    val site: SlideAttachmentSite,
    val t: Float,
    val angle: Float
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        val player = ctx.player() as? ServerPlayer ?: return
        ctx.enqueueWork { apply(player) }
    }

    private fun apply(player: ServerPlayer) {
        val level = player.level() as? ServerLevel ?: return
        val type = SlideAttachmentTypes.byTypeIdString(typeId) ?: return
        val hand = (1..2).map { if (it == 1) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND }
            .firstOrNull { player.getItemInHand(it).item is SlideAttachmentBlockItem } ?: return
        val stack = player.getItemInHand(hand)

        // live curve check: the stored anchor pair must still be connected
        val anchor = level.getBlockEntity(curveA) as? WaterslideAnchorBlockEntity ?: return
        if (!anchor.anchorPeerCurvesView.containsKey(curveB.immutable())) return

        val tFinal = when (type.site) {
            SlideAttachmentSite.ENDPOINT -> if (t < 0.5f) 0f else 1f
            SlideAttachmentSite.INTERIOR -> t.coerceIn(0.02f, 0.98f)
        }
        stack.set(
            ModDataComponents.SLIDE_ATTACHMENT_POS,
            SlideAttachmentPos(curveA, curveB, type.site, tFinal, ((angle % 360f) + 360f) % 360f)
        )
        player.setItemInHand(hand, stack)
        CreateWaterparked.LOGGER.debug(
            "Slide attachment position selected: type={} t={} angle={}", typeId, tFinal, angle
        )
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<SlideAttachmentSelectPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_attachment_select")
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideAttachmentSelectPayload> =
            StreamCodec.of(
                { buf, p ->
                    buf.writeUtf(p.typeId, 128)
                    buf.writeLong(p.curveA.asLong())
                    buf.writeLong(p.curveB.asLong())
                    buf.writeUtf(p.site.name, 16)
                    buf.writeFloat(p.t)
                    buf.writeFloat(p.angle)
                },
                { buf ->
                    SlideAttachmentSelectPayload(
                        buf.readUtf(128),
                        BlockPos.of(buf.readLong()),
                        BlockPos.of(buf.readLong()),
                        SlideAttachmentSite.valueOf(buf.readUtf(16)),
                        buf.readFloat(),
                        buf.readFloat()
                    )
                }
            )
    }
}
