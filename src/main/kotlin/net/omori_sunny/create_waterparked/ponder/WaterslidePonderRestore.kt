package net.omori_sunny.create_waterparked.ponder

import com.simibubi.create.foundation.ponder.CreateSceneBuilder
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper
import dev.silvergold.simulatedcoasters.track.anchor.AnchorJunctionVisualRefresh
import dev.silvergold.simulatedcoasters.track.anchor.AnchorPeerCurveClientIndex
import net.createmod.ponder.api.level.PonderLevel
import net.createmod.ponder.foundation.registration.PonderSceneRegistry
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import net.omori_sunny.create_waterparked.content.waterslide.WaterslideAnchorBlockEntity
import net.omori_sunny.create_waterparked.mixin.ponder.WaterslidePonderLevelAccessor
import net.omori_sunny.create_waterparked.mixin.ponder.WaterslidePonderLevelInvoker
import net.omori_sunny.create_waterparked.mixin.ponder.WaterslideSchematicLevelAccessor

// reinject waterslide anchor peer curve data into the Ponder level
object WaterslidePonderRestore {

    private val TEMPLATE_ANCHOR_NBTS: MutableMap<BlockPos, CompoundTag> = HashMap()

    @JvmStatic
    fun seedFromStructureTemplate(level: PonderLevel?, template: StructureTemplate?) {
        if (level == null || template == null) return
        TEMPLATE_ANCHOR_NBTS.clear()
        val registries = level.registryAccess()
        val settings = StructurePlaceSettings()

        for (info in template.filterBlocks(BlockPos.ZERO, settings, ModBlocks.WATERSLIDE_ANCHOR)) {
            val nbt = info.nbt() ?: continue
            val pos = info.pos()
            TEMPLATE_ANCHOR_NBTS[pos.immutable()] = nbt.copy()
            val anchor = if (nbt.contains("AnchorPeerCurves"))
                BlockEntity.loadStatic(pos, info.state(), nbt, registries) as? WaterslideAnchorBlockEntity
            else null
            if (anchor != null) {
                for (peer in anchor.getAnchorPeerCurvesView().keys) {
                    anchor.setCurveWatered(peer, true)
                }
                installBlockEntity(level, pos, anchor)
            }
        }
    }

    @JvmStatic
    fun applyDisplayedAnchorLayer(
        scene: CreateSceneBuilder, sourceY: Int, displayY: Int, vararg sourceAnchors: BlockPos
    ) {
        val dy = displayY - sourceY
        val registries = scene.world().getHolderLookupProvider()

        for (source in sourceAnchors) {
            val atSource = source.atY(sourceY)
            val target = source.atY(displayY)
            val tag = TEMPLATE_ANCHOR_NBTS[atSource]
            val applied = if (tag == null) CompoundTag() else translateAnchorTagForDisplay(tag.copy(), dy)
            scene.world().modifyBlockEntity(target, WaterslideAnchorBlockEntity::class.java) { be ->
                be.reloadCurveDataForPonder(applied, registries)
                be.repairAnchorPeerCurveKeys()
                for (peer in be.getAnchorPeerCurvesView().keys) {
                    be.setCurveWatered(peer, true)
                }
                AnchorPeerCurveClientIndex.refreshMembership(be)
                VisualizationHelper.queueUpdate(be)
                WaterslideTubeVisual.refreshAll()
            }
        }
    }

    // hide the tube on the displayed anchors by clearing their curve data
    @JvmStatic
    fun clearDisplayedCurves(scene: CreateSceneBuilder, anchorY: Int, vararg sourceAnchors: BlockPos) {
        for (source in sourceAnchors) {
            scene.world().modifyBlockEntity(source.atY(anchorY), WaterslideAnchorBlockEntity::class.java) { be ->
                be.reloadCurveDataForPonder(CompoundTag(), scene.world().getHolderLookupProvider())
                be.repairAnchorPeerCurveKeys()
                VisualizationHelper.queueUpdate(be)
                WaterslideTubeVisual.refreshAll()
            }
        }
    }

    @JvmStatic
    fun templateAnchorNbt(schematicPos: BlockPos): CompoundTag? =
        TEMPLATE_ANCHOR_NBTS[schematicPos.immutable()]

