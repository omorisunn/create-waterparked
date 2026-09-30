package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.content.kinetics.base.IRotate
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs
import kotlin.math.atan2
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.registry.ModBlocks
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.UUID

// a hinged run leaves the world: the shaft stays behind and the assembled body turns about it every tick
object RollerHingeTilt {

    private const val RECORD = "waterparked_hinge"
    private const val DEGREES_PER_RPM_TICK = 6.0 / 20.0
    // the physics reads its swing in rad/s, and the belt carries it in blocks per tick
    private const val TICKS_PER_SECOND = 20.0
    private const val ASSEMBLE_RETRY_TICKS = 20
    private const val HEAL_TICKS = 20
    private const val BLOCK_MARGIN = 16
    private const val RECORD_SCAN_TICKS = 20L
    // one debug line per hundred ticks shows whether a run is being driven
    private const val SHAFT_LOG_TICKS = 100L
    // only used when the plot container cannot answer: plots are saved millions of blocks out
    private const val PLOT_COORD_LIMIT = 1 shl 20
    // negative when the deck swings against the shaft it is driven by
    private const val ANGLE_SIGN = 1.0
    // the pin only steps in when physics really moved the run away from the pose it was given
    private const val PIN_EPSILON = 1.0 / 1024.0
    private const val PIN_ROT_EPSILON = 1.0E-4

    private class Run {
        var basePos: Vector3d? = null
        var baseRot: Quaterniond? = null
        var axis: Vector3d? = null
        var pivot: Vector3d? = null
        var baseCom: Vector3d? = null
        var angle = 0f
        var pinnedPos: Vector3d? = null
        var pinnedRot: Quaterniond? = null
        var healTicks = 0
        var loggedDiag = ""
        var lastDiagTick = -100L
        var reported = ""
        var entryDiag = ""
        // the latch the run really sits in: a locked run follows its shaft, an unlocked one hangs on its bearing
        var locked = true
        // an unlocked run whose bearing was refused stays on its angle drive and is never pinned by hand
        var fallback = false
        // the one bearing the run may hold, up to the moment the engine or its own unlock takes it back
        var constraint: RotaryConstraintHandle? = null
    }

    private val runs = HashMap<UUID, Run>()
    private val assembleCooldown = HashMap<BlockPos, Long>()
    private var disassembling = false

    // the plot controller carries the run's own copy of the record, so a lost tag can be rebuilt from it
    private fun recordOf(sub: ServerSubLevel, deck: RollerConveyorBlockEntity, controller: BlockPos): CompoundTag {
        val record = CompoundTag()
        record.putString("UUID", sub.uniqueId.toString())
        record.put("Controller", NbtUtils.writeBlockPos(controller))
        record.put("WorldPivot", NbtUtils.writeBlockPos(deck.hingeWorldPivot ?: controller))
        record.putString("Facing", (deck.hingeFacing ?: deck.deckFacing).serializedName)
        // the block state is what the player sees, so it decides where the hinge is
        val marker = deck.level?.let { hingeMarker(it, deck) }
        record.putString("Side", (marker?.second ?: deck.hingeSide).serializedName)
        record.putInt("Index", marker?.third ?: deck.hingeIndex)
        record.putFloat("Angle", deck.hingeAngle)
        return record
    }

    // a run whose record went missing is found again from the hinge state its plot controller still carries
    private fun hingedDeckInPlot(level: ServerLevel, sub: ServerSubLevel): RollerConveyorBlockEntity? {
        val plot = sub.plot
        val accessor = plot.embeddedLevelAccessor
        val bounds = plot.boundingBox
        for (x in bounds.minX()..bounds.maxX()) {
            for (y in bounds.minY()..bounds.maxY()) {
                for (z in bounds.minZ()..bounds.maxZ()) {
                    val deck = accessor.getBlockEntity(BlockPos(x, y, z)) as? RollerConveyorBlockEntity ?: continue
                    if (deck.isController && deck.hingeSub.isNotEmpty()) return deck
                }
            }
        }
        return null
    }

    // temporary: the entry state of every input, so a run that never tilts names its own null
    private fun diagEntry(
        sub: ServerSubLevel,
        run: Run,
        record: CompoundTag,
        controller: BlockPos?,
        worldPivot: BlockPos?,
        facing: Direction?,
        side: RollerHinge.Side?,
        deck: RollerConveyorBlockEntity?,
        handleOk: Boolean,
        physics: Boolean
    ) {
        val fingerprint = "${controller != null}|${worldPivot != null}|${facing != null}|${side != null}|" +
            "${deck != null}|$handleOk|$physics|${record.size()}|${deck?.hingeSide}"
        if (fingerprint == run.entryDiag) return
        run.entryDiag = fingerprint
        CreateWaterparked.LOGGER.debug(
            "[roller hinge entry] sub={} controller={} worldPivot={} facing={} side={} deck={} handle={} physics={} keys={} deckSide={}",
            sub.uniqueId.toString().take(8), controller, worldPivot, facing, side, deck != null,
            handleOk, physics, record.size(), deck?.hingeSide
        )
    }

    // one error per run per state, so a stuck run explains itself without flooding the log
    private fun report(run: Run, sub: ServerSubLevel, message: String, vararg args: Any?): Boolean {
        if (message == run.reported) return false
        run.reported = message
        CreateWaterparked.LOGGER.error("[roller hinge] run {} $message", sub.uniqueId, *args)
        return false
    }

