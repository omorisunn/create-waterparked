package net.omori_sunny.create_waterparked.content.attachment.detector

import net.minecraft.nbt.CompoundTag
import net.omori_sunny.create_waterparked.content.attachment.SlideBandProvider

// wall hugging band spanning the detection range
class DetectorProvider : SlideBandProvider() {

    override fun backDistance(data: CompoundTag): Double = DetectorAttachment.distL(data).toDouble()

    override fun frontDistance(data: CompoundTag): Double = DetectorAttachment.distR(data).toDouble()
}
