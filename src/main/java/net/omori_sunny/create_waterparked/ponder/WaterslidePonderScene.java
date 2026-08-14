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
import net.minecraft.world.phys.Vec3;
import net.omori_sunny.create_waterparked.content.registry.ModItems;

public final class WaterslidePonderScene {

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

        Selection twoAnchors = util.select().fromTo(3, DISPLAY_Y, 7, 11, DISPLAY_Y, 7);
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

    private static ElementLink<WorldSectionElement> swapAnchorLayer(
        CreateSceneBuilder scene,
        SceneBuildingUtil util,
        ElementLink<WorldSectionElement> previous,
        int sourceY
    ) {
        Selection layer = util.select().fromTo(3, sourceY, 7, 11, sourceY, 7);
        Vec3 offset = util.vector().of(0.0, DISPLAY_Y - sourceY, 0.0);
        scene.world().moveSection(previous, OFFSCREEN, 0);
        ElementLink<WorldSectionElement> section = scene.world().showIndependentSectionImmediately(layer);
        scene.world().hideIndependentSection(previous, Direction.DOWN);
        scene.world().moveSection(section, offset, 0);
        WaterslidePonderRestore.applyDisplayedAnchorLayer(scene, sourceY, DISPLAY_Y, ANCHOR_LEFT, ANCHOR_RIGHT);
        return section;
    }
}
