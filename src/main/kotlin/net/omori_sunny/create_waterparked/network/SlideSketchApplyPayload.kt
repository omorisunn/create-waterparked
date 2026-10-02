package net.omori_sunny.create_waterparked.network

import com.simibubi.create.content.trains.track.BezierConnection
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideTrackMaterials
import net.omori_sunny.create_waterparked.game.SlideProfile
import net.omori_sunny.create_waterparked.game.SlideSketchData

class SlideSketchApplyPayload(
    val first: BlockPos,
    val second: BlockPos,
    val clear: Boolean
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SlideSketchApplyPayload>(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_sketch_apply")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideSketchApplyPayload> =
            object : StreamCodec<RegistryFriendlyByteBuf, SlideSketchApplyPayload> {
                override fun decode(buf: RegistryFriendlyByteBuf): SlideSketchApplyPayload =
                    SlideSketchApplyPayload(
                        BlockPos.STREAM_CODEC.decode(buf),
                        BlockPos.STREAM_CODEC.decode(buf),
                        buf.readBoolean()
                    )

                override fun encode(buf: RegistryFriendlyByteBuf, value: SlideSketchApplyPayload) {
                    BlockPos.STREAM_CODEC.encode(buf, value.first)
                    BlockPos.STREAM_CODEC.encode(buf, value.second)
                    buf.writeBoolean(value.clear)
                }
            }

        fun register(registrar: PayloadRegistrar) {
            registrar.playToServer(TYPE, STREAM_CODEC) { buf, ctx ->
                ctx.enqueueWork { handle(buf, ctx.player() as? ServerPlayer ?: return@enqueueWork) }
            }
        }

        private fun handle(payload: SlideSketchApplyPayload, player: ServerPlayer) {
            if (!player.mayBuild()) return
            val level = player.serverLevel()
            val a = payload.first
            val b = payload.second
            val beA = level.getBlockEntity(a) as? WaterslideAnchorBlockEntity ?: return
            val raw = beA.anchorPeerCurvesView[b] ?: return
            val bc = if (raw.isPrimary) raw else raw.secondary() ?: return
            if (!WaterslideTrackMaterials.isWaterslide(bc)) return

            if (payload.clear) {
                commit(beA, a, b, null)
                return
            }
            val stack: ItemStack = player.mainHandItem
            if (stack.item !== ModItems.SLIDE_SKETCH) return
            val data = SlideSketchData.of(stack) ?: return
            commit(beA, a, b, data.profile)
        }

        private fun commit(beA: WaterslideAnchorBlockEntity, a: BlockPos, b: BlockPos, profile: SlideProfile?) {
            beA.setCurveProfile(b, profile)
            (beA.level?.getBlockEntity(b) as? WaterslideAnchorBlockEntity)?.setCurveProfile(a, profile)
        }
    }
}
