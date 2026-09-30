package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.api.stress.BlockStressValues
import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor
import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle
import dev.ryanhcode.sable.companion.math.Pose3d
import dev.ryanhcode.sable.sublevel.ServerSubLevel
import net.createmod.catnip.lang.LangBuilder
import net.createmod.catnip.math.VecHelper
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import net.neoforged.neoforge.items.IItemHandler
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.config.ModConfig
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import java.util.function.Function
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// the deck owns its segments, its loads and its passengers, with no Create belt behind it
class RollerConveyorBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) :
    KineticBlockEntity(type, pos, state), BlockEntitySubLevelActor {

    var deckLength: Int = 0
    var index: Int = 0
    var hingeSide: RollerHinge.Side = RollerHinge.Side.NONE
        private set
    var hingePivot: BlockPos? = null
        private set
    var hingeOwned: Boolean = false
        private set
    var hingeSub: String = ""
        private set
    var hingeAngle: Float = 0f
        private set
    var hingeFacing: Direction? = null
        private set
    var hingeIndex: Int = 0
        private set
    var hingeWorldPivot: BlockPos? = null
        private set
    var hingeGravity: Boolean = false
        private set
    // the run's hinge latch: a locked run follows its shaft, an unlocked one is left free
    var hingeLocked: Boolean = true
        private set
    // an unlocked run the engine would not give a bearing to stays on its angle drive alone
    var hingeFallback: Boolean = false
        private set
    private var syncedAngle = 0f
    private var syncedGravity = false
    // the swing the run's own body was measured with, in blocks per tick, and the last keyframe it sent
    var swingSpeed: Float = 0f
        private set
    private var lastRunSync = -1L
    // the client steps its own copy of the run, so it remembers where its own stepping got to
    private var mirrorTime = Long.MIN_VALUE
    private var mirrorTrace = ""
    private var controller: BlockPos? = null
    private var itemHandler: IItemHandler? = null
    private var deckInventory: RollerDeckInventory? = null
    private val passengers = HashMap<Entity, DeckPassenger>()

    val deckFacing: Direction get() = blockState.getValue(RollerConveyorBlock.HORIZONTAL_FACING)

    val isController: Boolean get() = controller == null || controller == worldPosition

    // the deck travels the way its front looks, reversing with the drive sense rather than with an axis
    val movementFacing: Direction get() = if (directionAwareTravelSpeed < 0f) deckFacing.opposite else deckFacing

    // the speed a load on the deck sees, with the idle drive already folded in
    val travelSpeed: Float get() = getSpeed()

    // the run's one belt source: a locked run is carried by its shaft, an unlocked one by the swing the
    // physics measured on its own body. Both inputs are read here and nowhere else.
    val deckMovementSpeed: Float get() = if (hingeUnlocked()) swingBeltSpeed() else shaftBeltSpeed()

    val directionAwareTravelSpeed: Float get() = deckMovementSpeed

    // the shaft side of the belt source: the run's own kinetic speed, in blocks per tick
    private fun shaftBeltSpeed(): Float = travelSpeed / 480f

    // the swing side of the belt source: the rate the tilt driver measured on the run's own body, which
    // reaches a client with the run's own state
    private fun swingBeltSpeed(): Float = (controllerBE() ?: this).swingSpeed

    val inventory: RollerDeckInventory?
        get() {
            if (!isController) return controllerBE()?.inventory
            if (deckInventory == null) deckInventory = RollerDeckInventory(this)
            return deckInventory
        }

    // the deck is self driven: an idle speed keeps travel facing, insertion gates and boat carry alive
    override fun getSpeed(): Float {
        val kineticSpeed = super.getSpeed()
        return if (kineticSpeed == 0f) DEFAULT_SPEED else kineticSpeed
    }

    // the raw network speed, without the idle speed the deck keeps for itself
    fun kineticSpeed(): Float = super.getSpeed()

    // the run drags whoever stands on it the way its own loads travel, so only the magnitude is needed
    fun carriedMovementSpeed(): Float = abs(runDeckSpeed())

    // a run is an assembled hinge once its controller carries the sub-level link
    fun hingeAssembled(): Boolean = (controllerBE() ?: this).hingeSub.isNotEmpty()

    // the speed the run is hooked up to right now, before sable moves it out of the network
    fun networkSpeed(): Float = super.getSpeed()

    // a run whose latch is open and whose bearing really holds it: the two flags its controller keeps. Only an
    // assembled run can hang on a bearing, so one still in the world keeps reading its shaft whatever the latch.
    fun hingeUnlocked(): Boolean {
        val head = controllerBE() ?: this
        if (!head.hingeAssembled()) return false
        return !head.hingeLocked && !head.hingeFallback
    }

    // the run's deck speed is the one its controller holds, and a run with no incoming speed stays still —
    // unless it is unlocked, whose belt comes from its own swing and not from the shaft
    fun runDeckSpeed(): Float {
        val head = controllerBE() ?: this
        if (head.hingeAssembled() && !head.hingeUnlocked() && head.kineticSpeed() == 0f) return 0f
        return head.deckMovementSpeed
    }

    // the travel direction of the run is the controller's as well
    fun runTravelSpeed(): Float = (controllerBE() ?: this).directionAwareTravelSpeed

    // a hinged run reads as level inside the tilt band its latch already uses, so gravity only pulls outside it
    fun hingeOffLevel(): Boolean {
        val head = controllerBE() ?: this
        return head.hingeAssembled() && abs(head.hingeAngle) > HINGE_TILT_EXIT
    }

    // a hinged run whose shaft stands still moves nothing but what gravity takes, on the bare speed
    fun gravityOnly(): Boolean = hingeAssembled() && kineticSpeed() == 0f

    // gravity takes the loads over past the enter angle and only hands them back below the exit angle
    fun hingeTilted(): Boolean {
        val head = controllerBE() ?: this
        if (head.hingeSub.isEmpty()) return false
        if (head.hingeGravity) {
            if (abs(head.hingeAngle) <= HINGE_TILT_EXIT) {
                head.hingeGravity = false
                head.syncHingeState()
            }
        } else if (abs(head.hingeAngle) >= HINGE_TILT_ENTER) {
            head.hingeGravity = true
            head.syncHingeState()
        }
        return head.hingeGravity
    }

    // the speed a load travels at along the deck: a turning shaft picks the way, and otherwise its own momentum does
    fun loadBeltSpeed(carried: Float, sliding: Boolean): Float {
        val drive = runDeckSpeed()
        val direction = if (runKineticSpeed() != 0f || carried == 0f) Math.signum(drive) else Math.signum(carried)
        return (if (sliding) abs(drive) else max(abs(drive), abs(carried))) * direction
    }

    // the same speed with whatever a slope has added, so the rollers and the transport read one source
    fun loadTravelSpeed(carried: Float, sliding: Boolean): Float =
        loadBeltSpeed(carried, sliding) + (if (sliding) carried else 0f)

    // the speed a load on the deck sees, without any slope term
    fun itemTravelSpeed(landing: Float): Float =
        loadBeltSpeed(if (landing == RollerMomentum.NO_LANDING) 0f else landing, false)

    fun controllerPosition(): BlockPos? = controller

    fun positionForOffset(offset: Int): BlockPos {
        val step = deckFacing.normal
        return worldPosition.offset(offset * step.x, 0, offset * step.z)
    }

    fun vectorForOffset(offset: Float): Vec3 {
        val step = deckFacing.normal
        return VecHelper.getCenterOf(worldPosition)
            .add(Vec3.atLowerCornerOf(step).scale((offset - .5f).toDouble()))
    }

    // the run tells every segment where it sits, and a reshaped run re-anchors its kinetics
    fun setDeck(controllerPos: BlockPos, segmentIndex: Int, length: Int) {
        if (controller == controllerPos && index == segmentIndex && deckLength == length) return
        controller = controllerPos
        index = segmentIndex
        deckLength = length
        invalidateItemHandler()
        attachKinetics()
        setChanged()
        sendData()
    }

    // a rebuilt run lets go of its loads, and the shaft it turns about belongs to its controller
    fun setHinge(side: RollerHinge.Side, pivot: BlockPos?, owned: Boolean) {
        hingeSide = side
        hingePivot = pivot
        hingeOwned = owned
        hingeGravity = false
        alignSync()
        setChanged()
        sendData()
    }

    // the player flips the run's latch: the driver builds or drops the bearing on its next tick
    fun setHingeLocked(locked: Boolean) {
        if (hingeLocked == locked) return
        hingeLocked = locked
        hingeFallback = false
        setChanged()
        sendData()
    }

    // an unlocked run the engine would not hold reads as a fallback, and only a real move is ever sent
    fun setHingeFallback(fallback: Boolean) {
        if (hingeFallback == fallback) return
        hingeFallback = fallback
        setChanged()
        sendData()
    }

    // the swing the run's body was measured with, written by the driver every tick and carried out on the
    // next sync, so both sides read the very same number
    fun setHingeSwing(swing: Float) {
        swingSpeed = swing
    }

    // the run tells its clients about a change at once, and otherwise waits for the next keyframe. A load that
    // only moved is never dragged across the wire on its own.
    fun syncRun(force: Boolean) {
        val world = level ?: return
        if (world.isClientSide) return
        if (!isController) return
        val now = world.gameTime
        if (!force && now - lastRunSync < ModConfig.rollerDeckSyncIntervalTicks()) return
        lastRunSync = now
        sendData()
    }

    // the assembled run keeps its sub-level link, its swing angle and where it lands again
    fun setHingeAssembly(sub: String, facing: Direction, pivotIndex: Int, worldPivot: BlockPos) {
        hingeSub = sub
        hingeGravity = false
        hingeFacing = facing
        hingeIndex = pivotIndex
        hingeWorldPivot = worldPivot
        hingeAngle = 0f
        inventory?.clearSlopeSpeeds()
        alignSync()
        setChanged()
        sendData()
    }

    // a run that comes back level drops the slope speed its loads gathered, in the very tick it comes level
    fun setHingeAngle(angle: Float) {
        if (hingeAngle == angle) return
        hingeAngle = angle
        if (abs(angle) <= HINGE_TILT_EXIT) inventory?.clearSlopeSpeeds()
        setChanged()
        syncHingeState()
    }

    // the bookkeeping follows the state, so the next move of it is always sent
    private fun alignSync() {
        syncedAngle = hingeAngle
        syncedGravity = hingeGravity
    }

    // traffic only when the mode flips or the angle moves past the sync step
    private fun syncHingeState() {
        if (level?.isClientSide != false) return
        if (!isController) return
        if (hingeGravity == syncedGravity && abs(hingeAngle - syncedAngle) < SYNC_ANGLE_DEGREES) return
        alignSync()
        syncRun(false)
    }

    // a run taken out of its hinge comes level, so its loads drop the slope speed they gathered
    fun clearHingeAssembly() {
        hingeSub = ""
        hingeGravity = false
        hingeFacing = null
        hingeIndex = 0
        hingeWorldPivot = null
        hingeAngle = 0f
        inventory?.clearSlopeSpeeds()
        alignSync()
        setChanged()
        sendData()
    }

    // a run moved into a plot keeps a world coordinate controller link, so it re-anchors its own chain
    fun healChain() {
        val world = level ?: return
        if (world.isClientSide) return
        if (linked(world, worldPosition) && linked(world, positionForOffset(1))) return
        RollerDeck.init(world, worldPosition)
    }

    private fun linked(world: Level, pos: BlockPos): Boolean {
        val segment = world.getBlockEntity(pos) as? RollerConveyorBlockEntity ?: return true
        val link = segment.controllerPosition() ?: return true
        val head = world.getBlockEntity(link) as? RollerConveyorBlockEntity ?: return false
        return head.controllerPosition() == link
    }

    fun releaseInventory(): RollerDeckInventory? {
        val released = deckInventory
        deckInventory = null
        invalidateItemHandler()
        return released
    }

    fun handler(): IItemHandler? {
        initializeItemHandler()
        return itemHandler
    }

    fun invalidateItemHandler() {
        itemHandler = null
        invalidateCapabilities()
    }

    override fun addBehaviours(behaviours: MutableList<BlockEntityBehaviour>) {
        behaviours.add(
            DirectBeltInputBehaviour(this)
                .onlyInsertWhen { canInsertFrom(it) }
                .setInsertionHandler { transported, side, simulate -> insertFromSide(transported, side, simulate) }
                .considerOccupiedWhen { isOccupied(it) }
        )
        behaviours.add(
            TransportedItemStackHandlerBehaviour(this) { maxDistance, processFunction ->
                applyToAllItems(maxDistance, processFunction)
            }.withStackPlacement { getWorldPositionOf(it) }
        )
        behaviours.add(VersionedInventoryTrackerBehaviour(this))
    }

    // the deck ticks its loads and then drags whoever stands on it, at the speed the run really carries
    override fun tick() {
        val world = level ?: return
        if (deckLength == 0) RollerDeck.init(world, worldPosition)
        super.tick()
        if (world.isClientSide) {
            // the client's copy is stepped by the client itself, and never moves a passenger: only the server
            // may act on what the run computes
            invalidateRenderBoundingBox()
            mirrorTick()
            return
        }
        if (!isController) return
        if (!world.isClientSide) {
            healChain()
            RollerEntry.tick(world.gameTime)
            if (hingeSide != RollerHinge.Side.NONE && hingeSub.isEmpty()) {
                RollerHingeTilt.tryAssemble(world, this)
            } else if (world.gameTime % HINGE_CHECK_TICKS == 0L) {
                if (hingeSide == RollerHinge.Side.NONE) RollerHingeTilt.restoreHinge(world, this)
            }
        }
        invalidateRenderBoundingBox()
        inventory?.tick()
        carryPassengers()
    }

    // sable never ticks plot entities, so the hinge driver runs this side: a level idle run keeps still, a tilted one slides
    fun tickPlotDeck() {
        val world = level ?: return
        if (deckLength == 0) RollerDeck.init(world, worldPosition)
        if (hingeAssembled() && world.gameTime % HINGE_SYNC_TICKS == 0L) sendData()
        grabLowerLane(world)
        // an unlocked run is carried by its own swing, so a still shaft never stops its loads
        if (hingeAssembled() && !hingeUnlocked() && kineticSpeed() == 0f && !hingeOffLevel()) return
        // only a run that really sits in a sub-level is driven from here, so a world run keeps its own single path
        if (!RollerConveyorBlock.insideSubLevel(world, worldPosition)) return
        inventory?.tick()
        carryPassengers()
    }

    // the client steps its own copy of the run: a plot copy is never ticked by the world, so the client does
    // it here, exactly once per game tick, through the very same code the server runs. A copy that missed ticks
    // catches up tick by tick, up to a bound; past that bound it keeps the numbers the last keyframe gave it.
    fun mirrorTick() {
        val world = level ?: return
        if (!world.isClientSide) return
        val head = controllerBE() ?: this
        val loads = head.inventory ?: return
        val now = world.gameTime
        if (head.mirrorTime == Long.MIN_VALUE) {
            // the first look at a run starts from the state it was sent and steps on from here
            head.mirrorTime = now
            return
        }
        val lag = now - head.mirrorTime
        if (lag <= 0L) return
        head.mirrorTime = now
        if (lag > MIRROR_CATCH_UP_TICKS) {
            traceMirrorLag(lag, loads.transportedItems.size)
            return
        }
        var steps = lag
        while (steps-- > 0L) loads.tick()
    }

    // temporary: one line while a client copy fell far enough behind to keep its keyframe instead of stepping
    private fun traceMirrorLag(lag: Long, loads: Int) {
        val line = "$lag|$loads"
        if (line == mirrorTrace) return
        mirrorTrace = line
        CreateWaterparked.LOGGER.debug("[roller mirror] deck={} lag={} loads={}", worldPosition, lag, loads)
    }

    // the plot pose this run sits in, read once a tick: the sweep, the carry and the inventory all asked for it
    // separately before, and no caller can see it change inside one tick
    private var poseCacheTick = Long.MIN_VALUE
    private var poseCache: Pose3d? = null

    fun plotPose(): Pose3d? {
        val here = level ?: return null
        val now = here.gameTime
        if (poseCacheTick != now) {
            poseCacheTick = now
            poseCache = Sable.HELPER.getContaining(here, worldPosition)?.logicalPose()
        }
        return poseCache
    }

    // a load pressed against the deck from below sits in the cell beneath it, so the run reaches down for it
    private fun grabLowerLane(world: Level) {
        if (world.isClientSide || deckLength <= 0) return
        val pose = plotPose() ?: return
        // one entity query over the whole strip under the run instead of one per segment, and the per segment band
        // is kept so a hit far down or off to the side of a tilted run still answers the same test it always did
        val bands = ArrayList<AABB>(deckLength)
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (offset in 0 until deckLength) {
            val cell = positionForOffset(offset)
            var cMinX = Double.MAX_VALUE
            var cMinY = Double.MAX_VALUE
            var cMinZ = Double.MAX_VALUE
            var cMaxX = -Double.MAX_VALUE
            var cMaxY = -Double.MAX_VALUE
            var cMaxZ = -Double.MAX_VALUE
            for (dx in doubleArrayOf(0.0, 1.0)) {
                for (dy in doubleArrayOf(cell.y - LOWER_REACH, cell.y + LOWER_TOUCH)) {
                    for (dz in doubleArrayOf(0.0, 1.0)) {
                        val corner = pose.transformPosition(Vec3(cell.x + dx, dy, cell.z + dz))
                        if (corner.x < cMinX) cMinX = corner.x
                        if (corner.y < cMinY) cMinY = corner.y
                        if (corner.z < cMinZ) cMinZ = corner.z
                        if (corner.x > cMaxX) cMaxX = corner.x
                        if (corner.y > cMaxY) cMaxY = corner.y
                        if (corner.z > cMaxZ) cMaxZ = corner.z
                    }
                }
            }
            bands.add(AABB(cMinX, cMinY, cMinZ, cMaxX, cMaxY, cMaxZ))
            if (cMinX < minX) minX = cMinX
            if (cMinY < minY) minY = cMinY
            if (cMinZ < minZ) minZ = cMinZ
            if (cMaxX > maxX) maxX = cMaxX
            if (cMaxY > maxY) maxY = cMaxY
            if (cMaxZ > maxZ) maxZ = cMaxZ
        }
        for (entity in world.getEntities(null, AABB(minX, minY, minZ, maxX, maxY, maxZ))) {
            // the cell this rider stands under, read back out of its own feet in the run's frame
            val foot = pose.transformPositionInverse(Vec3(entity.x, entity.boundingBox.minY, entity.z))
            val along = if (deckFacing.axis == Direction.Axis.X) foot.x - worldPosition.x else foot.z - worldPosition.z
            val offset = along.toInt().coerceIn(0, deckLength - 1)
            if (!bands[offset].intersects(entity.boundingBox)) continue
            if (RollerConveyorBlock.contactFace(pose, positionForOffset(offset), entity) != RollerDeckFace.LOWER) continue
            RollerConveyorBlock.takeIn(world, positionForOffset(offset), entity, RollerDeckFace.LOWER)
        }
    }

    // the world shaft keeps the clock, so the plot side only pins the body back between physics substeps
    override fun `sable$physicsTick`(subLevel: ServerSubLevel, handle: RigidBodyHandle, timeStep: Double) {
        if (!isController || !hingeAssembled()) return
        RollerHingeTilt.pinRun(subLevel)
    }

    // the whole run costs what its length costs, and only its controller reports that to the network
    override fun calculateStressApplied(): Float {
        if (!isController) return 0f
        return (BlockStressValues.getImpact(blockState.block) * deckLength).toFloat()
    }

    override fun createRenderBoundingBox(): AABB =
        if (isController) super.createRenderBoundingBox().inflate(deckLength + 1.0)
        else super.createRenderBoundingBox()

    // the goggles keep the kinetic readout the base class gives them, and then name the run's latch state
    // through its controller, so every segment of one run answers alike. The answer is only ever true when this
    // call really put a line in the tooltip, since Create trims the last line of a tooltip that claims content.
    override fun addToGoggleTooltip(tooltip: MutableList<Component>, isPlayerSneaking: Boolean): Boolean {
        val base = super.addToGoggleTooltip(tooltip, isPlayerSneaking)
        val head = controllerBE() ?: this
        // a run still in the world carries no latch to name, so nothing is added for it and nothing is claimed
        if (!head.hingeAssembled()) return base
        val before = tooltip.size
        val locked = head.hingeLocked
        LangBuilder(CreateWaterparked.ID)
            .translate("roller.hinge_header")
            .forGoggles(tooltip)
        // a locked run reads GOLD, a free bearing AQUA, and a latch that had to fall back GRAY
        val fallback = !locked && head.hingeFallback
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

    // the run shares one network, so a segment turns with the rest of its own deck and nothing else
    override fun propagateRotationTo(
        target: KineticBlockEntity,
        stateFrom: BlockState,
        stateTo: BlockState,
        diff: BlockPos,
        connectedViaAxes: Boolean,
        connectedViaCogs: Boolean
    ): Float {
        if (target is RollerConveyorBlockEntity && !connectedViaAxes)
            return if (controllerPosition() == target.controllerPosition()) 1f else 0f
        return 0f
    }

    override fun write(compound: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        controller?.let { compound.put("Controller", NbtUtils.writeBlockPos(it)) }
        compound.putBoolean("IsController", isController)
        compound.putInt("Length", deckLength)
        compound.putInt("Index", index)
        if (isController) {
            inventory?.let { compound.put("Inventory", it.write(registries)) }
            compound.putString("Hinge", hingeSide.serializedName)
            hingePivot?.let { compound.put("HingePivot", NbtUtils.writeBlockPos(it)) }
            compound.putBoolean("HingeOwned", hingeOwned)
            compound.putString("HingeSub", hingeSub)
            compound.putFloat("HingeAngle", hingeAngle)
            hingeFacing?.let { compound.putString("HingeFacing", it.serializedName) }
            compound.putInt("HingePivotIndex", hingeIndex)
            hingeWorldPivot?.let { compound.put("HingeWorldPivot", NbtUtils.writeBlockPos(it)) }
            compound.putBoolean("HingeGravity", hingeGravity)
            compound.putBoolean("HingeLocked", hingeLocked)
            compound.putBoolean("HingeFallback", hingeFallback)
            compound.putFloat("HingeSwing", swingSpeed)
        }
        super.write(compound, registries, clientPacket)
    }

    override fun read(compound: CompoundTag, registries: HolderLookup.Provider, clientPacket: Boolean) {
        super.read(compound, registries, clientPacket)
        if (compound.getBoolean("IsController")) controller = worldPosition
        else controller = NbtUtils.readBlockPos(compound, "Controller").orElse(null)
        index = compound.getInt("Index")
        deckLength = compound.getInt("Length")
        if (isController) {
            inventory?.read(compound.getCompound("Inventory"), registries)
            val hingeName = compound.getString("Hinge")
            hingeSide = RollerHinge.Side.entries.firstOrNull { it.serializedName == hingeName }
                ?: RollerHinge.Side.entries.firstOrNull { it.serializedName.equals(hingeName, ignoreCase = true) }
                ?: RollerHinge.Side.NONE
            hingePivot = NbtUtils.readBlockPos(compound, "HingePivot").orElse(null)
            hingeOwned = compound.getBoolean("HingeOwned")
            hingeSub = compound.getString("HingeSub")
            hingeAngle = compound.getFloat("HingeAngle")
            val hingeFacingName = compound.getString("HingeFacing")
            hingeFacing = Direction.byName(hingeFacingName)
                ?: Direction.values().firstOrNull { it.name.equals(hingeFacingName, ignoreCase = true) }
            hingeIndex = compound.getInt("HingePivotIndex")
            hingeWorldPivot = NbtUtils.readBlockPos(compound, "HingeWorldPivot").orElse(null)
            hingeGravity = compound.getBoolean("HingeGravity")
            // a run saved before the latch existed was always held on its shaft, so it reads as locked
            hingeLocked = !compound.contains("HingeLocked") || compound.getBoolean("HingeLocked")
            hingeFallback = compound.getBoolean("HingeFallback")
            swingSpeed = compound.getFloat("HingeSwing")
            alignSync()
        }
    }

    override fun destroy() {
        if (isController) inventory?.ejectAll()
        super.destroy()
    }

    override fun invalidate() {
        super.invalidate()
        invalidateCapabilities()
    }

    // a deck the client holds steps itself, so it is written down the moment its own level hands it over
    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level.isClientSide) registerClientDeck(this)
    }

    fun addPassenger(entity: Entity, pos: BlockPos, state: BlockState) {
        if (!isController) {
            controllerBE()?.addPassenger(entity, pos, state)
            return
        }
        val info = passengers[entity]
        if (info == null) {
            passengers[entity] = DeckPassenger(pos, state)
            entity.setOnGround(true)
            return
        }
        if (info.ticksSinceLastCollision != 0 || pos == entity.blockPosition()) info.refresh(pos, state)
    }

    private fun controllerBE(): RollerConveyorBlockEntity? {
        val pos = controller ?: return null
        return level?.getBlockEntity(pos) as? RollerConveyorBlockEntity
    }

    private fun initializeItemHandler() {
        val world = level ?: return
        if (world.isClientSide || itemHandler != null) return
        val deck = controllerBE() ?: return
        val inventory = deck.inventory ?: return
        itemHandler = ItemHandlerRollerSegment(inventory, index)
        invalidateCapabilities()
    }

    // the drive belongs to the run, so a feed anywhere along it is judged by the controller's speed
    private fun runKineticSpeed(): Float = (controllerBE() ?: this).kineticSpeed()

    // a run moves its loads by its drive, or by its own slope while the drive stands still
    private fun runMoves(): Boolean = runKineticSpeed() != 0f || hingeOffLevel()

    // a load that touches a deck is taken in on the face it touched, whichever face that is
    fun absorb(stack: ItemStack, face: RollerDeckFace): ItemStack =
        inventory?.insertOn(face, index, stack, face.side) ?: stack

    // the lower lane only exists while the run is assembled, and only a load pushed in from below rides it
    private fun canInsertFrom(side: Direction): Boolean {
        if (hingeAssembled() && !runMoves()) return false
        val face = RollerDeckFace.of(side)
        if (face == RollerDeckFace.LOWER) return hingeAssembled()
        return movementFacing != side.opposite
    }

    private fun isOccupied(side: Direction): Boolean {
        val inventory = inventory ?: return true
        if (hingeAssembled() && !runMoves()) return true
        val face = RollerDeckFace.of(side)
        if (face == RollerDeckFace.LOWER && !hingeAssembled()) return true
        if (face != RollerDeckFace.LOWER && movementFacing == side.opposite) return true
        return !inventory.canInsertAtFromSide(index, side, face.lane)
    }

    // items pushed in from a funnel or another deck enter at this segment, facing the way the deck travels
    private fun insertFromSide(transported: TransportedItemStack, side: Direction, simulate: Boolean): ItemStack {
        val inventory = inventory ?: return transported.stack
        if (isOccupied(side)) return transported.stack
        if (simulate) return ItemStack.EMPTY
        val inserted = transported.copy()
        (inserted as RollerMomentumAccess).`waterparked$setDeckLane`(RollerDeckFace.of(side).lane)
        inserted.beltPosition = index + .5f - Math.signum(directionAwareTravelSpeed) / 16f
        if (!side.axis.isVertical) {
            if (movementFacing != side) {
                inserted.sideOffset = side.axisDirection.step * .675f
                if (side.axis == Direction.Axis.X) inserted.sideOffset *= -1
            } else {
                val world = level
                val extraOffset =
                    if (inserted.prevBeltPosition != 0f && world != null &&
                        RollerDeck.isSegment(world, worldPosition.relative(movementFacing.opposite))
                    ) .26f else 0f
                inserted.beltPosition =
                    if (directionAwareTravelSpeed > 0) index - extraOffset else index + 1 + extraOffset
            }
        }
        inserted.prevSideOffset = inserted.sideOffset
        inserted.insertedAt = index
        inserted.insertedFrom = side
        inserted.prevBeltPosition = inserted.beltPosition
        inventory.addItem(inserted)
        setChanged()
        sendData()
        return ItemStack.EMPTY
    }

    // funnels and arms only reach a hinged run whose shaft turns, never one standing still under gravity
    private fun applyToAllItems(maxDistanceFromCenter: Float, processFunction: Function<TransportedItemStack, TransportedResult>) {
        if (gravityOnly()) return
        inventory?.applyToEachWithin(index + .5f, maxDistanceFromCenter) { processFunction.apply(it) }
    }

    private fun getWorldPositionOf(transported: TransportedItemStack): Vec3 =
        controllerBE()?.vectorForOffset(transported.beltPosition) ?: Vec3.ZERO

    // the run drags whatever stands on it the way a belt does, and drops whatever left the deck
    private fun carryPassengers() {
        if (passengers.isEmpty()) return
        val left = ArrayList<Entity>()
        for ((entity, info) in passengers) {
            if (!entity.isAlive || info.ticksSinceLastCollision > 1) {
                left.add(entity)
                continue
            }
            info.tick()
            carry(entity, info)
        }
        left.forEach(passengers::remove)
    }

    private fun carry(entity: Entity, info: DeckPassenger) {
        if (exemptFromCarry(entity)) return skipCarry(entity, info, "exempt")
        if (entity is Player && !CARRY_PLAYERS) return
        val pos = info.lastCollidedPos
        val state = info.lastCollidedState
        if (abs(getSpeed()) < 1f) return
        val fall = entity.deltaMovement.y
        val pose = plotPose()
        val foot = Vec3(entity.x, entity.boundingBox.minY, entity.z)
        val localFoot = if (pose == null) foot else pose.transformPositionInverse(foot)
        val height = localFoot.y - pos.y
        val face = RollerConveyorBlock.contactFace(pose, pos, entity)
        if (abs(height - face.surface) > RollerDeckFace.BAND) return skipCarry(entity, info, "off the deck dy=$fall")

        val facing = state.getValue(RollerConveyorBlock.HORIZONTAL_FACING)
        val speed = carriedMovementSpeed()
        var travel = Vec3.atLowerCornerOf(movementFacing.normal).scale(speed.toDouble())
        val diffCenter =
            if (facing.axis == Direction.Axis.Z) (pos.x + .5 - localFoot.x) else (pos.z + .5 - localFoot.z)
        if (abs(diffCenter) > 48 / 64.0) return skipCarry(entity, info, "off the middle of the deck dy=$fall")

        val centeringDirection = Direction.get(Direction.AxisDirection.POSITIVE, facing.clockWise.axis).normal
        var centering = Vec3.atLowerCornerOf(centeringDirection)
            .scale(diffCenter * min(abs(speed).toDouble(), 0.1) * 4)
        var hold = Vec3(0.0, face.surface - height, 0.0)
        var normal = Vec3(0.0, face.lift, 0.0)
        var across = Vec3.atLowerCornerOf(
            Direction.get(Direction.AxisDirection.POSITIVE, RollerConveyorBlock.rotationAxisOf(state)).normal
        )
        if (pose != null) {
            travel = pose.transformNormal(travel)
            centering = pose.transformNormal(centering)
            hold = pose.transformNormal(hold)
            normal = pose.transformNormal(normal)
            across = pose.transformNormal(across)
        }
        var movement = travel
        if (entity !is LivingEntity || (entity.zza == 0f && entity.xxa == 0f)) movement = movement.add(centering)
        if (pose != null) {
            movement = movement.add(hold)
            val motion = entity.deltaMovement
            val into = motion.dot(normal)
            val sideways = motion.dot(across)
            entity.setDeltaMovement(
                motion.subtract(normal.scale(if (into < 0.0) into else 0.0)).subtract(across.scale(sideways))
            )
        }
        skipCarry(entity, info, "moved $speed dy=$fall")

        entity.fallDistance = 0f
        entity.move(MoverType.SELF, movement)
        entity.setOnGround(true)
    }

    // temporary: one line per passenger per outcome, so a load the run never moves names its own gate
    private fun skipCarry(entity: Entity, info: DeckPassenger, reason: String) {
        if (info.reported == reason) return
        info.reported = reason
        CreateWaterparked.LOGGER.debug(
            "[roller carry] deck={} entity={} at={} result={}", worldPosition, entity.type, info.lastCollidedPos, reason
        )
    }

    // the deck only carries entity passengers: a floating contraption or sub-level runs on its own physics next
    // to it, and an id listed in the config is left to its own physics too. An unknown or malformed id only ever
    // answers at DEBUG, and never stops the carry of anything else
    private fun exemptFromCarry(entity: Entity): Boolean {
        val exempt = ModConfig.rollerDeckCarryExemptEntities()
        if (exempt.isEmpty()) return false
        val id = EntityType.getKey(entity.type)
        for (entry in exempt) {
            val key = ResourceLocation.tryParse(entry)
            if (key == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
                CreateWaterparked.LOGGER.debug("[roller carry] exempt entity id is unknown or malformed: {}", entry)
                continue
            }
            if (key == id) return true
        }
        return false
    }

    private class DeckPassenger(pos: BlockPos, state: BlockState) {
        var reported: String = ""
        var ticksSinceLastCollision: Int = 0
            private set
        var lastCollidedPos: BlockPos = pos.immutable()
            private set
        var lastCollidedState: BlockState = state
            private set

        fun refresh(pos: BlockPos, state: BlockState) {
            ticksSinceLastCollision = 0
            lastCollidedPos = pos.immutable()
            lastCollidedState = state
        }

        fun tick() {
            ticksSinceLastCollision++
        }
    }

    companion object {
        private const val DEFAULT_SPEED = 32f
        // how many ticks a client copy may catch up in one go before it keeps its keyframe instead
        private const val MIRROR_CATCH_UP_TICKS = 20L
        // the decks a client holds, drawn or not: a plot copy is never ticked by the world, so the client steps it
        private val clientDecks = ArrayList<RollerConveyorBlockEntity>()

        @JvmStatic
        fun registerClientDeck(deck: RollerConveyorBlockEntity) {
            if (clientDecks.none { it === deck }) clientDecks.add(deck)
        }

        // one step per game tick for every deck the client holds, and a deck that is gone is dropped right here
        @JvmStatic
        fun tickClientDecks() {
            val iterator = clientDecks.iterator()
            while (iterator.hasNext()) {
                val deck = iterator.next()
                if (deck.isRemoved || deck.level == null) {
                    iterator.remove()
                    continue
                }
                deck.mirrorTick()
            }
        }

        @JvmStatic
        fun forgetClientDecks() {
            clientDecks.clear()
        }

        internal const val HINGE_CHECK_TICKS = 20L
        // a run already in a plot repeats its assembly state, since the first sync can miss the client
        private const val HINGE_SYNC_TICKS = 20L
        // how far under a deck the lower lane reaches for a load, and how close a load has to be to touch it
        private const val LOWER_REACH = 0.6
        private const val LOWER_TOUCH = 0.02
        // a flat hinged run still conveys its loads; the two ends keep a tilting run from flip flopping
        const val HINGE_TILT_ENTER = 5f
        const val HINGE_TILT_EXIT = 3f
        // the client only needs the tilt mode and the angle when one of them really moved
        private const val SYNC_ANGLE_DEGREES = 1f
        // a player rides the deck like any other rider and leaves it the same way: walking or jumping off
        const val CARRY_PLAYERS = true

        @JvmStatic
        fun registerItemCapability(event: RegisterCapabilitiesEvent) {
            event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.ROLLER_CONVEYOR_BE
            ) { be, _ -> be.handler() }
        }
    }
}
