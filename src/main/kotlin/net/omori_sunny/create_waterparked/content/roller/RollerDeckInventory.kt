package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import com.simibubi.create.foundation.utility.BlockHelper
import dev.ryanhcode.sable.Sable
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.util.Mth
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.RollerPayloadTrace
import net.omori_sunny.create_waterparked.config.ModConfig
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// the deck's own item list: a level run carries its loads at its own speed, gravity adds to that off level, and coming level drops it
class RollerDeckInventory(var deck: RollerConveyorBlockEntity) {

    val transportedItems: MutableList<TransportedItemStack> = ArrayList()
    private val toInsert: MutableList<TransportedItemStack> = ArrayList()
    @Suppress("unused")
    private val toRemove: MutableList<TransportedItemStack> = ArrayList()
    var lazyClientItem: TransportedItemStack? = null
    var positiveOrder: Boolean = true
    private var stepTrace = ""
    // the last tick a step row was printed for each side and lane, so the step line cannot flood the log
    private val stepMarks = arrayOf(LongArray(LOWER_LANE + 1), LongArray(LOWER_LANE + 1))
    private var endTrace = ""
    private var backTrace = ""
    // the game tick the last keyframe was written on, so a client can hand the authority the same head start the
    // server had while the packet was on its way
    private var syncTick = 0L
    // the id every load answers to across keyframes, carried in the run's own tag so two loads of the same kind
    // can never answer for each other
    private val loadIds = IdentityHashMap<TransportedItemStack, Int>()
    private var nextLoadId = 1
    // WS68: every diagnostic line on this route hangs off one switch, so a run started with
    // -Dcreatewaterparked.pathdiag=true turns the load lines on; without it the routes stay quiet
    private val pathDiagnostics = System.getProperty("createwaterparked.pathdiag", "false") == "true"
    // WS69: the last game tick this inventory stepped. The block entity can reach one inventory twice in a tick,
    // and every part of this step is stateful - loads advance, a keyframe goes out - so a repeat is dropped here,
    // once per inventory, and counted so the evidence survives the guard.
    private var lastTick = -1L
    private var tickRepeats = 0
    private var dupTrace = ""
    // the car each load follows this tick: the neighbour the run's own spacing rule reads it against, which is the
    // one ahead in the direction the run is travelling
    private val leaderOf = IdentityHashMap<TransportedItemStack, TransportedItemStack>()

    fun tick() {
        // one entry per game tick: a second one would run the whole stateful step twice, so it is dropped and
        // counted. With a single entry a tick the guard changes nothing at all.
        deck.level?.gameTime?.let { now ->
            if (lastTick == now) {
                tickRepeats++
                traceTickRepeat(now)
                return
            }
            lastTick = now
        }
        lazyClientItem?.let { if (it.locked) lazyClientItem = null else it.locked = true }

        if (toInsert.isNotEmpty() || toRemove.isNotEmpty()) {
            toInsert.forEach(::insert)
            toInsert.clear()
            transportedItems.removeAll(toRemove)
            toRemove.clear()
            deck.notifyUpdate()
        }
        // the server runs the load and writes its plan; a client plays that plan back, and that is the whole of
        // the rule between them
        // Create's belt runs the same walk on both sides (BeltInventory:97 onClient): this side steps its own copy of
        // the load list with the same rules, so there is nothing to send and nothing to play back
        serverPath()
    }

    private fun serverPath() {
        if (deck.travelSpeed != 0f) {
            val positive = deck.directionAwareTravelSpeed > 0f
            if (positiveOrder != positive) {
                positiveOrder = positive
                transportedItems.reverse()
                deck.notifyUpdate()
            }
        }
        advance()
    }

    // temporary: one line while the same game tick reaches this inventory more than once, so the guard above hides
    // nothing: the window and the count make the fingerprint stable enough to stay quiet after the first line.
    private fun traceTickRepeat(tick: Long) {
        if (!pathDiagnostics) return
        val window = tick / PATH_TRACE_TICKS
        val line = "${deck.blockPos.asLong()}|$window|$tickRepeats"
        if (line == dupTrace) return
        dupTrace = line
        CreateWaterparked.LOGGER.debug("[inv dup] deck={} tick={} n={}", deck.blockPos, tick, tickRepeats + 1)
    }