    // rivet parity with WaterslideRivetSpawner.hold: one absolute pose per tick, COM compensation, cleared velocities
    private fun hold(sub: ServerSubLevel, run: Run, pivot: Vector3d, axis: Vector3d, angle: Float): Boolean {
        val basePos = run.basePos ?: return false
        val baseRot = run.baseRot ?: return false
        val rotation = Quaterniond().rotationAxis(Math.toRadians(angle.toDouble()), axis.x, axis.y, axis.z)
        val offset = Vector3d(basePos).sub(pivot)
        rotation.transform(offset)
        val q = rotation.mul(baseRot, Quaterniond())
        val world = Vector3d(pivot).add(offset)
        // block edits inside the plot shift the body-local COM; physics keeps the world COM fixed, so
        // compensate the anchor the same way the rivet does
        val baseCom = run.baseCom
        val comNow = comOf(sub)
        if (baseCom != null && comNow != null) {
            val drift = Vector3d(comNow).sub(baseCom)
            if (drift.lengthSquared() > 1.0E-12) {
                q.transform(drift)
                world.add(drift)
            }
        }
        val handle = RigidBodyHandle.of(sub)
            ?: return report(run, sub, "has no physics handle, so it cannot be held")
        if (!handle.isValid()) return report(run, sub, "has an invalid physics handle, so it cannot be held")
        handle.teleport(world, q)
        // cancel whatever gravity accumulated between the teleports
        val linear = handle.getLinearVelocity(Vector3d())
        val angular = handle.getAngularVelocity(Vector3d())
        handle.addLinearAndAngularVelocity(linear.negate(), angular.negate())
        run.pinnedPos = world
        run.pinnedRot = q
        return true
    }

    // the run is pinned again on every physics substep, so physics never gets to move it between game ticks
    @JvmStatic
    fun pinRun(sub: ServerSubLevel) {
        val run = runs[sub.uniqueId] ?: return
        // an unlocked run hangs on its bearing and a fallback run on nothing at all, so neither is pinned here
        if (!run.locked) return
        val pivot = run.pivot ?: return
        val axis = run.axis ?: return
        val handle = RigidBodyHandle.of(sub) ?: return
        if (!handle.isValid()) return
        val linear = handle.getLinearVelocity(Vector3d())
        val angular = handle.getAngularVelocity(Vector3d())
        if (linear.lengthSquared() > 1.0E-12 || angular.lengthSquared() > 1.0E-12) {
            handle.addLinearAndAngularVelocity(linear.negate(), angular.negate())
        }
        if (!drifted(sub, run)) return
        hold(sub, run, pivot, axis, run.angle)
    }

    // the pin wakes up only when the body really left the pose the run was last given
    private fun drifted(sub: ServerSubLevel, run: Run): Boolean {
        val pinned = run.pinnedPos ?: return true
        val pose = sub.logicalPose()
        if (pose.position().distanceSquared(pinned) > PIN_EPSILON * PIN_EPSILON) return true
        val rot = run.pinnedRot ?: return true
        return abs(pose.orientation().dot(rot)) < 1.0 - PIN_ROT_EPSILON
    }

    // a wrench on a run flips its latch, and only a run that left the world has one to flip: a run still in the
    // world keeps the wrench it always had, so the click goes back to the default interaction. The driver on the
    // world shaft builds or drops the bearing on its next tick, so one click never has two owners.
    @JvmStatic
    fun toggleLock(level: Level, pos: BlockPos): Boolean {
        val segment = level.getBlockEntity(pos) as? RollerConveyorBlockEntity ?: return false
        val linked = segment.controllerPosition()?.let { level.getBlockEntity(it) }
        val head = linked as? RollerConveyorBlockEntity ?: segment
        if (!head.hingeAssembled()) return false
        if (level.isClientSide) return true
        head.setHingeLocked(!head.hingeLocked)
        return true
    }

    // the shaft a run left behind flips the very same latch its deck does, and only a run that is still assembled
    // has one to flip: a shaft with no run in the world hands the click back instead of swallowing it. The server
    // owns the answer, so a client never guesses a latch it cannot see.
    @JvmStatic
    fun toggleLockAtShaft(level: Level, pos: BlockPos): Boolean {
        val server = level as? ServerLevel ?: return false
        val shaft = server.getBlockEntity(pos) as? RollerWorldShaftBlockEntity ?: return false
        val id = shaft.runId ?: return false
        val sub = SubLevelContainer.getContainer(server)?.getSubLevel(id) as? ServerSubLevel ?: return false
        val tag = sub.userDataTag ?: CompoundTag()
        val record = tag.getCompound(RECORD)
        var deck = NbtUtils.readBlockPos(record, "Controller").orElse(null)?.let { plotDeckOf(server, sub, it) }
        if (deck == null) deck = hingedDeckInPlot(server, sub)
        if (deck == null || !deck.hingeAssembled()) return false
        deck.setHingeLocked(!deck.hingeLocked)
        return true
    }

    // the latch the driver last applied to a run, read back by its own world shaft so both name one state
    @JvmStatic
    fun latchOf(sub: ServerSubLevel): Pair<Boolean, Boolean> {
        val run = runs[sub.uniqueId] ?: return true to false
        return run.locked to run.fallback
    }

    // a run that is gone hands nothing back to the engine, and keeps nothing of its own either
    @JvmStatic
    fun forgetRun(id: UUID) {
        val run = runs.remove(id) ?: return
        forgetBearing(run)
    }

    private fun forgetRun(sub: ServerSubLevel) {
        forgetRun(sub.uniqueId)
    }

    // the bearing goes back to the engine in the very place a run is dropped, so no run holds two of them and
    // no body walks off with one still attached
    private fun forgetBearing(run: Run) {
        val bearing = run.constraint ?: return
        run.constraint = null
        runCatching { bearing.remove() }
    }

    // a bearing the engine no longer knows (its body removed, its scene dropped) is not a bearing
    private fun bearingHolds(run: Run): Boolean {
        val bearing = run.constraint ?: return false
        return runCatching { bearing.isValid() }.getOrDefault(false)
    }

