package dev.sphc.srccon.core

enum class ProfileOwner { BUILT_IN, USER }

/** Immutable, absolute-set-state protocol definition. Commands must be safe to transmit more than once. */
class RelayProtocolProfile private constructor(
    val id: String,
    val name: String,
    val description: String,
    val serial: SerialConfiguration,
    val onHex: String,
    val offHex: String,
    val owner: ProfileOwner,
) {
    fun snapshotFor(target: RelayTarget): RelayConfigurationSnapshot = RelayConfigurationSnapshot(
        target = target,
        serial = serial,
        profileId = id,
        onCommand = HexCommandParser.parse(onHex),
        offCommand = HexCommandParser.parse(offHex),
    ).also { it.validate() }

    /** Editing a built-in profile always changes ownership and ID. */
    fun duplicateAsUser(newId: String, newName: String = name): RelayProtocolProfile =
        user(newId, newName, description, serial, onHex, offHex)

    companion object {
        internal fun builtIn(
            id: String,
            name: String,
            description: String,
            serial: SerialConfiguration,
            onHex: String,
            offHex: String,
        ): RelayProtocolProfile = RelayProtocolProfile(id, name, description, serial, onHex, offHex, ProfileOwner.BUILT_IN)

        fun user(
            id: String,
            name: String,
            description: String,
            serial: SerialConfiguration,
            onHex: String,
            offHex: String,
        ): RelayProtocolProfile {
            require(id.startsWith("user:") && id.length > 5) { "User profile ID must start with user:." }
            require(name.isNotBlank()) { "Profile name cannot be empty." }
            HexCommandParser.parse(onHex)
            HexCommandParser.parse(offHex)
            return RelayProtocolProfile(id, name, description, serial, onHex, offHex, ProfileOwner.USER)
        }
    }
}

object BuiltInRelayProfiles {
    val vctecNoyito: RelayProtocolProfile = RelayProtocolProfile.builtIn(
        id = "builtin:vctec-noyito-standard",
        name = "VCTEC / NOYITO",
        description = "Reference SeRelCam serial relay protocol",
        serial = SerialConfiguration(baudRate = 9600),
        onHex = "A0 01 01 A2",
        offHex = "A0 01 00 A1",
    )

    val all: List<RelayProtocolProfile> = listOf(vctecNoyito)
}
