package net.omori_sunny.create_waterparked.content.attachment.door

import net.minecraft.core.BlockPos
import net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideDistanceHandleEditor

// how close the door stops a rider on each side
class DoorStopDistanceEditor(bePos: BlockPos) :
    SlideDistanceHandleEditor(bePos, EDITOR_KEY) {

    override fun handleTag(side: Int): String =
        if (side < 0) MechanicalDoorAttachment.TAG_STOP_L else MechanicalDoorAttachment.TAG_STOP_R

    override val commitPrefix: String = "stop"

    override val readoutKey: String = "create_waterparked.track.attachment_edit_readout"

    companion object {
        const val EDITOR_KEY = "mechanical_door_stop"
    }
}