    fun canInsertAt(segment: Int): Boolean =
        canInsertAtFromSide(segment, RollerDeckFace.UPPER.side, RollerDeckFace.UPPER.lane)

    // the lane a load on the given face rides in, so callers never spell the two faces out
    fun faceOf(transported: TransportedItemStack): RollerDeckFace = RollerDeckFace.ofLane(laneOf(transported))

    fun canInsertAtFromSide(segment: Int, side: Direction, lane: Int = UPPER_LANE): Boolean {
        if (deck.movementFacing == side.opposite) return false
        var segmentPos = segment.toFloat()
        if (deck.movementFacing != side) segmentPos += .5f
        else if (!positiveOrder) segmentPos += 1f
        return transportedItems.none { isBlocking(segment, side, segmentPos, it, lane) } &&
            toInsert.none { isBlocking(segment, side, segmentPos, it, lane) }
    }

    fun addItem(newStack: TransportedItemStack) {
        toInsert.add(newStack)
        traceLoad("in", newStack, "")
    }

    // one insertion path for both faces, so the lower lane is the upper one mirrored through the deck
    fun insertOn(face: RollerDeckFace, segment: Int, stack: ItemStack, from: Direction?): ItemStack {
        if (!canInsertAtFromSide(segment, face.side, face.lane)) return stack
        val transported = TransportedItemStack(stack)
        (transported as RollerMomentumAccess).`waterparked$setDeckLane`(face.lane)
        transported.insertedAt = segment
        transported.insertedFrom = from
        transported.beltPosition = segment + .5f + (if (positiveOrder) -1 else 1) / 16f
        transported.prevBeltPosition = transported.beltPosition
        addItem(transported)
        deck.setChanged()
        deck.sendData()
        return ItemStack.EMPTY
    }

    fun removeItem(transported: TransportedItemStack) {
        toRemove.add(transported)
    }

    fun getStackAtOffset(offset: Int): TransportedItemStack? {
        val min = offset.toFloat()
        val max = offset + 1f
        for (transported in transportedItems) {
            if (toRemove.contains(transported)) continue
            if (transported.beltPosition > max) continue
            if (transported.beltPosition > min) return transported
        }
        return null
    }

    // a load leaves the way it was travelling, no faster than the deck itself carries one, and true means the world took it
    fun eject(
        transported: TransportedItemStack,
        exitFacing: Direction,
        lane: Int = UPPER_LANE,
        forward: Boolean = exitFacing == deck.deckFacing
    ): Boolean {
        val level = deck.level ?: return false
        val outPos = deck.vectorForOffset(transported.beltPosition)
        // Create's own belt throw, copied: the speed is the belt's own speed with a floor of an eighth, the direction is
        // the line the belt runs, and the stack is nudged clear by a thousandth - never launched into the distance.
        // The belt never tilts, so its own speed is all a load leaves with; ours do, and a fast slider lobbed out at
        // the drive speed alone would fall straight back onto the tilt it left - so the load keeps no less than it
        // was moving by
        val movementSpeed = max(max(abs(deck.runDeckSpeed()), abs(carriedSpeed(transported))), 1 / 8f)
        // Create's chain direction carries the speed sign, so on a belt it is always the way the load itself was
        // moving; our loads can also keep their own slide momentum against the drive, so the sign is read off the
        // load's travel this tick instead of the drive - the one reading that stays right on either face and in a sub-level
        val runSign = if (forward) 1.0 else -1.0
        val localAxis = Vec3.atLowerCornerOf(deck.deckFacing.normal).scale(runSign)
        val face = RollerDeckFace.ofLane(lane)
        val pose = Sable.HELPER.getContaining(level, deck.blockPos)?.logicalPose()
        val worldAxis = if (pose != null) pose.transformNormal(localAxis) else localAxis
        // the hop off the belt is a world up eighth on Create; here it points out of the face the load rides, so an
        // inverted run or the lower lane kicks the stack away from the deck instead of back into it
        val outNormal = Vec3(0.0, face.lift, 0.0)
        val worldNormal = if (pose != null) pose.transformNormal(outNormal) else outNormal
        val outMotion = worldAxis.normalize()
            .scale(movementSpeed.toDouble())
            .add(worldNormal.scale(1 / 8.0))
        // Create's belt, BeltInventory:443 exactly: the stack appears eleven sixteenths above the surface it rode
        // (six sixteenths above the cell centre), here measured off the face so both lanes leave on their own side
        val localLift = face.surface + face.lift * (11.0 / 16.0) - 0.5
        var spawn = outPos.add(0.0, localLift, 0.0)
        // the load is pushed clear along the line the belt runs, never along the face it leaves by: a face normal
        // points down on the lower deck, and pushing that way drops the load under its own rollers
        var clearance = localAxis
        if (pose != null) {
            spawn = pose.transformPosition(spawn)
            // the throw is already world space (it was aimed through the same pose above), so only the spot and the
            // nudge are carried across here
            clearance = pose.transformNormal(clearance)
        }
        // Create nudges the stack a thousandth clear; inside a plot the deck's inside callback fires on a bare cell
        // overlap, so a stack born straddling the end cell is taken straight back on its first move. The push is a
        // full stack width instead, along the run and never along the face it leaves by
        spawn = spawn.add(clearance.normalize().scale(0.15))
        // Create's belt puts the stack exactly on that spot (BeltInventory:443): nothing is lifted, nothing moved
        traceLoad("out", transported, "exit=$exitFacing fwd=$forward lane=$lane len=${deck.deckLength}")
        val entity = ItemEntity(level, spawn.x, spawn.y, spawn.z, transported.stack)
        entity.deltaMovement = outMotion
        // Create's belt does both of these, and both matter here: the pick up delay keeps the stack from being caught
        // again the moment it lands, and hurtMarked tells the client the new velocity straight away
        entity.setDefaultPickUpDelay()
        entity.hurtMarked = true
        level.addFreshEntity(entity)
        return true
    }

