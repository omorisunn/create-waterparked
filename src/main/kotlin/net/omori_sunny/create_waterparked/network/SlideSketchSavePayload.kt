package net.omori_sunny.create_waterparked.network

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
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.sketch.SlideDraftingTableBlockEntity
import net.omori_sunny.create_waterparked.game.SlideProfile
import net.omori_sunny.create_waterparked.game.SlideSketchData

class SlideSketchSavePayload(
    val tablePos: BlockPos,
    val anchors: FloatArray,
    val handles: FloatArray,
    val transition: Float,
    val inHandles: FloatArray?,
    val modes: ByteArray?
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SlideSketchSavePayload>(
            ResourceLocation.fromNamespaceAndPath(CreateWaterparked.ID, "slide_sketch_save")
        )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SlideSketchSavePayload> =
            object : StreamCodec<RegistryFriendlyByteBuf, SlideSketchSavePayload> {
                override fun decode(buf: RegistryFriendlyByteBuf): SlideSketchSavePayload {
                    val pos = BlockPos.STREAM_CODEC.decode(buf)
                    val size = buf.readVarInt()
                    val anchors = FloatArray(size)
                    for (i in anchors.indices) anchors[i] = buf.readFloat()
                    val handles = FloatArray(size)
                    for (i in handles.indices) handles[i] = buf.readFloat()
                    val transition = buf.readFloat()
                    val inHandles = if (buf.readBoolean()) FloatArray(size) { buf.readFloat() } else null
                    val modes = if (buf.readBoolean()) buf.readByteArray() else null
                    return SlideSketchSavePayload(pos, anchors, handles, transition, inHandles, modes)
                }

                override fun encode(buf: RegistryFriendlyByteBuf, value: SlideSketchSavePayload) {
                    BlockPos.STREAM_CODEC.encode(buf, value.tablePos)
                    buf.writeVarInt(value.anchors.size)
                    for (v in value.anchors) buf.writeFloat(v)
                    for (v in value.handles) buf.writeFloat(v)
                    buf.writeFloat(value.transition)
                    buf.writeBoolean(value.inHandles != null)
                    value.inHandles?.let { for (v in it) buf.writeFloat(v) }
                    buf.writeBoolean(value.modes != null)
                    value.modes?.let { buf.writeByteArray(it) }
                }
            }

        fun register(registrar: PayloadRegistrar) {
            registrar.playToServer(TYPE, STREAM_CODEC) { buf, ctx ->
                ctx.enqueueWork { handle(buf, ctx.player() as? ServerPlayer ?: return@enqueueWork) }
            }
        }

        private fun handle(payload: SlideSketchSavePayload, player: ServerPlayer) {
            if (!player.mayBuild()) return
            val level = player.serverLevel()
            val be = level.getBlockEntity(payload.tablePos) as? SlideDraftingTableBlockEntity ?: return
            val stack: ItemStack = be.sketchSlot[0]
            if (stack.item !== ModItems.SLIDE_SKETCH) return

            val profile = SlideProfile.of(payload.anchors, payload.handles, payload.inHandles, payload.modes, payload.transition) ?: return
            stack.set(ModDataComponents.SLIDE_SKETCH, SlideSketchData(profile, EDITED_NAME))
            be.setChanged()
        }

        const val EDITED_NAME = "edited"
    }
}
