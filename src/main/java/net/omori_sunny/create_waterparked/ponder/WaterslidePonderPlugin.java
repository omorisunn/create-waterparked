package net.omori_sunny.create_waterparked.ponder;

import com.simibubi.create.foundation.ponder.CreatePonderPlugin;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.registration.IndexExclusionHelper;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.registration.SharedTextRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

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
    }
}
