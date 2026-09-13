package net.omori_sunny.create_waterparked.ponder

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
import net.omori_sunny.create_waterparked.content.registry.ModItems

object WaterslidePonderScenes {

    const val CONNECT_SCHEMATIC = "waterslide_anchor/ponder_connect"
    const val SLIDE_USE_SCHEMATIC = "waterslide_anchor/slide_ponder_0"
    const val SLIDE_SECTOR_SCHEMATIC = "waterslide_anchor/slide_ponder_1"
    const val SECTOR_SCENE_ID = "slide_ponder_1"
    const val GHOST_SCENE_ID = "slide_ponder_2"
    const val ATTACHMENT_SCHEMATIC = "slide_attachment/sa_ponder_0"
    const val ATTACHMENT_SCENE_ID = "sa_ponder_0"
    const val DOOR_SCENE_ID = "door_ponder_0"
    const val DETECTOR_SCENE_ID = "detector_ponder_0"

    private val SCHEMATIC_PATHS: Map<ResourceLocation, ResourceLocation> = mapOf(
        path("ponder_connect") to path(CONNECT_SCHEMATIC),
        path("slide_ponder_0") to path(SLIDE_USE_SCHEMATIC),
        path(SECTOR_SCENE_ID) to path(SLIDE_SECTOR_SCHEMATIC),
        path(GHOST_SCENE_ID) to path(SLIDE_SECTOR_SCHEMATIC),
        path(ATTACHMENT_SCENE_ID) to path(ATTACHMENT_SCHEMATIC),
        path(DOOR_SCENE_ID) to path(ATTACHMENT_SCHEMATIC),
        path(DETECTOR_SCENE_ID) to path(ATTACHMENT_SCHEMATIC)
    )

    private fun path(id: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath("create_waterparked", id)

    @JvmStatic
    fun schematicPathFor(sceneId: ResourceLocation): ResourceLocation? = SCHEMATIC_PATHS[sceneId]

    @JvmStatic
    fun register(registry: PonderSceneRegistrationHelper<ResourceLocation>) {
        val anchor = BuiltInRegistries.ITEM.getKey(ModItems.WATERSLIDE_ANCHOR)
        val track = BuiltInRegistries.ITEM.getKey(ModItems.WATERSLIDE_TRACK)
        registry.addStoryBoard(anchor, SLIDE_USE_SCHEMATIC, WaterslidePonderScene::useSlideTrack)
        registry.addStoryBoard(track, SLIDE_USE_SCHEMATIC, WaterslidePonderScene::useSlideTrack)
        registry.addStoryBoard(anchor, SLIDE_SECTOR_SCHEMATIC, WaterslidePonderScene::sectorSystem)
        registry.addStoryBoard(track, SLIDE_SECTOR_SCHEMATIC, WaterslidePonderScene::sectorSystem)
        registry.addStoryBoard(anchor, SLIDE_SECTOR_SCHEMATIC, WaterslidePonderScene::ghostBlocks)
        registry.addStoryBoard(track, SLIDE_SECTOR_SCHEMATIC, WaterslidePonderScene::ghostBlocks)
        for (type in SlideAttachmentTypes.all()) {
            val binding = BuiltInRegistries.ITEM.getKey(type.item.get())
            registry.addStoryBoard(binding, ATTACHMENT_SCHEMATIC, WaterslidePonderScene::placeAttachment)
        }
        val door = BuiltInRegistries.ITEM.getKey(ModSlideAttachments.MECHANICAL_DOOR.item.get())
        registry.addStoryBoard(door, ATTACHMENT_SCHEMATIC, WaterslidePonderScene::mechanicalDoor)
        val detector = BuiltInRegistries.ITEM.getKey(ModSlideAttachments.SLIDE_DETECTOR.item.get())
        registry.addStoryBoard(detector, ATTACHMENT_SCHEMATIC, WaterslidePonderScene::slideDetector)
    }
}
