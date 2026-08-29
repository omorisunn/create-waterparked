package net.omori_sunny.create_waterparked.content.waterslide

import com.simibubi.create.AllBlocks
import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity
import com.simibubi.create.content.contraptions.StructureTransform
import com.simibubi.create.content.trains.track.BezierConnection
import com.simibubi.create.foundation.fluid.SmartFluidTank
import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.createmod.catnip.data.Couple
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.flywheel.WaterslideTubeVisual
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.omori_sunny.create_waterparked.game.SlideAnchorIndex
import net.omori_sunny.create_waterparked.game.contraption.AnchorPeerCurveDataAccess
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import java.util.function.Consumer

// anchor BE
class WaterslideAnchorBlockEntity(pos: BlockPos, state: BlockState) :
    CoasterAnchorpointBlockEntity(pos, state), TransformableBlockEntity {

    var waterActive: Boolean = false
        private set

// radius
    var radius: Float = ModConfig.defaultSlideRadius()
        private set

// effective lift includes the pipe radius
    override fun getLiftBlocks(): Float = super.getLiftBlocks() + radius

// sector configs
    val sectorConfigs: MutableMap<BlockPos, WaterslideSectorConfig> = mutableMapOf()

// watered curves, keyed by peer
    val wateredCurves: MutableMap<BlockPos, Boolean> = mutableMapOf()

// support copycat materials per part, like Create's copycat block
// NOTE: bracket + beam share ONE material/visibility set (unified support).
// The per-part write calls update both, the reads return the union - so a
// right click on EITHER part always applies to the whole support.
    var supportBracketMaterial: BlockState = AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        private set
    var supportBeamMaterial: BlockState = AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        private set
    private var supportBracketConsumedItem: ItemStack = ItemStack.EMPTY
    private var supportBeamConsumedItem: ItemStack = ItemStack.EMPTY
    // axle-axe deletes a support part; wrench restores it (unified)
    var supportBracketVisible: Boolean = true
        private set
    var supportBeamVisible: Boolean = true
        private set

    fun setSupportVisible(part: WaterslideSupportPart, visible: Boolean) {
        supportBracketVisible = visible
        supportBeamVisible = visible
        setChanged()
        notifyBlockUpdated()
    }

    fun isSupportVisible(part: WaterslideSupportPart): Boolean =
        supportBracketVisible && supportBeamVisible

    // unified: the effective material is whatever either part carries
    fun supportMaterial(part: WaterslideSupportPart): BlockState =
        if (!AllBlocks.COPYCAT_BASE.has(supportBracketMaterial)) supportBracketMaterial
        else supportBeamMaterial

    fun supportConsumedItem(part: WaterslideSupportPart): ItemStack =
        if (!supportBracketConsumedItem.isEmpty) supportBracketConsumedItem else supportBeamConsumedItem

    fun hasCustomSupportMaterial(part: WaterslideSupportPart): Boolean =
        !AllBlocks.COPYCAT_BASE.has(supportBracketMaterial) ||
            !AllBlocks.COPYCAT_BASE.has(supportBeamMaterial)

    fun setSupportMaterial(part: WaterslideSupportPart, material: BlockState, consumed: ItemStack) {
        // both parts get the same material (unified support)
        supportBracketMaterial = material
        supportBeamMaterial = material
        supportBracketConsumedItem = consumed.copyWithCount(1)
        supportBeamConsumedItem = consumed.copyWithCount(1)
        setChanged()
        notifyBlockUpdated()
    }

    fun cycleSupportMaterial(part: WaterslideSupportPart): Boolean {
        val cycled = WaterslideSupportMaterials.cycleMaterial(supportMaterial(part)) ?: return false
        supportBracketMaterial = cycled
        supportBeamMaterial = cycled
        setChanged()
        notifyBlockUpdated()
        return true
    }

    fun resetSupportMaterial(part: WaterslideSupportPart): ItemStack {
        val returned = supportConsumedItem(part)
        // both parts reset together (unified support)
        supportBracketMaterial = AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        supportBeamMaterial = AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        supportBracketConsumedItem = ItemStack.EMPTY
        supportBeamConsumedItem = ItemStack.EMPTY
        setChanged()
        notifyBlockUpdated()
        return returned
    }

    // Copycat parity: destroying the anchor returns the consumed material items.
    fun dropSupportConsumedItems() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        if (!supportBracketConsumedItem.isEmpty)
            net.minecraft.world.level.block.Block.popResource(lvl, blockPos, supportBracketConsumedItem)
        if (!supportBeamConsumedItem.isEmpty)
            net.minecraft.world.level.block.Block.popResource(lvl, blockPos, supportBeamConsumedItem)
    }

    private val waterTank: WaterTank = WaterTank(ModConfig.anchorFluidCapacity()) { onWaterChanged() }
    private val waterHandler: IFluidHandler = WaterOnlyHandler()
    private var drainAccum = 0.0

    fun isCurveWatered(peer: BlockPos): Boolean = wateredCurves[peer.immutable()] ?: false

    fun setCurveWatered(peer: BlockPos, watered: Boolean) {
        val key = peer.immutable()
        if (wateredCurves[key] == watered) return
        if (watered) wateredCurves[key] = true else wateredCurves.remove(key)
        setChanged()
        notifyBlockUpdated()
        waterStructureChanged()
    }

    fun hasWater(): Boolean = waterTank.fluidAmount > 0

    fun waterAmount(): Int = waterTank.fluidAmount

    fun refillWater() {
        if (waterTank.fluidAmount < waterTank.capacity) {
            val filled = waterTank.fill(
                FluidStack(Fluids.WATER, waterTank.capacity),
                IFluidHandler.FluidAction.EXECUTE
            )
            if (filled > 0) {
                net.omori_sunny.create_waterparked.CreateWaterparked.LOGGER.info(
                    "Anchor {} refilled {} mb", blockPos, filled
                )
            }
        }
    }

    fun drainWater(mb: Int): Int = waterTank.drain(mb, IFluidHandler.FluidAction.EXECUTE).amount

    fun waterDrainAccum(): Double = drainAccum

    fun addDrainAccum(value: Double) {
        drainAccum += value
    }

    fun resetDrainAccum() {
        drainAccum = 0.0
    }

    private fun onWaterChanged() {
        if (level != null && !level!!.isClientSide) {
            setChanged()
            notifyBlockUpdated()
        }
    }

    fun sectorConfigFor(peer: BlockPos): WaterslideSectorConfig =
        sectorConfigs.getOrPut(peer.immutable()) { WaterslideSectorConfig.defaultConfig() }

    fun setSectorConfig(peer: BlockPos, config: WaterslideSectorConfig) {
        sectorConfigs[peer.immutable()] = config
        setChanged()
        notifyBlockUpdated()
        waterStructureChanged()
    }