    // the latch the run really sits in, applied here and nowhere else: a locked run is pinned to its shaft, an
    // unlocked one is handed one rotary bearing between the world and its own body. The switch is one tick
    // wide, the old bearing out before the new one is asked for, and the deck's own flag is what asks.
    private fun applyLatch(level: ServerLevel, sub: ServerSubLevel, deck: RollerConveyorBlockEntity?, run: Run) {
        val want = deck?.hingeLocked ?: run.locked
        if (run.locked == want) return
        run.locked = want
        if (want) {
            forgetBearing(run)
            run.fallback = false
        } else {
            unlock(level, sub, run)
        }
        deck?.setHingeFallback(!want && run.fallback)
    }

    // the run is let loose: any old bearing goes first, the body is no longer pinned, and one bearing is asked
    // for. A bearing the engine will not hand over falls the run back to its angle drive at once.
    private fun unlock(level: ServerLevel, sub: ServerSubLevel, run: Run) {
        forgetBearing(run)
        run.fallback = false
        val reason = buildBearing(level, sub, run)
        if (reason == null) return
        run.fallback = true
        CreateWaterparked.LOGGER.debug("[roller hinge] run {} stays on its angle drive: {}", sub.uniqueId, reason)
    }

    // the bearing itself: the world holds the hinge point the shaft stands at, and the body turns about the
    // width axis running through it. The world side is a level coordinate and the body side the same point read
    // in the plot, which is the frame a sub-level body is posed in.
    private fun buildBearing(level: ServerLevel, sub: ServerSubLevel, run: Run): String? {
        val pivot = run.pivot ?: return "the run has no hinge point"
        val axis = run.axis ?: return "the run has no hinge axis"
        val pipeline = SubLevelPhysicsSystem.get(level)?.pipeline ?: return "the level has no physics pipeline"
        val pose = sub.logicalPose()
        val anchor = Vector3d(pivot)
        val local = pose.transformPositionInverse(Vector3d(pivot))
        val worldAxis = Vector3d(axis).normalize()
        val localAxis = pose.transformNormalInverse(Vector3d(axis)).normalize()
        val bearing = runCatching {
            pipeline.addConstraint(null, sub, RotaryConstraintConfiguration(anchor, local, worldAxis, localAxis))
        }.getOrElse { error -> return "the engine refused the bearing (" + error.message + ")" }
        if (bearing == null) return "the engine did not take the bearing"
        if (!runCatching { bearing.isValid() }.getOrDefault(false)) return "the engine dropped the bearing at once"
        run.constraint = bearing
        return null
    }

    // the angle a run really swung to, read off the pose it holds: the turn about the hinge axis since the run
    // was assembled. One rotation reads as two opposite quaternions, so the short way round is the one measured.
    private fun swungAngle(sub: ServerSubLevel, run: Run, axis: Vector3d): Float {
        val base = run.baseRot ?: return run.angle
        val delta = Quaterniond(sub.logicalPose().orientation()).mul(Quaterniond(base).conjugate())
        if (delta.w < 0.0) delta.set(-delta.x, -delta.y, -delta.z, -delta.w)
        val along = delta.x * axis.x + delta.y * axis.y + delta.z * axis.z
        return Math.toDegrees(2.0 * atan2(along, delta.w)).toFloat()
    }

    // the belt a swinging run carries, in blocks per tick: the swing rate about the width axis in rad/s over the
    // lever arm the hinge sits at, divided into ticks. The sign is applied here once, by the signed rate the
    // projection keeps, so an unlocked run travels the way a locked one does for the same sense of turn.
    private fun measuredSwing(sub: ServerSubLevel, axis: Vector3d, pivotIndex: Int): Float {
        val handle = RigidBodyHandle.of(sub) ?: return 0f
        if (!handle.isValid()) return 0f
        val omega = handle.getAngularVelocity(Vector3d())
        val rate = omega.x * axis.x + omega.y * axis.y + omega.z * axis.z
        return (rate * pivotIndex / TICKS_PER_SECOND).toFloat()
    }

    // the run has to be pinned on the assembly tick itself, or gravity already has a tick of free fall
    private fun holdAtOnce(sub: ServerSubLevel) {
        val handle = RigidBodyHandle.of(sub)
        if (handle == null || !handle.isValid()) {
            CreateWaterparked.LOGGER.error("[roller hinge] assembled run {} has no handle to pin it", sub.uniqueId)
            return
        }
        val pose = sub.logicalPose()
        handle.teleport(pose.position(), pose.orientation())
        val linear = handle.getLinearVelocity(Vector3d())
        val angular = handle.getAngularVelocity(Vector3d())
        handle.addLinearAndAngularVelocity(linear.negate(), angular.negate())
    }

    // temporary: one line per state change, so a run that never tilts names its own cause
    private fun diag(
        level: ServerLevel,
        run: Run,
        sub: ServerSubLevel,
        deck: RollerConveyorBlockEntity?,
        rpm: Float,
        angle: Float,
        held: Boolean
    ) {
        val physics = SubLevelPhysicsSystem.get(level) != null
        val fingerprint = "$rpm|${(angle / 5f).toInt()}|$held|${deck != null}|$physics"
        if (fingerprint == run.loggedDiag) return
        if (level.gameTime - run.lastDiagTick < 20L) return
        run.loggedDiag = fingerprint
        run.lastDiagTick = level.gameTime
        CreateWaterparked.LOGGER.debug(
            "[roller hinge diag] sub={} rpm={} angle={} held={} deck={} physics={}",
            sub.uniqueId.toString().take(8), rpm, angle, held, deck != null, physics
        )
    }

