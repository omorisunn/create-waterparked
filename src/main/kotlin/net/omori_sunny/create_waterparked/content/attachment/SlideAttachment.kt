package net.omori_sunny.create_waterparked.content.attachment

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity

// behaviour base of one placed attachment instance. The framework owns the
// placement, the index, the rendering and the detectors; subclasses implement
// their own reaction (a door opens, a drain pump runs, ...).
abstract class SlideAttachment(
    val type: SlideAttachmentType,
    val entry: SlideAttachmentEntry
) {

    /** per-server-tick hook while the host block is loaded */
    open fun serverTick(level: ServerLevel, sab: SlideAttachmentBlockEntity) {}

    /**
     * trigger callback fired by the framework's detectors:
     * proximity/path hits pass the candidate entity, custom triggers pass
     * whatever the subclass's own detector found
     */
    open fun onTrigger(level: ServerLevel, sab: SlideAttachmentBlockEntity, candidate: Entity?) {}

    /** custom trigger detector; only called when the type declares CUSTOM */
    open fun shouldTrigger(level: ServerLevel, sab: SlideAttachmentBlockEntity, candidate: Entity): Boolean = false

    /**
     * slide session speed scale this attachment currently demands for the
     * given rider (e.g. a closing door braking to 0); null = no opinion, the
     * framework takes the minimum over all attachments
     */
    open fun speedScale(candidate: Entity?): Double? = null

    /**
     * distance-aware variant for path triggers: distance = remaining arc
     * length (blocks) between the rider and this attachment; default falls
     * back to the plain demand
     */
    open fun speedScaleAt(distance: Double): Double? = speedScale(null)

    /** subclass data bag persisted with the entry */
    val data: CompoundTag get() = entry.data

    /** mark the host BE changed so data edits reach clients */
    protected fun sync(sab: SlideAttachmentBlockEntity) {
        sab.setChanged()
        sab.notifyBlockUpdated()
    }
}
