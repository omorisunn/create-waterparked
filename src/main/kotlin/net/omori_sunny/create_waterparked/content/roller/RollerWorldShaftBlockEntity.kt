package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.createmod.catnip.lang.LangBuilder
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.omori_sunny.create_waterparked.CreateWaterparked
import java.util.UUID

// the world side drive of a hinged run: it owns the clock and hands the run its own speed once a game tick
class RollerWorldShaftBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) :
    SimpleKineticBlockEntity(type, pos, state), BlockEntitySubLevelActor {

    var runId: UUID? = null
    // the latch of the run this shaft drives, mirrored here so the shaft names the very state its own deck holds
    var runLocked: Boolean = true
        private set
    var runFallback: Boolean = false
        private set
    private var lastDriveGameTime = Long.MIN_VALUE

    override fun tick() {
        super.tick()
        driveOnce()
    }

    override fun `sable$tick`(subLevel: ServerSubLevel) {
        driveOnce()
    }

    // one drive per game tick, whichever entry reached the shaft, and none at all once its run is gone
    private fun driveOnce() {
        val world = level as? ServerLevel ?: return
        if (world.gameTime == lastDriveGameTime) return
        lastDriveGameTime = world.gameTime
        val id = runId ?: return
        val sub = SubLevelContainer.getContainer(world)?.getSubLevel(id) as? ServerSubLevel
        if (sub == null) {
            // the run is gone from the world, so any bearing we still hold for it is handed back here
            RollerHingeTilt.forgetRun(id)
            return
        }
        RollerHingeTilt.driveFromShaft(world, sub, speed)
        val latch = RollerHingeTilt.latchOf(sub)
        setRunLatch(latch.first, latch.second)
    }

    // the run's own latch, written by the driver: a real move is the only thing that goes out
    fun setRunLatch(locked: Boolean, fallback: Boolean) {
        if (runLocked == locked && runFallback == fallback) return
        runLocked = locked
        runFallback = fallback
        setChanged()
        sendData()
    }

    override fun write(compound: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        runId?.let { compound.putString(RUN_ID, it.toString()) }
        compound.putBoolean(LOCKED, runLocked)
        compound.putBoolean(FALLBACK, runFallback)
        super.write(compound, registries, clientPacket)
    }

    override fun read(compound: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        super.read(compound, registries, clientPacket)
        val stored = compound.getString(RUN_ID)
        runId = if (stored.isEmpty()) null else runCatching { UUID.fromString(stored) }.getOrNull()
        // a shaft saved before the latch existed drove a locked run, so that is what it reads back as
        runLocked = !compound.contains(LOCKED) || compound.getBoolean(LOCKED)
        runFallback = compound.getBoolean(FALLBACK)
    }

    // the goggles name the run's latch on its shaft too, from the very state its own deck holds. Nothing is
    // claimed for a shaft with no run in the world, and Create trims the last line of a tooltip that claims one.
    override fun addToGoggleTooltip(tooltip: MutableList<Component>, isPlayerSneaking: Boolean): Boolean {
        val base = super.addToGoggleTooltip(tooltip, isPlayerSneaking)
        if (runId == null) return base
        val before = tooltip.size
        val locked = runLocked
        LangBuilder(CreateWaterparked.ID)
            .translate("roller.hinge_header")
            .forGoggles(tooltip)
        // a locked run reads GOLD, a free bearing AQUA, and a latch that had to fall back GRAY
        val fallback = !locked && runFallback
        LangBuilder(CreateWaterparked.ID)
            .translate(
                when {
                    locked -> "roller.hinge_locked"
                    fallback -> "roller.hinge_fallback"
                    else -> "roller.hinge_unlocked"
                }
            )
            .style(
                when {
                    locked -> ChatFormatting.GOLD
                    fallback -> ChatFormatting.GRAY
                    else -> ChatFormatting.AQUA
                }
            )
            .forGoggles(tooltip, 1)
        // the run's own lines are what this call added, so the answer follows the tooltip and not the branch
        return base || tooltip.size > before
    }

    companion object {
        private const val RUN_ID = "RunId"
        private const val LOCKED = "RunLocked"
        private const val FALLBACK = "RunFallback"
    }
}
