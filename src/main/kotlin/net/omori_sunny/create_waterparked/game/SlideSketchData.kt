package net.omori_sunny.create_waterparked.game

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.omori_sunny.create_waterparked.content.registry.ModDataComponents

data class SlideSketchData(
    val profile: SlideProfile,
    val name: String = ""
) {

    companion object {

        private val floats: Codec<FloatArray> = Codec.DOUBLE.listOf().xmap(
            { list -> DoubleArray(list.size) { list[it] }.let { FloatArray(it.size) { idx -> it[idx].toFloat() } } },
            { arr -> arr.map { it.toDouble() } }
        )

        private val modeBytes: Codec<ByteArray> = Codec.BYTE.listOf().xmap(
            { list -> ByteArray(list.size) { list[it] } },
            { arr -> arr.map { it } }
        )

        val CODEC: Codec<SlideSketchData> = RecordCodecBuilder.create { instance ->
            instance.group(
                floats.fieldOf("Anchors").forGetter { it.profile.anchors },
                floats.fieldOf("Handles").forGetter { it.profile.handles },
                floats.optionalFieldOf("InHandles", FloatArray(0)).forGetter { it.profile.inHandles ?: FloatArray(0) },
                modeBytes.optionalFieldOf("Modes", ByteArray(0)).forGetter { it.profile.modes ?: ByteArray(0) },
                Codec.FLOAT.fieldOf("Transition").forGetter { it.profile.transition },
                Codec.STRING.optionalFieldOf("Name", "").forGetter { it.name }
            ).apply(instance) { a, h, ih, m, t, n ->
                val inHandles = if (ih.isNotEmpty()) ih else null
                val modes = if (m.isNotEmpty()) m else null
                SlideProfile.of(a, h, inHandles, modes, t)?.let { SlideSketchData(it, n) }
                    ?: SlideSketchData(SlideProfile.circleFallback(), n)
            }
        }

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SlideSketchData> =
            object : StreamCodec<FriendlyByteBuf, SlideSketchData> {
                override fun decode(buf: FriendlyByteBuf): SlideSketchData {
                    val profile = SlideProfile.read(buf) ?: SlideProfile.circleFallback()
                    val name = buf.readUtf(64)
                    return SlideSketchData(profile, name)
                }

                override fun encode(buf: FriendlyByteBuf, value: SlideSketchData) {
                    value.profile.write(buf)
                    buf.writeUtf(value.name, 64)
                }
            }

        fun of(stack: ItemStack): SlideSketchData? = stack.get(ModDataComponents.SLIDE_SKETCH)
    }
}
