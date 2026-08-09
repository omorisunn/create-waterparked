package net.omori_sunny.create_waterparked.content.waterslide

import dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity
import net.omori_sunny.create_waterparked.client.render.WaterslideCurveRenderer
import net.omori_sunny.create_waterparked.config.ModConfig
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn

// anchor BE
class WaterslideAnchorBlockEntity(pos: BlockPos, state: BlockState) : CoasterAnchorpointBlockEntity(pos, state) {

    var waterActive: Boolean = false
        private set

// radius
    var radius: Float = ModConfig.defaultSlideRadius()
        private set

// sector configs
    val sectorConfigs: MutableMap<BlockPos, WaterslideSectorConfig> = mutableMapOf()

    fun sectorConfigFor(peer: BlockPos): WaterslideSectorConfig =
        sectorConfigs.getOrPut(peer.immutable()) { WaterslideSectorConfig.defaultConfig() }

    fun setSectorConfig(peer: BlockPos, config: WaterslideSectorConfig) {
        sectorConfigs[peer.immutable()] = config
        setChanged()
        notifyBlockUpdated()
    }

// init missing configs
    fun initCurveSectorConfig(level: ServerLevel, peer: BlockPos) {
        val peerPos = peer.immutable()
        val peerBe = level.getBlockEntity(peerPos) as? WaterslideAnchorBlockEntity
        val remote = peerBe?.sectorConfigs?.get(blockPos.immutable())
        val local = sectorConfigs[peerPos]

        val config = local ?: remote ?: WaterslideSectorConfig.defaultConfig()
        if (local == null) setSectorConfig(peerPos, config)
        if (remote == null) peerBe?.setSectorConfig(blockPos, config)
    }

    fun setWaterActive(active: Boolean) {
        if (waterActive == active) return
        waterActive = active
        setChanged()
        notifyBlockUpdated()
    }

    @OnlyIn(Dist.CLIENT)
    override fun onLoad() {
        super.onLoad()
        if (level?.isClientSide == true) {
            WaterslideCurveRenderer.registerClientAnchor(this)
        }
    }

    @OnlyIn(Dist.CLIENT)
    override fun onChunkUnloaded() {
        super.onChunkUnloaded()
        WaterslideCurveRenderer.unregisterClientAnchor(this)
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
        super.read(tag, registries, clientPacket)
        waterActive = if (tag.contains("WaterActive", 1)) tag.getBoolean("WaterActive") else false
        radius = if (tag.contains("Radius", 5)) {
            ModConfig.clampSlideRadius(tag.getFloat("Radius"))
        } else {
            ModConfig.defaultSlideRadius()
        }
        sectorConfigs.clear()
        for (entry in tag.getList("SectorConfigs", 10)) {
            if (entry is CompoundTag && entry.contains("Peer", 4) && entry.contains("Config", 10)) {
                sectorConfigs[BlockPos.of(entry.getLong("Peer"))] =
                    WaterslideSectorConfig.read(entry.getCompound("Config"))
            }
        }
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
        super.write(tag, registries, clientPacket)
    }

    companion object {
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
}