// drop the config when the curve is removed
    fun removeSectorConfig(peer: BlockPos) {
        if (sectorConfigs.remove(peer.immutable()) == null) return
        removeWateredCurve(peer)
        setChanged()
        notifyBlockUpdated()
        waterStructureChanged()
    }

    fun removeWateredCurve(peer: BlockPos) {
        if (wateredCurves.remove(peer.immutable()) == null) return
        setChanged()
        notifyBlockUpdated()
        waterStructureChanged()
    }

// reset the opening when the last curve goes
    fun resetRadiusIfEmpty() {
        if (legCount() != 0) return
        val def = ModConfig.defaultSlideRadius()
        if (radius == def) return
        radius = def
        setChanged()
        notifyBlockUpdated()
        waterStructureChanged()
    }

// init missing configs
    fun initCurveSectorConfig(level: ServerLevel, peer: BlockPos) {
        val peerPos = peer.immutable()
        val peerBe = level.getBlockEntity(peerPos) as? WaterslideAnchorBlockEntity
        val remote = peerBe?.sectorConfigs?.get(blockPos.immutable())
        val local = sectorConfigs[peerPos]

// inherit the style of an existing slide at either anchor
        val config = local ?: remote ?: inheritedSectorConfig(level, peerPos)
            ?: WaterslideSectorConfig.defaultConfig()
        if (local == null) setSectorConfig(peerPos, config)
        if (remote == null) peerBe?.setSectorConfig(blockPos, config)
    }

    private fun inheritedSectorConfig(level: Level, peerPos: BlockPos): WaterslideSectorConfig? {
        for ((p, c) in sectorConfigs) {
            if (p != peerPos && anchorPeerCurvesView.containsKey(p)) return c.copyOf()
        }
        val peerBe = level.getBlockEntity(peerPos) as? WaterslideAnchorBlockEntity ?: return null
        for ((p, c) in peerBe.sectorConfigs) {
            if (p != blockPos && peerBe.anchorPeerCurvesView.containsKey(p)) return c.copyOf()
        }
        return null
    }

    fun setWaterActive(active: Boolean) {
        if (waterActive == active) return
        waterActive = active
        setChanged()
        notifyBlockUpdated()
        waterStructureChanged()
    }

    override fun onLoad() {
        super.onLoad()
        val lvl = level ?: return
        if (lvl.isClientSide) {
            WaterslideCurveRenderer.registerClientAnchor(this)
        } else {
            SlideAnchorIndex.register(lvl, blockPos)
        }
    }

    override fun onChunkUnloaded() {
        super.onChunkUnloaded()
        val lvl = level ?: return
        if (lvl.isClientSide) {
            WaterslideCurveRenderer.unregisterClientAnchor(this)
        } else {
            SlideAnchorIndex.unregister(lvl, blockPos)
        }
    }

