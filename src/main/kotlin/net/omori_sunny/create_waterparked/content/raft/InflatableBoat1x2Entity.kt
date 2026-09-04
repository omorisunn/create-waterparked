package net.omori_sunny.create_waterparked.content.raft

import net.omori_sunny.create_waterparked.content.registry.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

// inflatable boat like a boat: a LivingEntity riding mount with two seats at
// model coordinates P(8,2,10.5) and Q(8,2,21.5), mirrored across z=16.
// Right-click to sit, left-click breaking drops the dyed item.
class InflatableBoat1x2Entity(type: EntityType<out LivingEntity>, level: Level) : LivingEntity(type, level) {

    companion object {
        private val COLOR: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(InflatableBoat1x2Entity::class.java, EntityDataSerializers.INT)

        // seats sit on the deck centre line, y is the deck height (2/16 of a
        // block)
        private const val SEAT_HEIGHT = 2.0 / 16.0 // 0.125

        fun createAttributes(): AttributeSupplier.Builder =
            Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.MAX_HEALTH, 6.0)
    }

    var color: Int
        get() = entityData.get(COLOR)
        set(value) = entityData.set(COLOR, value)

    init {
        setNoGravity(false)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(COLOR, 0xFFFFFF)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains("Color")) color = tag.getInt("Color")
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putInt("Color", color)
    }

    override fun getBoundingBoxForCulling(): AABB = boundingBox.inflate(2.0)

    override fun tick() {
        super.tick()
        if (level().isClientSide) return
        // package-style: the insertion countdown recovers while not absorbed,
        // so the belt/depot centring animation replays after each insertion
        insertionDelay = (insertionDelay + 1).coerceAtMost(30)
        // buoyancy: gently rise until the boat is no longer submerged
        if (isInWater() && getY() < waterLevel()) {
            setDeltaMovement(deltaMovement.x, 0.08, deltaMovement.z)
        }
    }

    // package-style insertion countdown, see PackageEntity: while an absorbing
    // belt/depot centres the entity it slides toward the target spot and the
    // timer ticks down 3 per call; the host absorbs the boat once it hits 0
    var insertionDelay = 30
        private set

    fun decreaseInsertionTimer(targetSpot: Vec3?): Boolean {
        if (targetSpot != null) {
            setDeltaMovement(deltaMovement.scale(0.75).multiply(1.0, 0.25, 1.0))
            val pos = position().add(targetSpot.subtract(position()).scale(0.2))
            setPos(pos.x, pos.y, pos.z)
            val yawTarget = (yRot.toInt() / 90) * 90
            yRot = net.minecraft.util.Mth.rotLerp(0.5f, yRot, yawTarget.toFloat())
        }
        insertionDelay = (insertionDelay - 3).coerceAtLeast(0)
        return insertionDelay == 0
    }

    // the boat as a dyed item stack, shared by pickup / breaking / belt insert
    fun createItemStack(): ItemStack {
        val stack = ItemStack(ModItems.INFLATABLE_BOAT_1X2)
        stack.set(DataComponents.DYED_COLOR, net.minecraft.world.item.component.DyedItemColor(color, true))
        return stack
    }

    // surface height of the water column under the boat
    private fun waterLevel(): Double {
        var level = boundingBox.minY
        for (y in boundingBox.minY.toInt() downTo boundingBox.minY.toInt() - 4) {
            if (level().getFluidState(BlockPos(x.toInt(), y, z.toInt())).isSource) {
                level = y + 1.0 - 0.02
                break
            }
        }
        return level
    }

    // right-click to sit, boat-style: first passenger takes the front seat (z-),
    // the second takes the rear seat (z+). Sneak + right-click picks the boat
    // back up as a dyed item, package-style
    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        if (player.isSecondaryUseActive) {
            if (level().isClientSide) return InteractionResult.SUCCESS
            val stack = createItemStack()
            if (!player.abilities.instabuild && !player.addItem(stack)) {
                spawnAtLocation(stack, 0.5f)
            }
            discard()
            return InteractionResult.SUCCESS
        }
        if (level().isClientSide) return InteractionResult.CONSUME
        return if (player.startRiding(this)) InteractionResult.CONSUME else InteractionResult.PASS
    }

    // seat position: centred on the deck. Entity.getPassengerRidingPosition
    // adds this local offset to the entity position, so only the local point
    // is overridden here.
    private fun seatOffset(passenger: Entity): Vec3 {
        val local = Vec3(0.0, SEAT_HEIGHT, 0.0)
        // rotate the local offset with the boat's yaw, exactly like boat seats
        return local.yRot(-yRot * 0.017453292f)
    }

    override fun getPassengerAttachmentPoint(
        passenger: Entity,
        dimensions: EntityDimensions,
        partialTick: Float
    ): Vec3 = seatOffset(passenger)

    override fun canAddPassenger(passenger: Entity): Boolean = passengers.size < 2

    // collision locked to the rendered hull centre: the renderer's net offset
    // is T(0, 0.02, 0), so the hull centre sits 0.11375 above the entity
    // origin (hull spans y 0.02..0.2075), and the 1x1 box is centred there
    override fun makeBoundingBox(): AABB {
        val cy = y + 0.11375
        return AABB(
            x - 0.5, cy - 0.09375, z - 0.5,
            x + 0.5, cy + 0.09375, z + 0.5
        )
    }

    // package parity: not solid, so a freshly tossed boat cannot shove the
    // player; pushing the boat around still works through isPushable
    override fun canBeCollidedWith(): Boolean = false

    override fun isPickable(): Boolean = true

    // left-click breaking: the dyed boat goes straight into the attacker's
    // inventory (spills on the ground only when it is full or non-player)
    override fun hurt(damageSource: DamageSource, amount: Float): Boolean {
        if (level().isClientSide || isRemoved) return true
        discard()
        val stack = createItemStack()
        val attacker = damageSource.entity
        if (attacker is net.minecraft.server.level.ServerPlayer) {
            if (!attacker.inventory.add(stack)) spawnAtLocation(stack, 0.5f)
        } else {
            spawnAtLocation(stack, 0.5f)
        }
        return true
    }

    // ---- LivingEntity scaffolding: no armour, no drops ----

    override fun getArmorSlots(): Iterable<ItemStack> = emptyList()

    override fun getItemBySlot(slot: EquipmentSlot): ItemStack = ItemStack.EMPTY

    override fun setItemSlot(slot: EquipmentSlot, stack: ItemStack) {}

    override fun getMainArm(): HumanoidArm = HumanoidArm.RIGHT
}
