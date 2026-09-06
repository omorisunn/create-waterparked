package net.omori_sunny.create_waterparked.content.attachment

// where an attachment may mount on a slide
enum class SlideAttachmentSite {
    // mounted on a slide mouth (t = 0 or 1)
    ENDPOINT,

    // mounted somewhere along the tube interior
    INTERIOR
}

// how the framework watches for activation; the concrete behaviour lives in
// the attachment subclass, the spec only configures the detector
sealed interface SlideAttachmentTriggerSpec {

    // real world distance between an entity and the attachment position
    data class Proximity(val range: Double) : SlideAttachmentTriggerSpec

    // arc-length distance along the slide path between a sliding rider and
    // the attachment; reverse = watch the approach direction behind the rider
    data class Path(val distanceBlocks: Double, val reverse: Boolean = false) : SlideAttachmentTriggerSpec

    // the attachment subclass decides entirely on its own
    data object Custom : SlideAttachmentTriggerSpec
}
