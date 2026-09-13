package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.AllBlocks
import com.simibubi.create.AllItems
import com.simibubi.create.foundation.ponder.CreateSceneBuilder
import net.createmod.catnip.math.Pointing
import net.createmod.ponder.api.PonderPalette
import net.createmod.ponder.api.element.ElementLink
import net.createmod.ponder.api.element.WorldSectionElement
import net.createmod.ponder.api.scene.SceneBuilder
import net.createmod.ponder.api.scene.SceneBuildingUtil
import net.createmod.ponder.api.scene.Selection
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlock
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentSite
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorAttachment
import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial
import net.omori_sunny.create_waterparked.content.waterslide.SectorType
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart
import kotlin.math.min

object WaterslidePonderScene {

    private const val DISPLAY_Y = 1
    private val ANCHOR_LEFT = BlockPos(3, DISPLAY_Y, 7)
    private val ANCHOR_RIGHT = BlockPos(11, DISPLAY_Y, 7)
    private val OFFSCREEN = Vec3(0.0, -100.0, 0.0)

    private val DOOR_POS = BlockPos(6, 4, 6)
    private val DOOR_SHAFT_A = BlockPos(6, 4, 5)
    private val DOOR_SHAFT_B = BlockPos(6, 4, 4)
    private val DETECTOR_POS = BlockPos(6, 4, 6)
    private val DETECTOR_LAMP = BlockPos(6, 4, 7)
    private val ACCELERATOR_POS = BlockPos(6, 4, 6)
    private val ACCELERATOR_SHAFT = BlockPos(6, 4, 5)
    private val ATTACH_HOST_POS = BlockPos(7, DISPLAY_Y, 7)
    private const val SITE_OUTLINE = "create_waterparked:attachment_site"
    private const val ATTACH_T = 0.5f

    @JvmStatic
    fun connect(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title("ponder_connect", "Connecting Waterslide Anchors")
        scene.configureBasePlate(0, 0, 15)
        scene.scaleSceneView(0.7f)
        scene.setSceneOffsetY(-1.0f)
        scene.showBasePlate()
        scene.idle(10)

        val twoAnchors = util.select().fromTo(
            ANCHOR_LEFT.x, DISPLAY_Y, ANCHOR_LEFT.z,
            ANCHOR_RIGHT.x, DISPLAY_Y, ANCHOR_RIGHT.z
        )
        var anchorLayer: ElementLink<WorldSectionElement> =
            scene.world().showIndependentSection(twoAnchors, Direction.DOWN)
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, DISPLAY_Y, DISPLAY_Y, ANCHOR_LEFT, ANCHOR_RIGHT)

        val leftTop = util.vector().topOf(ANCHOR_LEFT)
        val rightTop = util.vector().topOf(ANCHOR_RIGHT)
        val midTop = util.vector().topOf(7, DISPLAY_Y, 7)
        val trackStack = ItemStack(ModItems.WATERSLIDE_TRACK)

        scene.idle(20)
        scene.overlay()
            .showText(90)
            .attachKeyFrame()
            .independent(20)
            .text("Right-click two waterslide anchors with a waterslide track to connect them")
        scene.idle(30)
        scene.overlay().showControls(leftTop, Pointing.DOWN, 60).withItem(trackStack).rightClick()
        scene.idle(40)
        scene.overlay().showControls(rightTop, Pointing.DOWN, 40).withItem(trackStack).rightClick()
        scene.idle(50)

        anchorLayer = swapAnchorLayer(scene, util, anchorLayer, 2)
        scene.idle(20)
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A waterslide spline is created between the two anchors")
            .placeNearTarget()
            .pointAt(midTop)
        scene.idle(70)

