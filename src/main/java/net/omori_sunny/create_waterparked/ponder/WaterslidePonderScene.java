package net.omori_sunny.create_waterparked.ponder;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.content.registry.ModItems;
import net.omori_sunny.create_waterparked.content.waterslide.SectorMaterial;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;

import java.util.List;

public class WaterslidePonderScene {

    private static final int DISPLAY_Y = 1;
    private static final BlockPos ANCHOR_LEFT = new BlockPos(3, DISPLAY_Y, 7);
    private static final BlockPos ANCHOR_RIGHT = new BlockPos(11, DISPLAY_Y, 7);
    private static final Vec3 OFFSCREEN = new Vec3(0.0, -100.0, 0.0);

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

    // ------------------------------------------------------------------
    // ponder.md scene: slide_ponder_0
    // ------------------------------------------------------------------

    public static void useSlideTrack(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("slide_ponder_0", "Using the Waterslide Track");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.7f);
        scene.setSceneOffsetY(-1.0f);
        scene.showBasePlate();
        scene.idle(10);

        // anchors come straight from the schematic so the layout always matches
        BlockPos[] anchors = WaterslidePonderRestore.schemaAnchors(scene.getScene().getWorld());
        BlockPos anchorLeft = anchors.length >= 1 ? anchors[0] : ANCHOR_LEFT;
        BlockPos anchorRight = anchors.length >= 2 ? anchors[1] : ANCHOR_RIGHT;
        int anchorY = Math.min(anchorLeft.getY(), anchorRight.getY());

        // [1+2] show the base plate, then fade the two anchors in from above
        ElementLink<WorldSectionElement> anchorLayer = scene.world()
            .showIndependentSection(
                util.select().fromTo(
                    anchorLeft.getX(), anchorY, anchorLeft.getZ(),
                    anchorRight.getX(), anchorY, anchorRight.getZ()
                ),
                Direction.DOWN
            );
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight);
        // keep the tube hidden for now: the anchors stay visible, but the curve
        // data is cleared so the BER draws nothing yet - the tube only appears
        // at the [4] "slide appears" beat when the curve data is restored
        WaterslidePonderRestore.clearDisplayedCurves(scene, anchorY, anchorLeft, anchorRight);
        scene.idle(30);

        // [3] item hint boxes + text (both track item boxes show at once, no
        //     right-click icon; they vanish when the slide appears)
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
        // [4] restore the curve data: the tube appears right where it belongs
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, anchorY, anchorY, anchorLeft, anchorRight);
        WaterslideAnchorBlockEntity leftBe4 =
            scene.getScene().getWorld().getBlockEntity(anchorLeft) instanceof WaterslideAnchorBlockEntity be4 ? be4 : null;
        if (leftBe4 != null) {
            // must run at the storyboard beat: a direct call here fires once at
            // build time, so scene replays would play without water
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
        // [5] ease the slide into its concrete look while the text shows: one grey
        // concrete AUTO sector, radius 1.0, drained, default supports
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

        // [6] switch the slide to a drained state: no water, no copied support
        //     material, radius eased down to 1.0
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

        // [7] wrench beat: smooth spline lift while the water is drained
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

        // [8] point at the tube opening handle
        Vec3 handleTip = util.vector().topOf(anchorLeft);
        scene.overlay()
            .showText(70)
            .text("Drag left and right to adjust the tube opening size")
            .placeNearTarget()
            .pointAt(handleTip);
        scene.idle(30);
        // [9] ease the tube opening to 1.5m
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

        // [10] fade the slide + anchors out
        scene.world().hideIndependentSection(anchorLayer, Direction.UP);
        scene.idle(60);
    }

    // ------------------------------------------------------------------
    // ponder.md scene: slide_ponder_1 (sector system)
    // ------------------------------------------------------------------

    public static void sectorSystem(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title(WaterslidePonderScenes.SECTOR_SCENE_ID, "Sector System");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(0.7f);
        scene.setSceneOffsetY(-1.0f);
        // manuscript: camera scale matches scene 1, view rotated by 90 degrees
        // (end-on view so the sector ring on the tube opening reads clearly)
        scene.rotateCameraY(90f);
        scene.showBasePlate();
        scene.idle(10);

        // anchors come straight from the schematic so the layout always matches
        BlockPos[] anchors = WaterslidePonderRestore.schemaAnchors(scene.getScene().getWorld());
        BlockPos anchorLeft = anchors.length >= 1 ? anchors[0] : ANCHOR_LEFT;
        BlockPos anchorRight = anchors.length >= 2 ? anchors[1] : ANCHOR_RIGHT;
        int anchorY = Math.min(anchorLeft.getY(), anchorRight.getY());

        // [1+2] show the base plate, then fade the two anchors in from above.
        //      Unlike scene 1 the tube stays visible from the start: this story
        //      adds sectors onto the already-existing slide
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

        // [3] block hint: same two-item-box mechanism and timing as scene 1
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

        // [4] right after the second item box shows: add the oak planks sector
        addSector(scene, anchorLeft, anchorRight, SectorMaterial.BLOCK, "minecraft:oak_planks");
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("A sector with the corresponding block texture can be added.")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(80);

        // [5] axe hint: same mechanism and timing as scene 1
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

        // [6] right after the second item box shows: add the empty (None) sector
        addSector(scene, anchorLeft, anchorRight, SectorMaterial.OPEN, null);
        scene.overlay()
            .showText(80)
            .attachKeyFrame()
            .text("An empty sector can be added.")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(80);

        // [7] wrench text: sectors can be moved and resized afterwards
        scene.overlay()
            .showText(90)
            .text("You can use a wrench to adjust the position and size of sectors")
            .placeNearTarget()
            .pointAt(midTop);
        scene.idle(80);

        // end: fade the slide and anchors out like scene 1
        scene.world().hideIndependentSection(anchorLayer, Direction.UP);
        scene.idle(60);
    }

    // both anchors must receive the same sector so the tube looks right on both
    // ends: each BE stores the config of the curve towards its peer
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
