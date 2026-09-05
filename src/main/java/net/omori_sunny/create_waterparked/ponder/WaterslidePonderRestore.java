package net.omori_sunny.create_waterparked.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import dev.silvergold.simulatedcoasters.track.anchor.AnchorJunctionVisualRefresh;
import dev.silvergold.simulatedcoasters.track.anchor.AnchorPeerCurveClientIndex;
import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual;
import net.omori_sunny.create_waterparked.content.registry.ModBlocks;
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity;
import net.omori_sunny.create_waterparked.mixin.ponder.WaterslidePonderLevelAccessor;
import net.omori_sunny.create_waterparked.mixin.ponder.WaterslidePonderLevelInvoker;
import net.omori_sunny.create_waterparked.mixin.ponder.WaterslideSchematicLevelAccessor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// reinject waterslide anchor peer curve data into the Ponder level
public class WaterslidePonderRestore {

    private static final Map<BlockPos, CompoundTag> TEMPLATE_ANCHOR_NBTS = new HashMap<>();

    private WaterslidePonderRestore() {
    }

    public static void seedFromStructureTemplate(PonderLevel level, StructureTemplate template) {
        if (level == null || template == null) return;
        TEMPLATE_ANCHOR_NBTS.clear();
        HolderLookup.Provider registries = level.registryAccess();
        StructurePlaceSettings settings = new StructurePlaceSettings();

        for (StructureTemplate.StructureBlockInfo info :
            template.filterBlocks(BlockPos.ZERO, settings, ModBlocks.INSTANCE.getWATERSLIDE_ANCHOR())) {
            CompoundTag nbt = info.nbt();
            if (nbt == null) continue;
            BlockPos pos = info.pos();
            TEMPLATE_ANCHOR_NBTS.put(pos.immutable(), nbt.copy());
            if (nbt.contains("AnchorPeerCurves")
                && BlockEntity.loadStatic(pos, info.state(), nbt, registries)
                    instanceof WaterslideAnchorBlockEntity anchor) {
                // storyboard water: every seeded curve shows a gentle flow, so
                // the translucent water band is visible in the Ponder scene even
                // though no server sync ever runs over there
                for (BlockPos peer : anchor.getAnchorPeerCurvesView().keySet()) {
                    anchor.setCurveWatered(peer, true);
                }
                installBlockEntity(level, pos, anchor);
            }
        }
    }

    public static void applyDisplayedAnchorLayer(
        CreateSceneBuilder scene, int sourceY, int displayY, BlockPos... sourceAnchors
    ) {
        int dy = displayY - sourceY;
        HolderLookup.Provider registries = scene.world().getHolderLookupProvider();

        for (BlockPos source : sourceAnchors) {
            BlockPos atSource = source.atY(sourceY);
            BlockPos target = source.atY(displayY);
            CompoundTag tag = TEMPLATE_ANCHOR_NBTS.get(atSource);
            CompoundTag applied = tag == null ? new CompoundTag() : translateAnchorTagForDisplay(tag.copy(), dy);
            scene.world().modifyBlockEntity(target, WaterslideAnchorBlockEntity.class, be -> {
                be.reloadCurveDataForPonder(applied, registries);
                be.repairAnchorPeerCurveKeys();
                for (BlockPos peer : be.getAnchorPeerCurvesView().keySet()) {
                    be.setCurveWatered(peer, true);
                }
                AnchorPeerCurveClientIndex.refreshMembership(be);
                VisualizationHelper.queueUpdate(be);
                WaterslideTubeVisual.refreshAll();
            });
        }
    }

    // hide the tube on the displayed anchors: the anchor blocks stay visible,
    // but the curve data is removed so the BER draws nothing yet - the tube
    // only reappears when applyDisplayedAnchorLayer is called again (the
    // "slide appears" beat of the storyboard)
    public static void clearDisplayedCurves(
        CreateSceneBuilder scene, int anchorY, BlockPos... sourceAnchors
    ) {
        for (BlockPos source : sourceAnchors) {
            scene.world().modifyBlockEntity(source.atY(anchorY), WaterslideAnchorBlockEntity.class, be -> {
                be.reloadCurveDataForPonder(new CompoundTag(), scene.world().getHolderLookupProvider());
                be.repairAnchorPeerCurveKeys();
                VisualizationHelper.queueUpdate(be);
                WaterslideTubeVisual.refreshAll();
            });
        }
    }

    @Nullable
    public static CompoundTag templateAnchorNbt(BlockPos schematicPos) {
        return TEMPLATE_ANCHOR_NBTS.get(schematicPos.immutable());
    }