// contraption assembly removes the BE on pickup, drop our index entries
    override fun remove() {
        val lvl = level
        if (lvl?.isClientSide == true) {
            WaterslideCurveRenderer.unregisterClientAnchor(this)
        } else if (lvl != null) {
            SlideAnchorIndex.unregister(lvl, blockPos)
        }
        super.remove()
    }

// render bounds
    override fun getRenderBoundingBox(): AABB {
        var box = AABB(blockPos)
        for ((_, raw) in anchorPeerCurvesView) {
            val primary = if (raw.isPrimary) raw else raw.secondary()
            if (!WaterslideTrackMaterials.isWaterslide(primary)) continue
            box = box.minmax(AABB(primary.bePositions.getFirst()).inflate(8.0))
                .minmax(AABB(primary.bePositions.getSecond()).inflate(8.0))
        }
        return box
    }

    fun setRadius(newRadius: Float) {
        val clamped = ModConfig.clampSlideRadius(newRadius)
        if (radius == clamped) return
        radius = clamped
        setChanged()
        notifyBlockUpdated()
        waterStructureChanged()
    }

    // any structural edit that changes the water field (radius, curves,
    // watering, water switch, sector config) marks the space dirty so the
    // water sim recalcs on the next tick instead of the slow fallback scan
    private fun waterStructureChanged() {
        val lvl = level
        if (lvl != null && !lvl.isClientSide) {
            net.omori_sunny.create_waterparked.game.water.ServerWaterSimulation.markDirty(lvl)
        }
    }

    private fun notifyBlockUpdated() {
        if (level != null && !level!!.isClientSide) {
// client sync
            notifyUpdate()
        }
    }

    override fun read(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
// restore radius first, curve loading may need it
        waterActive = if (tag.contains("WaterActive", 1)) tag.getBoolean("WaterActive") else false
        radius = if (tag.contains("Radius", 5)) {
            ModConfig.clampSlideRadius(tag.getFloat("Radius"))
        } else {
            ModConfig.defaultSlideRadius()
        }
        super.read(tag, registries, clientPacket)
        sectorConfigs.clear()
        for (entry in tag.getList("SectorConfigs", 10)) {
            if (entry is CompoundTag && entry.contains("Peer", 4) && entry.contains("Config", 10)) {
                sectorConfigs[BlockPos.of(entry.getLong("Peer"))] =
                    WaterslideSectorConfig.read(entry.getCompound("Config"))
            }
        }
// drop configs without a live curve
        sectorConfigs.keys.retainAll(anchorPeerCurvesView.keys)
        wateredCurves.clear()
        for (entry in tag.getList("WateredCurves", 10)) {
            if (entry is CompoundTag && entry.contains("Peer", 4) && entry.contains("Watered", 1)) {
                wateredCurves[BlockPos.of(entry.getLong("Peer"))] = entry.getBoolean("Watered")
            }
        }
        wateredCurves.keys.retainAll(anchorPeerCurvesView.keys)
        supportBracketMaterial = if (tag.contains("SupportBracketMaterial", 10))
            NbtUtils.readBlockState(blockHolderGetter(), tag.getCompound("SupportBracketMaterial"))
                ?: AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        else
            AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        supportBeamMaterial = if (tag.contains("SupportBeamMaterial", 10))
            NbtUtils.readBlockState(blockHolderGetter(), tag.getCompound("SupportBeamMaterial"))
                ?: AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        else
            AllBlocks.COPYCAT_BASE.get().defaultBlockState()
        supportBracketConsumedItem = ItemStack.parseOptional(
            registries, tag.getCompound("SupportBracketItem")
        )
        supportBeamConsumedItem = ItemStack.parseOptional(
            registries, tag.getCompound("SupportBeamItem")
        )
        val prevBracketVisible = supportBracketVisible
        val prevBeamVisible = supportBeamVisible
        supportBracketVisible = if (tag.contains("SupportBracketVisible", 1))
            tag.getBoolean("SupportBracketVisible") else true
        supportBeamVisible = if (tag.contains("SupportBeamVisible", 1))
            tag.getBoolean("SupportBeamVisible") else true
        if (tag.contains("WaterTank", 10)) {
            waterTank.readFromNBT(registries, tag.getCompound("WaterTank"))
        }
// refresh visuals after curve data arrives
        if (level?.isClientSide == true) {
            // support visibility is NOT part of the tube visual data signature,
            // so a visibility-only change (wrench restore / axe delete) would
            // never rebuild the flywheel visuals - force the rebuild here
            if (supportBracketVisible != prevBracketVisible || supportBeamVisible != prevBeamVisible) {
                WaterslideTubeVisual.refreshAll()
            }
            WaterslideTubeVisual.refreshAnchor(blockPos)
        } else if (level != null) {
            // server: curve topology (place/remove/drag) arrives through NBT.
            // only mark dirty when the curve set actually changed, so regular
            // block syncing does not spam recalculations
            val topoSig = StringBuilder()
            for ((peer, raw) in anchorPeerCurvesView) {
                if (raw == null) continue
                val bc = if (raw.isPrimary) raw else raw.secondary()
                if (bc == null) continue
                topoSig.append(peer.asLong()).append('=').append(bc.getSegmentCount())
                    .append(',').append(bc.starts.getFirst().x).append(',').append(bc.starts.getFirst().y).append(',')
                    .append(bc.starts.getFirst().z).append(',').append(bc.starts.getSecond().x).append(',')
                    .append(bc.starts.getSecond().y).append(',').append(bc.starts.getSecond().z).append(';')
            }
            val sig = topoSig.toString()
            if (sig != lastPeerTopoSig) {
                lastPeerTopoSig = sig
                waterStructureChanged()
            }
        }
    }

    // last NBT curve-topology snapshot on the server, avoids re-dirtying on
    // every regular block sync; transient across reloads (recovers via the
    // slow fallback rescan anyway)
    private var lastPeerTopoSig: String? = null

    // public entry for contraption space reconstruction from captured NBT
    fun readCaptured(tag: CompoundTag, registries: HolderLookup.Provider?) {
        val regs = registries ?: level?.registryAccess() ?: return
        read(tag, regs, false)
    }

    override fun write(tag: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        tag.putBoolean("WaterActive", waterActive)
        tag.putFloat("Radius", radius)
        val list = ListTag()
        for ((peer, config) in sectorConfigs) {
            val entry = CompoundTag()
            entry.putLong("Peer", peer.asLong())
            val configTag = CompoundTag()
            config.write(configTag)
            entry.put("Config", configTag)
            list.add(entry)
        }
        tag.put("SectorConfigs", list)
        val wateredList = ListTag()
        for ((peer, watered) in wateredCurves) {
            val entry = CompoundTag()
            entry.putLong("Peer", peer.asLong())
            entry.putBoolean("Watered", watered)
            wateredList.add(entry)
        }
        tag.put("WateredCurves", wateredList)
        tag.put("SupportBracketMaterial", NbtUtils.writeBlockState(supportBracketMaterial))
        tag.put("SupportBeamMaterial", NbtUtils.writeBlockState(supportBeamMaterial))
        tag.put("SupportBracketItem", supportBracketConsumedItem.saveOptional(registries))
        tag.put("SupportBeamItem", supportBeamConsumedItem.saveOptional(registries))
        tag.putBoolean("SupportBracketVisible", supportBracketVisible)
        tag.putBoolean("SupportBeamVisible", supportBeamVisible)
        tag.put("WaterTank", waterTank.writeToNBT(registries, CompoundTag()))
        super.write(tag, registries, clientPacket)
    }

