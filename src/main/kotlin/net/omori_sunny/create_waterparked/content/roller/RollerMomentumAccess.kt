package net.omori_sunny.create_waterparked.content.roller

// per item deck momentum on Create's transported stack, implemented by TransportedItemStackRollerSpeedMixin
interface RollerMomentumAccess {

    fun `waterparked$rollerSpeed`(): Float

    fun `waterparked$setRollerSpeed`(speed: Float)

    fun `waterparked$deckLane`(): Int

    fun `waterparked$setDeckLane`(lane: Int)
}
