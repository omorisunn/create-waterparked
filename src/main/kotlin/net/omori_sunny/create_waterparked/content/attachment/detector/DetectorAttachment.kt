package net.omori_sunny.create_waterparked.content.attachment.detector

import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.omori_sunny.create_waterparked.content.attachment.IHaveSlideAttachmentEditor
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachment
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlock
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentEntry
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType
import net.omori_sunny.create_waterparked.content.attachment.SlideHandleDistance
import java.util.UUID

// emits redstone while a rider sits inside the band between the two distance handles
class DetectorAttachment(
    type: SlideAttachmentType,
    entry: SlideAttachmentEntry
) : SlideAttachment(type, entry), IHaveSlideAttachmentEditor {

    override fun slideEditorKey(): String? = DetectorDistanceEditor.EDITOR_KEY

    companion object {
        const val TAG_DIST_L = "DetectorDistL"

        const val TAG_DIST_R = "DetectorDistR"

        const val TAG_COUNT = "DetectorCount"

        private const val PRESENCE_GRACE = 5L

        fun distL(data: CompoundTag): Float = SlideHandleDistance.read(data, TAG_DIST_L)

        fun distR(data: CompoundTag): Float = SlideHandleDistance.read(data, TAG_DIST_R)

        fun riders(data: CompoundTag): Int = data.getInt(TAG_COUNT)
    }

    private val seen = HashMap<UUID, Long>()

    private var lastNotified = riders(data)

    // one count per pass, the grace window decides when a pass has ended
    override fun onTrigger(
        level: ServerLevel,
        sab: SlideAttachmentBlockEntity,
        candidate: Entity?,
        arc: Double
    ) {
        if (candidate == null) return
        if (arc < -distL(data).toDouble() || arc > distR(data).toDouble()) return
        if (seen.put(candidate.uuid, level.gameTime) != null) return
        data.putInt(TAG_COUNT, riders(data) + 1)
        sync(sab)
    }

    override fun serverTick(level: ServerLevel, sab: SlideAttachmentBlockEntity) {
        val cutoff = level.gameTime - PRESENCE_GRACE
        val entries = seen.entries.iterator()
        while (entries.hasNext()) {
            if (entries.next().value < cutoff) entries.remove()
        }
        val powered = seen.isNotEmpty()
        if (sab.blockState.getValue(SlideAttachmentBlock.POWERED) != powered) {
            level.setBlockAndUpdate(
                sab.blockPos,
                sab.blockState.setValue(SlideAttachmentBlock.POWERED, powered)
            )
        }
        val riders = riders(data)
        if (riders != lastNotified) {
            lastNotified = riders
            DisplayLinkBlock.notifyGatherers(level, sab.blockPos)
        }
    }
}
