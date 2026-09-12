package net.omori_sunny.create_waterparked.client.editor

import net.minecraft.core.BlockPos
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideControlPointEditor

@OnlyIn(Dist.CLIENT)
object SlideEditState {

    enum class Mode { NONE, SLIDE, ATTACHMENT }

    private var mode = Mode.NONE
    private var attachmentPos: BlockPos? = null

    fun mode(): Mode = mode

    fun isEditingAttachment(): Boolean = mode == Mode.ATTACHMENT

    fun isEditingSlide(): Boolean = mode == Mode.SLIDE

    fun editingAttachmentPos(): BlockPos? = attachmentPos

    // caller has already validated the attachment
    fun enterAttachment(pos: BlockPos) {
        if (mode == Mode.ATTACHMENT && attachmentPos == pos) return
        if (SlideControlPointEditor.anyDragging()) return
        mode = Mode.ATTACHMENT
        attachmentPos = pos
        dev.silvergold.simulatedcoasters.client.track.BezierHandleEditMode.clear()
        SlideControlPointEditor.suppressNextGrab()
    }

    fun exitAttachment() {
        if (mode != Mode.ATTACHMENT) return
        mode = Mode.NONE
        attachmentPos = null
    }

    fun enterSlide() {
        if (mode == Mode.SLIDE) return
        if (mode == Mode.ATTACHMENT) {
            if (SlideControlPointEditor.anyDragging()) return
            attachmentPos = null
        }
        mode = Mode.SLIDE
    }

    fun exitSlide() {
        if (mode != Mode.SLIDE) return
        mode = Mode.NONE
    }

    fun clear() {
        mode = Mode.NONE
        attachmentPos = null
    }
}
