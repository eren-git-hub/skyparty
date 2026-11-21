package dev.skyparty.cosmetics

import dev.skyparty.module.impl.party.FriendAlerts
import dev.skyparty.net.ActiveUsers
import net.minecraft.network.chat.Component
import java.util.UUID

/** A small pink ✦ after the names of players who use Skyparty too (name tags and tab list). Visual only. */
object SkypartyBadge {
	const val COLOR = 0xFF7EB6

	@JvmStatic
	fun decorate(uuid: UUID?, text: Component): Component {
		if (uuid == null || !FriendAlerts.badge.value || !ActiveUsers.isUser(uuid)) return text
		return text.copy().append(Component.literal(" ✦").withColor(COLOR))
	}
}
