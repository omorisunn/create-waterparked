package net.omori_sunny.create_waterparked.content.attachment

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity

// framework handles placement and detection; subclasses react
abstract class SlideAttachment(
    val type: SlideAttachmentType,
    val entry: SlideAttachmentEntry
) {

    // only while the host block is loaded
    open fun serverTick(level: ServerLevel, sab: SlideAttachmentBlockEntity) {}

    // candidate is the triggering entity, or whatever a custom detector found
    open fun onTrigger(level: ServerLevel, sab: SlideAttachmentBlockEntity, candidate: Entity?) {}

    // only called when the type declares CUSTOM
    open fun shouldTrigger(level: ServerLevel, sab: SlideAttachmentBlockEntity, candidate: Entity): Boolean = false

    // null = no opinion; the framework keeps the minimum over all attachments
    open fun speedScale(candidate: Entity?): Double? = null

    // distance is the remaining arc length in blocks to the attachment
    open fun speedScaleAt(distance: Double): Double? = speedScale(null)

    // only reached while the player wears Engineer's Goggles
    open fun addToGoggleTooltip(
        sab: SlideAttachmentBlockEntity,
        tooltip: MutableList<Component>,
        isPlayerSneaking: Boolean
    ) {}

    val data: CompoundTag get() = entry.data

    // marks the host changed so edits reach clients
    protected fun sync(sab: SlideAttachmentBlockEntity) {
        sab.setChanged()
        sab.notifyBlockUpdated()
    }
}
