package net.omori_sunny.create_waterparked.ponder;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.omori_sunny.create_waterparked.content.registry.ModItems;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public final class WaterslidePonderScenes {

    public static final String CONNECT_SCHEMATIC = "waterslide_anchor/ponder_connect";

    private static final Map<ResourceLocation, ResourceLocation> SCHEMATIC_PATHS = Map.of(
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "ponder_connect"),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", CONNECT_SCHEMATIC)
    );

    private WaterslidePonderScenes() {
    }

    @Nullable
    public static ResourceLocation schematicPathFor(ResourceLocation sceneId) {
        return SCHEMATIC_PATHS.get(sceneId);
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> registry) {
        ResourceLocation anchor = BuiltInRegistries.ITEM.getKey(ModItems.INSTANCE.getWATERSLIDE_ANCHOR());
        ResourceLocation track = BuiltInRegistries.ITEM.getKey(ModItems.INSTANCE.getWATERSLIDE_TRACK());
        registry.addStoryBoard(anchor, CONNECT_SCHEMATIC, WaterslidePonderScene::connect, new ResourceLocation[0]);
        registry.addStoryBoard(track, CONNECT_SCHEMATIC, WaterslidePonderScene::connect, new ResourceLocation[0]);
    }
}
