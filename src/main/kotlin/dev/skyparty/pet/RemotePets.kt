package dev.skyparty.pet

import com.google.gson.JsonObject
import dev.skyparty.module.impl.pets.CustomPets
import dev.skyparty.net.Net
import dev.skyparty.util.Location
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Pets of other Skyparty players. Everyone on the same Hypixel server instance (the "m12AB" id from the
 * sidebar) shares one backend room "pets:<server>" and announces their pet's look once with "pet.info"
 * (again when someone new joins or the look changes). Movement is NOT sent: each client animates the
 * pet next to the owner's player entity it already sees, so the traffic is a few tiny messages per lobby.
 *
 * Only active while the backend is connected (Online module); nothing is ever sent to Hypixel.
 */
object RemotePets {
	private val log = LoggerFactory.getLogger("Skyparty/RemotePets")

	/** "11/07/26 m12AB" (date + server id) on the Hypixel sidebar. */
	private val SERVER_LINE = Regex("\\d{2}/\\d{2}/\\d{2}\\s+([A-Za-z0-9]{2,12})")
	private const val ANNOUNCE_DELAY_MS = 1_500L

	class Info(val name: String, val body: Int, val accent: Int, val species: PetSpecies = PetSpecies.DRAGON)
	class Pet(val owner: UUID, var info: Info) {
		var model = PetModels.create(info.species)
		val follower = PetFollower()
		val body get() = info.body
		val accent get() = info.accent
	}

	private val infos = HashMap<UUID, Info>()
	private val pets = HashMap<UUID, Pet>()

	private var room: Net.Room? = null
	private var roomTransport: Net.Transport? = null
	private var lastSent: String? = null
	private var announceAt = 0L
	private var ticks = 0
	private var serverId: String? = null

	fun visible(): Collection<Pet> = pets.values

	/** Players whose pet info we have (for tests and the pets screen). */
	val knownOwners: Set<UUID> get() = infos.keys

	fun tick(mc: Minecraft) {
		if (ticks++ % 20 == 0) serverId = currentServer()
		syncRoom()
		val r = room
		if (r != null) {
			val look = ownLook()
			val now = System.currentTimeMillis()
			if (look != lastSent && now >= announceAt) announce(r, look)
		}
		tickPets(mc)
	}

	private fun currentServer(): String? {
		if (Location.isDev && Minecraft.getInstance().hasSingleplayerServer()) return "dev"
		if (!Location.onHypixel) return null
		return Location.sidebarLines.firstNotNullOfOrNull { SERVER_LINE.find(it)?.groupValues?.get(1) }
	}

	private fun syncRoom() {
		val wanted = serverId?.takeIf { CustomPets.enabled && Net.transport.connected }?.let { "pets:$it" }
		val current = room
		if (current?.id == wanted && (wanted == null || roomTransport === Net.transport)) return
		current?.leave()
		room = null
		infos.clear()
		lastSent = null
		if (wanted == null) return
		val r = Net.join(wanted)
		roomTransport = Net.transport
		r.onMessage = ::onMessage
		r.onMembersChanged = { members ->
			infos.keys.retainAll(members)
			// Someone new is here: tell them about our pet (after a short delay, so a lobby of joins is one send).
			lastSent = null
			announceAt = System.currentTimeMillis() + ANNOUNCE_DELAY_MS
		}
		room = r
		announceAt = System.currentTimeMillis() + ANNOUNCE_DELAY_MS
		log.info("Pet room is now {}", wanted)
	}

	/** What we announce, or "hidden" when the pet is set to Only Me. */
	private fun ownLook(): String =
		if (CustomPets.visibility.value != "Everyone") "hidden"
		else "${CustomPets.petName.value}|${CustomPets.bodyColor.value}|${CustomPets.accentColor.value}|${CustomPets.species.value}"

	private fun announce(r: Net.Room, look: String) {
		lastSent = look
		if (look == "hidden") {
			r.send("pet.hide")
			return
		}
		r.send("pet.info", JsonObject().apply {
			addProperty("name", CustomPets.petName.value.take(16))
			addProperty("body", CustomPets.bodyColor.value)
			addProperty("accent", CustomPets.accentColor.value)
			addProperty("species", PetSpecies.own.name.lowercase())
		})
	}

	private fun onMessage(msg: Net.Message) {
		if (msg.from == Net.selfId) return
		when (msg.type) {
			"pet.info" -> runCatching {
				val p = msg.payload
				infos[msg.from] = Info(
					p["name"]?.asString?.take(16) ?: "",
					p["body"].asInt or 0xFF000000.toInt(),
					p["accent"].asInt or 0xFF000000.toInt(),
					PetSpecies.entries.firstOrNull { it.name.equals(p["species"]?.asString, ignoreCase = true) } ?: PetSpecies.DRAGON,
				)
			}.onFailure { log.debug("Bad pet.info from {}", msg.fromName) }
			"pet.hide" -> infos.remove(msg.from)
		}
	}

	private fun tickPets(mc: Minecraft) {
		val level = mc.level
		if (level == null || infos.isEmpty()) {
			pets.clear()
			return
		}
		pets.keys.retainAll(infos.keys)
		for ((uuid, info) in infos) {
			val owner = level.getPlayerByUUID(uuid)
			if (owner == null || owner.isInvisible) {
				pets.remove(uuid)
				continue
			}
			val pet = pets.getOrPut(uuid) { Pet(uuid, info) }
			pet.info = info
			if (pet.model.species != info.species) pet.model = PetModels.create(info.species)
			pet.follower.tick(owner)
		}
	}

	/** Test helper: pretend another player announced a pet. */
	fun injectForTest(uuid: UUID, info: Info) {
		infos[uuid] = info
	}
}