        anchorLayer = swapAnchorLayer(scene, util, anchorLayer, 3)
        scene.idle(20)
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A wrench can be used to adjust the path of the spline")
            .placeNearTarget()
            .pointAt(midTop)
        scene.overlay().showControls(midTop, Pointing.DOWN, 60).withItem(AllItems.WRENCH.asStack())
        scene.idle(80)
    }

    @JvmStatic
    fun useSlideTrack(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title("slide_ponder_0", "Using the Waterslide Track")
        scene.configureBasePlate(0, 0, 15)
        scene.scaleSceneView(0.7f)
        scene.setSceneOffsetY(-1.0f)
        scene.showBasePlate()
        scene.idle(10)

        val anchors = WaterslidePonderRestore.schemaAnchors(scene.scene.world)
        val anchorLeft = if (anchors.size >= 1) anchors[0] else ANCHOR_LEFT
        val anchorRight = if (anchors.size >= 2) anchors[1] else ANCHOR_RIGHT
        val anchorY = min(anchorLeft.y, anchorRight.y)

        var anchorLayer: ElementLink<WorldSectionElement> = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.x, anchorY, anchorLeft.z,
                    anchorRight.x, anchorY, anchorRight.z
                ),
                Direction.DOWN
            )
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight)
        WaterslidePonderRestore.clearDisplayedCurves(scene, anchorY, anchorLeft, anchorRight)
        scene.idle(30)

        val leftTop = util.vector().topOf(anchorLeft)
        val rightTop = util.vector().topOf(anchorRight)
        val trackStack = ItemStack(ModItems.WATERSLIDE_TRACK)
        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-click two waterslide anchors with a waterslide track......")
            .placeNearTarget()
            .pointAt(leftTop)
        scene.idle(30)
        scene.overlay().showControls(leftTop, Pointing.DOWN, 70).withItem(trackStack)
        scene.idle(20)
        scene.overlay().showControls(rightTop, Pointing.DOWN, 50).withItem(trackStack)

        scene.idle(10)
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight)
        val leftBe4 = scene.scene.world.getBlockEntity(anchorLeft) as? WaterslideAnchorBlockEntity
        if (leftBe4 != null) {
            scene.addInstruction { sc ->
                val live = sc.world.getBlockEntity(anchorLeft) as? WaterslideAnchorBlockEntity
                if (live != null) {
                    PonderSlideHelper.waterFlowShow(live, 0.5f)
                }
            }
        }
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A new waterslide is created.")
            .placeNearTarget()
            .pointAt(
                util.vector().topOf(
                    (anchorLeft.x + anchorRight.x) / 2, anchorY,
                    (anchorLeft.z + anchorRight.z) / 2
                )
            )
        scene.idle(80)

        scene.overlay()
            .showText(90)
            .text("Some controls of the waterslide work exactly like coaster track.")
            .placeNearTarget()
            .pointAt(
                util.vector().topOf(
                    (anchorLeft.x + anchorRight.x) / 2, anchorY,
                    (anchorLeft.z + anchorRight.z) / 2
                )
            )
        if (leftBe4 != null) {
            val rightBe5 = scene.scene.world.getBlockEntity(anchorRight) as? WaterslideAnchorBlockEntity
            scene.world().modifyBlockEntity(anchorLeft, WaterslideAnchorBlockEntity::class.java) { be ->
                if (be == null) return@modifyBlockEntity
                val cfg = be.sectorConfigFor(anchorRight).copyOf()
                cfg.sectors.clear()
                cfg.sectors.add(
                    WaterslideSector(
                        cfg.newId(),
                        SectorMaterial.BLOCK,
                        ResourceLocation.parse("minecraft:gray_concrete"),
                        SectorType.AUTO,
                        0f
                    )
                )
                be.setSectorConfig(anchorRight, cfg)
            }
            if (rightBe5 != null) {
                scene.world().modifyBlockEntity(anchorRight, WaterslideAnchorBlockEntity::class.java) { be ->
                    if (be == null) return@modifyBlockEntity
                    val cfg = be.sectorConfigFor(anchorLeft).copyOf()
                    cfg.sectors.clear()
                    cfg.sectors.add(
                        WaterslideSector(
                            cfg.newId(),
                            SectorMaterial.BLOCK,
                            ResourceLocation.parse("minecraft:gray_concrete"),
                            SectorType.AUTO,
                            0f
                        )
                    )
                    be.setSectorConfig(anchorLeft, cfg)
                }
                scene.addInstruction(
                    PonderSlideRadiusInstruction(
                        listOf(leftBe4, rightBe5),
                        1.0f,
                        40,
                        0
                    )
                )
            }
            val beForHide = leftBe4
            scene.addInstruction { PonderSlideHelper.waterFlowHide(beForHide) }
            PonderSlideEditUiElement.show(scene, leftBe4)
        }
        scene.idle(80)

        scene.world().modifyBlockEntity(anchorLeft, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            be.setWaterActive(false)
            for (peer in be.anchorPeerCurvesView.keys) {
                be.setCurveWatered(peer, false)
            }
            be.setSupportMaterial(
                WaterslideSupportPart.BEAM,
                AllBlocks.COPYCAT_BASE.get().defaultBlockState(),
                ItemStack.EMPTY
            )
            be.setSupportMaterial(
                WaterslideSupportPart.BRACKET,
                AllBlocks.COPYCAT_BASE.get().defaultBlockState(),
                ItemStack.EMPTY
            )
        }
        scene.world().modifyBlockEntity(anchorRight, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            be.setWaterActive(false)
            for (peer in be.anchorPeerCurvesView.keys) {
                be.setCurveWatered(peer, false)
            }
            be.setSupportMaterial(
                WaterslideSupportPart.BEAM,
                AllBlocks.COPYCAT_BASE.get().defaultBlockState(),
                ItemStack.EMPTY
            )
            be.setSupportMaterial(
                WaterslideSupportPart.BRACKET,
                AllBlocks.COPYCAT_BASE.get().defaultBlockState(),
                ItemStack.EMPTY
            )
        }
        val leftBe1 = scene.scene.world.getBlockEntity(anchorLeft) as? WaterslideAnchorBlockEntity
        val rightBe1 = scene.scene.world.getBlockEntity(anchorRight) as? WaterslideAnchorBlockEntity
        if (leftBe1 != null && rightBe1 != null) {
            scene.addInstruction(
                PonderSlideRadiusInstruction(
                    listOf(leftBe1, rightBe1),
                    1.0f,
                    40,
                    0
                )
            )
        }
        if (leftBe1 != null) {
            scene.addInstruction { sc ->
                val live = sc.world.getBlockEntity(anchorLeft) as? WaterslideAnchorBlockEntity
                if (live != null) {
                    PonderSlideHelper.waterFlowHide(live)
                }
            }
        }
        scene.idle(40)

        scene.overlay()
            .showText(80)
            .text("A wrench can be used to adjust the path of the slide")
            .placeNearTarget()
            .pointAt(
                util.vector().topOf(
                    (anchorLeft.x + anchorRight.x) / 2, anchorY,
                    (anchorLeft.z + anchorRight.z) / 2
                )
            )
        scene.idle(20)
        if (leftBe1 != null) {
            PonderSlideHelper.easeMove(scene.scene, leftBe1, 0.0, 1.25, 0.0, 50, 5)
        }
        scene.idle(65)

        val handleTip = util.vector().topOf(anchorLeft)
        scene.overlay()
            .showText(70)
            .text("Drag left and right to adjust the tube opening size")
            .placeNearTarget()
            .pointAt(handleTip)
        scene.idle(30)
        val leftBe2 = scene.scene.world.getBlockEntity(anchorLeft) as? WaterslideAnchorBlockEntity
        val rightBe2 = scene.scene.world.getBlockEntity(anchorRight) as? WaterslideAnchorBlockEntity
        if (leftBe2 != null && rightBe2 != null) {
            scene.addInstruction(
                PonderSlideRadiusInstruction(
                    listOf(leftBe2, rightBe2),
                    1.5f,
                    60,
                    0
                )
            )
        }
        scene.idle(90)

        scene.world().hideIndependentSection(anchorLayer, Direction.UP)
        scene.idle(60)
    }

    @JvmStatic
    fun sectorSystem(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title(WaterslidePonderScenes.SECTOR_SCENE_ID, "Sector System")
        scene.configureBasePlate(0, 0, 15)
        scene.scaleSceneView(0.7f)
        scene.setSceneOffsetY(-1.0f)
        scene.rotateCameraY(90f)
        scene.showBasePlate()
        scene.idle(10)

        val anchors = WaterslidePonderRestore.schemaAnchors(scene.scene.world)
        val anchorLeft = if (anchors.size >= 1) anchors[0] else ANCHOR_LEFT
        val anchorRight = if (anchors.size >= 2) anchors[1] else ANCHOR_RIGHT
        val anchorY = min(anchorLeft.y, anchorRight.y)

        val anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.x, anchorY, anchorLeft.z,
                    anchorRight.x, anchorY, anchorRight.z
                ),
                Direction.DOWN
            )
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight)
        scene.idle(30)

        val leftTop = util.vector().topOf(anchorLeft)
        val rightTop = util.vector().topOf(anchorRight)
        val midTop = util.vector().topOf(
            (anchorLeft.x + anchorRight.x) / 2, anchorY,
            (anchorLeft.z + anchorRight.z) / 2
        )
        val planks = ItemStack(Items.OAK_PLANKS)
        val axe = ItemStack(Items.IRON_AXE)

        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-click two waterslide anchors with a block......")
            .placeNearTarget()
            .pointAt(leftTop)
        scene.idle(30)
        scene.overlay().showControls(leftTop, Pointing.DOWN, 70).withItem(planks)
        scene.idle(20)
        scene.overlay().showControls(rightTop, Pointing.DOWN, 50).withItem(planks)

        addSector(scene, anchorLeft, anchorRight, SectorMaterial.BLOCK, "minecraft:oak_planks")
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A sector with the corresponding block texture can be added.")
            .placeNearTarget()
            .pointAt(midTop)
        scene.idle(80)

        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-click two waterslide anchors with an axe......")
            .placeNearTarget()
            .pointAt(leftTop)
        scene.idle(30)
        scene.overlay().showControls(leftTop, Pointing.DOWN, 70).withItem(axe)
        scene.idle(20)
        scene.overlay().showControls(rightTop, Pointing.DOWN, 50).withItem(axe)

        addSector(scene, anchorLeft, anchorRight, SectorMaterial.OPEN, null)
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("An empty sector can be added.")
            .placeNearTarget()
            .pointAt(midTop)
        scene.idle(80)

        scene.overlay()
            .showText(90)
            .text("You can use a wrench to adjust the position and size of sectors")
            .placeNearTarget()
            .pointAt(midTop)
        scene.idle(80)

        scene.world().hideIndependentSection(anchorLayer, Direction.UP)
        scene.idle(60)
    }

    @JvmStatic
    fun ghostBlocks(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title(WaterslidePonderScenes.GHOST_SCENE_ID, "Ghost Blocks")
        scene.configureBasePlate(0, 0, 15)
        scene.scaleSceneView(0.7f)
        scene.setSceneOffsetY(-1.0f)
        scene.rotateCameraY(90f)
        scene.showBasePlate()
        scene.idle(10)

        val anchors = WaterslidePonderRestore.schemaAnchors(scene.scene.world)
        val anchorLeft = if (anchors.size >= 1) anchors[0] else ANCHOR_LEFT
        val anchorRight = if (anchors.size >= 2) anchors[1] else ANCHOR_RIGHT
        val anchorY = min(anchorLeft.y, anchorRight.y)

        val anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.x, anchorY, anchorLeft.z,
                    anchorRight.x, anchorY, anchorRight.z
                ),
                Direction.DOWN
            )
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight)
        scene.idle(30)

        val midTop = util.vector().topOf(
            (anchorLeft.x + anchorRight.x) / 2, anchorY,
            (anchorLeft.z + anchorRight.z) / 2
        )
        val leftTop = util.vector().topOf(anchorLeft)
        val wrench = AllItems.WRENCH.asStack()
        val planks = ItemStack(Items.OAK_PLANKS)
        val axe = ItemStack(Items.IRON_AXE)

        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Hold a wrench in your main hand and a block in your offhand......")
            .placeNearTarget()
            .pointAt(leftTop)
        scene.idle(30)
        scene.overlay().showControls(midTop, Pointing.DOWN, 70).withItem(wrench)
        scene.idle(30)
        addGhost(scene, anchorLeft, anchorRight, planks, 0.35f, 45f)
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A ghost block is placed, hugging the outside of the tube wall.")
            .placeNearTarget()
            .pointAt(midTop)
        scene.idle(80)

        addGhost(scene, anchorLeft, anchorRight, planks, 0.65f, 45f)
        scene.overlay()
            .showText(80)
            .text("Ghost blocks follow the curve of the slide.")
            .placeNearTarget()
            .pointAt(midTop)
        scene.idle(80)

        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-clicking with an axe removes ghost blocks......")
            .placeNearTarget()
            .pointAt(leftTop)
        scene.idle(30)
        scene.overlay().showControls(midTop, Pointing.DOWN, 60).withItem(axe)
        scene.idle(30)
        clearGhosts(scene, anchorLeft, anchorRight)
        scene.idle(40)

        scene.world().hideIndependentSection(anchorLayer, Direction.UP)
        scene.idle(60)
    }

    @JvmStatic
    fun placeAttachment(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title(WaterslidePonderScenes.ATTACHMENT_SCENE_ID, "Mounting a Slide Attachment")
        scene.configureBasePlate(0, 0, 15)
        scene.scaleSceneView(0.7f)
        scene.setSceneOffsetY(-1.0f)
        scene.showBasePlate()
        scene.idle(10)

        val anchors = WaterslidePonderRestore.schemaAnchors(scene.scene.world)
        val anchorLeft = if (anchors.size >= 1) anchors[0] else ANCHOR_LEFT
        val anchorRight = if (anchors.size >= 2) anchors[1] else ANCHOR_RIGHT
        val anchorY = min(anchorLeft.y, anchorRight.y)

        val anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.x, anchorY, anchorLeft.z,
                    anchorRight.x, anchorY, anchorRight.z
                ).substract(util.select().position(ATTACH_HOST_POS)),
                Direction.DOWN
            )
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight)

        val type = attachmentExample()
        val bindingStack = ItemStack(type.item.get())
        val siteTop = util.vector().topOf(DOOR_POS)
        val hostTop = util.vector().topOf(ATTACH_HOST_POS)
        val midTop = util.vector().topOf(
            (anchorLeft.x + anchorRight.x) / 2, anchorY,
            (anchorLeft.z + anchorRight.z) / 2
        )
        scene.idle(30)
        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-click the slide with a slide attachment to pick a spot")
            .placeNearTarget()
            .pointAt(siteTop)
        scene.idle(30)
        scene.overlay().showControls(siteTop, Pointing.DOWN, 70).withItem(bindingStack).rightClick()
        scene.overlay().showOutline(PonderPalette.GREEN, SITE_OUTLINE, util.select().position(DOOR_POS), 70)
        scene.overlay().showLine(PonderPalette.GREEN, hostTop, siteTop, 70)
        scene.idle(70)
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("Then place the block on the ground")
            .placeNearTarget()
            .pointAt(siteTop)
        scene.idle(30)
        scene.world().setBlock(ATTACH_HOST_POS, attachmentBlock(type, Direction.Axis.Y), true)
        scene.world().showIndependentSection(util.select().position(ATTACH_HOST_POS), Direction.DOWN)
        bindAttachment(scene, util, ATTACH_HOST_POS, type, anchorLeft, anchorRight)
        scene.idle(60)
        scene.overlay()
            .showText(80)
            .text("The attachment appears on the slide at the bound spot")
            .placeNearTarget()
            .pointAt(midTop)
    }

    @JvmStatic
    fun mechanicalDoor(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title(WaterslidePonderScenes.DOOR_SCENE_ID, "Using the Mechanical Door")
        scene.configureBasePlate(0, 0, 15)
        scene.scaleSceneView(0.7f)
        scene.setSceneOffsetY(-1.0f)
        scene.rotateCameraY(90f)
        scene.showBasePlate()
        scene.idle(10)

        val anchors = WaterslidePonderRestore.schemaAnchors(scene.scene.world)
        val anchorLeft = if (anchors.size >= 1) anchors[0] else ANCHOR_LEFT
        val anchorRight = if (anchors.size >= 2) anchors[1] else ANCHOR_RIGHT
        val anchorY = min(anchorLeft.y, anchorRight.y)

        val anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.x, anchorY, anchorLeft.z,
                    anchorRight.x, anchorY, anchorRight.z
                ),
                Direction.DOWN
            )
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight)

        setRingGlass(scene, anchorLeft, anchorRight)
        setRingGlass(scene, anchorRight, anchorLeft)

        val hubTop = util.vector().topOf(DOOR_POS)
        val shaftStack = ItemStack(AllBlocks.SHAFT.get())
        scene.world()
            .setBlock(DOOR_POS, attachmentBlock(ModSlideAttachments.MECHANICAL_DOOR, Direction.Axis.Z), true)
        val doorLayer = scene.world()
            .showIndependentSection(util.select().position(DOOR_POS), Direction.DOWN)
        bindDoor(scene, util, anchorLeft, anchorRight)
        scene.idle(30)
        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Put a shaft into the door hub and it will drive the door")
            .placeNearTarget()
            .pointAt(hubTop)
        scene.idle(20)
        scene.overlay().showControls(hubTop, Pointing.DOWN, 60).withItem(shaftStack)
        scene.world().setBlock(DOOR_SHAFT_A, shaftBlock(), true)
        val shaftALayer = scene.world()
            .showIndependentSection(util.select().position(DOOR_SHAFT_A), Direction.DOWN)
        scene.world().setBlock(DOOR_SHAFT_B, shaftBlock(), true)
        val shaftBLayer = scene.world()
            .showIndependentSection(util.select().position(DOOR_SHAFT_B), Direction.DOWN)
        scene.idle(80)
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("How far the shaft turns decides how far the door opens")
            .placeNearTarget()
            .pointAt(hubTop)
        scene.idle(30)
        scene.addInstruction(PonderDoorSweepInstruction(DOOR_POS, 0f, 1f, 0, 24))
        scene.idle(70)
        scene.addInstruction(PonderDoorSweepInstruction(DOOR_POS, 1f, 0f, 0, 24))
        scene.idle(70)
        scene.world().hideIndependentSection(doorLayer, Direction.UP)
        scene.world().hideIndependentSection(shaftALayer, Direction.UP)
        scene.world().hideIndependentSection(shaftBLayer, Direction.UP)
        scene.idle(15)
        scene.world().setBlock(DOOR_POS, Blocks.AIR.defaultBlockState(), false)
        scene.world().setBlock(DOOR_SHAFT_A, Blocks.AIR.defaultBlockState(), false)
        scene.world().setBlock(DOOR_SHAFT_B, Blocks.AIR.defaultBlockState(), false)
        scene.world().hideIndependentSection(anchorLayer, Direction.UP)
    }

    @JvmStatic
    fun slideDetector(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title(WaterslidePonderScenes.DETECTOR_SCENE_ID, "Using the Slide Detector")
        scene.configureBasePlate(0, 0, 15)
        scene.scaleSceneView(0.7f)
        scene.setSceneOffsetY(-1.0f)
        scene.rotateCameraY(90f)
        scene.showBasePlate()
        scene.idle(10)

        val anchors = WaterslidePonderRestore.schemaAnchors(scene.scene.world)
        val anchorLeft = if (anchors.size >= 1) anchors[0] else ANCHOR_LEFT
        val anchorRight = if (anchors.size >= 2) anchors[1] else ANCHOR_RIGHT
        val anchorY = min(anchorLeft.y, anchorRight.y)

        val anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.x, anchorY, anchorLeft.z,
                    anchorRight.x, anchorY, anchorRight.z
                ),
                Direction.DOWN
            )
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight)
        setRingHalfOpen(scene, anchorLeft, anchorRight)
        setRingHalfOpen(scene, anchorRight, anchorLeft)

        val hubTop = util.vector().topOf(DETECTOR_POS)
        scene.world()
            .setBlock(DETECTOR_POS, attachmentBlock(ModSlideAttachments.SLIDE_DETECTOR, Direction.Axis.Z), true)
        val detectorLayer = scene.world()
            .showIndependentSection(util.select().position(DETECTOR_POS), Direction.DOWN)
        bindAttachment(scene, util, DETECTOR_POS, ModSlideAttachments.SLIDE_DETECTOR, anchorLeft, anchorRight)
        scene.idle(30)
        scene.overlay()
            .showText(90)
            .independent(20)
            .text("The band hugs the inner wall and ends where a sector is open")
            .placeNearTarget()
            .pointAt(hubTop)
        scene.idle(100)

        scene.world().setBlock(DETECTOR_LAMP, Blocks.REDSTONE_LAMP.defaultBlockState(), true)
        val lampLayer = scene.world()
            .showIndependentSection(util.select().position(DETECTOR_LAMP), Direction.DOWN)
        scene.idle(20)
        scene.overlay()
            .showText(90)
            .attachKeyFrame()
            .text("A rider crossing it switches the hub on and it emits redstone")
            .placeNearTarget()
            .pointAt(hubTop)
        scene.idle(30)
        scene.world()
            .modifyBlock(DETECTOR_POS, { it.setValue(SlideAttachmentBlock.POWERED, true) }, true)
        scene.world().setBlock(
            DETECTOR_LAMP,
            Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true),
            true
        )
        scene.idle(70)
        scene.overlay()
            .showText(80)
            .text("Put a display link on the hub to read how many riders have passed")
            .placeNearTarget()
            .pointAt(hubTop)
        scene.idle(90)

        scene.world()
            .modifyBlock(DETECTOR_POS, { it.setValue(SlideAttachmentBlock.POWERED, false) }, true)
        scene.world().setBlock(DETECTOR_LAMP, Blocks.REDSTONE_LAMP.defaultBlockState(), true)
        scene.idle(20)
        scene.world().hideIndependentSection(detectorLayer, Direction.UP)
        scene.world().hideIndependentSection(lampLayer, Direction.UP)
        scene.world().hideIndependentSection(anchorLayer, Direction.UP)
        scene.idle(15)
        scene.world().setBlock(DETECTOR_POS, Blocks.AIR.defaultBlockState(), false)
        scene.world().setBlock(DETECTOR_LAMP, Blocks.AIR.defaultBlockState(), false)
    }

    @JvmStatic
    fun slideAccelerator(builder: SceneBuilder, util: SceneBuildingUtil) {
        val scene = CreateSceneBuilder(builder)
        scene.title(WaterslidePonderScenes.ACCELERATOR_SCENE_ID, "Using the Slide Accelerator")
        scene.configureBasePlate(0, 0, 15)
        scene.scaleSceneView(0.7f)
        scene.setSceneOffsetY(-1.0f)
        scene.rotateCameraY(90f)
        scene.showBasePlate()
        scene.idle(10)

        val anchors = WaterslidePonderRestore.schemaAnchors(scene.scene.world)
        val anchorLeft = if (anchors.size >= 1) anchors[0] else ANCHOR_LEFT
        val anchorRight = if (anchors.size >= 2) anchors[1] else ANCHOR_RIGHT
        val anchorY = min(anchorLeft.y, anchorRight.y)

        val anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.x, anchorY, anchorLeft.z,
                    anchorRight.x, anchorY, anchorRight.z
                ),
                Direction.DOWN
            )
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight)
        setRingHalfOpen(scene, anchorLeft, anchorRight)
        setRingHalfOpen(scene, anchorRight, anchorLeft)

        val hubTop = util.vector().topOf(ACCELERATOR_POS)
        scene.world().setBlock(
            ACCELERATOR_POS,
            attachmentBlock(ModSlideAttachments.SLIDE_ACCELERATOR, Direction.Axis.Z),
            true
        )
        val padLayer = scene.world()
            .showIndependentSection(util.select().position(ACCELERATOR_POS), Direction.DOWN)
        bindAttachment(
            scene, util, ACCELERATOR_POS,
            ModSlideAttachments.SLIDE_ACCELERATOR, anchorLeft, anchorRight
        )
        scene.idle(30)
        scene.overlay()
            .showText(90)
            .independent(20)
            .text("The band boosts whoever crosses it, along the direction of the arrows")
            .placeNearTarget()
            .pointAt(hubTop)
        scene.idle(100)

        scene.world().setBlock(ACCELERATOR_SHAFT, shaftBlock(), true)
        val shaftLayer = scene.world()
            .showIndependentSection(util.select().position(ACCELERATOR_SHAFT), Direction.DOWN)
        scene.idle(20)
        scene.overlay()
            .showText(90)
            .attachKeyFrame()
            .text("A shaft drives it: the faster it turns, the stronger the boost")
            .placeNearTarget()
            .pointAt(hubTop)
        scene.idle(100)

        scene.overlay()
            .showText(80)
            .text("Wrench the direction handle to aim the boost, or hold Alt to ignore the grid")
            .placeNearTarget()
            .pointAt(hubTop)
        scene.idle(90)

        scene.world().hideIndependentSection(shaftLayer, Direction.UP)
        scene.world().hideIndependentSection(padLayer, Direction.UP)
        scene.world().hideIndependentSection(anchorLayer, Direction.UP)
        scene.idle(15)
        scene.world().setBlock(ACCELERATOR_POS, Blocks.AIR.defaultBlockState(), false)
        scene.world().setBlock(ACCELERATOR_SHAFT, Blocks.AIR.defaultBlockState(), false)
    }

    private fun attachmentExample(): SlideAttachmentType =
        SlideAttachmentTypes.all()
            .iterator()
            .next()

    private fun attachmentBlock(type: SlideAttachmentType, axis: Direction.Axis): BlockState =
        type.block
            .get()
            .defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, axis)

    private fun shaftBlock(): BlockState =
        AllBlocks.SHAFT.get()
            .defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z)

    // the door hub is bound to the same wall point the placement story uses
    private fun bindDoor(
        scene: CreateSceneBuilder,
        util: SceneBuildingUtil,
        curveA: BlockPos,
        curveB: BlockPos
    ) {
        bindAttachment(scene, util, DOOR_POS, ModSlideAttachments.MECHANICAL_DOOR, curveA, curveB)
        setDoor(scene, util, 0f, 0)
    }

    // without this entry a binding block has no geometry: keys must match SlideAttachmentEntry.write
    private fun bindAttachment(
        scene: CreateSceneBuilder,
        util: SceneBuildingUtil,
        block: BlockPos,
        type: SlideAttachmentType,
        curveA: BlockPos,
        curveB: BlockPos
    ) {
        scene.world().modifyBlockEntityNBT(
            util.select().position(block),
            SlideAttachmentBlockEntity::class.java
        ) { tag ->
            tag.putString("Type", type.id.toString())
            tag.putLong("CurveA", curveA.asLong())
            tag.putLong("CurveB", curveB.asLong())
            tag.putString("Site", SlideAttachmentSite.INTERIOR.name)
            tag.putFloat("CurveT", ATTACH_T)
            tag.putFloat("WallAngle", 0f)
            tag.put("Data", CompoundTag())
        }
    }

    // no server tick runs in a ponder scene: write the mode slot and the entry data together
    private fun setDoor(scene: CreateSceneBuilder, util: SceneBuildingUtil, open: Float, mode: Int) {
        scene.world().modifyBlockEntityNBT(
            util.select().position(DOOR_POS),
            SlideAttachmentBlockEntity::class.java
        ) { tag ->
            tag.putInt("ScrollValue", mode)
            tag.put("Data", doorData(open, mode))
        }
    }

    private fun doorData(open: Float, mode: Int): CompoundTag {
        val data = CompoundTag()
        data.putFloat(MechanicalDoorAttachment.TAG_OPEN, open)
        data.putInt(MechanicalDoorAttachment.TAG_MODE, mode)
        return data
    }

    // ring angle 0 at the curve side, 90 at the top; the first FIXED sector takes 180 degrees
    private fun setRingGlass(scene: CreateSceneBuilder, anchor: BlockPos, peer: BlockPos) {
        scene.world().modifyBlockEntity(anchor, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            val cfg = be.sectorConfigFor(peer).copyOf()
            cfg.startAngle = 0f
            cfg.sectors.clear()
            cfg.sectors.add(
                WaterslideSector(
                    cfg.newId(),
                    SectorMaterial.BLOCK,
                    ResourceLocation.parse("minecraft:glass"),
                    SectorType.FIXED,
                    180f
                )
            )
            cfg.sectors.add(
                WaterslideSector(
                    cfg.newId(),
                    SectorMaterial.BLOCK,
                    ResourceLocation.parse("minecraft:gray_concrete"),
                    SectorType.FIXED,
                    180f
                )
            )
            be.setSectorConfig(peer, cfg)
        }
    }

    // detector scene: half the ring is open, so the band shows where it stops
    private fun setRingHalfOpen(scene: CreateSceneBuilder, anchor: BlockPos, peer: BlockPos) {
        scene.world().modifyBlockEntity(anchor, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            val cfg = be.sectorConfigFor(peer).copyOf()
            cfg.startAngle = 270f
            cfg.sectors.clear()
            cfg.sectors.add(
                WaterslideSector(
                    cfg.newId(),
                    SectorMaterial.BLOCK,
                    ResourceLocation.parse("minecraft:glass"),
                    SectorType.FIXED,
                    180f
                )
            )
            cfg.sectors.add(
                WaterslideSector(cfg.newId(), SectorMaterial.OPEN, null, SectorType.FIXED, 180f)
            )
            be.setSectorConfig(peer, cfg)
        }
    }

    private fun addGhost(
        scene: CreateSceneBuilder,
        left: BlockPos,
        right: BlockPos,
        stack: ItemStack,
        t: Float,
        angle: Float
    ) {
        scene.world().modifyBlockEntity(left, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            PonderSlideHelper.createGhost(be, right, stack, t, angle)
        }
        scene.world().modifyBlockEntity(right, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            PonderSlideHelper.createGhost(be, left, stack, t, angle)
        }
    }

    private fun clearGhosts(scene: CreateSceneBuilder, left: BlockPos, right: BlockPos) {
        scene.world().modifyBlockEntity(left, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            PonderSlideHelper.clearGhosts(be, right)
        }
        scene.world().modifyBlockEntity(right, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            PonderSlideHelper.clearGhosts(be, left)
        }
    }

    // both anchors need the same sector: each BE stores the config for its peer curve
    private fun addSector(
        scene: CreateSceneBuilder,
        left: BlockPos,
        right: BlockPos,
        material: SectorMaterial,
        blockId: String?
    ) {
        scene.world().modifyBlockEntity(left, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            PonderSlideHelper.createSector(be, right, 0f, material, blockId, false)
        }
        scene.world().modifyBlockEntity(right, WaterslideAnchorBlockEntity::class.java) { be ->
            if (be == null) return@modifyBlockEntity
            PonderSlideHelper.createSector(be, left, 0f, material, blockId, false)
        }
    }

    private fun swapAnchorLayer(
        scene: CreateSceneBuilder,
        util: SceneBuildingUtil,
        previous: ElementLink<WorldSectionElement>,
        sourceY: Int
    ): ElementLink<WorldSectionElement> {
        val layer: Selection = util.select().fromTo(
            ANCHOR_LEFT.x, sourceY, ANCHOR_LEFT.z,
            ANCHOR_RIGHT.x, sourceY, ANCHOR_RIGHT.z
        )
        val offset = util.vector().of(0.0, (DISPLAY_Y - sourceY).toDouble(), 0.0)
        scene.world().moveSection(previous, OFFSCREEN, 0)
        val section = scene.world().showIndependentSectionImmediately(layer)
        scene.world().hideIndependentSection(previous, Direction.DOWN)
        scene.world().moveSection(section, offset, 0)
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, sourceY, DISPLAY_Y, ANCHOR_LEFT, ANCHOR_RIGHT)
        return section
    }
}
