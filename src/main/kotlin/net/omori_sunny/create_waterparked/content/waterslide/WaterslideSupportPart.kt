package net.omori_sunny.create_waterparked.content.waterslide

// which support piece an interaction targets
enum class WaterslideSupportPart {
    BEAM,
    BRACKET;

    companion object {
        @JvmStatic
        fun fromId(id: Int): WaterslideSupportPart? = entries.getOrNull(id)
    }
}
