package net.omori_sunny.create_waterparked.content.attachment

import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorAttachment
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorProvider

// every slide attachment kind, registered through the spec DSL
object ModSlideAttachments {

    val MECHANICAL_DOOR: SlideAttachmentType by lazy {
        SlideAttachmentRegistry.register(
            "mechanical_door", SlideAttachmentSite.INTERIOR
        ) {
            attachment(::MechanicalDoorAttachment)
            provider(::MechanicalDoorProvider)
            trigger(SlideAttachmentTriggerSpec.Path(distanceBlocks = 5.0))
            maxHostDistance(16.0)
            // Create stress budget unit is SU per RPM; the kinetic network
            // multiplies by |speed| itself, so 2.0 == "2 x RPM"
            stressImpact(2.0)
        }
    }

    fun init() {
        // forces the DSL chains to run during mod construction
        MECHANICAL_DOOR.toString()
    }
}