// rewrite peer curves, tint keys and sector data for the contraption transform
    override fun transform(blockEntity: BlockEntity, transform: StructureTransform) {
        // copycat parity: material states rotate with the structure
        supportBracketMaterial = transform.apply(supportBracketMaterial)
        supportBeamMaterial = transform.apply(supportBeamMaterial)

        val access = this as? AnchorPeerCurveDataAccess ?: return
        val oldCurves = access.`waterparked$anchorPeerCurves`()
        if (oldCurves.isEmpty()) return

        val selfPos = worldPosition
        val selfVec = Vec3.atCenterOf(selfPos)
        val newCurves = HashMap<BlockPos, BezierConnection>(oldCurves.size)
        val keyRemap = HashMap<BlockPos, BlockPos>(oldCurves.size)

        for ((oldPeer, bc) in oldCurves.toList()) {
            // Orient the curve: which endpoint belongs to this block entity?
            val selfEndpoint: BlockPos
            val peerEndpoint: BlockPos
            if (oldPeer == bc.bePositions.getSecond()) {
                // Curve stored with self first, peer second.
                selfEndpoint = bc.bePositions.getFirst()
                peerEndpoint = bc.bePositions.getSecond()
            } else if (bc.bePositions.getFirst() == selfPos) {
                // Curve stored with peer first, self second.
                selfEndpoint = bc.bePositions.getFirst()
                peerEndpoint = bc.bePositions.getSecond()
            } else {
                // Fallback: endpoint closer to this block entity.
                val first = bc.bePositions.getFirst()
                val second = bc.bePositions.getSecond()
                val firstDist = Vec3.atCenterOf(first).distanceToSqr(selfVec)
                val secondDist = Vec3.atCenterOf(second).distanceToSqr(selfVec)
                selfEndpoint = if (firstDist <= secondDist) first else second
                peerEndpoint = if (selfEndpoint == first) second else first
            }
            val selfEndpointIsFirst = selfEndpoint == bc.bePositions.getFirst()

            // subtract the read re anchoring, leaves the true endpoint delta
            val oldDelta = Vec3.atLowerCornerOf(peerEndpoint)
                .subtract(Vec3.atLowerCornerOf(selfEndpoint))

            // Rebuild the delta from the block's new contraption-local space.
            val selfLocal = transform.unapplyWithoutOffset(
                selfVec.subtract(Vec3.atLowerCornerOf(transform.offset))
            )
            val peerLocal = selfLocal.add(oldDelta)
            val peerGlobal = transform.apply(peerLocal)
            val newPeer = BlockPos.containing(peerGlobal)
            val newSelf = selfPos

            // starts rotate around the endpoint center like Create's track transform
            val endpointCenter = Vec3.atCenterOf(selfEndpoint)
            val newStarts = Couple.create(
                transform.applyWithoutOffsetUncentered(
                    bc.starts.getFirst().subtract(endpointCenter)
                ).add(endpointCenter),
                transform.applyWithoutOffsetUncentered(
                    bc.starts.getSecond().subtract(endpointCenter)
                ).add(endpointCenter)
            )
            val newAxes = Couple.create(
                transform.applyWithoutOffsetUncentered(bc.axes.getFirst()),
                transform.applyWithoutOffsetUncentered(bc.axes.getSecond())
            )
            val newNormals = Couple.create(
                transform.applyWithoutOffsetUncentered(bc.normals.getFirst()),
                transform.applyWithoutOffsetUncentered(bc.normals.getSecond())
            )

            val newPositions = if (selfEndpointIsFirst) {
                Couple.create(newSelf, newPeer)
            } else {
                Couple.create(newPeer, newSelf)
            }

            val rebuilt = BezierConnection(
                newPositions, newStarts, newAxes, newNormals,
                bc.primary, bc.hasGirder, bc.getMaterial()
            )
            // smoothing is mutable and nullable; carry it over verbatim.
            if (bc.smoothing != null) {
                rebuilt.smoothing = bc.smoothing
            }

            newCurves[newPeer.immutable()] = rebuilt
            keyRemap[oldPeer] = newPeer
        }

        // Apply to inherited maps through the mixin accessor.
        access.`waterparked$anchorPeerCurves`().clear()
        access.`waterparked$anchorPeerCurves`().putAll(newCurves)
        remapKeys(access.`waterparked$railRgb`(), keyRemap)
        remapKeys(access.`waterparked$beamRgb`(), keyRemap)

        // Remap our own data; drop entries whose curve did not survive.
        remapSectorConfigs(keyRemap)
        remapWateredCurves(keyRemap)

        CreateWaterparked.LOGGER.debug(
            "Anchor {} contraption transform: {} curve(s) remapped",
            selfPos, keyRemap.size
        )
        setChanged()
    }

    private fun <V> remapKeys(target: MutableMap<BlockPos, V>, keyRemap: Map<BlockPos, BlockPos>) {
        if (target.isEmpty()) return
        val remapped = HashMap<BlockPos, V>(target.size)
        for ((oldKey, value) in target) {
            val newKey = keyRemap[oldKey]
            if (newKey != null) remapped[newKey.immutable()] = value
        }
        target.clear()
        target.putAll(remapped)
    }

    private fun remapSectorConfigs(keyRemap: Map<BlockPos, BlockPos>) {
        if (sectorConfigs.isEmpty()) return
        val remapped = HashMap<BlockPos, WaterslideSectorConfig>(sectorConfigs.size)
        for ((oldPeer, config) in sectorConfigs) {
            val newPeer = keyRemap[oldPeer]
            if (newPeer != null) {
                remapped[newPeer.immutable()] = config
            } else {
                CreateWaterparked.LOGGER.debug(
                    "Anchor {}: dropping sector config for unmatched peer {}", blockPos, oldPeer
                )
            }
        }
        sectorConfigs.clear()
        sectorConfigs.putAll(remapped)
    }

    private fun remapWateredCurves(keyRemap: Map<BlockPos, BlockPos>) {
        if (wateredCurves.isEmpty()) return
        val remapped = HashMap<BlockPos, Boolean>(wateredCurves.size)
        for ((oldPeer, watered) in wateredCurves) {
            val newPeer = keyRemap[oldPeer]
            if (newPeer != null) {
                remapped[newPeer.immutable()] = watered
            } else {
                CreateWaterparked.LOGGER.debug(
                    "Anchor {}: dropping watered flag for unmatched peer {}", blockPos, oldPeer
                )
            }
        }
        wateredCurves.clear()
        wateredCurves.putAll(remapped)
    }

    companion object {
        @JvmStatic
        fun registerCapabilities(event: RegisterCapabilitiesEvent) {
            event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntities.WATERSLIDE_ANCHOR_BE
            ) { be, _ -> be.waterHandler }
        }

