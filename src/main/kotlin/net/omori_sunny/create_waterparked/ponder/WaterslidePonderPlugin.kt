package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.foundation.ponder.CreatePonderPlugin
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags
import net.createmod.catnip.registry.RegisteredObjectsHelper
import net.createmod.ponder.api.level.PonderLevel
import net.createmod.ponder.api.registration.IndexExclusionHelper
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper
import net.createmod.ponder.api.registration.SharedTextRegistrationHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ItemLike
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import net.omori_sunny.create_waterparked.content.registry.ModItems

class WaterslidePonderPlugin : CreatePonderPlugin() {

    // the tag the whole slide attachment series is filed under in the ponder index
    private val slideAttachments: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "slide_attachments")

    override fun getModId(): String = "create_waterparked"

    override fun registerScenes(helper: PonderSceneRegistrationHelper<ResourceLocation>) {
        WaterslidePonderScenes.register(helper)
    }

    override fun registerTags(helper: PonderTagRegistrationHelper<ResourceLocation>) {
        val itemHelper: PonderTagRegistrationHelper<ItemLike> =
            helper.withKeyFunction(RegisteredObjectsHelper::getKeyOrThrow)
        helper.registerTag(slideAttachments)
            .addToIndex()
            .item(ModSlideAttachments.MECHANICAL_DOOR.item.get(), true, false)
            .title("Slide Attachments")
            .description("Attachments which mount onto a waterslide and change how riders pass through it")
            .register()
        val attachments = itemHelper.addToTag(slideAttachments)
        for (type in SlideAttachmentTypes.all()) attachments.add(type.item.get())
        itemHelper.addToTag(AllCreatePonderTags.DISPLAY_SOURCES)
            .add(ModSlideAttachments.MECHANICAL_DOOR.item.get())
            .add(ModSlideAttachments.SLIDE_DETECTOR.item.get())
            .add(ModSlideAttachments.SLIDE_ACCELERATOR.item.get())
            .add(ModSlideAttachments.GRAB_BAR.item.get())
        itemHelper.addToTag(AllCreatePonderTags.KINETIC_APPLIANCES)
            .add(ModItems.ROLLER_CONVEYOR)
    }

    override fun registerSharedText(helper: SharedTextRegistrationHelper) {
    }

    override fun onPonderLevelRestore(ponderLevel: PonderLevel) {
        WaterslidePonderRestore.onLevelRestore(ponderLevel)
    }

    override fun indexExclusions(helper: IndexExclusionHelper) {
        helper.exclude(ModBlocks.WATERSLIDE_TRACK)
        helper.exclude(ModItems.WATERSLIDE_TRACK)
    }
}
