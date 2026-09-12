package net.omori_sunny.create_waterparked.content.attachment

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag

// stored per curve and re-derived live, so curve edits move it along
class SlideAttachmentEntry(
    val typeId: String,
    val curveA: BlockPos,
    val curveB: BlockPos,
    val site: SlideAttachmentSite,
    var t: Float,
    val angle: Float,
    val data: CompoundTag = CompoundTag()
) {

    fun write(tag: CompoundTag) {
        tag.putString("Type", typeId)
        tag.putLong("CurveA", curveA.asLong())
        tag.putLong("CurveB", curveB.asLong())
        tag.putString("Site", site.name)
        tag.putFloat("CurveT", t)
        tag.putFloat("WallAngle", angle)
        tag.put("Data", data)
    }

    companion object {
        fun read(tag: CompoundTag): SlideAttachmentEntry? {
            if (!tag.contains("Type")) return null
            val site = runCatching { SlideAttachmentSite.valueOf(tag.getString("Site")) }.getOrNull()
                ?: return null
            return SlideAttachmentEntry(
                tag.getString("Type"),
                BlockPos.of(tag.getLong("CurveA")),
                BlockPos.of(tag.getLong("CurveB")),
                site,
                tag.getFloat("CurveT"),
                tag.getFloat("WallAngle"),
                tag.getCompound("Data")
            )
        }
    }
}

// held on the item between the two placement phases and persisted across relog
data class SlideAttachmentPos(
    val curveA: BlockPos,
    val curveB: BlockPos,
    val site: SlideAttachmentSite,
    val t: Float,
    val angle: Float
)
