package net.omori_sunny.create_waterparked.ponder;

import com.simibubi.create.foundation.ponder.CreatePonderPlugin;
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;
import net.createmod.catnip.registry.RegisteredObjectsHelper;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.registration.IndexExclusionHelper;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.registration.SharedTextRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments;
import net.omori_sunny.create_waterparked.content.registry.ModBlocks;
import net.omori_sunny.create_waterparked.content.registry.ModItems;

public class WaterslidePonderPlugin extends CreatePonderPlugin {

    @Override
    public String getModId() {
        return "create_waterparked";
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        WaterslidePonderScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PonderTagRegistrationHelper<ItemLike> itemHelper = helper.withKeyFunction(
            RegisteredObjectsHelper::getKeyOrThrow);
        itemHelper.addToTag(AllCreatePonderTags.DISPLAY_SOURCES)
            .add(ModSlideAttachments.INSTANCE.getMECHANICAL_DOOR()
                .getItem()
                .get());
    }

    @Override
    public void registerSharedText(SharedTextRegistrationHelper helper) {
    }

    @Override
    public void onPonderLevelRestore(PonderLevel ponderLevel) {
        WaterslidePonderRestore.onLevelRestore(ponderLevel);
    }

    @Override
    public void indexExclusions(IndexExclusionHelper helper) {
        helper.exclude(ModBlocks.INSTANCE.getWATERSLIDE_TRACK());
        helper.exclude(ModItems.INSTANCE.getWATERSLIDE_TRACK());
        helper.exclude(ModSlideAttachments.INSTANCE.getMECHANICAL_DOOR()
            .getBlock()
            .get());
        helper.exclude(ModSlideAttachments.INSTANCE.getMECHANICAL_DOOR()
            .getItem()
            .get());
    }
}