    public static void onLevelRestore(PonderLevel level) {
        if (!level.isClientSide()) return;
        if (needsCurveSeed(level)) {
            seedFromSceneSchematic(level);
        }

        Map<BlockPos, CompoundTag> backup =
            ((WaterslidePonderLevelAccessor) level).create_waterparked$originalBlockEntities();
        HolderLookup.Provider registries = level.registryAccess();
        List<WaterslideAnchorBlockEntity> anchors = new ArrayList<>();

        for (BlockEntity be : level.getBlockEntities()) {
            if (be instanceof WaterslideAnchorBlockEntity anchor) {
                anchors.add(anchor);
                if (anchor.getAnchorPeerCurvesView().isEmpty()) {
                    CompoundTag tag = backup.get(anchor.getBlockPos());
                    if (tag != null && tag.contains("AnchorPeerCurves")) {
                        anchor.reloadCurveDataForPonder(tag, registries);
                    }
                } else {
                    anchor.repairAnchorPeerCurveKeys();
                }
                // storyboard water: all restored curves flow, the server never
                // syncs water data into the Ponder world
                for (BlockPos peer : anchor.getAnchorPeerCurvesView().keySet()) {
                    anchor.setCurveWatered(peer, true);
                }
            }
        }

        for (WaterslideAnchorBlockEntity anchor : anchors) {
            AnchorPeerCurveClientIndex.refreshMembership(anchor);
            VisualizationHelper.queueUpdate(anchor);
        }
        if (!anchors.isEmpty()) {
            AnchorJunctionVisualRefresh.refreshAround(
                level,
                anchors.stream().map(BlockEntity::getBlockPos).toArray(BlockPos[]::new)
            );
            WaterslideTubeVisual.refreshAll();
        }
    }

    private static boolean needsCurveSeed(PonderLevel level) {
        for (BlockEntity be : level.getBlockEntities()) {
            if (be instanceof WaterslideAnchorBlockEntity anchor
                && !anchor.getAnchorPeerCurvesView().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void seedFromSceneSchematic(PonderLevel level) {
        PonderScene scene = level.scene;
        if (scene == null) return;
        ResourceLocation schematicPath = WaterslidePonderScenes.schematicPathFor(scene.getId());
        if (schematicPath == null) return;
        StructureTemplate template = PonderSceneRegistry.loadSchematic(schematicPath);
        if (template != null && !template.getSize().equals(BlockPos.ZERO)) {
            seedFromStructureTemplate(level, template);
        }
    }

    /** anchor block positions inside the current scene's schematic (for storyboard layout) */
    public static BlockPos[] schemaAnchors(PonderLevel level) {
        PonderScene scene = level == null ? null : level.scene;
        if (scene == null) return new BlockPos[0];
        ResourceLocation schematicPath = WaterslidePonderScenes.schematicPathFor(scene.getId());
        if (schematicPath == null) return new BlockPos[0];
        StructureTemplate template = PonderSceneRegistry.loadSchematic(schematicPath);
        if (template == null) return new BlockPos[0];
        StructurePlaceSettings settings = new StructurePlaceSettings();
        List<StructureTemplate.StructureBlockInfo> infos = template.filterBlocks(
            BlockPos.ZERO, settings, ModBlocks.INSTANCE.getWATERSLIDE_ANCHOR());
        BlockPos[] out = new BlockPos[infos.size()];
        for (int i = 0; i < infos.size(); i++) {
            out[i] = infos.get(i).pos();
        }
        return out;
    }

    private static void installBlockEntity(PonderLevel level, BlockPos pos, BlockEntity blockEntity) {
        ((WaterslidePonderLevelInvoker) level).create_waterparked$onBEAdded(blockEntity, pos);
        WaterslideSchematicLevelAccessor access = (WaterslideSchematicLevelAccessor) level;
        access.create_waterparked$blockEntities().put(pos.immutable(), blockEntity);
        if (!access.create_waterparked$renderedBlockEntities().contains(blockEntity)) {
            access.create_waterparked$renderedBlockEntities().add(blockEntity);
        }
    }

    private static CompoundTag translateAnchorTagForDisplay(CompoundTag tag, int dy) {
        if (dy == 0) return tag;
        offsetPeerLongs(tag, "AnchorPeerCurves", dy);
        offsetPeerLongs(tag, "AnchorPeerCurveTints", dy);
        offsetPeerLongs(tag, "SectorConfigs", dy);
        offsetPeerLongs(tag, "WateredCurves", dy);
        offsetPeerLongs(tag, "GhostBlocks", dy);
        return tag;
    }

    private static void offsetPeerLongs(CompoundTag tag, String listKey, int dy) {
        if (!tag.contains(listKey)) return;
        ListTag list = tag.getList(listKey, 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.contains("Peer")) {
                BlockPos peer = BlockPos.of(entry.getLong("Peer")).offset(0, dy, 0);
                entry.putLong("Peer", peer.asLong());
            }
        }
    }
}
