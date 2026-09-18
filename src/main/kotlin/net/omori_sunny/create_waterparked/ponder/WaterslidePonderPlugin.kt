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
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import net.omori_sunny.create_waterparked.content.registry.ModItems

class WaterslidePonderPlugin : CreatePonderPlugin() {

    override fun getModId(): String = "create_waterparked"

    override fun registerScenes(helper: PonderSceneRegistrationHelper<ResourceLocation>) {
        WaterslidePonderScenes.register(helper)
    }

    override fun registerTags(helper: PonderTagRegistrationHelper<ResourceLocation>) {
        val itemHelper: PonderTagRegistrationHelper<ItemLike> =
            helper.withKeyFunction(RegisteredObjectsHelper::getKeyOrThrow)
        itemHelper.addToTag(AllCreatePonderTags.DISPLAY_SOURCES)
            .add(ModSlideAttachments.MECHANICAL_DOOR.item.get())
            .add(ModSlideAttachments.SLIDE_DETECTOR.item.get())
            .add(ModSlideAttachments.GRAB_BAR.item.get())
    }

    override fun registerSharedText(helper: SharedTextRegistrationHelper) {
    }

    override fun onPonderLevelRestore(ponderLevel: PonderLevel) {
        WaterslidePonderRestore.onLevelRestore(ponderLevel)
    }

    override fun indexExclusions(helper: IndexExclusionHelper) {
        helper.exclude(ModBlocks.WATERSLIDE_TRACK)
        helper.exclude(ModItems.WATERSLIDE_TRACK)
        helper.exclude(ModSlideAttachments.MECHANICAL_DOOR.block.get())
        helper.exclude(ModSlideAttachments.MECHANICAL_DOOR.item.get())
        helper.exclude(ModSlideAttachments.SLIDE_DETECTOR.block.get())
        helper.exclude(ModSlideAttachments.SLIDE_DETECTOR.item.get())
        helper.exclude(ModSlideAttachments.SLIDE_ACCELERATOR.block.get())
        helper.exclude(ModSlideAttachments.SLIDE_ACCELERATOR.item.get())
        helper.exclude(ModSlideAttachments.GRAB_BAR.block.get())
        helper.exclude(ModSlideAttachments.GRAB_BAR.item.get())
    }
}
