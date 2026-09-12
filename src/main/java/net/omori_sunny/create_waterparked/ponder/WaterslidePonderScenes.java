package net.omori_sunny.create_waterparked.ponder;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments;
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType;
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes;
import net.omori_sunny.create_waterparked.content.registry.ModItems;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class WaterslidePonderScenes {

    public static final String CONNECT_SCHEMATIC = "waterslide_anchor/ponder_connect";
    public static final String SLIDE_USE_SCHEMATIC = "waterslide_anchor/slide_ponder_0";
    public static final String SLIDE_SECTOR_SCHEMATIC = "waterslide_anchor/slide_ponder_1";
    public static final String SECTOR_SCENE_ID = "slide_ponder_1";
    public static final String GHOST_SCENE_ID = "slide_ponder_2";
    public static final String ATTACHMENT_SCHEMATIC = "slide_attachment/sa_ponder_0";
    public static final String ATTACHMENT_SCENE_ID = "sa_ponder_0";
    public static final String DOOR_SCENE_ID = "door_ponder_0";

    private static final Map<ResourceLocation, ResourceLocation> SCHEMATIC_PATHS = Map.of(
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "ponder_connect"),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", CONNECT_SCHEMATIC),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", "slide_ponder_0"),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", SLIDE_USE_SCHEMATIC),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", SECTOR_SCENE_ID),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", SLIDE_SECTOR_SCHEMATIC),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", GHOST_SCENE_ID),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", SLIDE_SECTOR_SCHEMATIC),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", ATTACHMENT_SCENE_ID),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", ATTACHMENT_SCHEMATIC),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", DOOR_SCENE_ID),
        ResourceLocation.fromNamespaceAndPath("create_waterparked", ATTACHMENT_SCHEMATIC)
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
        registry.addStoryBoard(anchor, SLIDE_USE_SCHEMATIC, WaterslidePonderScene::useSlideTrack, new ResourceLocation[0]);
        registry.addStoryBoard(track, SLIDE_USE_SCHEMATIC, WaterslidePonderScene::useSlideTrack, new ResourceLocation[0]);
        registry.addStoryBoard(anchor, SLIDE_SECTOR_SCHEMATIC, WaterslidePonderScene::sectorSystem, new ResourceLocation[0]);
        registry.addStoryBoard(track, SLIDE_SECTOR_SCHEMATIC, WaterslidePonderScene::sectorSystem, new ResourceLocation[0]);
        registry.addStoryBoard(anchor, SLIDE_SECTOR_SCHEMATIC, WaterslidePonderScene::ghostBlocks, new ResourceLocation[0]);
        registry.addStoryBoard(track, SLIDE_SECTOR_SCHEMATIC, WaterslidePonderScene::ghostBlocks, new ResourceLocation[0]);
        for (SlideAttachmentType type : SlideAttachmentTypes.INSTANCE.all()) {
            ResourceLocation binding = BuiltInRegistries.ITEM.getKey(type.getItem()
                .get());
            registry.addStoryBoard(binding, ATTACHMENT_SCHEMATIC, WaterslidePonderScene::placeAttachment, new ResourceLocation[0]);
        }
        ResourceLocation door = BuiltInRegistries.ITEM.getKey(ModSlideAttachments.INSTANCE.getMECHANICAL_DOOR()
            .getItem()
            .get());
        registry.addStoryBoard(door, ATTACHMENT_SCHEMATIC, WaterslidePonderScene::mechanicalDoor, new ResourceLocation[0]);
    }
}