    @JvmStatic
    fun tryAssemble(level: Level, deck: RollerConveyorBlockEntity) {
        if (level !is ServerLevel) return
        if (level.isClientSide) return
        val now = level.gameTime
        pruneCooldowns(level, now)
        val key = deck.blockPos.immutable()
        if ((assembleCooldown[key] ?: 0L) > now) return
        val side = deck.hingeSide
        val pivot = deck.hingePivot
        if (side == RollerHinge.Side.NONE || pivot == null) {
            assembleCooldown.remove(key)
            return
        }
        val facing = deck.deckFacing
        val face = side.face(facing) ?: return
        val pivotIndex = (pivot.x - deck.blockPos.x) * facing.stepX + (pivot.z - deck.blockPos.z) * facing.stepZ
        // the deck carries loads along its whole run, so the gather takes the run and the hinge limit only asks for a pivot
        val runLength = RollerDeck.segments(level, deck.blockPos, facing).size
        val limit = runLength + BLOCK_MARGIN
        // temporary: the world pivot has to stay where the player left it, so watch that cell
        diagPivotCell(level, "before", pivot)
        val gathered = SubLevelAssemblyHelper.gatherConnectedBlocks(
            deck.blockPos, level, limit
        ) { _, _, _, state, direction ->
            state.block is RollerConveyorBlock &&
                direction != null && direction.axis == facing.axis &&
                state.getValue(RollerConveyorBlock.HORIZONTAL_FACING) == facing
        }
        if (gathered.assemblyState() != SubLevelAssemblyHelper.GatherResult.State.SUCCESS) {
            assembleCooldown[key] = now + ASSEMBLE_RETRY_TICKS
            return
        }
        // the whole run leaves the world, pivot cell included, so the deck has no hole in it
        val blocks = gathered.blocks() ?: return
        if (pivot !in blocks) {
            CreateWaterparked.LOGGER.debug("[roller hinge diag] run {} does not gather its pivot {}", deck.blockPos, pivot)
            assembleCooldown[key] = now + ASSEMBLE_RETRY_TICKS
            return
        }
        val bounds = gathered.boundingBox() ?: return
        val pivotState = level.getBlockState(pivot)
        val assembled = RollerSubLevelScope.around {
            runCatching { SubLevelAssemblyHelper.assembleBlocks(level, pivot, blocks, bounds) }
        }
        val sub = assembled.getOrElse { error ->
            CreateWaterparked.LOGGER.error("[roller hinge] assembly failed at {}", deck.blockPos, error)
            assembleCooldown[key] = now + ASSEMBLE_RETRY_TICKS
            return
        }
        assembleCooldown.remove(key)
        CreateWaterparked.LOGGER.debug(
            "[roller hinge diag] assembled {} blocks={} pivot={} anchor={} plotCenter={}",
            deck.blockPos, blocks.size, pivot, pivot, sub.plot.centerBlock
        )
        diagPivotCell(level, "after", pivot)
        val offset = sub.plot.centerBlock.subtract(deck.blockPos)
        val plotController = deck.blockPos.offset(offset.x, offset.y, offset.z)
        val plotPivot = pivot.offset(offset.x, offset.y, offset.z)
        val plotDeck = plotDeckOf(level, sub, plotController)
        if (plotDeck == null) {
            CreateWaterparked.LOGGER.error(
                "[roller hinge] assembled run at {} has no plot controller {}, laying it back down flat",
                deck.blockPos, plotController
            )
            // nothing here can carry the run, so it goes back down flat
            removeWorldShaft(level, pivot)
            layDownWorld(level, facing, pivotIndex, blocks.size, pivot)
            releaseSub(level, sub)
            assembleCooldown[key] = now + ASSEMBLE_RETRY_TICKS
            return
        }
        val chain = plotChain(blocks, offset, facing, pivot)
        val live = sub as? RollerSubLevel
        live?.setRun(plotController, plotPivot, side, facing, pivotIndex, chain)
        // the pivot is part of the chain now, so a claimed run never carries a hole
        if (live != null && !live.carries(plotPivot)) {
            CreateWaterparked.LOGGER.debug("[roller hinge diag] pivot {} is missing from the run chain", plotPivot)
        }
        val record = CompoundTag()
        record.putString("UUID", sub.uniqueId.toString())
        record.put("Controller", NbtUtils.writeBlockPos(plotController))
        record.put("Pivot", NbtUtils.writeBlockPos(plotPivot))
        record.put("WorldPivot", NbtUtils.writeBlockPos(pivot))
        record.putString("Facing", facing.serializedName)
        record.putString("Side", side.serializedName)
        record.putInt("Index", pivotIndex)
        record.putInt("Length", chain.size)
        record.putFloat("Angle", 0f)
        val tag = sub.userDataTag ?: CompoundTag()
        tag.put(RECORD, record)
        sub.userDataTag = tag
        plotDeck.setHinge(side, plotPivot, deck.hingeOwned)
        plotDeck.setHingeAssembly(sub.uniqueId.toString(), facing, pivotIndex, pivot)
        plotDeck.healChain()
        placeWorldShaft(level, pivot, RollerConveyorBlock.rotationAxisOf(pivotState), sub.uniqueId)
        holdAtOnce(sub)
        CreateWaterparked.LOGGER.debug(
            "[roller hinge] assembled {} blocks about {} side {} shaft {}",
            blocks.size, pivot, side.serializedName, pivot.relative(face)
        )
    }

    // the run's plot cells in travel order, counted from the pivot the world shaft stands at
    private fun plotChain(blocks: Set<BlockPos>, offset: BlockPos, facing: Direction, pivot: BlockPos): List<BlockPos> =
        blocks.sortedBy { (it.x - pivot.x) * facing.stepX + (it.z - pivot.z) * facing.stepZ }
            .map { it.offset(offset.x, offset.y, offset.z) }

