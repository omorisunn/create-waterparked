package net.omori_sunny.create_waterparked.content.attachment.detector

import net.minecraft.core.BlockPos
import net.omori_sunny.create_waterparked.client.editor.controlpoint.SlideDistanceHandleEditor

// how far the detection band reaches on each side of the hub
class DetectorDistanceEditor(bePos: BlockPos) :
    SlideDistanceHandleEditor(bePos, EDITOR_KEY) {

    override fun handleTag(side: Int): String =
        if (side < 0) DetectorAttachment.TAG_DIST_L else DetectorAttachment.TAG_DIST_R

    override val commitPrefix: String = "dist"

    override val readoutKey: String = "create_waterparked.track.detector_edit_readout"

    companion object {
        const val EDITOR_KEY = "detector_distance"
    }
}
