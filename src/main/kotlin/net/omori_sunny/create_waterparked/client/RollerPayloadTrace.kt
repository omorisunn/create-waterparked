package net.omori_sunny.create_waterparked.client

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack
import net.minecraft.core.BlockPos
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.content.roller.RollerConveyorBlockEntity
import net.omori_sunny.create_waterparked.content.roller.RollerMomentum
import net.omori_sunny.create_waterparked.content.roller.RollerMomentumAccess
import kotlin.math.abs

// temporary: where a client draws each of its own loads, one line per load per game tick, so a load that
// teleports names the tick, the endpoint pair and the frame it happened in. The rendered value is handed in by
// the renderer itself, so the line reads exactly what the frame drew and nothing here recomputes it.
//
// Runtime diagnostics only: the load id is built from the object the client steps (System.identityHashCode),
// so it is stable within one session and never comparable across sessions or JVMs.
object RollerPayloadTrace {

    // off unless a run asks for it: the row keys are built per load per frame, so the whole recorder hangs behind
    // one switch like the server's path diagnostics, only defaulting the other way
    private val enabled = System.getProperty("createwaterparked.payloaddiag", "false") == "true"

    // the very cap the run clamps its own steps with, read from the very source the movement reads, so the
    // log can never drift from the config: a step longer than this is a jump
    private fun cap(): Float = RollerMomentum.speedLimit()
    // a clean tick hands the renderer prev = from and cur = to, so any gap past this is a real mismatch
    private const val ENDPOINT_EPSILON = 1.0E-3f
    // the render span a tick may show beyond what its endpoints and partial ticks explain
    private const val SPAN_SLACK = 0.05f
    // the plain rows one deck may print in one tick; a row that names a jump is never held back by this
    private const val ROWS_PER_TICK = 8
    private const val MAX_ROWS = 64

    private class Row(val key: String, val deck: Long, val lane: Int, var tick: Long) {
        var load: TransportedItemStack? = null
        var identity = 0
        var from = 0f
        var to = 0f
        var prev = 0f
        var cur = 0f
        var len = 0
        var flag = ""
        var force = false
        var partial = -1f
        var render = Float.NaN
        var rmin = Float.NaN
        var rmax = Float.NaN
        var pmin = Float.NaN
        var pmax = Float.NaN
        var samples = 0
    }

    private val rows = LinkedHashMap<String, Row>()
    private val lastState = HashMap<String, String>()

    // only the client draws its own loads, so only the client records them
    private fun clientDeck(deck: RollerConveyorBlockEntity): Boolean = deck.level?.isClientSide == true

    private fun laneOf(transported: TransportedItemStack): Int =
        (transported as RollerMomentumAccess).`waterparked$deckLane`()

    // the row key: the deck, the lane, the object the client steps and draws, and the run's own length. A load
    // the keyframe adopted is a new object, so it never answers for the one it replaced.
    private fun keyOf(deck: RollerConveyorBlockEntity, identity: Int, lane: Int): String =
        deck.blockPos.asLong().toString() + "|" + lane + "|" + identity + "|" + deck.deckLength

    // one client tick of its own copy is about to be stepped: the last tick goes out, this one is captured
    @JvmStatic
    fun beforeAdvance(deck: RollerConveyorBlockEntity) {
        if (!enabled) return
        if (!clientDeck(deck)) return
        val tick = deck.level?.gameTime ?: return
        flush(deck, tick)
        val loads = deck.inventory?.transportedItems ?: return
        for (transported in loads) addRow(deck, transported, tick)
    }

    // the step is done, so to/cur/prev are the very numbers the renderer will read this tick
    @JvmStatic
    fun afterAdvance(deck: RollerConveyorBlockEntity) {
        if (!enabled) return
        if (!clientDeck(deck)) return
        val tick = deck.level?.gameTime ?: return
        val loads = deck.inventory?.transportedItems ?: return
        val present = HashSet<Int>()
        for (transported in loads) present.add(System.identityHashCode(transported))
        val deckKey = deck.blockPos.asLong()
        for (row in rows.values) {
            if (row.deck != deckKey || row.tick != tick) continue
            val load = row.load ?: continue
            if (!present.contains(row.identity)) {
                // the load left the chain this tick, so its row names the step it never took
                row.flag = "dropped"
                row.force = true
                continue
            }
            row.to = load.beltPosition
            row.prev = load.prevBeltPosition
            row.cur = load.beltPosition
            val advance = row.to - row.from
            if (abs(advance) > cap() + ENDPOINT_EPSILON) {
                row.flag = "step"
                row.force = true
            } else if (abs(row.cur - row.prev - advance) > ENDPOINT_EPSILON) {
                row.flag = "endpoint"
                row.force = true
            }
        }
    }

    // a keyframe adopted the incoming copy (snapped) or brought a load this run never carried (fresh)
    @JvmStatic
    fun markEvent(deck: RollerConveyorBlockEntity, transported: TransportedItemStack, flag: String) {
        if (!enabled) return
        if (!clientDeck(deck)) return
        val tick = deck.level?.gameTime ?: return
        val row = rowOf(deck, transported, tick)
        row.flag = flag
        row.force = true
        row.to = transported.beltPosition
        row.cur = transported.beltPosition
        row.prev = transported.prevBeltPosition
    }