    // the world pivot cell gets the shaft that drives the run, placed without a sound or a particle
    private fun placeWorldShaft(level: ServerLevel, pos: BlockPos, axis: Direction.Axis, runId: UUID): Boolean {
        val state = ModBlocks.ROLLER_HINGE_SHAFT.defaultBlockState().setValue(BlockStateProperties.AXIS, axis)
        level.setBlockAndUpdate(pos, state)
        val shaft = level.getBlockEntity(pos) as? RollerWorldShaftBlockEntity ?: return false
        shaft.runId = runId
        shaft.setChanged()
        return true
    }

    private fun removeWorldShaft(level: ServerLevel, pos: BlockPos) {
        (level.getBlockEntity(pos) as? RollerWorldShaftBlockEntity)?.runId = null
        if (level.getBlockState(pos).block !is RollerWorldShaftBlock) return
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
    }

    // the run goes home at most once at a time, whatever took its shaft away
    private fun closeRunGuarded(level: ServerLevel, sub: ServerSubLevel): Boolean {
        if (disassembling) return false
        disassembling = true
        return try {
            closeRun(level, sub)
        } finally {
            disassembling = false
        }
    }

    private fun closeRun(level: ServerLevel, sub: ServerSubLevel): Boolean {
        val tag = sub.userDataTag ?: CompoundTag()
        val record = tag.getCompound(RECORD)
        var deck = NbtUtils.readBlockPos(record, "Controller").orElse(null)?.let { plotDeckOf(level, sub, it) }
        if (deck == null) deck = hingedDeckInPlot(level, sub)
        if (deck == null) return layDownAndRelease(level, sub, tag, record)
        disassemble(level, sub, tag, record, deck)
        return true
    }

    // a shaft that leaves the world takes its run with it, so the two can never drift apart
    @JvmStatic
    fun dismantleRun(level: Level, pos: BlockPos): Boolean {
        if (level !is ServerLevel) return false
        if (disassembling) return false
        val id = runOfShaft(level, pos)
        disassembling = true
        return try {
            removeWorldShaft(level, pos)
            if (id == null) false else {
                val sub = SubLevelContainer.getContainer(level)?.getSubLevel(id) as? ServerSubLevel
                if (sub != null) closeRun(level, sub)
                else {
                    // the run itself is gone, so nothing may keep its bearing
                    forgetRun(id)
                    true
                }
            }
        } finally {
            disassembling = false
        }
    }

    // a run assembled before the world shaft existed gets one, once its pivot is back inside its plot
    @JvmStatic
    fun adoptLegacyRun(level: ServerLevel, sub: ServerSubLevel): Boolean {
        if (sub.isRemoved) return false
        val tag = sub.userDataTag ?: return false
        val record = tag.getCompound(RECORD)
        if (record.isEmpty) return false
        val worldPivot = NbtUtils.readBlockPos(record, "WorldPivot").orElse(null) ?: return false
        val plotPivot = NbtUtils.readBlockPos(record, "Pivot").orElse(null) ?: return false
        val facing = directionOf(record.getString("Facing")) ?: return false
        val worldState = level.getBlockState(worldPivot)
        if (worldState.block is RollerWorldShaftBlock) return false
        if (worldState.block !is RollerConveyorBlock) return false
        val worldDeck = level.getBlockEntity(worldPivot) as? RollerConveyorBlockEntity ?: return false
        if (!level.getBlockState(plotPivot).canBeReplaced()) {
            CreateWaterparked.LOGGER.warn("[roller hinge] run {} cannot be adopted, its plot pivot cell {} is taken", sub.uniqueId, plotPivot)
            return false
        }
        val pivotTag = worldDeck.saveWithFullMetadata(level.registryAccess())
        if (!movePivotIntoPlot(level, sub, plotPivot, worldState, pivotTag)) return false
        worldDeck.releaseInventory()
        level.setBlockAndUpdate(worldPivot, Blocks.AIR.defaultBlockState())
        rechainPlot(level, sub, tag, record, plotPivot, facing)
        if (!placeWorldShaft(level, worldPivot, RollerConveyorBlock.rotationAxisOf(worldState), sub.uniqueId)) {
            CreateWaterparked.LOGGER.warn("[roller hinge] adopted run {} but its world shaft did not come up", sub.uniqueId)
            return false
        }
        CreateWaterparked.LOGGER.debug("[roller hinge] adopted the run {} saved before the world shaft existed", sub.uniqueId)
        return true
    }

    // the pivot block and its data move into the plot cell the record points at, so the chain has no hole
    private fun movePivotIntoPlot(level: ServerLevel, sub: ServerSubLevel, plotPivot: BlockPos, state: BlockState, data: CompoundTag): Boolean {
        val plot = sub.plot
        val accessor = plot.embeddedLevelAccessor
        val local = plotPivot.subtract(plot.centerBlock)
        accessor.setBlock(local, state, 3, 512)
        val deck = accessor.getBlockEntity(local) as? RollerConveyorBlockEntity
        if (deck == null) {
            accessor.setBlock(local, Blocks.AIR.defaultBlockState(), 3, 512)
            CreateWaterparked.LOGGER.warn("[roller hinge] could not move the pivot of run {} into its plot", sub.uniqueId)
            return false
        }
        deck.loadWithComponents(data, level.registryAccess())
        return true
    }

    // the run is hung again from its pivot cell, now that the whole chain sits in the plot
    private fun rechainPlot(level: ServerLevel, sub: ServerSubLevel, tag: CompoundTag, record: CompoundTag, plotPivot: BlockPos, facing: Direction) {
        RollerDeck.init(level, plotPivot)
        val chain = RollerDeck.segments(level, plotPivot, facing)
        val controller = chain.firstOrNull() ?: plotPivot
        record.put("Controller", NbtUtils.writeBlockPos(controller))
        record.putInt("Length", chain.size)
        tag.put(RECORD, record)
        sub.userDataTag = tag
        val side = sideOf(record.getString("Side")) ?: RollerHinge.Side.NONE
        (sub as? RollerSubLevel)?.setRun(controller, plotPivot, side, facing, record.getInt("Index"), chain)
    }

