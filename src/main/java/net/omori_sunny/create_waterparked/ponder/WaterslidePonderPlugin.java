package net.omori_sunny.create_waterparked.ponder;

import com.simibubi.create.foundation.ponder.CreatePonderPlugin;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.registration.IndexExclusionHelper;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.registration.SharedTextRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
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
        // the water slide track material is a regular Create TrackMaterial, so
        // Create's train-track storyboards would otherwise attach to it. CCS
        // uses excludeBlockVariants for its own material; ours is a distinct
        // block/item class, so exclude the item likes directly (both the block
        // and its item get train-track scenes otherwise).
        helper.exclude(ModBlocks.INSTANCE.getWATERSLIDE_TRACK());
        helper.exclude(ModItems.INSTANCE.getWATERSLIDE_TRACK());
        // the door hub is a shaft driven kinetic block, so Create's generic
        // kinetic storyboards would attach to the block and its item as well -
        // the door has a storyboard of its own instead
        helper.exclude(ModSlideAttachments.INSTANCE.getMECHANICAL_DOOR()
            .getBlock()
            .get());
        helper.exclude(ModSlideAttachments.INSTANCE.getMECHANICAL_DOOR()
            .getItem()
            .get());
    }
}
