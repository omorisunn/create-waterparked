package net.omori_sunny.create_waterparked.content.roller

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver
import dev.ryanhcode.sable.platform.SableEventPlatform
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.SubLevel
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
import net.omori_sunny.create_waterparked.CreateWaterparked
import java.util.UUID

// gives runs saved before the world shaft existed their drive back, once their sub-level is loaded
object RollerLegacyAdoption : SubLevelObserver {

    private val pending = HashMap<UUID, Int>()

    // registered from the mod entrypoint, one observer per ready plot grid
    @JvmStatic
    fun register() {
        SableEventPlatform.INSTANCE.onSubLevelContainerReady { _, container ->
            if (container is ServerSubLevelContainer) container.addObserver(this)
        }
    }

    // a loaded sub-level carries its tag only after it was added, so the look is delayed a moment
    override fun onSubLevelAdded(subLevel: SubLevel) {
        val sub = subLevel as? ServerSubLevel ?: return
        if (pending.size >= MAX_PENDING) return
        pending.putIfAbsent(sub.uniqueId, ADOPT_DELAY_TICKS)
    }

    override fun onSubLevelRemoved(subLevel: SubLevel, reason: SubLevelRemovalReason) {
        pending.remove(subLevel.uniqueId)
    }

    override fun tick(subLevels: SubLevelContainer) {
        if (pending.isEmpty()) return
        val container = subLevels as? ServerSubLevelContainer ?: return
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value > 0) {
                entry.setValue(entry.value - 1)
                continue
            }
            iterator.remove()
            val sub = container.getSubLevel(entry.key) as? ServerSubLevel ?: continue
            runCatching { RollerHingeTilt.adoptLegacyRun(container.level, sub) }
                .onFailure { CreateWaterparked.LOGGER.warn("[roller hinge] could not adopt the run {}", entry.key, it) }
        }
    }

    private const val ADOPT_DELAY_TICKS = 20
    private const val MAX_PENDING = 64
}