    // the run a world shaft stands for: its own block entity first, the saved records second
    private fun runOfShaft(level: ServerLevel, pos: BlockPos): UUID? {
        val shaft = level.getBlockEntity(pos) as? RollerWorldShaftBlockEntity
        if (shaft?.runId != null) return shaft.runId
        val container = SubLevelContainer.getContainer(level) ?: return null
        for (raw in container.allSubLevels) {
            val sub = raw as? ServerSubLevel ?: continue
            val tag = sub.userDataTag ?: continue
            val record = tag.getCompound(RECORD)
            if (record.isEmpty) continue
            if (NbtUtils.readBlockPos(record, "WorldPivot").orElse(null) == pos) return sub.uniqueId
        }
        return null
    }

    // a run whose plot side cannot be found is written back from its own record and released
    private fun layDownAndRelease(level: ServerLevel, sub: ServerSubLevel, tag: CompoundTag, record: CompoundTag): Boolean {
        val worldPivot = NbtUtils.readBlockPos(record, "WorldPivot").orElse(null)
        val facing = directionOf(record.getString("Facing"))
        val length = record.getInt("Length")
        if (worldPivot == null || facing == null || length <= 0) {
            CreateWaterparked.LOGGER.debug("[roller hinge] run {} cannot be laid back down", sub.uniqueId)
            return false
        }
        layDownWorld(level, facing, record.getInt("Index"), length, worldPivot)
        tag.remove(RECORD)
        sub.userDataTag = tag
        forgetRun(sub)
        releaseSub(level, sub)
        CreateWaterparked.LOGGER.debug("[roller hinge] run {} laid back down at {}", sub.uniqueId, worldPivot)
        return true
    }

    // the world shaft owns the clock of its run, so the run's tick path takes the speed it is handed
    @JvmStatic
    fun driveFromShaft(level: ServerLevel, sub: ServerSubLevel, rpm: Float) {
        val tag = sub.userDataTag ?: CompoundTag()
        drive(level, sub, tag, tag.getCompound(RECORD), rpm)
        if (level.gameTime % SHAFT_LOG_TICKS != 0L) return
        val record = sub.userDataTag?.getCompound(RECORD) ?: CompoundTag()
        CreateWaterparked.LOGGER.debug(
            "[roller hinge diag] shaft rpm={} angle={} segments={} pose={}",
            rpm, record.getFloat("Angle"), segmentsOf(sub, record), sub.logicalPose().position()
        )
    }

    // a claimed run counts its own chain, a reloaded one counts the length its record kept
    private fun segmentsOf(sub: ServerSubLevel, record: CompoundTag): Int =
        (sub as? RollerSubLevel)?.takeIf { it.claimed }?.length ?: record.getInt("Length")

    private fun drive(level: ServerLevel, sub: ServerSubLevel, tag: CompoundTag, record: CompoundTag, rpm: Float) {
        val run = runs.getOrPut(sub.uniqueId) { Run() }
        var rec = record
        val live = (sub as? RollerSubLevel)?.takeIf { it.claimed }
        val recorded = live?.controller ?: NbtUtils.readBlockPos(rec, "Controller").orElse(null)
        var deck = recorded?.let { plotDeckOf(level, sub, it) }
        // the scan is the last resort, so it only runs on the same slow cadence as the record refill
        if (deck == null && level.gameTime % RECORD_SCAN_TICKS == 0L) deck = hingedDeckInPlot(level, sub)
        var worldPivot = NbtUtils.readBlockPos(rec, "WorldPivot").orElse(null)
        var facing = live?.facing ?: directionOf(rec.getString("Facing"))
        var side: RollerHinge.Side? = live?.side ?: sideOf(rec.getString("Side"))
        val handle = RigidBodyHandle.of(sub)
        val handleOk = handle != null && handle.isValid()
        val physics = SubLevelPhysicsSystem.get(level) != null
        diagEntry(sub, run, rec, recorded, worldPivot, facing, side, deck, handleOk, physics)
        if (deck != null && (recorded != deck.blockPos || worldPivot == null || facing == null || side == null)) {
            tag.put(RECORD, recordOf(sub, deck, deck.blockPos))
            sub.userDataTag = tag
            rec = tag.getCompound(RECORD)
            worldPivot = NbtUtils.readBlockPos(rec, "WorldPivot").orElse(null)
            facing = directionOf(rec.getString("Facing"))
            side = sideOf(rec.getString("Side"))
            CreateWaterparked.LOGGER.warn("[roller hinge] rebuilt the record of {} from its plot deck", sub.uniqueId)
        }
        if (worldPivot == null || facing == null || side == null) {
            report(run, sub, "has no hinge geometry, record {}", rec)
            return
        }
        if (deck != null) {
            run.healTicks++
            if (run.healTicks >= HEAL_TICKS) {
                run.healTicks = 0
                deck.healChain()
            }
            if (deck.hingeSide == RollerHinge.Side.NONE) {
                // only a run whose record still points into the world can be laid back down
                if (!plotCoordinate(level, worldPivot)) {
                    closeRunGuarded(level, sub)
                    return
                }
                report(run, sub, "is assembled but shows no hinge marker and no world anchor")
                // the run cannot go home, but it must never be left to fall on its own
                val anchoredPivot = run.pivot
                val anchoredAxis = run.axis
                // an unlocked run is already held by its own bearing, so only a locked one is pinned here
                if (run.locked && anchoredPivot != null && anchoredAxis != null) {
                    hold(sub, run, anchoredPivot, anchoredAxis, rec.getFloat("Angle"))
                }
                return
            }
        }
        val face = side.face(facing)
        if (face == null) {
            report(run, sub, "has no hinge side to turn about, record {}", rec)
            return
        }
        if (deck != null) {
            deck.setSpeed(rpm)
            deck.tickPlotDeck()
        }
        val pivot = run.pivot ?: shaftCentre(worldPivot, face).also { run.pivot = it }
        val axis = run.axis ?: axisOf(facing).also { run.axis = it }
        if (run.basePos == null || run.baseRot == null) captureBase(sub, pivot, axis, rec.getFloat("Angle"), run)
        // a bearing the engine dropped is no bearing at all, so the latch is taken from the deck once more.
        // A run already fallen back is left alone, so no tick ever rebuilds a bearing the engine refuses.
        if (!run.locked && !run.fallback && !bearingHolds(run)) run.locked = true
        applyLatch(level, sub, deck, run)
        var angle = run.angle
        var held = false
        if (run.locked || run.fallback) {
            // the shaft drives the angle, and only a locked run is pinned to the pose that angle gives it
            val limit = ModConfig.rollerHingeMaxAngleDegrees().toFloat()
            angle = rec.getFloat("Angle") + (rpm * DEGREES_PER_RPM_TICK * ANGLE_SIGN).toFloat()
            if (limit > 0f) angle = angle.coerceIn(-limit, limit)
            run.angle = angle
            held = run.locked && hold(sub, run, pivot, axis, angle)
        } else {
            // an unlocked run is read, never driven: its angle comes off the pose it holds and its belt off the
            // swing rate the physics gives its body, so nothing here sets a speed or adds to an angle
            angle = swungAngle(sub, run, axis)
            run.angle = angle
            // the belt this run carries is the swing of its own body, and the deck carries that number to
            // whoever asks, a client included
            deck?.setHingeSwing(measuredSwing(sub, axis, rec.getInt("Index")))
        }
        diag(level, run, sub, deck, rpm, angle, held)
        if (angle != rec.getFloat("Angle")) {
            rec.putFloat("Angle", angle)
            tag.put(RECORD, rec)
            sub.userDataTag = tag
        }
        deck?.setHingeAngle(angle)
    }

