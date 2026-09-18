package net.omori_sunny.create_waterparked.content.attachment

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour
import net.minecraft.network.chat.Component
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.attachment.accelerator.AcceleratorAttachment
import net.omori_sunny.create_waterparked.content.attachment.accelerator.AcceleratorProvider
import net.omori_sunny.create_waterparked.content.attachment.detector.DetectorAttachment
import net.omori_sunny.create_waterparked.content.attachment.detector.DetectorProvider
import net.omori_sunny.create_waterparked.content.attachment.door.DoorModeSlot
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorAttachment
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorMode
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorProvider
import net.omori_sunny.create_waterparked.content.attachment.grab_bar.GrabBarAttachment
import net.omori_sunny.create_waterparked.content.attachment.grab_bar.GrabBarProvider

object ModSlideAttachments {

    val MECHANICAL_DOOR: SlideAttachmentType by lazy {
        SlideAttachmentRegistry.register(
            "mechanical_door", SlideAttachmentSite.INTERIOR
        ) {
            attachment(::MechanicalDoorAttachment)
            provider(::MechanicalDoorProvider)
            trigger(SlideAttachmentTriggerSpec.Path(distanceBlocks = 5.0))
            maxHostDistance(16.0)
            stressImpact(2.0)
            extraBehaviours { be ->
                listOf(
                    ScrollOptionBehaviour(
                        MechanicalDoorMode::class.java,
                        Component.translatable("create_waterparked.door.mode_slot"),
                        be,
                        DoorModeSlot()
                    )
                )
            }
        }
    }

    val SLIDE_DETECTOR: SlideAttachmentType by lazy {
        SlideAttachmentRegistry.register(
            "detector", SlideAttachmentSite.INTERIOR
        ) {
            attachment(::DetectorAttachment)
            provider(::DetectorProvider)
            trigger(
                SlideAttachmentTriggerSpec.Path(
                    distanceBlocks = SlideHandleDistance.MAX_DIST.toDouble()
                )
            )
            goggleInfo(false)
        }
    }

    val SLIDE_ACCELERATOR: SlideAttachmentType by lazy {
        SlideAttachmentRegistry.register(
            "accelerator", SlideAttachmentSite.INTERIOR
        ) {
            attachment(::AcceleratorAttachment)
            provider(::AcceleratorProvider)
            trigger(SlideAttachmentTriggerSpec.Custom)
            stressImpact { ModConfig.acceleratorStressImpact() }
        }
    }

    val GRAB_BAR: SlideAttachmentType by lazy {
        SlideAttachmentRegistry.register(
            "grab_bar", SlideAttachmentSite.ENDPOINT
        ) {
            attachment(::GrabBarAttachment)
            provider(::GrabBarProvider)
            trigger(SlideAttachmentTriggerSpec.Custom)
            maxHostDistance(8.0)
            goggleInfo(false)
        }
    }

    fun init() {
        MECHANICAL_DOOR.toString()
        SLIDE_DETECTOR.toString()
        SLIDE_ACCELERATOR.toString()
        GRAB_BAR.toString()
    }
}
