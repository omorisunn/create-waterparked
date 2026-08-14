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

// Same role as Coasters Simulated's CoasterPonderRestore: waterslide anchor
// peer-curve data is stored in the schematic's BlockEntity NBT and has to be
// (re)injected into the Ponder level before backup and after every restore.
public final class WaterslidePonderRestore {

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
            BlockPos pos = info.pos();
            CompoundTag nbt = info.nbt();
            if (nbt != null) {
                TEMPLATE_ANCHOR_NBTS.put(pos.immutable(), nbt.copy());
            }
            if (nbt != null
                && nbt.contains("AnchorPeerCurves")
                && BlockEntity.loadStatic(pos, info.state(), nbt, registries)
                    instanceof WaterslideAnchorBlockEntity anchor) {
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
                AnchorPeerCurveClientIndex.refreshMembership(be);
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
