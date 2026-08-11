package net.omori_sunny.create_waterparked.content.sit

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.SynchedEntityData

// invisible ride target for the vanilla sitting pose
class SlideSitEntity(type: EntityType<SlideSitEntity>, level: Level) : Entity(type, level) {

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        // no synced data
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        // no save data
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        // no save data
    }

    override fun positionRider(entity: Entity, mover: Entity.MoveFunction) {
        // trajectory drives the rider
    }

    override fun getPassengerRidingPosition(passenger: Entity): Vec3 = position()

    override fun isPickable(): Boolean = false

    override fun isPushable(): Boolean = false

    override fun isAttackable(): Boolean = false

    override fun isInvisible(): Boolean = true

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = false
}
