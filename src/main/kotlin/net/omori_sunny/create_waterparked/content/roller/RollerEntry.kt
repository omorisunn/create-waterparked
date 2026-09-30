package net.omori_sunny.create_waterparked.content.roller

import net.minecraft.world.entity.Entity
import java.util.WeakHashMap

// the speed a load brought onto a deck, kept while the load slides to the centre before it is taken in
object RollerEntry {

    private const val TTL = 20L

    private class Entry(var speed: Float, var touchedAt: Long)

    private val entries = WeakHashMap<Entity, Entry>()

    @JvmStatic
    fun speedFor(entity: Entity, current: Float, gameTime: Long): Float {
        val known = entries[entity]
        if (known != null) {
            known.touchedAt = gameTime
            return known.speed
        }
        entries[entity] = Entry(current, gameTime)
        return current
    }

    @JvmStatic
    fun forget(entity: Entity) {
        entries.remove(entity)
    }

    // entries no load touches any more fall away, counted in world time so extra decks change nothing
    @JvmStatic
    fun tick(gameTime: Long) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            if (gameTime - iterator.next().touchedAt > TTL) iterator.remove()
        }
    }
}
