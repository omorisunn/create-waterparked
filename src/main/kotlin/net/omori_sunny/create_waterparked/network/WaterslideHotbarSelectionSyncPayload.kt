package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackItem
import net.omori_sunny.create_waterparked.game.WaterslideTrackPlacement
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext

// Hotbar selection sync.
class WaterslideHotbarSelectionSyncPayload(
    val hotbarSlot: Int,
    val anchorBlockPosLong: Long
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    fun handleOnClient(ctx: IPayloadContext) {
        ctx.enqueueWork {
            val player = ctx.player() ?: return@enqueueWork
            if (player !is LocalPlayer) return@enqueueWork
            if (hotbarSlot !in 0..8) return@enqueueWork
            val stack: ItemStack = player.inventory.getItem(hotbarSlot)
            if (stack.item !is WaterslideTrackItem) return@enqueueWork
            if (anchorBlockPosLong == 0L) {
                WaterslideTrackPlacement.clearPendingConnection(stack)
            } else {
                WaterslideTrackPlacement.setAnchorFirstSelection(stack, BlockPos.of(anchorBlockPosLong))
            }
            player.inventory.setItem(hotbarSlot, stack)
        }
    }

    companion object {
        val TYPE: CustomPacketPayload.Type<WaterslideHotbarSelectionSyncPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "waterslide_hotbar_selection_sync")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WaterslideHotbarSelectionSyncPayload> =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                WaterslideHotbarSelectionSyncPayload::hotbarSlot,
                ByteBufCodecs.VAR_LONG,
                WaterslideHotbarSelectionSyncPayload::anchorBlockPosLong,
                ::WaterslideHotbarSelectionSyncPayload
            )

        fun broadcast(player: ServerPlayer, hotbarSlot: Int, anchorLong: Long) {
            if (hotbarSlot !in 0..8) return
            PacketDistributor.sendToPlayer(
                player,
                WaterslideHotbarSelectionSyncPayload(hotbarSlot, anchorLong)
            )
        }
    }
}