    @JvmStatic
    fun onLevelRestore(level: PonderLevel) {
        if (!level.isClientSide) return
        if (needsCurveSeed(level)) {
            seedFromSceneSchematic(level)
        }

        val backup = (level as WaterslidePonderLevelAccessor).`create_waterparked$originalBlockEntities`()
        val registries = level.registryAccess()
        val anchors = ArrayList<WaterslideAnchorBlockEntity>()

        for (be in level.getBlockEntities()) {
            if (be is WaterslideAnchorBlockEntity) {
                anchors.add(be)
                if (be.getAnchorPeerCurvesView().isEmpty()) {
                    val tag = backup[be.blockPos]
                    if (tag != null && tag.contains("AnchorPeerCurves")) {
                        be.reloadCurveDataForPonder(tag, registries)
                    }
                } else {
                    be.repairAnchorPeerCurveKeys()
                }
                for (peer in be.getAnchorPeerCurvesView().keys) {
                    be.setCurveWatered(peer, true)
                }
            }
        }

        for (anchor in anchors) {
            AnchorPeerCurveClientIndex.refreshMembership(anchor)
            VisualizationHelper.queueUpdate(anchor)
        }
        if (anchors.isNotEmpty()) {
            AnchorJunctionVisualRefresh.refreshAround(level, *anchors.map { it.blockPos }.toTypedArray())
            WaterslideTubeVisual.refreshAll()
        }
    }

    private fun needsCurveSeed(level: PonderLevel): Boolean {
        for (be in level.getBlockEntities()) {
            if (be is WaterslideAnchorBlockEntity && be.getAnchorPeerCurvesView().isNotEmpty()) {
                return false
            }
        }
        return true
    }

    private fun seedFromSceneSchematic(level: PonderLevel) {
        val scene = level.scene ?: return
        val schematicPath = WaterslidePonderScenes.schematicPathFor(scene.getId()) ?: return
        val template = PonderSceneRegistry.loadSchematic(schematicPath) ?: return
        if (template.size != BlockPos.ZERO) {
            seedFromStructureTemplate(level, template)
        }
    }

    // anchor block positions inside the current scene's schematic
    @JvmStatic
    fun schemaAnchors(level: PonderLevel?): Array<BlockPos> {
        val scene = level?.scene ?: return arrayOf()
        val schematicPath = WaterslidePonderScenes.schematicPathFor(scene.getId()) ?: return arrayOf()
        val template = PonderSceneRegistry.loadSchematic(schematicPath) ?: return arrayOf()
        val settings = StructurePlaceSettings()
        val infos = template.filterBlocks(BlockPos.ZERO, settings, ModBlocks.WATERSLIDE_ANCHOR)
        return Array(infos.size) { infos[it].pos() }
    }

    private fun installBlockEntity(level: PonderLevel, pos: BlockPos, blockEntity: BlockEntity) {
        (level as WaterslidePonderLevelInvoker).`create_waterparked$onBEAdded`(blockEntity, pos)
        val access = level as WaterslideSchematicLevelAccessor
        access.`create_waterparked$blockEntities`()[pos.immutable()] = blockEntity
        if (!access.`create_waterparked$renderedBlockEntities`().contains(blockEntity)) {
            access.`create_waterparked$renderedBlockEntities`().add(blockEntity)
        }
    }

    private fun translateAnchorTagForDisplay(tag: CompoundTag, dy: Int): CompoundTag {
        if (dy == 0) return tag
        offsetPeerLongs(tag, "AnchorPeerCurves", dy)
        offsetPeerLongs(tag, "AnchorPeerCurveTints", dy)
        offsetPeerLongs(tag, "SectorConfigs", dy)
        offsetPeerLongs(tag, "WateredCurves", dy)
        offsetPeerLongs(tag, "GhostBlocks", dy)
        return tag
    }

    private fun offsetPeerLongs(tag: CompoundTag, listKey: String, dy: Int) {
        if (!tag.contains(listKey)) return
        val list = tag.getList(listKey, 10)
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            if (entry.contains("Peer")) {
                val peer = BlockPos.of(entry.getLong("Peer")).offset(0, dy, 0)
                entry.putLong("Peer", peer.asLong())
            }
        }
    }
}
