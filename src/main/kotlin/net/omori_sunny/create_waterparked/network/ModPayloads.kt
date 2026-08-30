package net.omori_sunny.create_waterparked.network
// Payload registration: ghost place/mine and clipboard slide copy/paste.

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import net.omori_sunny.create_waterparked.CreateWaterparked

object ModPayloads {
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(CreateWaterparked.ID)
        registrar.server(WaterslideConnectPayload.TYPE, WaterslideConnectPayload.STREAM_CODEC, WaterslideConnectPayload::handleOnServer)
        registrar.server(WaterslideAnchorFirstPayload.TYPE, WaterslideAnchorFirstPayload.STREAM_CODEC, WaterslideAnchorFirstPayload::handleOnServer)
        registrar.server(WaterslideAnchorClearPayload.TYPE, WaterslideAnchorClearPayload.STREAM_CODEC, WaterslideAnchorClearPayload::handleOnServer)
        registrar.server(WaterslideRadiusEditPayload.TYPE, WaterslideRadiusEditPayload.STREAM_CODEC, WaterslideRadiusEditPayload::handleOnServer)
        registrar.server(WaterslideSectorEditPayload.TYPE, WaterslideSectorEditPayload.STREAM_CODEC, WaterslideSectorEditPayload::handleOnServer)
        registrar.server(WaterslideSectorBlockEditPayload.TYPE, WaterslideSectorBlockEditPayload.STREAM_CODEC, WaterslideSectorBlockEditPayload::handleOnServer)
        registrar.server(WaterslideGhostPlacePayload.TYPE, WaterslideGhostPlacePayload.STREAM_CODEC, WaterslideGhostPlacePayload::handleOnServer)
        registrar.server(WaterslideGhostMinePayload.TYPE, WaterslideGhostMinePayload.STREAM_CODEC, WaterslideGhostMinePayload::handleOnServer)
        registrar.client(WaterslideHotbarSelectionSyncPayload.TYPE, WaterslideHotbarSelectionSyncPayload.STREAM_CODEC, WaterslideHotbarSelectionSyncPayload::handleOnClient)
        registrar.client(SlideTrajectoryPayload.TYPE, SlideTrajectoryPayload.STREAM_CODEC, SlideTrajectoryPayload::handleOnClient)
        registrar.client(SlideSegmentPayload.TYPE, SlideSegmentPayload.STREAM_CODEC, SlideSegmentPayload::handleOnClient)
        registrar.client(SlideEndPayload.TYPE, SlideEndPayload.STREAM_CODEC, SlideEndPayload::handleOnClient)
        registrar.client(SlideSyncPayload.TYPE, SlideSyncPayload.STREAM_CODEC, SlideSyncPayload::handleOnClient)
        registrar.client(WaterslideWaterSyncPayload.TYPE, WaterslideWaterSyncPayload.STREAM_CODEC, WaterslideWaterSyncPayload::handleOnClient)
        registrar.server(SlideCancelPayload.TYPE, SlideCancelPayload.STREAM_CODEC, SlideCancelPayload::handleOnServer)
        registrar.server(WaterslideDebugRequestPayload.TYPE, WaterslideDebugRequestPayload.STREAM_CODEC, WaterslideDebugRequestPayload::handleOnServer)
        registrar.server(WaterslideSupportApplyPayload.TYPE, WaterslideSupportApplyPayload.STREAM_CODEC, WaterslideSupportApplyPayload::handleOnServer)
        registrar.server(WaterslideSupportHoverPayload.TYPE, WaterslideSupportHoverPayload.STREAM_CODEC, WaterslideSupportHoverPayload::handleOnServer)
        registrar.server(WaterslideSlideCopyPayload.TYPE, WaterslideSlideCopyPayload.STREAM_CODEC, WaterslideSlideCopyPayload::handleOnServer)
        registrar.server(WaterslideSlidePastePayload.TYPE, WaterslideSlidePastePayload.STREAM_CODEC, WaterslideSlidePastePayload::handleOnServer)
        registrar.server(WaterslideSlidePasteStatePayload.TYPE, WaterslideSlidePasteStatePayload.STREAM_CODEC, WaterslideSlidePasteStatePayload::handleOnServer)
        registrar.client(WaterslideDebugTrajectoryPayload.TYPE, WaterslideDebugTrajectoryPayload.STREAM_CODEC, WaterslideDebugTrajectoryPayload::handleOnClient)
    }

    private fun <P : CustomPacketPayload> PayloadRegistrar.server(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<RegistryFriendlyByteBuf, P>,
        handler: IPayloadHandler<P>
    ) = playToServer(type, codec, handler)

    private fun <P : CustomPacketPayload> PayloadRegistrar.client(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<RegistryFriendlyByteBuf, P>,
        handler: IPayloadHandler<P>
    ) = playToClient(type, codec, handler)
}