    // temporary: prints the block that owns a cell and its six neighbours
    private fun diagPivotCell(level: ServerLevel, stage: String, pivot: BlockPos) {
        val cells = Direction.values().joinToString(",") { side ->
            side.serializedName + "=" + level.getBlockState(pivot.relative(side)).block
        }
        CreateWaterparked.LOGGER.debug(
            "[roller hinge diag] pivot {} {} holds={} neighbours={}",
            stage, pivot, level.getBlockState(pivot).block, cells
        )
    }

    private fun plotCoordinate(level: ServerLevel, pos: BlockPos): Boolean {
        val container = SubLevelContainer.getContainer(level)
        if (container != null) return container.inBounds(ChunkPos(pos))
        return abs(pos.x) > PLOT_COORD_LIMIT || abs(pos.z) > PLOT_COORD_LIMIT
    }

    // the block state carries the hinge the player sees, so it is the source of truth for the side
    private fun hingeMarker(level: Level, deck: RollerConveyorBlockEntity): Triple<BlockPos, RollerHinge.Side, Int>? {
        // the pivot stays in the world, so its own block state is the first place to look
        val worldPivot = deck.hingeWorldPivot
        if (worldPivot != null) {
            val state = level.getBlockState(worldPivot)
            if (state.block is RollerConveyorBlock) {
                val side = state.getValue(RollerConveyorBlock.HINGE)
                if (side != RollerHinge.Side.NONE) return Triple(worldPivot, side, deck.hingeIndex)
            }
        }
        val span = minOf(maxOf(deck.deckLength, 1), ModConfig.rollerHingeMaxLength())
        for (offset in 0 until span) {
            val pos = deck.positionForOffset(offset)
            val state = level.getBlockState(pos)
            if (state.block !is RollerConveyorBlock) continue
            val side = state.getValue(RollerConveyorBlock.HINGE)
            if (side != RollerHinge.Side.NONE) return Triple(pos, side, offset)
        }
        return null
    }

    // a lay down that skipped an occupied pivot leaves its marker behind while the entity was cleared
    @JvmStatic
    fun restoreHinge(level: Level, deck: RollerConveyorBlockEntity) {
        val hit = hingeMarker(level, deck)
        if (deck.hingeSide != RollerHinge.Side.NONE) {
            if (hit == null) {
                CreateWaterparked.LOGGER.debug("[roller hinge diag] run {} is hinged in the entity without a block", deck.blockPos)
            }
            return
        }
        val found = hit?.first ?: return
        CreateWaterparked.LOGGER.debug("[roller hinge diag] run {} shows a hinge on its block, restoring it", deck.blockPos)
        deck.setHinge(hit.second, found, false)
        CreateWaterparked.LOGGER.info("[roller hinge] restored the hinge of the run at {} from its block", deck.blockPos)
    }

    // the pose the run was assembled at, recovered from the current pose and the angle it was saved with
    private fun captureBase(sub: ServerSubLevel, pivot: Vector3d, axis: Vector3d, angle: Float, run: Run) {
        val pose = sub.logicalPose()
        val inverse = Quaterniond().rotationAxis(Math.toRadians(-angle.toDouble()), axis.x, axis.y, axis.z)
        val offset = Vector3d(pose.position()).sub(pivot)
        inverse.transform(offset)
        run.basePos = Vector3d(pivot).add(offset)
        run.baseRot = inverse.mul(pose.orientation(), Quaterniond())
        run.baseCom = comOf(sub)
    }

    private fun comOf(sub: ServerSubLevel): Vector3d? =
        runCatching { sub.massTracker.centerOfMass }.getOrNull()?.let { Vector3d(it) }