// sync to both ends
        @JvmStatic
        fun commitSectorConfig(level: ServerLevel, curve: com.simibubi.create.content.trains.track.BezierConnection, config: WaterslideSectorConfig) {
            val primary = if (curve.isPrimary) curve else curve.secondary()
            val a = primary.bePositions.getFirst()
            val b = primary.bePositions.getSecond()
            (level.getBlockEntity(a) as? WaterslideAnchorBlockEntity)?.setSectorConfig(b, config)
            (level.getBlockEntity(b) as? WaterslideAnchorBlockEntity)?.setSectorConfig(a, config)
        }

        private val pendingType = ThreadLocal<BlockEntityType<*>?>()

        @JvmStatic
        fun pendingType(): BlockEntityType<*>? = pendingType.get()

        @JvmStatic
        fun withPendingType(type: BlockEntityType<*>, action: Runnable) {
            pendingType.set(type)
            try {
                action.run()
            } finally {
                pendingType.remove()
            }
        }

        @JvmStatic
        fun tick(level: Level, pos: BlockPos, state: BlockState, be: WaterslideAnchorBlockEntity) {
            CoasterAnchorpointBlockEntity.serverTick(level, pos, state, be)
        }
    }

    private class WaterTank(capacity: Int, callback: Consumer<FluidStack>) : SmartFluidTank(capacity, callback) {
        override fun isFluidValid(stack: FluidStack): Boolean = stack.`is`(Fluids.WATER)
    }

    private inner class WaterOnlyHandler : IFluidHandler {
        override fun getTanks(): Int = 1

        override fun getFluidInTank(tank: Int): FluidStack = waterTank.fluid

        override fun getTankCapacity(tank: Int): Int = waterTank.capacity

        override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = waterTank.isFluidValid(stack)

        override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int =
            waterTank.fill(resource, action)

        override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack = FluidStack.EMPTY

        override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack = FluidStack.EMPTY
    }
}