    // the value the frame really draws this load at, handed over by the renderer's own expression
    @JvmStatic
    fun sample(deck: RollerConveyorBlockEntity, transported: TransportedItemStack, partialTicks: Float, rendered: Float) {
        if (!enabled) return
        if (!clientDeck(deck)) return
        val tick = deck.level?.gameTime ?: return
        val row = rowOf(deck, transported, tick)
        val first = row.samples == 0
        row.samples++
        row.partial = partialTicks
        row.render = rendered
        row.rmin = if (first || rendered < row.rmin) rendered else row.rmin
        row.rmax = if (first || rendered > row.rmax) rendered else row.rmax
        row.pmin = if (first || partialTicks < row.pmin) partialTicks else row.pmin
        row.pmax = if (first || partialTicks > row.pmax) partialTicks else row.pmax
    }

    private fun rowOf(deck: RollerConveyorBlockEntity, transported: TransportedItemStack, tick: Long): Row {
        val lane = laneOf(transported)
        val identity = System.identityHashCode(transported)
        val existing = rows[keyOf(deck, identity, lane)]
        if (existing != null && existing.tick == tick) return existing
        return addRow(deck, transported, tick)
    }

    private fun addRow(deck: RollerConveyorBlockEntity, transported: TransportedItemStack, tick: Long): Row {
        val lane = laneOf(transported)
        val identity = System.identityHashCode(transported)
        val row = Row(keyOf(deck, identity, lane), deck.blockPos.asLong(), lane, tick)
        row.load = transported
        row.identity = identity
        row.from = transported.beltPosition
        row.to = transported.beltPosition
        row.prev = transported.prevBeltPosition
        row.cur = transported.beltPosition
        row.len = deck.deckLength
        rows[row.key] = row
        while (rows.size > MAX_ROWS) {
            val oldest = rows.keys.firstOrNull() ?: break
            if (oldest == row.key) break
            rows.remove(oldest)
        }
        return row
    }

    // the rows of every earlier tick of this deck go out here, once per game tick and never twice. A row that
    // names a jump always goes out; plain rows stop at the cap, and the rest is counted in one summary line.
    private fun flush(deck: RollerConveyorBlockEntity, tick: Long) {
        val deckKey = deck.blockPos.asLong()
        val iterator = rows.values.iterator()
        var flushedTick = Long.MIN_VALUE
        var kept = 0
        var truncated = 0
        while (iterator.hasNext()) {
            val row = iterator.next()
            if (row.deck != deckKey || row.tick >= tick) continue
            iterator.remove()
            if (flushedTick == Long.MIN_VALUE) flushedTick = row.tick
            val flag = flagOf(row)
            val forced = flag != "ok"
            if (forced || kept < ROWS_PER_TICK) {
                if (!forced) kept++
                emit(row, flag)
            } else {
                truncated++
            }
        }
        if (truncated > 0) {
            CreateWaterparked.LOGGER.debug(
                "[roller pos] t={} deck={} side=client truncated={} kept={}",
                flushedTick, BlockPos.of(deckKey), truncated, kept
            )
        }
    }

    // what this row is: a named event first, then a swing the frame showed that its endpoints cannot explain
    private fun flagOf(row: Row): String {
        if (row.flag.isNotEmpty()) return row.flag
        if (row.samples > 1) {
            val span = row.rmax - row.rmin
            val expect = abs(row.cur - row.prev) * (row.pmax - row.pmin)
            if (span > expect + SPAN_SLACK) return "span"
        }
        return "ok"
    }

    private fun emit(row: Row, flag: String) {
        val advance = row.to - row.from
        val forced = flag != "ok"
        val span = if (row.samples > 1) row.rmax - row.rmin else 0f
        val dpartial = if (row.samples > 1) row.pmax - row.pmin else 0f
        val expect = abs(row.cur - row.prev) * dpartial
        // the load id carries the lane, the object, the deck position it sits at (1/100) and the run length
        val load = row.lane.toString() + "|" + row.identity + "|" + (row.cur * 100f).toInt() + "|" + row.len
        val line = "t=" + row.tick +
            " deck=" + BlockPos.of(row.deck) +
            " side=client lane=" + row.lane +
            " load=" + load +
            " from=" + row.from + " to=" + row.to + " step=" + abs(advance) + " adv=" + advance +
            " limit=" + cap() +
            " prev=" + row.prev + " cur=" + row.cur + " len=" + row.len +
            " partial=" + row.partial + " render=" + row.render +
            " rmin=" + row.rmin + " rmax=" + row.rmax + " samples=" + row.samples +
            " dpartial=" + dpartial + " span=" + span + " expect=" + expect +
            " flag=" + flag + (if (forced) " forced=1" else "")
        // a row whose whole state is the one already printed says nothing new, so it stays quiet
        val state = row.key + "|" + quant(row.from) + "|" + quant(row.to) + "|" + quant(row.prev) +
            "|" + quant(row.cur) + "|" + row.len + "|" + flag
        if (!forced && lastState[row.key] == state) return
        lastState[row.key] = state
        CreateWaterparked.LOGGER.debug("[roller pos] {}", line)
    }

    // the grid the dedupe reads positions on: a millimetre, so real moves still speak and noise does not
    private fun quant(value: Float): Int = (value * 1000f).toInt()
}
