package net.omori_sunny.create_waterparked.content.waterslide

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
import net.minecraft.server.level.ServerLevel
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
    }

// drop the config when the curve is removed
    fun removeSectorConfig(peer: BlockPos) {
        if (sectorConfigs.remove(peer.immutable()) == null) return
        removeWateredCurve(peer)
        setChanged()
        notifyBlockUpdated()
    }

    fun removeWateredCurve(peer: BlockPos) {
        if (wateredCurves.remove(peer.immutable()) == null) return
        setChanged()
        notifyBlockUpdated()
    }

// reset the opening when the last curve goes
    fun resetRadiusIfEmpty() {
        if (legCount() != 0) return
        val def = ModConfig.defaultSlideRadius()
        if (radius == def) return
        radius = def
        setChanged()
        notifyBlockUpdated()
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

// pick up by contraption assembly removes the block entity from the world
// immediately; drop our index entries so stale anchors do not linger. Called
// by SmartBlockEntity#setRemoved when the block entity is not being chunk-unloaded.
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
        if (tag.contains("WaterTank", 10)) {
            waterTank.readFromNBT(registries, tag.getCompound("WaterTank"))
        }
// refresh visuals after curve data arrives
        if (level?.isClientSide == true) {
            WaterslideTubeVisual.refreshAnchor(blockPos)
        }
    }

    // Public entry used by contraption-space reconstruction to populate this
    // (worldless) BE from the captured contraption NBT; read() itself is
    // protected, so everything else goes through this wrapper.
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
        tag.put("WaterTank", waterTank.writeToNBT(registries, CompoundTag()))
        super.write(tag, registries, clientPacket)
    }

// ===== contraption disassembly transform =====
// Rewrite the inherited peer-curve maps, their tint keys and our own
// sectorConfigs/wateredCurves so slide data stays intact at the new
// position/rotation. Translation-only moves also go through here because
// peer keys must shift. radius/waterActive/waterTank are intentionally left
// untouched (they are position-independent).
// Endpoint/vector math is valid for any 90-degree axis, but Create only
// rotates TrackShape block states around Y (TrackBlockEntity does the same),
// so horizontal-axis rotations keep the curve data consistent while the rail
// models may not follow; this matches Create's own track convention.
    override fun transform(blockEntity: BlockEntity, transform: StructureTransform) {
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

            // Curve-local delta between the two endpoints. Simulated's read()
            // already re-anchored both endpoints onto the NEW self position
            // (BezierConnection(tag, getBlockPos())), so subtracting cancels
            // that pre-translation and leaves the true old endpoint delta.
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

            // Starts are absolute vectors; rotate them around the endpoint
            // block CENTER exactly like Create's own TrackBlockEntity.transform.
            // Because read() re-anchored the endpoints, selfEndpoint already IS
            // newSelf here, so endpointCenter == Vec3.atCenterOf(newSelf) - the
            // two pivots coincide and the handle offset is exact.
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
