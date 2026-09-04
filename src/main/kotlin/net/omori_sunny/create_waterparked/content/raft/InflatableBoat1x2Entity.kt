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
        // buoyancy: gently rise until the boat is no longer submerged
        if (isInWater() && getY() < waterLevel()) {
            setDeltaMovement(deltaMovement.x, 0.08, deltaMovement.z)
        }
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
    // the second takes the rear seat (z+)
    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        if (player.isSecondaryUseActive) return InteractionResult.PASS
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

    override fun canBeCollidedWith(): Boolean = true

    override fun isPickable(): Boolean = true

    // left-click breaking drops the dyed item, boat-style
    override fun hurt(damageSource: DamageSource, amount: Float): Boolean {
        if (level().isClientSide || isRemoved) return true
        discard()
        val stack = ItemStack(ModItems.INFLATABLE_BOAT_1X2)
        stack.set(DataComponents.DYED_COLOR, net.minecraft.world.item.component.DyedItemColor(color, true))
        spawnAtLocation(stack, 0.5f)
        return true
    }

    // ---- LivingEntity scaffolding: no armour, no drops ----

    override fun getArmorSlots(): Iterable<ItemStack> = emptyList()

    override fun getItemBySlot(slot: EquipmentSlot): ItemStack = ItemStack.EMPTY

    override fun setItemSlot(slot: EquipmentSlot, stack: ItemStack) {}

    override fun getMainArm(): HumanoidArm = HumanoidArm.RIGHT
}
