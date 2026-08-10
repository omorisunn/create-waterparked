package net.omori_sunny.create_waterparked.network

import net.omori_sunny.create_waterparked.CreateWaterparked
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

object ModPayloads {
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(CreateWaterparked.ID)
        registrar.playToServer(
            WaterslideConnectPayload.TYPE,
            WaterslideConnectPayload.STREAM_CODEC,
            WaterslideConnectPayload::handleOnServer
        )
        registrar.playToServer(
            WaterslideAnchorFirstPayload.TYPE,
            WaterslideAnchorFirstPayload.STREAM_CODEC,
            WaterslideAnchorFirstPayload::handleOnServer
        )
        registrar.playToServer(
            WaterslideAnchorClearPayload.TYPE,
            WaterslideAnchorClearPayload.STREAM_CODEC,
            WaterslideAnchorClearPayload::handleOnServer
        )
        registrar.playToServer(
            WaterslideRadiusEditPayload.TYPE,
            WaterslideRadiusEditPayload.STREAM_CODEC,
            WaterslideRadiusEditPayload::handleOnServer
        )
        registrar.playToServer(
            WaterslideSectorEditPayload.TYPE,
            WaterslideSectorEditPayload.STREAM_CODEC,
            WaterslideSectorEditPayload::handleOnServer
        )
        registrar.playToServer(
            WaterslideSectorBlockEditPayload.TYPE,
            WaterslideSectorBlockEditPayload.STREAM_CODEC,
            WaterslideSectorBlockEditPayload::handleOnServer
        )
        registrar.playToClient(
            WaterslideHotbarSelectionSyncPayload.TYPE,
            WaterslideHotbarSelectionSyncPayload.STREAM_CODEC,
            WaterslideHotbarSelectionSyncPayload::handleOnClient
        )
    }
}
