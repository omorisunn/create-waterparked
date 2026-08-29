package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents
import net.omori_sunny.create_waterparked.content.waterslide.SlideClipboardInteraction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.handling.IPayloadContext

// Client to server: enter (line != null) or exit (null) the paste mode.
class WaterslideSlidePasteStatePayload(
    val line: String?
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() as? ServerPlayer ?: return@enqueueWork
            val stack = player.mainHandItem
            if (!SlideClipboardInteraction.isClipboard(stack)) return@enqueueWork
            if (line == null) {
                stack.remove(ModDataComponents.SLIDE_PASTE_LINE)
                stack.remove(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE)
            } else {
                stack.set(ModDataComponents.SLIDE_PASTE_LINE, line)
                stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            }
            // component sync pushes the foil state back to the client
            player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideSlidePasteStatePayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_slide_paste_state")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideSlidePasteStatePayload> =
            StreamCodec.composite(
                ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), WaterslideSlidePasteStatePayload::optionalLine,
                { line -> WaterslideSlidePasteStatePayload(line.orElse(null)) }
            )
    }
}

private fun WaterslideSlidePasteStatePayload.optionalLine(): java.util.Optional<String> =
    java.util.Optional.ofNullable(line)
