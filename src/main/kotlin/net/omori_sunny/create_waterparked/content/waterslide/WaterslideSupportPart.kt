package net.omori_sunny.create_waterparked.content.waterslide

// Which support structure piece an interaction targets. Beam = the girder-style
// column between the anchor top and the tube underside; Bracket = the short
// shell band hugging the tube at the anchor.
enum class WaterslideSupportPart {
    BEAM,
    BRACKET;

    companion object {
        @JvmStatic
        fun fromId(id: Int): WaterslideSupportPart? = entries.getOrNull(id)
    }
}
