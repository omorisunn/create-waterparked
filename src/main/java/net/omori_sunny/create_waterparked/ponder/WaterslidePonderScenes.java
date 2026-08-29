package net.omori_sunny.create_waterparked.ponder;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.omori_sunny.create_waterparked.content.registry.ModItems;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class WaterslidePonderScenes {

    public static final String CONNECT_SCHEMATIC = "waterslide_anchor/ponder_connect";
    public static final String SLIDE_USE_SCHEMATIC = "waterslide_anchor/slide_ponder_0";
    public static final String SECTOR_SCENE_ID = "slide_ponder_1";

    private static final Map<ResourceLocation, ResourceLocation> SCHEMATIC_PATHS = Map.of(
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "ponder_connect"),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", CONNECT_SCHEMATIC),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "slide_ponder_0"),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", SLIDE_USE_SCHEMATIC),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", SECTOR_SCENE_ID),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", SLIDE_USE_SCHEMATIC)
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
        // Both items open the same story list: the "Using the Waterslide Track"
        // scene first, then the "Sector System" scene, both built from the
        // same slide_ponder_0 schematic level
        registry.addStoryBoard(anchor, SLIDE_USE_SCHEMATIC, WaterslidePonderScene::useSlideTrack, new ResourceLocation[0]);
        registry.addStoryBoard(track, SLIDE_USE_SCHEMATIC, WaterslidePonderScene::useSlideTrack, new ResourceLocation[0]);
        registry.addStoryBoard(anchor, SLIDE_USE_SCHEMATIC, WaterslidePonderScene::sectorSystem, new ResourceLocation[0]);
        registry.addStoryBoard(track, SLIDE_USE_SCHEMATIC, WaterslidePonderScene::sectorSystem, new ResourceLocation[0]);
    }
}