    private fun disassemble(
        level: ServerLevel,
        sub: ServerSubLevel,
        tag: CompoundTag,
        record: CompoundTag,
        plotDeck: RollerConveyorBlockEntity
    ) {
        val worldPivot = NbtUtils.readBlockPos(record, "WorldPivot").orElse(null)
        val facing = directionOf(record.getString("Facing"))
        val side = sideOf(record.getString("Side")) ?: RollerHinge.Side.NONE
        val pivotIndex = record.getInt("Index")
        if (worldPivot == null || facing == null) {
            CreateWaterparked.LOGGER.debug("[roller hinge] run {} cannot be laid back down", sub.uniqueId)
            return
        }
        removeWorldShaft(level, worldPivot)
        val controllerPos = layDownWorld(level, facing, pivotIndex, plotDeck.deckLength, worldPivot)
        val worldDeck = level.getBlockEntity(controllerPos) as? RollerConveyorBlockEntity
        if (worldDeck != null) {
            worldDeck.loadWithComponents(
                plotDeck.saveWithFullMetadata(level.registryAccess()), level.registryAccess()
            )
            worldDeck.setHinge(RollerHinge.Side.NONE, null, false)
            worldDeck.clearHingeAssembly()
            worldDeck.healChain()
        }
        tag.remove(RECORD)
        sub.userDataTag = tag
        forgetRun(sub)
        releaseSub(level, sub)
        CreateWaterparked.LOGGER.debug("[roller hinge] run {} laid back down at {}", sub.uniqueId, controllerPos)
    }

    // the chain only ever sits on its own row from the world pivot, so it rebuilds without any plot state
    private fun layDownWorld(
        level: ServerLevel,
        facing: Direction,
        pivotIndex: Int,
        length: Int,
        worldPivot: BlockPos
    ): BlockPos {
        // a run being laid down always belongs to the world, so a plot cell here is a bug
        if (plotCoordinate(level, worldPivot)) {
            CreateWaterparked.LOGGER.error("[roller hinge] refused to lay a run down at plot coordinates {}", worldPivot)
            return worldPivot.relative(facing, -pivotIndex)
        }
        for (index in 0 until length) {
            val pos = worldPivot.relative(facing, index - pivotIndex)
            // a segment still standing at the pivot keeps its block and only loses the hinge marker
            val standing = level.getBlockState(pos)
            val keepsMarker = index == pivotIndex && standing.block is RollerConveyorBlock &&
                standing.getValue(RollerConveyorBlock.HINGE) != RollerHinge.Side.NONE
            if (keepsMarker) {
                level.setBlockAndUpdate(pos, standing.setValue(RollerConveyorBlock.HINGE, RollerHinge.Side.NONE))
                continue
            }
            if (!level.getBlockState(pos).canBeReplaced()) {
                CreateWaterparked.LOGGER.debug("[roller hinge] {} is occupied, the run keeps that gap", pos)
                continue
            }
            val state = ModBlocks.ROLLER_CONVEYOR.defaultBlockState()
                .setValue(RollerConveyorBlock.HORIZONTAL_FACING, facing)
                .setValue(RollerConveyorBlock.HINGE, RollerHinge.Side.NONE)
                .setValue(BlockStateProperties.WATERLOGGED, false)
            level.setBlockAndUpdate(pos, state)
        }
        return worldPivot.relative(facing, -pivotIndex)
    }

    private fun releaseSub(level: ServerLevel, sub: ServerSubLevel) {
        val container = SubLevelContainer.getContainer(level) ?: return
        if (container.getSubLevel(sub.uniqueId) == null) return
        sub.plot.kickAllEntities()
        container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED)
    }

    // a run that is gone or unwrenched leaves no assembly cooldown behind
    private fun pruneCooldowns(level: ServerLevel, now: Long) {
        val iterator = assembleCooldown.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value > now && level.getBlockEntity(entry.key) is RollerConveyorBlockEntity) continue
            iterator.remove()
        }
    }

    // the embedded plot accessor is centred on the plot's centre block, which is exactly where the anchor lands
    private fun plotDeckOf(level: ServerLevel, sub: ServerSubLevel, plotController: BlockPos): RollerConveyorBlockEntity? {
        val plot = sub.plot
        val accessor = plot.embeddedLevelAccessor
        val local = plotController.subtract(plot.centerBlock)
        // only a controller that really carries its hinge answers the click: the record's controller has more
        // than one coordinate space among its writers, so a candidate from a misread one must never stand in
        // for the run - the honest fall back is the scan of the plot itself
        fun cand(pos: BlockPos): RollerConveyorBlockEntity? {
            val deck = accessor.getBlockEntity(pos) as? RollerConveyorBlockEntity
                ?: level.getBlockEntity(pos) as? RollerConveyorBlockEntity
                ?: return null
            if (!deck.isController || deck.hingeSub.isEmpty()) return null
            return deck
        }
        cand(local)?.let { return it }
        for (dx in -2..2) for (dy in -2..2) for (dz in -2..2) {
            cand(local.offset(dx, dy, dz))?.let { return it }
        }
        return hingedDeckInPlot(level, sub)
    }

    private fun shaftCentre(worldPivot: BlockPos, face: Direction): Vector3d {
        val centre = Vec3.atCenterOf(worldPivot).add(Vec3.atLowerCornerOf(face.normal))
        return Vector3d(centre.x, centre.y, centre.z)
    }

    private fun axisOf(facing: Direction): Vector3d =
        if (facing.axis == Direction.Axis.X) Vector3d(0.0, 0.0, 1.0) else Vector3d(1.0, 0.0, 0.0)

    // records written before the facing was stored in its serialized form still read back in any case
    private fun directionOf(name: String): Direction? =
        Direction.byName(name) ?: Direction.values().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun sideOf(name: String): RollerHinge.Side? =
        RollerHinge.Side.entries.firstOrNull { it.serializedName == name }
            ?: RollerHinge.Side.entries.firstOrNull { it.serializedName.equals(name, ignoreCase = true) }
}
