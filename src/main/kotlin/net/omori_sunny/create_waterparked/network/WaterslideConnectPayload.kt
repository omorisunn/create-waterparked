package net.omori_sunny.create_waterparked.network

import dev.silvergold.simulatedcoasters.track.CoasterAnchorBezierOptimizer
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackItem
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.game.WaterslideConnectionRules
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

class WaterslideConnectPayload(val first: BlockPos, val second: BlockPos) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnServer(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is ServerPlayer) return@enqueueWork
            val level = player.serverLevel()
            val aBe = level.getBlockEntity(first) as? WaterslideAnchorBlockEntity
                ?: return@enqueueWork fail(player, "create_waterparked.connect.missing_anchor")
            val bBe = level.getBlockEntity(second) as? WaterslideAnchorBlockEntity
                ?: return@enqueueWork fail(player, "create_waterparked.connect.missing_anchor")

            val result = WaterslideConnectionRules.validate(level, first, second)
            if (!result.valid) {
                player.displayClientMessage(
                    Component.translatable(result.messageKey ?: "create.track.too_sharp")
                        .withStyle(ChatFormatting.RED),
                    true
                )
                return@enqueueWork
            }

            val curve = CoasterAnchorBezierOptimizer.buildAnchorAnchorBezier(
                level, first, second, WaterslideTrackMaterials.WATERSLIDE, false
            ) ?: return@enqueueWork fail(player, "create_waterparked.connect.invalid_curve")

            aBe.putAnchorPeerCurve(level, second, curve)
            aBe.initCurveSectorConfig(level, second)

            // Clear leftover selection.
            val stack = player.mainHandItem
            if (stack.item is WaterslideTrackItem) {
                stack.remove(ModDataComponents.CONNECTING_FROM)
                player.setItemInHand(InteractionHand.MAIN_HAND, stack)
            }
        }
    }

    private fun fail(player: ServerPlayer, key: String) {
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true)
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideConnectPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_connect")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideConnectPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, WaterslideConnectPayload::first,
            BlockPos.STREAM_CODEC, WaterslideConnectPayload::second,
            ::WaterslideConnectPayload
        )
    }
}