    // temporary: reports every load the run takes in and hands out, so an early exit reads straight off the log
    private fun traceLoad(action: String, transported: TransportedItemStack, detail: String) {
        if (!pathDiagnostics) return
        CreateWaterparked.LOGGER.debug(
            "[roller load] {} deck={} pos={} lane={} run={} {}", action, deck.blockPos, transported.beltPosition,
            laneOf(transported), deck.runDeckSpeed(), detail
        )
    }

    // temporary: what the server decides at an end, so a run that ends short names its own numbers
    private fun traceEnd(
        world: Level,
        transported: TransportedItemStack,
        from: Float,
        movement: Float,
        diffToEnd: Float,
        ending: Ending,
        forward: Boolean
    ) {
        if (world.isClientSide) return
        if (!pathDiagnostics) return
        val line = "$ending|${(from * 10).toInt()}|undefined|$forward"
        if (line == endTrace) return
        endTrace = line
        CreateWaterparked.LOGGER.debug(
            "[roller end] deck={} lane={} from={} len={} move={} diff={} ending={} fwd={}",
            deck.blockPos, laneOf(transported), from, deck.deckLength, movement, diffToEnd, ending, forward
        )
    }

    // temporary: one line per distinct step and lane, at most once every STEP_TRACE_TICKS ticks, so a whole run of
    // small changes cannot flood the log while a jump still shows up
    private fun traceStep(transported: TransportedItemStack, from: Float, step: Float, seed: Float) {
        if (!pathDiagnostics) return
        val client = deck.level?.isClientSide == true
        val now = deck.level?.gameTime ?: 0L
        val lane = laneOf(transported)
        val gate = if (client) 1 else 0
        if (now - stepMarks[gate][lane] < STEP_TRACE_TICKS) return
        val line = "${(step * 100).toInt()}|${(seed * 100).toInt()}|${deck.deckLength}|$client|$lane|${now / STEP_TRACE_TICKS}"
        if (line == stepTrace) return
        stepMarks[gate][lane] = now
        stepTrace = line
        CreateWaterparked.LOGGER.debug(
            "[roller step] deck={} side={} lane={} from={} to={} step={} seed={} len={}",
            deck.blockPos, if (client) "client" else "server", laneOf(transported),
            from, transported.beltPosition, step, seed, deck.deckLength
        )
    }

    // temporary: one line while a load counted outside the run is pulled back onto it
    private fun traceBack(transported: TransportedItemStack) {
        if (!pathDiagnostics) return
        val line = transported.beltPosition.toString()
        if (line == backTrace) return
        backTrace = line
        CreateWaterparked.LOGGER.debug(
            "[roller load] back deck={} pos={} len={} lane={}",
            deck.blockPos, transported.beltPosition, deck.deckLength, laneOf(transported)
        )
    }

