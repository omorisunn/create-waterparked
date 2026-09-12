package net.omori_sunny.create_waterparked.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.content.attachment.ModSlideAttachments;
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentBlockEntity;
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentSite;
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentType;
import net.omori_sunny.create_waterparked.content.attachment.SlideAttachmentTypes;
import net.omori_sunny.create_waterparked.content.attachment.door.MechanicalDoorAttachment;
import net.omori_sunny.create_waterparked.content.registry.ModItems;
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;

import java.util.List;

public class WaterslidePonderScene {

    private static final int DISPLAY_Y = 1;
    private static final BlockPos ANCHOR_LEFT = new BlockPos(3, DISPLAY_Y, 7);
    private static final BlockPos ANCHOR_RIGHT = new BlockPos(11, DISPLAY_Y, 7);
    private static final Vec3 OFFSCREEN = new Vec3(0.0, -100.0, 0.0);

    private static final BlockPos DOOR_POS = new BlockPos(6, 4, 6);
    private static final BlockPos DOOR_SHAFT_A = new BlockPos(6, 4, 5);
    private static final BlockPos DOOR_SHAFT_B = new BlockPos(6, 4, 4);
    private static final BlockPos ATTACH_HOST_POS = new BlockPos(7, DISPLAY_Y, 7);
    private static final String SITE_OUTLINE = "create_waterparked:attachment_site";
    private static final float ATTACH_T = 0.5f;

    private WaterslidePonderScene() {
    }

    public static void connect(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("ponder_connect", "Connecting Waterslide Anchors");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.7f);
        scene.setSceneOffsetY(-1.0f);
        scene.showBasePlate();
        scene.idle(10);

