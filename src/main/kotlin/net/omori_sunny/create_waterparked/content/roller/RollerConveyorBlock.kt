package net.omori_sunny.create_waterparked.content.roller

import com.simibubi.create.AllItems
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock
import com.simibubi.create.content.kinetics.base.IRotate
import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import com.simibubi.create.foundation.block.IBE
import com.simibubi.create.foundation.block.ProperWaterloggedBlock
import com.simibubi.create.content.logistics.box.PackageEntity
import com.simibubi.create.foundation.item.ItemHelper
import dev.ryanhcode.sable.Sable
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer
import dev.ryanhcode.sable.companion.math.Pose3d
import net.createmod.catnip.math.VecHelper
import net.createmod.catnip.placement.PlacementHelpers
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.omori_sunny.create_waterparked.CreateWaterparked
import net.omori_sunny.create_waterparked.client.placement.RollerConveyorPlacementHelper
import net.omori_sunny.create_waterparked.content.registry.ModBlockEntities
import net.omori_sunny.create_waterparked.content.registry.ModItems

// the roller deck: a run of self driven segments that carry their own loads, with no Create belt involved
class RollerConveyorBlock(properties: BlockBehaviour.Properties) :
    HorizontalKineticBlock(properties), IBE<RollerConveyorBlockEntity>, ProperWaterloggedBlock {

    init {
        registerDefaultState(
            defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, false)
                .setValue(HINGE, RollerHinge.Side.NONE)
        )
    }

    override fun getBlockEntityClass(): Class<RollerConveyorBlockEntity> = RollerConveyorBlockEntity::class.java

    override fun getBlockEntityType(): BlockEntityType<out RollerConveyorBlockEntity> =
        ModBlockEntities.ROLLER_CONVEYOR_BE

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(BlockStateProperties.WATERLOGGED)
        builder.add(HINGE)
    }

    // a segment dropped beside a run takes that run's direction, so a deck is never built travelling two ways
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val placed = super.getStateForPlacement(context) ?: return null
        val landingPos = context.clickedPos.relative(context.clickedFace)
        val neighbour = runFacingAt(context.level, landingPos) ?: runFacingAt(context.level, context.clickedPos)
        val faced = if (neighbour == null) placed else placed.setValue(HORIZONTAL_FACING, neighbour)
        return withWater(faced, context)
    }

    // placing or wrenching a segment re-gathers the run around the direction that segment was just given
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (level.isClientSide || isMoving || state == oldState) return
        if (RollerDeck.isHinged(level, pos)) return
        RollerDeck.turnRun(level, pos, state.getValue(HORIZONTAL_FACING))
        RollerHinge.adoptShaft(level, pos, state)
    }

    // a shaft a player puts down by hand is picked up as the hinge as well, not only an installed one
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        fromPos: BlockPos,
        isMoving: Boolean
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)
        logConnection(state, level, pos, fromPos)
        if (level.isClientSide || isMoving || state.getValue(HINGE) != RollerHinge.Side.NONE) return
        RollerHinge.adoptShaft(level, pos, state)
    }

    // temporary: which gate a placed kinetic neighbour fails on, logged once per pair
    private fun logConnection(state: BlockState, level: Level, pos: BlockPos, fromPos: BlockPos) {
        if (level.isClientSide) return
        val there = level.getBlockState(fromPos)
        val other = there.block as? IRotate ?: return
        if (!loggedPairs.add("$pos|$fromPos")) return
        val dir = Direction.getNearest(
            (fromPos.x - pos.x).toDouble(), (fromPos.y - pos.y).toDouble(), (fromPos.z - pos.z).toDouble()
        )
        val deck = level.getBlockEntity(pos) as? RollerConveyorBlockEntity
        val head = deck?.controllerPosition()?.let { level.getBlockEntity(it) as? RollerConveyorBlockEntity } ?: deck
        CreateWaterparked.LOGGER.debug(
            "[roller hinge diag] pair deck={} dir={} deckAxis={} deckHasShaft={} other={} otherAxis={} otherHasShaft={} otherKinetic={} " +
                "hinge={} sub={} len={} speed={} head={}",
            pos, dir, rotationAxisOf(state), hasShaftTowards(level, pos, state, dir), there.block,
            other.getRotationAxis(there), other.hasShaftTowards(level, fromPos, there, dir.opposite),
            level.getBlockEntity(fromPos) is KineticBlockEntity,
            head?.hingeSide, head?.hingeSub?.isNotEmpty() == true, head?.deckLength, head?.kineticSpeed(),
            head?.blockPos
        )
    }

    // catnip's placement route only calls setBlockAndUpdate, so the segment it lands is adopted from here
    private fun adoptPlaced(level: Level, pos: BlockPos) {
        if (level.isClientSide) return
        if (RollerHinge.adoptShaft(level, pos, level.getBlockState(pos))) return
        for (side in Direction.values()) {
            val neighbour = pos.relative(side)
            val state = level.getBlockState(neighbour)
            if (state.block !is RollerConveyorBlock) continue
            if (RollerHinge.adoptShaft(level, neighbour, state)) return
        }
    }

    override fun getFluidState(state: BlockState): FluidState = fluidState(state)

    override fun getRotationAxis(state: BlockState): Direction.Axis = rotationAxisOf(state)

    // every segment takes a shaft on its two width faces, so any of them can carry the run's hinge
    override fun hasShaftTowards(level: LevelReader, pos: BlockPos, state: BlockState, face: Direction): Boolean =
        face.axis == getRotationAxis(state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    // a deck inside a sub-level cannot be aimed at, so no outline and no breaking frame is drawn for it
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        if (insideSubLevel(level as? Level, pos)) Shapes.empty() else deckShape(state)

    // Create's belt overrides this and hands entity collision to its own belt shape, so this deck answers itself
    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = deckShape(state)

    // the visible model is mostly air, so it must not hide its neighbours
    override fun getOcclusionShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape = Shapes.empty()

    // a deck inside a sub-level belongs to the run alone, so a player cannot build into its cell
    override fun canBeReplaced(state: BlockState, context: BlockPlaceContext): Boolean {
        if (context.player != null && insideSubLevel(context.level, context.clickedPos)) return false
        return super.canBeReplaced(state, context)
    }

    // a deck in a sub-level takes no damage from a player, whatever tool they hold
    override fun getDestroyProgress(state: BlockState, player: Player, level: BlockGetter, pos: BlockPos): Float =
        if (insideSubLevel(level as? Level, pos)) 0f else super.getDestroyProgress(state, player, level, pos)

    // a deck inside a sub-level never breaks, so it never sheds dust or a sound for one
    override fun spawnAfterBreak(state: BlockState, level: ServerLevel, pos: BlockPos, stack: ItemStack, dropExperience: Boolean) {
        if (insideSubLevel(level, pos)) return
        super.spawnAfterBreak(state, level, pos, stack, dropExperience)
    }

    override fun getCloneItemStack(
        state: BlockState,
        target: HitResult,
        level: LevelReader,
        pos: BlockPos,
        player: Player
    ): ItemStack = ItemStack(ModItems.ROLLER_CONVEYOR)

    override fun updateShape(
        state: BlockState,
        side: Direction,
        neighborState: BlockState,
        level: LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos
    ): BlockState {
        updateWater(level, state, pos)
        if (!level.isClientSide && neighborState.block is RollerConveyorBlock) {
            val world = level as? Level
            if (world != null) RollerDeck.init(world, pos)
        }
        return state
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (!level.isClientSide && !isMoving && state.block != newState.block) {
            val deck = level.getBlockEntity(pos) as? RollerConveyorBlockEntity
            if (deck != null && deck.isController) deck.inventory?.ejectAll()
        }
        super.onRemove(state, level, pos, newState, isMoving)
        if (level.isClientSide || isMoving || state.block == newState.block) return
        val facing = state.getValue(HORIZONTAL_FACING)
        for (side in arrayOf(facing, facing.opposite)) {
            val neighbour = pos.relative(side)
            if (RollerDeck.isSegment(level, neighbour)) RollerDeck.init(level, neighbour)
        }
    }

    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        val speed = if (level.isClientSide) 0f
        else RollerEntry.speedFor(entity, landingSpeed(level, pos, entity), level.gameTime)
        RollerMomentum.hold(speed)
        handleEntity(level, pos, entity)
        RollerMomentum.clear()
    }

    override fun updateEntityAfterFallOn(blockGetter: BlockGetter, entity: Entity) {
        super.updateEntityAfterFallOn(blockGetter, entity)
        if (blockGetter !is Level) return
        val above = entity.blockPosition()
        val pos = when {
            blockGetter.getBlockState(above).block is RollerConveyorBlock -> above
            blockGetter.getBlockState(above.below()).block is RollerConveyorBlock -> above.below()
            else -> return
        }
        entityInside(blockGetter.getBlockState(pos), blockGetter, pos, entity)
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult
    ): ItemInteractionResult {
        // a wrench on a run flips its hinge latch, in its plot or still in the world. The server owns the
        // answer, so the client hands the click over instead of guessing a latch it cannot see.
        if (latchWrench(stack, player, hand)) {
            if (!level.isClientSide && RollerHingeTilt.toggleLock(level, pos)) return ItemInteractionResult.SUCCESS
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
        if (insideSubLevel(level, pos)) return ItemInteractionResult.SUCCESS
        if (!player.mayBuild() || hand != InteractionHand.MAIN_HAND)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        val shaft = RollerHinge.isShaft(stack)
        if (player.isShiftKeyDown) {
            if (!shaft) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
            if (!RollerHinge.uninstall(level, pos, player)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
            return ItemInteractionResult.SUCCESS
        }
        if (stack.item === ModItems.ROLLER_CONVEYOR) {
            val helper = PlacementHelpers.get(PLACEMENT_HELPER_ID)
            val offset = helper.getOffset(player, level, state, pos, hitResult, stack)
            val item = stack.item
            if (offset.isSuccessful && item is BlockItem) {
                val result = offset.placeInWorld(level, item, player, hand, hitResult)
                adoptPlaced(level, pos)
                return result
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
        if (shaft) {
            // a run that already carries its hinge leaves the face free for the real shaft
            if (RollerDeck.isHinged(level, pos)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
            if (!RollerHinge.install(level, pos, state, player, stack, hitResult.direction)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
            return ItemInteractionResult.SUCCESS
        }
        if (stack.isEmpty && BoatDeckInteraction.takeBoat(level, pos, player)) return ItemInteractionResult.SUCCESS
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
    }

    // the wrench that owns the latch: main hand, not sneaking, and the player is allowed to build there
    private fun latchWrench(stack: ItemStack, player: Player, hand: InteractionHand): Boolean =
        !player.isShiftKeyDown && hand == InteractionHand.MAIN_HAND && player.mayBuild() &&
            AllItems.WRENCH.isIn(stack)

    // the direction of the run a placement touches, preferring the neighbour whose front points back at us
    private fun runFacingAt(level: LevelReader, pos: BlockPos): Direction? {
        var fallback: Direction? = null
        for (side in Direction.Plane.HORIZONTAL) {
            val neighbour = pos.relative(side)
            if (!RollerDeck.isSegment(level, neighbour)) continue
            val facing = level.getBlockState(neighbour).getValue(HORIZONTAL_FACING)
            if (facing == side.opposite) return facing
            fallback = facing
        }
        return fallback
    }

    // every route into a deck ends here, so both faces and both discovery paths share one body
    private fun handleEntity(level: Level, pos: BlockPos, entity: Entity) {
        if (level.isClientSide) return
        takeIn(level, pos, entity, contactFace(Sable.HELPER.getContaining(level, pos)?.logicalPose(), pos, entity))
    }

    // the part of the incoming velocity that points along the deck travels with the load, either way
    private fun landingSpeed(level: Level, pos: BlockPos, entity: Entity): Float {
        if (level.isClientSide) return 0f
        val segment = level.getBlockEntity(pos) as? RollerConveyorBlockEntity ?: return 0f
        val sub = Sable.HELPER.getContaining(level, pos)
        if (sub == null && entity.deltaMovement.y > 0.0) return 0f
        val here = segment.deckFacing.normal
        var along = Vec3(here.x.toDouble(), 0.0, here.z.toDouble())
        if (sub != null) along = sub.logicalPose().transformNormal(along)
        return entity.deltaMovement.dot(along).toFloat()
    }

    companion object {
        private val ROLLER_TOP: VoxelShape = Shapes.box(0.0, 0.0, 0.0, 1.0, 3.0 / 16.0, 1.0)
        private val loggedPairs = HashSet<String>()

        private val PIN_X_A: VoxelShape = Shapes.box(0.0, 6.0 / 16.0, 6.0 / 16.0, 2.0 / 16.0, 10.0 / 16.0, 10.0 / 16.0)
        private val PIN_X_B: VoxelShape = Shapes.box(14.0 / 16.0, 6.0 / 16.0, 6.0 / 16.0, 1.0, 10.0 / 16.0, 10.0 / 16.0)
        private val PIN_Z_A: VoxelShape = Shapes.box(6.0 / 16.0, 6.0 / 16.0, 0.0, 10.0 / 16.0, 10.0 / 16.0, 2.0 / 16.0)
        private val PIN_Z_B: VoxelShape = Shapes.box(6.0 / 16.0, 6.0 / 16.0, 14.0 / 16.0, 10.0 / 16.0, 10.0 / 16.0, 1.0)

    // the inserted shaft leaves two solid pins on the deck's width faces, on top of the usual deck top
    private fun deckShape(state: BlockState): VoxelShape =
        if (state.getValue(HINGE) == RollerHinge.Side.NONE) ROLLER_TOP
        else if (rotationAxisOf(state) == Direction.Axis.X) Shapes.or(ROLLER_TOP, PIN_X_A, PIN_X_B)
        else Shapes.or(ROLLER_TOP, PIN_Z_A, PIN_Z_B)

        // the facing a deck reads is the horizontal kinetic block's own property, exposed under our name
        @JvmField
        val HORIZONTAL_FACING: Property<Direction> = HorizontalKineticBlock.HORIZONTAL_FACING

        @JvmField
        val HINGE: EnumProperty<RollerHinge.Side> = EnumProperty.create("hinge", RollerHinge.Side::class.java)

        // the same helper draws the ghost and places the segment that ghost points at
        private val PLACEMENT_HELPER_ID: Int = RollerConveyorPlacementHelper.register()

        // a load that touches a deck is taken in on the face it touched, and it stops moving under its own steam
        fun takeIn(level: Level, pos: BlockPos, entity: Entity, face: RollerDeckFace) {
            val segment = level.getBlockEntity(pos) as? RollerConveyorBlockEntity ?: return
            val stack = ItemHelper.fromItemEntity(entity)
            if (stack.isEmpty) {
                val motion = entity.deltaMovement
                if (motion.y < 0.0) entity.setDeltaMovement(motion.x, 0.0, motion.z)
                segment.addPassenger(entity, pos, level.getBlockState(pos))
                return
            }
            val pose = Sable.HELPER.getContaining(level, pos)?.logicalPose()
            if (pose == null && entity.deltaMovement.y > 0) return
            // packages and boats slide onto the deck centre of the face they touched, the way they do on a belt
            val target = VecHelper.getCenterOf(pos).add(0.0, 5 / 16.0 - face.drop, 0.0)
            val worldTarget = if (pose == null) target else pose.transformPosition(target)
            if (!PackageEntity.centerPackage(entity, worldTarget)) return
            val remainder = segment.absorb(stack, face)
            if (remainder.isEmpty) {
                entity.setDeltaMovement(Vec3.ZERO)
                RollerEntry.forget(entity)
                entity.discard()
                return
            }
            if (entity is ItemEntity && remainder.count != entity.item.count) entity.item = remainder
        }

        // the face an entity touches is read from its feet in the deck's frame, so a load under the deck reads lower
        fun contactFace(pose: Pose3d?, pos: BlockPos, entity: Entity): RollerDeckFace {
            if (pose == null) return RollerDeckFace.UPPER
            val foot = Vec3(entity.x, entity.boundingBox.minY, entity.z)
            return RollerDeckFace.at(pose.transformPositionInverse(foot).y - pos.y)
        }

        // a plot cell belongs to a sub-level, where our decks must stay exactly as the run left them
        fun insideSubLevel(level: Level?, pos: BlockPos): Boolean {
            val container = SubLevelContainer.getContainer(level ?: return false) ?: return false
            return container.inBounds(pos)
        }

        // a protected cell is one of our decks inside a sub-level, where players neither build nor mine
        fun isProtected(level: Level?, pos: BlockPos): Boolean =
            level != null && level.getBlockState(pos).block is RollerConveyorBlock && insideSubLevel(level, pos)

        // a deck's shaft runs across its width, which is also the axis every roller and the hinge turn about
        fun rotationAxisOf(state: BlockState): Direction.Axis = state.getValue(HORIZONTAL_FACING).clockWise.axis

        fun defaultProperties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(1.5f, 6.0f)
            .sound(SoundType.STONE)
            .friction(0.98f)
            .noOcclusion()
    }
}