    // the world takes a load only where the block the spot sits in and the one above it are both free
    private fun freeSpot(level: Level, spot: Vec3): Boolean {
        val pos = BlockPos.containing(spot)
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) return false
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return false
        val above = pos.above()
        if (level.isOutsideBuildHeight(above)) return false
        return level.getBlockState(above).getCollisionShape(level, above).isEmpty
    }

    // a run torn down still hands its loads over, lifting them clear of whatever stands in the way
    private fun liftedSpot(level: Level, spot: Vec3): Vec3 {
        for (step in 1..EJECT_LIFT_STEPS) {
            val lifted = spot.add(0.0, step.toDouble(), 0.0)
            if (freeSpot(level, lifted)) return lifted
        }
        return spot.add(0.0, 1.0, 0.0)
    }

    // a run turned end for end mirrors its loads about the edge its old front sat on
    fun mirrorAbout(pivot: Int) = relocate { pivot - it }

    // a run that only moved its front keeps every load where it was, counted from the new front
    fun shiftBy(offset: Int) = if (offset == 0) Unit else relocate { it + offset }

    private fun relocate(move: (Float) -> Float) {
        for (list in arrayOf(transportedItems, toInsert)) {
            for (transported in list) {
                transported.beltPosition = move(transported.beltPosition)
                transported.prevBeltPosition = move(transported.prevBeltPosition)
                transported.insertedFrom = null
            }
        }
        lazyClientItem?.let {
            it.beltPosition = move(it.beltPosition)
            it.prevBeltPosition = move(it.prevBeltPosition)
        }
        sortItems()
    }

    // a second run joining this one brings its loads along, still at the place they sit on
    fun mergeInto(target: RollerDeckInventory) {
        for (transported in transportedItems) {
            transported.insertedFrom = null
            target.transportedItems.add(transported)
        }
        target.toInsert.addAll(toInsert)
        target.toRemove.addAll(toRemove)
        if (target.lazyClientItem == null) target.lazyClientItem = lazyClientItem
        target.sortItems()
        transportedItems.clear()
        toInsert.clear()
        toRemove.clear()
        lazyClientItem = null
    }

    private fun sortItems() {
        transportedItems.sortByDescending { it.beltPosition }
        if (!positiveOrder) transportedItems.reverse()
    }

    fun ejectAll() {
        val exitFacing = deck.movementFacing
        transportedItems.forEach { eject(it, exitFacing, laneOf(it)) }
        transportedItems.clear()
    }

    // the loads a run carries when it comes back level lose the slope speed gravity gave them, at once
    fun clearSlopeSpeeds() {
        for (list in arrayOf(transportedItems, toInsert)) {
            for (transported in list) (transported as RollerMomentumAccess).`waterparked$setRollerSpeed`(0f)
        }
        deck.notifyUpdate()
    }

    fun applyToEachWithin(position: Float, maxDistanceToPosition: Float, processFunction: (TransportedItemStack) -> TransportedResult?) {
        var dirty = false
        for (transported in transportedItems) {
            if (toRemove.contains(transported)) continue
            val stackBefore = transported.stack.copy()
            if (abs(position - transported.beltPosition) >= maxDistanceToPosition) continue
            val result = processFunction(transported) ?: continue
            if (result.didntChangeFrom(stackBefore)) continue
            dirty = true
            val held = if (result.hasHeldOutput()) result.heldOutput else null
            if (held != null) {
                held.beltPosition = position.toInt() + .5f - (if (positiveOrder) 1 / 512f else -1 / 512f)
                toInsert.add(held)
            }
            toInsert.addAll(result.outputs)
            toRemove.add(transported)
        }
        if (dirty) deck.notifyUpdate()
    }

    // a list read back is put in the order the spacing rule reads it, whatever order the tag was written in    // a list read back is put in the order the spacing rule reads it, whatever order the tag was written in
    fun read(nbt: CompoundTag, registries: HolderLookup.Provider) {
        transportedItems.clear()
        val ids = nbt.getIntArray("LoadIds")
        var index = 0
        nbt.getList("Items", Tag.TAG_COMPOUND.toInt())
            .forEach {
                val transported = TransportedItemStack.read(it as CompoundTag, registries)
                // the id the keyframe brought is the one this load keeps, so the next keyframe answers by it
                if (index < ids.size) loadIds[transported] = ids[index]
                transportedItems.add(transported)
                index++
            }
        nextLoadId = max(nextLoadId, nbt.getInt("NextLoadId"))
        syncTick = nbt.getLong("SyncTick")
        if (nbt.contains("LazyItem")) lazyClientItem = TransportedItemStack.read(nbt.getCompound("LazyItem"), registries)
        positiveOrder = nbt.getBoolean("PositiveOrder")
        sortItems()
    }

    fun write(registries: HolderLookup.Provider): CompoundTag {
        if (toInsert.isNotEmpty() || toRemove.isNotEmpty()) {
            toInsert.forEach(::insert)
            toInsert.clear()
            transportedItems.removeAll(toRemove)
            toRemove.clear()
        }
        pruneLoadIds()
        val nbt = CompoundTag()
        val itemsNBT = ListTag()
        val idsNBT = IntArray(transportedItems.size)
        var idIndex = 0
        for (transported in transportedItems) {
            val itemNBT = transported.serializeNBT(registries)
            itemsNBT.add(itemNBT)
            idsNBT[idIndex++] = idOf(transported)
        }
        nbt.put("Items", itemsNBT)
        nbt.putIntArray("LoadIds", idsNBT)
        nbt.putInt("NextLoadId", nextLoadId)
        nbt.putLong("SyncTick", deck.level?.gameTime ?: 0L)
        lazyClientItem?.let { nbt.put("LazyItem", it.serializeNBT(registries)) }
        nbt.putBoolean("PositiveOrder", positiveOrder)
        return nbt
    }

    private fun isBlocking(segment: Int, side: Direction, segmentPos: Float, transported: TransportedItemStack, lane: Int): Boolean {
        val currentPos = transported.beltPosition
        if (laneOf(transported) != lane) return false
        if (transported.insertedAt != segment || transported.insertedFrom != side) return false
        return if (positiveOrder) currentPos <= segmentPos + 1 else currentPos >= segmentPos - 1
    }
    private fun insert(newStack: TransportedItemStack) {
        // Create's insert does nothing of its own here: no uuid stamp, no debounce window, no refusal
        idOf(newStack)
        if (transportedItems.isEmpty()) {
            transportedItems.add(newStack)
            return
        }
        var slot = 0
        for (transported in transportedItems) {
            if ((transported.compareTo(newStack) > 0) == positiveOrder) break
            slot++
        }
        transportedItems.add(slot, newStack)
    }
    // every load advances by its own plan, a load held or waiting behind another stays put, and a tilted deck adds
    // its pull to the plan exactly as it did to the step
    private fun advance() {
        val world = deck.level ?: return
        deck.hingeTilted()
        // temporary: the client records where each of its own loads stood before this step
        RollerPayloadTrace.beforeAdvance(deck)
        // WS71h: the car ahead of every load is the neighbour the run's own spacing rule reads, which is the one
        // ahead in the direction the run travels and not simply the one before it in the list. The pair is read by
        // place, exactly as the old step read its higher and lower neighbours, so a list in any other order cannot
        // make a load follow the car behind it - which, now that a floor is planned from this, would walk it
        // backwards.
        leaderOf.clear()
        // the pull is a property of the run, not of a load: gravity along the deck is read once a tick, so a run of
        // N loads costs one sub level query instead of N
        val slideAccel = slideAcceleration(world)
        val forward = deck.directionAwareTravelSpeed > 0f
        var previous: TransportedItemStack? = null
        for (transported in transportedItems) {
            val last = previous
            if (last != null && laneOf(last) == laneOf(transported)) {
                val ahead = if (forward) last.beltPosition >= transported.beltPosition
                else last.beltPosition <= transported.beltPosition
                if (ahead) leaderOf[transported] = last else leaderOf[last] = transported
            }
            previous = transported
        }
        var ended = false
        val iterator = transportedItems.iterator()
        while (iterator.hasNext()) {
            val transported = iterator.next()
            val lane = laneOf(transported)
            transported.prevBeltPosition = transported.beltPosition
            transported.prevSideOffset = transported.sideOffset
            if (transported.stack.isEmpty) {
                iterator.remove()
                ended = true
                continue
            }
            if (transported.lockedExternally) {
                transported.lockedExternally = false
                // a load the world holds still does not step, so the cars behind it are told where it stands
                // rather than left without a floor at all
                                continue
            }
            val left = trajStep(world, transported, lane, leaderOf[transported], slideAccel)
            if (left) {
                iterator.remove()
                ended = true
            }
        }
        // a load that really left the chain is an event and goes out at once; one that only moved waits for the
        // next keyframe, since both sides compute it themselves
        RollerPayloadTrace.afterAdvance(deck)
        if (ended || transportedItems.isNotEmpty()) deck.syncRun(ended)
    }

    // does - the whole way from where it stands to the end, the gap to the car ahead, a hook that takes it and a
    // tilted pull included - and every world effect stays exactly where it was: the end is read on the tick the
    // plan names, and the deck hands the load out itself.
    // Create has no plan and no tables (BeltInventory:115-232): the walk of one load is one call, and the car in
    // front is the one the caller already found in the direction of travel. The old plan walk stays below, unused,
    // until it is deleted outright.
    private fun trajStep(
        world: Level,
        transported: TransportedItemStack,
        lane: Int,
        leader: TransportedItemStack?,
        slideAccel: Float
    ): Boolean {
        val forward = deck.directionAwareTravelSpeed > 0f
        return advanceItem(
            world, transported,
            if (forward) leader else null,
            if (forward) null else leader,
            deck.deckFacing, slideAccel, lane
        )
    }

    // the belt term and the gravity term are added in one place here, and a true answer means the load left the chain
    private fun advanceItem(
        world: Level,
        transported: TransportedItemStack,
        higher: TransportedItemStack?,
        lower: TransportedItemStack?,
        runFacing: Direction,
        slideAccel: Float,
        lane: Int
    ): Boolean {
        val carried = carriedSpeed(transported)
        val sliding = slideAccel != 0f
        val belt = deck.loadBeltSpeed(carried, sliding)
        var movement = belt + if (sliding) carried else 0f
        val currentPos = transported.beltPosition
        // a load counted outside the run is pulled back onto it, so it can only ever leave at a real end
        if (currentPos < 0f || currentPos > deck.deckLength) {
            transported.beltPosition = currentPos.coerceIn(0f, deck.deckLength.toFloat())
            transported.prevBeltPosition = transported.beltPosition
            traceBack(transported)
            return false
        }
        var blocked = false
        val front = if (movement > 0f) higher else lower
        if (front != null) {
            val diff = front.beltPosition - currentPos
            if (abs(diff) <= SPACING) blocked = true
            else movement = if (movement > 0f) min(movement, diff - SPACING) else max(movement, diff + SPACING)
        }
        if (blocked) {
            if (!world.isClientSide && handleProcessing(transported, currentPos, true)) return true
            return false
        }
        val step = RollerMomentum.speedLimit()
        movement = movement.coerceIn(-step, step)
        val forward = movement > 0f
        val exitFacing = if (forward) runFacing else runFacing.opposite
        var ending = Ending.UNRESOLVED
        var diffToEnd = if (forward) deck.deckLength - currentPos else -currentPos
        if (abs(diffToEnd) < abs(movement) + 1f) {
            ending = resolveEnding(world, forward, exitFacing)
            diffToEnd += if (forward) -ending.margin else ending.margin
            traceEnd(world, transported, currentPos, movement, diffToEnd, ending, forward)
        }
        val limited = if (forward) min(movement, diffToEnd) else max(movement, diffToEnd)
        // the end clamp can turn the step around on its own (a blocked or inserting end reaches margin deep), so
        // the cap is applied to the very delta that is written, after every other term has had its say
        val advance = limited.coerceIn(-step, step)
        val nextOffset = currentPos + advance
        if (!world.isClientSide && handleProcessing(transported, nextOffset, false)) return true
        if (transported.locked) return false
        val pressed = limited != movement && ending != Ending.UNRESOLVED
        if (slideAccel != 0f) accumulateSlide(transported, carried, slideAccel, pressed)
        transported.beltPosition = nextOffset
        traceStep(transported, currentPos, advance, carried)
        val diffToMiddle = transported.targetSideOffset - transported.sideOffset
        transported.sideOffset += Mth.clamp(diffToMiddle * abs(limited) * 6f, -abs(diffToMiddle), abs(diffToMiddle))
        if (limited == movement) return false
        // the server owns leaving, so a client copy can never hand a load out at a shorter end of its own
        if (world.isClientSide) return false
        if (ending == Ending.EJECT) {
            if (!reachedEnd(nextOffset, forward)) return false
            return eject(transported, exitFacing, lane, forward)
        }
        if (ending != Ending.INSERT) return false
        return insertAtEnd(world, transported, forward, exitFacing)
    }

    // a load leaves only from the controller's own end, and that controller owns the list it sits in
    private fun reachedEnd(nextOffset: Float, forward: Boolean): Boolean =
        if (forward) nextOffset >= deck.deckLength - END_EPSILON else nextOffset <= END_EPSILON

    // a run ends in the cell past its last segment, whatever lane its load rides in
    private fun endPosition(forward: Boolean): BlockPos =
        deck.positionForOffset(if (forward) deck.deckLength else -1)

    // the keyframe is the only place a client is told where its own loads really are. The copy this side keeps is
    // carried over from where it was drawn, so the renderer lerps it into place; a pair further apart than one
    // window is refused whether it came from an id or from a match by place, and the keyframe's own copy then
    // arrives at the authority's place. A copy that lags the run is never walked forward: only a brake is applied.    // the keyframe is the only place a client is told where its own loads really are, and with a plan on the wire
    // the plan itself is that answer: what the packet carries is played, so nothing here corrects anything. What is
    // left of the two sided pairing is the id bookkeeping a running copy needs, and that is all this did.
    // arrives, so the two sides can never mint the same number for two different loads.
    private fun idOf(transported: TransportedItemStack): Int {
        loadIds[transported]?.let { return it }
        if (deck.level?.isClientSide != false) return 0
        val id = nextLoadId++
        loadIds[transported] = id
        return id
    }

    // an id follows the load it was taken for: once a load is gone from the list, its entry goes with it, so the
    // map never holds more keys than the run carries loads plus the ones waiting to be let in. Both callers are
    // rare (a keyframe rebuild and a sync write), so the live set is rebuilt every time instead of being guessed.
    private fun pruneLoadIds() {
        val live = IdentityHashMap<TransportedItemStack, Boolean>()
        for (transported in transportedItems) live[transported] = true
        for (transported in toInsert) live[transported] = true
        lazyClientItem?.let { live[it] = true }
        loadIds.keys.retainAll(live.keys)
    }

    // the momentum a load may keep, whatever speed it landed with and whatever an older save wrote down
    private fun carriedSpeed(transported: TransportedItemStack): Float {
        val landing = (transported as RollerMomentumAccess).`waterparked$rollerSpeed`()
        if (landing == RollerMomentum.NO_LANDING) return 0f
        val limit = RollerMomentum.speedLimit()
        return landing.coerceIn(-limit, limit)
    }

    // the lane an item rides in: the lower one only exists while the run is assembled
    private fun laneOf(transported: TransportedItemStack): Int =
        (transported as RollerMomentumAccess).`waterparked$deckLane`().coerceIn(UPPER_LANE, LOWER_LANE)

    // a hinged deck has no drive of its own, so gravity moves its loads, always read along the deck's own facing
    private fun slideAcceleration(world: Level): Float {
        val gravity = RollerDeckGravity.localGravity(deck.plotPose())
        return RollerDeckGravity.accelerationAlong(gravity, deck.deckFacing) * ModConfig.rollerHingeSlideScale().toFloat()
    }



    // the pull is in blocks per second squared while the stored speed is per tick, so a tick adds a * dt * dt; only a blocked end, a load ahead or a held load skips it, since a load at rest must still start
    private fun accumulateSlide(transported: TransportedItemStack, carried: Float, acceleration: Float, pressed: Boolean) {
        if (pressed) return
        val step = acceleration * SLIDE_STEP_SECONDS * SLIDE_STEP_SECONDS
        (transported as RollerMomentumAccess).`waterparked$setRollerSpeed`(Mth.clamp(carried + step, -SLIDE_SPEED_LIMIT, SLIDE_SPEED_LIMIT))
    }

    private fun resolveEnding(world: Level, forward: Boolean, exitFacing: Direction): Ending {
        val nextPosition = endPosition(forward)
        val input = BlockEntityBehaviour.get(world, nextPosition, DirectBeltInputBehaviour.TYPE)
        if (input != null) return Ending.INSERT
        val state = world.getBlockState(nextPosition)
        if (BlockHelper.hasBlockSolidSide(state, world, nextPosition, exitFacing.opposite)) return Ending.BLOCKED
        return Ending.EJECT
    }

    private fun insertAtEnd(world: Level, transported: TransportedItemStack, forward: Boolean, exitFacing: Direction): Boolean {
        val nextPosition = endPosition(forward)
        val input = BlockEntityBehaviour.get(world, nextPosition, DirectBeltInputBehaviour.TYPE) ?: return false
        if (!input.canInsertFromSide(exitFacing)) return false
        val remainder = input.handleInsertion(transported, exitFacing, false)
        if (ItemStack.matches(remainder, transported.stack)) return false
        transported.stack = remainder
        return remainder.isEmpty
    }

    // funnels, depots and arms reach a load on the deck through the behaviours they place above it
    private fun handleProcessing(transported: TransportedItemStack, nextOffset: Float, noMovement: Boolean): Boolean {
        if (laneOf(transported) != UPPER_LANE) return false
        if (deck.gravityOnly()) return false
        val segment = transported.beltPosition.toInt()

        if (transported.locked) {
            val processingBehaviour = processingAt(segment)
            val stackHandlerBehaviour = handlerAt(segment)
            if (stackHandlerBehaviour == null) return false
            if (processingBehaviour == null) {
                transported.locked = false
                deck.notifyUpdate()
                return false
            }
            val result = processingBehaviour.handleHeldItem(transported, stackHandlerBehaviour)
            if (result == BeltProcessingBehaviour.ProcessingResult.REMOVE) return true
            if (result == BeltProcessingBehaviour.ProcessingResult.HOLD) return false
            transported.locked = false
            deck.notifyUpdate()
            return false
        }

        if (noMovement) return false
        if (transported.beltPosition <= .5f && !positiveOrder) return false

        val firstUpcomingSegment = (transported.beltPosition + (if (positiveOrder) .5f else -.5f)).toInt()
        val step = if (positiveOrder) 1 else -1
        var segmentCursor = firstUpcomingSegment
        while (if (positiveOrder) segmentCursor + .5f <= nextOffset else segmentCursor + .5f >= nextOffset) {
            val processingBehaviour = processingAt(segmentCursor)
            val stackHandlerBehaviour = handlerAt(segmentCursor)
            if (processingBehaviour != null && stackHandlerBehaviour != null &&
                !BeltProcessingBehaviour.isBlocked(deck.level, deck.positionForOffset(segmentCursor))
            ) {
                val result = processingBehaviour.handleReceivedItem(transported, stackHandlerBehaviour)
                if (result == BeltProcessingBehaviour.ProcessingResult.REMOVE) return true
                if (result == BeltProcessingBehaviour.ProcessingResult.HOLD) {
                    transported.beltPosition = segmentCursor + .5f + (if (positiveOrder) 1 / 512f else -1 / 512f)
                    transported.locked = true
                    deck.notifyUpdate()
                    return false
                }
            }
            segmentCursor += step
        }
        return false
    }

    private fun processingAt(segment: Int): BeltProcessingBehaviour? =
        deck.level?.let { BlockEntityBehaviour.get(it, deck.positionForOffset(segment).above(2), BeltProcessingBehaviour.TYPE) }

    private fun handlerAt(segment: Int): TransportedItemStackHandlerBehaviour? =
        deck.level?.let { BlockEntityBehaviour.get(it, deck.positionForOffset(segment), TransportedItemStackHandlerBehaviour.TYPE) }

    private enum class Ending(val margin: Float) {
        UNRESOLVED(0f),
        EJECT(0f),
        INSERT(0.25f),
        BLOCKED(0.45f)
    }

    companion object {
        const val UPPER_LANE = 0
        const val LOWER_LANE = 1
        // the lower lane sits half a block under the top surface, mirroring the load height above it
        const val LOWER_LANE_DROP = 8.0 / 16.0
        private const val SPACING = 1f
        // how many ticks the path lines wait between prints
        private const val PATH_TRACE_TICKS = 40L
        // how many ticks the step line waits between prints, per side and lane
        private const val STEP_TRACE_TICKS = 10L
        // how close to an end a load has to sit before it may leave the run
        private const val END_EPSILON = 0.001f
        private const val SLIDE_STEP_SECONDS = 1f / 20f
        // the fastest a load may slide, in blocks per tick, clamped on the signed speed
        private const val SLIDE_SPEED_LIMIT = 1f
        private const val EJECT_LIFT_STEPS = 3
    }
}