        Selection twoAnchors = util.select().fromTo(
            ANCHOR_LEFT.getX(), DISPLAY_Y, ANCHOR_LEFT.getZ(),
            ANCHOR_RIGHT.getX(), DISPLAY_Y, ANCHOR_RIGHT.getZ()
        );
        ElementLink<WorldSectionElement> anchorLayer =
            scene.world().showIndependentSection(twoAnchors, Direction.DOWN);
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, DISPLAY_Y, DISPLAY_Y, ANCHOR_LEFT, ANCHOR_RIGHT);

        Vec3 leftTop = util.vector().topOf(ANCHOR_LEFT);
        Vec3 rightTop = util.vector().topOf(ANCHOR_RIGHT);
        Vec3 midTop = util.vector().topOf(7, DISPLAY_Y, 7);
        ItemStack trackStack = new ItemStack(ModItems.INSTANCE.getWATERSLIDE_TRACK());

        scene.idle(20);
        scene.overlay()
            .showText(90)
            .attachKeyFrame()
            .independent(20)
            .text("Right-click two waterslide anchors with a waterslide track to connect them");
        scene.idle(30);
        scene.overlay().showControls(leftTop, Pointing.DOWN, 60).withItem(trackStack).rightClick();
        scene.idle(40);
        scene.overlay().showControls(rightTop, Pointing.DOWN, 40).withItem(trackStack).rightClick();
        scene.idle(50);

        anchorLayer = swapAnchorLayer(scene, util, anchorLayer, 2);
        scene.idle(20);
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A waterslide spline is created between the two anchors")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(70);

        anchorLayer = swapAnchorLayer(scene, util, anchorLayer, 3);
        scene.idle(20);
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A wrench can be used to adjust the path of the spline")
            .placeNearTarget()
            .pointAt(midTop);
        scene.overlay().showControls(midTop, Pointing.DOWN, 60).withItem(AllItems.WRENCH.asStack());
        scene.idle(80);
    }

    public static void useSlideTrack(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("slide_ponder_0", "Using the Waterslide Track");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.7f);
        scene.setSceneOffsetY(-1.0f);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos[] anchors = WaterslidePonderRestore.schemaAnchors(scene.getScene().getWorld());
        BlockPos anchorLeft = anchors.length >= 1 ? anchors[0] : ANCHOR_LEFT;
        BlockPos anchorRight = anchors.length >= 2 ? anchors[1] : ANCHOR_RIGHT;
        int anchorY = Math.min(anchorLeft.getY(), anchorRight.getY());

        ElementLink<WorldSectionElement> anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.getX(), anchorY, anchorLeft.getZ(),
                    anchorRight.getX(), anchorY, anchorRight.getZ()
                ),
                Direction.DOWN
            );
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight);
        WaterslidePonderRestore.clearDisplayedCurves(scene, anchorY, anchorLeft, anchorRight);
        scene.idle(30);

        Vec3 leftTop = util.vector().topOf(anchorLeft);
        Vec3 rightTop = util.vector().topOf(anchorRight);
        ItemStack trackStack = new ItemStack(ModItems.INSTANCE.getWATERSLIDE_TRACK());
        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-click two waterslide anchors with a waterslide track......")
            .placeNearTarget()
            .pointAt(leftTop);
        scene.idle(30);
        scene.overlay().showControls(leftTop, Pointing.DOWN, 70).withItem(trackStack);
        scene.idle(20);
        scene.overlay().showControls(rightTop, Pointing.DOWN, 50).withItem(trackStack);

        scene.idle(10);
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight);
        WaterslideAnchorBlockEntity leftBe4 =
            scene.getScene().getWorld().getBlockEntity(anchorLeft) instanceof WaterslideAnchorBlockEntity be4 ? be4 : null;
        if (leftBe4 != null) {
            scene.addInstruction(sc -> {
                if (sc.getWorld().getBlockEntity(anchorLeft) instanceof WaterslideAnchorBlockEntity live) {
                    net.omori_sunny.create_waterparked.ponder.PonderSlideHelper.waterFlowShow(live, 0.5f);
                }
            });
        }
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A new waterslide is created.")
            .placeNearTarget()
            .pointAt(util.vector().topOf(
                (anchorLeft.getX() + anchorRight.getX()) / 2, anchorY,
                (anchorLeft.getZ() + anchorRight.getZ()) / 2
            ));
        scene.idle(80);

        scene.overlay()
            .showText(90)
            .text("Some controls of the waterslide work exactly like coaster track.")
            .placeNearTarget()
            .pointAt(util.vector().topOf(
                (anchorLeft.getX() + anchorRight.getX()) / 2, anchorY,
                (anchorLeft.getZ() + anchorRight.getZ()) / 2
            ));
        if (leftBe4 != null) {
            WaterslideAnchorBlockEntity rightBe5 =
                scene.getScene().getWorld().getBlockEntity(anchorRight) instanceof WaterslideAnchorBlockEntity be5 ? be5 : null;
            scene.world().modifyBlockEntity(anchorLeft, WaterslideAnchorBlockEntity.class, be -> {
                if (be == null) return;
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig cfg =
                    be.sectorConfigFor(anchorRight).copyOf();
                cfg.getSectors().clear();
                cfg.getSectors().add(new net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector(
                    cfg.newId(),
                    net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial.BLOCK,
                    net.minecraft.resources.ResourceLocation.parse("minecraft:gray_concrete"),
                    net.omori_sunny.create_waterparked.content.waterslide.SectorType.AUTO,
                    0f
                ));
                be.setSectorConfig(anchorRight, cfg);
            });
            if (rightBe5 != null) {
                scene.world().modifyBlockEntity(anchorRight, WaterslideAnchorBlockEntity.class, be -> {
                    if (be == null) return;
                    net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig cfg =
                        be.sectorConfigFor(anchorLeft).copyOf();
                    cfg.getSectors().clear();
                    cfg.getSectors().add(new net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector(
                        cfg.newId(),
                        net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial.BLOCK,
                        net.minecraft.resources.ResourceLocation.parse("minecraft:gray_concrete"),
                        net.omori_sunny.create_waterparked.content.waterslide.SectorType.AUTO,
                        0f
                    ));
                    be.setSectorConfig(anchorLeft, cfg);
                });
                scene.addInstruction(new PonderSlideRadiusInstruction(
                    List.of(leftBe4, rightBe5),
                    1.0f,
                    40,
                    0
                ));
            }
            WaterslideAnchorBlockEntity beForHide = leftBe4;
            scene.addInstruction(sc ->
                net.omori_sunny.create_waterparked.ponder.PonderSlideHelper.waterFlowHide(beForHide));
            PonderSlideEditUiElement.show(scene, leftBe4);
        }
        scene.idle(80);

        scene.world().modifyBlockEntity(anchorLeft, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            be.setWaterActive(false);
            for (BlockPos peer : be.getAnchorPeerCurvesView().keySet()) {
                be.setCurveWatered(peer, false);
            }
            be.setSupportMaterial(
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM,
                com.simibubi.create.AllBlocks.COPYCAT_BASE.get().defaultBlockState(),
                ItemStack.EMPTY
            );
            be.setSupportMaterial(
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET,
                com.simibubi.create.AllBlocks.COPYCAT_BASE.get().defaultBlockState(),
                ItemStack.EMPTY
            );
        });
        scene.world().modifyBlockEntity(anchorRight, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            be.setWaterActive(false);
            for (BlockPos peer : be.getAnchorPeerCurvesView().keySet()) {
                be.setCurveWatered(peer, false);
            }
            be.setSupportMaterial(
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BEAM,
                com.simibubi.create.AllBlocks.COPYCAT_BASE.get().defaultBlockState(),
                ItemStack.EMPTY
            );
            be.setSupportMaterial(
                net.omori_sunny.create_waterparked.content.waterslide.WaterslideSupportPart.BRACKET,
                com.simibubi.create.AllBlocks.COPYCAT_BASE.get().defaultBlockState(),
                ItemStack.EMPTY
            );
        });
        WaterslideAnchorBlockEntity leftBe1 =
            scene.getScene().getWorld().getBlockEntity(anchorLeft) instanceof WaterslideAnchorBlockEntity be1 ? be1 : null;
        WaterslideAnchorBlockEntity rightBe1 =
            scene.getScene().getWorld().getBlockEntity(anchorRight) instanceof WaterslideAnchorBlockEntity be1b ? be1b : null;
        if (leftBe1 != null && rightBe1 != null) {
            scene.addInstruction(new PonderSlideRadiusInstruction(
                List.of(leftBe1, rightBe1),
                1.0f,
                40,
                0
            ));
        }
        if (leftBe1 != null) {
            scene.addInstruction(sc -> {
                if (sc.getWorld().getBlockEntity(anchorLeft) instanceof WaterslideAnchorBlockEntity live) {
                    net.omori_sunny.create_waterparked.ponder.PonderSlideHelper.waterFlowHide(live);
                }
            });
        }
        scene.idle(40);

        scene.overlay()
            .showText(80)
            .text("A wrench can be used to adjust the path of the slide")
            .placeNearTarget()
            .pointAt(util.vector().topOf(
                (anchorLeft.getX() + anchorRight.getX()) / 2, anchorY,
                (anchorLeft.getZ() + anchorRight.getZ()) / 2
            ));
        scene.idle(20);
        if (leftBe1 != null) {
            PonderSlideHelper.easeMove(scene.getScene(), leftBe1, 0.0, 1.25, 0.0, 50, 5);
        }
        scene.idle(65);

        Vec3 handleTip = util.vector().topOf(anchorLeft);
        scene.overlay()
            .showText(70)
            .text("Drag left and right to adjust the tube opening size")
            .placeNearTarget()
            .pointAt(handleTip);
        scene.idle(30);
        WaterslideAnchorBlockEntity leftBe2 = scene.getScene().getWorld().getBlockEntity(anchorLeft) instanceof WaterslideAnchorBlockEntity be2a ? be2a : null;
        WaterslideAnchorBlockEntity rightBe2 = scene.getScene().getWorld().getBlockEntity(anchorRight) instanceof WaterslideAnchorBlockEntity be2b ? be2b : null;
        if (leftBe2 != null && rightBe2 != null) {
            scene.addInstruction(new PonderSlideRadiusInstruction(
                List.of(leftBe2, rightBe2),
                1.5f,
                60,
                0
            ));
        }
        scene.idle(90);

        scene.world().hideIndependentSection(anchorLayer, Direction.UP);
        scene.idle(60);
    }

    public static void sectorSystem(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(WaterslidePonderScenes.SECTOR_SCENE_ID, "Sector System");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.7f);
        scene.setSceneOffsetY(-1.0f);
        scene.rotateCameraY(90f);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos[] anchors = WaterslidePonderRestore.schemaAnchors(scene.getScene().getWorld());
        BlockPos anchorLeft = anchors.length >= 1 ? anchors[0] : ANCHOR_LEFT;
        BlockPos anchorRight = anchors.length >= 2 ? anchors[1] : ANCHOR_RIGHT;
        int anchorY = Math.min(anchorLeft.getY(), anchorRight.getY());

        ElementLink<WorldSectionElement> anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.getX(), anchorY, anchorLeft.getZ(),
                    anchorRight.getX(), anchorY, anchorRight.getZ()
                ),
                Direction.DOWN
            );
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight);
        scene.idle(30);

        Vec3 leftTop = util.vector().topOf(anchorLeft);
        Vec3 rightTop = util.vector().topOf(anchorRight);
        Vec3 midTop = util.vector().topOf(
            (anchorLeft.getX() + anchorRight.getX()) / 2, anchorY,
            (anchorLeft.getZ() + anchorRight.getZ()) / 2
        );
        ItemStack planks = new ItemStack(Items.OAK_PLANKS);
        ItemStack axe = new ItemStack(Items.IRON_AXE);

        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-click two waterslide anchors with a block......")
            .placeNearTarget()
            .pointAt(leftTop);
        scene.idle(30);
        scene.overlay().showControls(leftTop, Pointing.DOWN, 70).withItem(planks);
        scene.idle(20);
        scene.overlay().showControls(rightTop, Pointing.DOWN, 50).withItem(planks);

        addSector(scene, anchorLeft, anchorRight, SectorMaterial.BLOCK, "minecraft:oak_planks");
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A sector with the corresponding block texture can be added.")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(80);

        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-click two waterslide anchors with an axe......")
            .placeNearTarget()
            .pointAt(leftTop);
        scene.idle(30);
        scene.overlay().showControls(leftTop, Pointing.DOWN, 70).withItem(axe);
        scene.idle(20);
        scene.overlay().showControls(rightTop, Pointing.DOWN, 50).withItem(axe);

        addSector(scene, anchorLeft, anchorRight, SectorMaterial.OPEN, null);
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("An empty sector can be added.")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(80);

        scene.overlay()
            .showText(90)
            .text("You can use a wrench to adjust the position and size of sectors")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(80);

        scene.world().hideIndependentSection(anchorLayer, Direction.UP);
        scene.idle(60);
    }

    public static void ghostBlocks(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(WaterslidePonderScenes.GHOST_SCENE_ID, "Ghost Blocks");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.7f);
        scene.setSceneOffsetY(-1.0f);
        scene.rotateCameraY(90f);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos[] anchors = WaterslidePonderRestore.schemaAnchors(scene.getScene().getWorld());
        BlockPos anchorLeft = anchors.length >= 1 ? anchors[0] : ANCHOR_LEFT;
        BlockPos anchorRight = anchors.length >= 2 ? anchors[1] : ANCHOR_RIGHT;
        int anchorY = Math.min(anchorLeft.getY(), anchorRight.getY());

        ElementLink<WorldSectionElement> anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.getX(), anchorY, anchorLeft.getZ(),
                    anchorRight.getX(), anchorY, anchorRight.getZ()
                ),
                Direction.DOWN
            );
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight);
        scene.idle(30);

        Vec3 midTop = util.vector().topOf(
            (anchorLeft.getX() + anchorRight.getX()) / 2, anchorY,
            (anchorLeft.getZ() + anchorRight.getZ()) / 2
        );
        Vec3 leftTop = util.vector().topOf(anchorLeft);
        ItemStack wrench = AllItems.WRENCH.asStack();
        ItemStack planks = new ItemStack(Items.OAK_PLANKS);
        ItemStack axe = new ItemStack(Items.IRON_AXE);

        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Hold a wrench in your main hand and a block in your offhand......")
            .placeNearTarget()
            .pointAt(leftTop);
        scene.idle(30);
        scene.overlay().showControls(midTop, Pointing.DOWN, 70).withItem(wrench);
        scene.idle(30);
        addGhost(scene, anchorLeft, anchorRight, planks, 0.35f, 45f);
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A ghost block is placed, hugging the outside of the tube wall.")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(80);

        addGhost(scene, anchorLeft, anchorRight, planks, 0.65f, 45f);
        scene.overlay()
            .showText(80)
            .text("Ghost blocks follow the curve of the slide.")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(80);

        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-clicking with an axe removes ghost blocks......")
            .placeNearTarget()
            .pointAt(leftTop);
        scene.idle(30);
        scene.overlay().showControls(midTop, Pointing.DOWN, 60).withItem(axe);
        scene.idle(30);
        clearGhosts(scene, anchorLeft, anchorRight);
        scene.idle(40);

        scene.world().hideIndependentSection(anchorLayer, Direction.UP);
        scene.idle(60);
    }

    public static void placeAttachment(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(WaterslidePonderScenes.ATTACHMENT_SCENE_ID, "Mounting a Slide Attachment");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.7f);
        scene.setSceneOffsetY(-1.0f);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos[] anchors = WaterslidePonderRestore.schemaAnchors(scene.getScene().getWorld());
        BlockPos anchorLeft = anchors.length >= 1 ? anchors[0] : ANCHOR_LEFT;
        BlockPos anchorRight = anchors.length >= 2 ? anchors[1] : ANCHOR_RIGHT;
        int anchorY = Math.min(anchorLeft.getY(), anchorRight.getY());

        ElementLink<WorldSectionElement> anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.getX(), anchorY, anchorLeft.getZ(),
                    anchorRight.getX(), anchorY, anchorRight.getZ()
                ).substract(util.select().position(ATTACH_HOST_POS)),
                Direction.DOWN
            );
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight);

        SlideAttachmentType type = attachmentExample();
        ItemStack bindingStack = new ItemStack(type.getItem()
            .get());
        Vec3 siteTop = util.vector().topOf(DOOR_POS);
        Vec3 hostTop = util.vector().topOf(ATTACH_HOST_POS);
        Vec3 midTop = util.vector().topOf(
            (anchorLeft.getX() + anchorRight.getX()) / 2, anchorY,
            (anchorLeft.getZ() + anchorRight.getZ()) / 2
        );
        scene.idle(30);
        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Right-click the slide with a slide attachment to pick a spot")
            .placeNearTarget()
            .pointAt(siteTop);
        scene.idle(30);
        scene.overlay().showControls(siteTop, Pointing.DOWN, 70).withItem(bindingStack).rightClick();
        scene.overlay().showOutline(PonderPalette.GREEN, SITE_OUTLINE, util.select().position(DOOR_POS), 70);
        scene.overlay().showLine(PonderPalette.GREEN, hostTop, siteTop, 70);
        scene.idle(70);
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("Then place the block on the ground")
            .placeNearTarget()
            .pointAt(siteTop);
        scene.idle(30);
        scene.world().setBlock(ATTACH_HOST_POS, bindingBlock(type), true);
        scene.world().showIndependentSection(util.select().position(ATTACH_HOST_POS), Direction.DOWN);
        bindAttachment(scene, util, ATTACH_HOST_POS, type, anchorLeft, anchorRight);
        scene.idle(60);
        scene.overlay()
            .showText(80)
            .text("The attachment appears on the slide at the bound spot")
            .placeNearTarget()
            .pointAt(midTop);
    }

    public static void mechanicalDoor(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(WaterslidePonderScenes.DOOR_SCENE_ID, "Using the Mechanical Door");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.7f);
        scene.setSceneOffsetY(-1.0f);
        scene.rotateCameraY(90f);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos[] anchors = WaterslidePonderRestore.schemaAnchors(scene.getScene().getWorld());
        BlockPos anchorLeft = anchors.length >= 1 ? anchors[0] : ANCHOR_LEFT;
        BlockPos anchorRight = anchors.length >= 2 ? anchors[1] : ANCHOR_RIGHT;
        int anchorY = Math.min(anchorLeft.getY(), anchorRight.getY());

        ElementLink<WorldSectionElement> anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.getX(), anchorY, anchorLeft.getZ(),
                    anchorRight.getX(), anchorY, anchorRight.getZ()
                ),
                Direction.DOWN
            );
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight);

        setRingGlass(scene, anchorLeft, anchorRight);
        setRingGlass(scene, anchorRight, anchorLeft);

        Vec3 hubTop = util.vector().topOf(DOOR_POS);
        ItemStack shaftStack = new ItemStack(AllBlocks.SHAFT.get());
        scene.world().setBlock(DOOR_POS, doorBlock(), true);
        ElementLink<WorldSectionElement> doorLayer = scene.world()
            .showIndependentSection(util.select().position(DOOR_POS), Direction.DOWN);
        bindDoor(scene, util, anchorLeft, anchorRight);
        scene.idle(30);
        scene.overlay()
            .showText(90)
            .independent(20)
            .text("Put a shaft into the door hub and it will drive the door")
            .placeNearTarget()
            .pointAt(hubTop);
        scene.idle(20);
        scene.overlay().showControls(hubTop, Pointing.DOWN, 60).withItem(shaftStack);
        scene.world().setBlock(DOOR_SHAFT_A, shaftBlock(), true);
        ElementLink<WorldSectionElement> shaftALayer = scene.world()
            .showIndependentSection(util.select().position(DOOR_SHAFT_A), Direction.DOWN);
        scene.world().setBlock(DOOR_SHAFT_B, shaftBlock(), true);
        ElementLink<WorldSectionElement> shaftBLayer = scene.world()
            .showIndependentSection(util.select().position(DOOR_SHAFT_B), Direction.DOWN);
        scene.idle(80);
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("How far the shaft turns decides how far the door opens")
            .placeNearTarget()
            .pointAt(hubTop);
        scene.idle(30);
        scene.addInstruction(new PonderDoorSweepInstruction(DOOR_POS, 0f, 1f, 0, 24));
        scene.idle(70);
        scene.addInstruction(new PonderDoorSweepInstruction(DOOR_POS, 1f, 0f, 0, 24));
        scene.idle(70);
        scene.world().hideIndependentSection(doorLayer, Direction.UP);
        scene.world().hideIndependentSection(shaftALayer, Direction.UP);
        scene.world().hideIndependentSection(shaftBLayer, Direction.UP);
        scene.idle(15);
        scene.world().setBlock(DOOR_POS, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(DOOR_SHAFT_A, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(DOOR_SHAFT_B, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
        scene.world().hideIndependentSection(anchorLayer, Direction.UP);
    }

    private static SlideAttachmentType attachmentExample() {
        return SlideAttachmentTypes.INSTANCE.all()
            .iterator()
            .next();
    }

    private static BlockState doorBlock() {
        return ModSlideAttachments.INSTANCE.getMECHANICAL_DOOR()
            .getBlock()
            .get()
            .defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
    }

    private static BlockState bindingBlock(SlideAttachmentType type) {
        return type.getBlock()
            .get()
            .defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
    }

    private static BlockState shaftBlock() {
        return AllBlocks.SHAFT.get()
            .defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
    }

    // the door hub is bound to the same wall point the placement story uses
    private static void bindDoor(
        CreateSceneBuilder scene,
        SceneBuildingUtil util,
        BlockPos curveA,
        BlockPos curveB
    ) {
        bindAttachment(scene, util, DOOR_POS, ModSlideAttachments.INSTANCE.getMECHANICAL_DOOR(), curveA, curveB);
        setDoor(scene, util, 0f, 0);
    }

    // without this entry a binding block has no geometry: keys must match SlideAttachmentEntry.write
    private static void bindAttachment(
        CreateSceneBuilder scene,
        SceneBuildingUtil util,
        BlockPos block,
        SlideAttachmentType type,
        BlockPos curveA,
        BlockPos curveB
    ) {
        scene.world().modifyBlockEntityNBT(
            util.select().position(block),
            SlideAttachmentBlockEntity.class,
            tag -> {
                tag.putString("Type", type.getId()
                    .toString());
                tag.putLong("CurveA", curveA.asLong());
                tag.putLong("CurveB", curveB.asLong());
                tag.putString("Site", SlideAttachmentSite.INTERIOR.name());
                tag.putFloat("CurveT", ATTACH_T);
                tag.putFloat("WallAngle", 0f);
                tag.put("Data", new CompoundTag());
            }
        );
    }

    // no server tick runs in a ponder scene: write the mode slot and the entry data together
    private static void setDoor(CreateSceneBuilder scene, SceneBuildingUtil util, float open, int mode) {
        scene.world().modifyBlockEntityNBT(
            util.select().position(DOOR_POS),
            SlideAttachmentBlockEntity.class,
            tag -> {
                tag.putInt("ScrollValue", mode);
                tag.put("Data", doorData(open, mode));
            }
        );
    }

    private static CompoundTag doorData(float open, int mode) {
        CompoundTag data = new CompoundTag();
        data.putFloat(MechanicalDoorAttachment.TAG_OPEN, open);
        data.putInt(MechanicalDoorAttachment.TAG_MODE, mode);
        return data;
    }

    // ring angle 0 at the curve side, 90 at the top; the first FIXED sector takes 180 degrees
    private static void setRingGlass(CreateSceneBuilder scene, BlockPos anchor, BlockPos peer) {
        scene.world().modifyBlockEntity(anchor, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            net.omori_sunny.create_waterparked.content.waterslide.WaterslideSectorConfig cfg =
                be.sectorConfigFor(peer).copyOf();
            cfg.setStartAngle(0f);
            cfg.getSectors().clear();
            cfg.getSectors().add(new net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector(
                cfg.newId(),
                net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial.BLOCK,
                net.minecraft.resources.ResourceLocation.parse("minecraft:glass"),
                net.omori_sunny.create_waterparked.content.waterslide.SectorType.FIXED,
                180f
            ));
            cfg.getSectors().add(new net.omori_sunny.create_waterparked.content.waterslide.WaterslideSector(
                cfg.newId(),
                net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial.BLOCK,
                net.minecraft.resources.ResourceLocation.parse("minecraft:gray_concrete"),
                net.omori_sunny.create_waterparked.content.waterslide.SectorType.FIXED,
                180f
            ));
            be.setSectorConfig(peer, cfg);
        });
    }

    private static void addGhost(
        CreateSceneBuilder scene,
        BlockPos left,
        BlockPos right,
        ItemStack stack,
        float t,
        float angle
    ) {
        scene.world().modifyBlockEntity(left, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            PonderSlideHelper.createGhost(be, right, stack, t, angle);
        });
        scene.world().modifyBlockEntity(right, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            PonderSlideHelper.createGhost(be, left, stack, t, angle);
        });
    }

    private static void clearGhosts(CreateSceneBuilder scene, BlockPos left, BlockPos right) {
        scene.world().modifyBlockEntity(left, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            PonderSlideHelper.clearGhosts(be, right);
        });
        scene.world().modifyBlockEntity(right, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            PonderSlideHelper.clearGhosts(be, left);
        });
    }

    // both anchors need the same sector: each BE stores the config for its peer curve
    private static void addSector(
        CreateSceneBuilder scene,
        BlockPos left,
        BlockPos right,
        SectorMaterial material,
        String blockId
    ) {
        scene.world().modifyBlockEntity(left, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            PonderSlideHelper.createSector(be, right, 0f, material, blockId, false);
        });
        scene.world().modifyBlockEntity(right, WaterslideAnchorBlockEntity.class, be -> {
            if (be == null) return;
            PonderSlideHelper.createSector(be, left, 0f, material, blockId, false);
        });
    }

    private static ElementLink<WorldSectionElement> swapAnchorLayer(
        CreateSceneBuilder scene,
        SceneBuildingUtil util,
        ElementLink<WorldSectionElement> previous,
        int sourceY
    ) {
        Selection layer = util.select().fromTo(
            ANCHOR_LEFT.getX(), sourceY, ANCHOR_LEFT.getZ(),
            ANCHOR_RIGHT.getX(), sourceY, ANCHOR_RIGHT.getZ()
        );
        Vec3 offset = util.vector().of(0.0, DISPLAY_Y - sourceY, 0.0);
        scene.world().moveSection(previous, OFFSCREEN, 0);
        ElementLink<WorldSectionElement> section = scene.world().showIndependentSectionImmediately(layer);
        scene.world().hideIndependentSection(previous, Direction.DOWN);
        scene.world().moveSection(section, offset, 0);
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, sourceY, DISPLAY_Y, ANCHOR_LEFT, ANCHOR_RIGHT);
        return section;
    }
}
