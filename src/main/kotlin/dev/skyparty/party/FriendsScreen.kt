package dev.skyparty.party

import dev.skyparty.cosmetics.SkypartyBadge
import dev.skyparty.minigames.ui.GameColors
import dev.skyparty.minigames.ui.GameScreen
import dev.skyparty.minigames.ui.HubTabs
import dev.skyparty.net.ActiveUsers
import dev.skyparty.ui.Theme
import dev.skyparty.util.Chat
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Hub tab with your Hypixel friends: who is in your lobby, who is online and where, who has Skyparty.
 * "Invite" sends /p invite only when you click it.
 */
class FriendsScreen : GameScreen("Friends") {
	override val backToHub = false
	private var scroll = 0
	private var maxScroll = 0
	private var listBox = intArrayOf(0, 0, 0, 0)
	private val invited = HashMap<String, Long>()

	private enum class Group(val label: String, val color: Int) {
		HERE("In your lobby", 0xFF7CE38B.toInt()), ONLINE("Online", 0xFFFFD866.toInt()), OFFLINE("Offline", 0xFF8A8A99.toInt())
	}

	private class Row(val name: String, val group: Group, val where: String, val skyparty: Boolean)

	private fun rows(): List<Row> {
		val here = FriendTracker.friendsHere
		return FriendTracker.names.map { n ->
			val l = n.lowercase()
			val st = FriendTracker.statusOf(n)
			val sp = ActiveUsers.isUserName(n)
			val group = when {
				l in here -> Group.HERE
				sp || !st.isNullOrEmpty() -> Group.ONLINE
				else -> Group.OFFLINE
			}
			val where = when {
				group == Group.HERE -> "Same server as you"
				!st.isNullOrEmpty() && st != "Online" -> st
				sp || st == "Online" -> "Online"
				st == "" -> "Offline"
				else -> "Unknown – sync to update"
			}
			Row(n, group, where, sp)
		}.sortedWith(compareBy<Row>({ it.group.ordinal }, { !it.skyparty }, { it.name.lowercase() }))
	}

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = hubChrome(g, HubTabs.FRIENDS)
		val m = 8
		val x = m
		val y = top + m
		val w = width - m * 2
		val h = height - y - m
		panel(g, x, y, w, h, "Friends")
		val all = rows()
		val counts = Group.entries.associateWith { gr -> all.count { it.group == gr } }
		val summary = "§a${counts[Group.HERE]} here §8· §e${counts[Group.ONLINE]} online §8· §7${all.size} total"
		g.text(font, summary, x + 6 + font.width("Friends") + 10, y + 5, Theme.TEXT, false)
		val syncing = FriendTracker.syncing
		button(g, x + w - 66, y + 2, 60, 12, if (syncing) "Syncing…" else "⟳ Sync", mouseX, mouseY, enabled = !syncing,
			hint = "Reads your /f list from Hypixel (output hidden)") { FriendTracker.sync(loud = true) }

		val lx = x + 6
		val ly = y + 20
		val lw = w - 12
		val lh = h - 26
		listBox = intArrayOf(lx, ly, lw, lh)
		if (all.isEmpty()) {
			centered(g, "§7No friends known yet.", x + w / 2, ly + lh / 2 - 14, Theme.TEXT)
			centered(g, "§7Press §fSync§7, or open §f/f list §7in chat once.", x + w / 2, ly + lh / 2 - 2, Theme.TEXT)
			return
		}
		val rowH = 20
		var contentH = 0
		var last: Group? = null
		for (r in all) { if (r.group != last) { contentH += 13; last = r.group }; contentH += rowH }
		maxScroll = (contentH - lh).coerceAtLeast(0)
		scroll = scroll.coerceIn(0, maxScroll)

		g.enableScissor(lx, ly, lx + lw, ly + lh)
		var cy = ly - scroll
		last = null
		val now = System.currentTimeMillis()
		for (r in all) {
			if (r.group != last) {
				last = r.group
				if (cy + 13 > ly && cy < ly + lh) g.text(font, "§l${r.group.label}", lx + 2, cy + 3, r.group.color, false)
				cy += 13
			}
			if (cy + rowH > ly && cy < ly + lh) {
				val visible = cy >= ly && cy + rowH <= ly + lh
				val hover = visible && hovered(lx, cy, lw, rowH - 2, mouseX, mouseY)
				g.fill(lx, cy, lx + lw, cy + rowH - 2, if (hover) GameColors.CARD_HOVER else GameColors.CARD)
				g.fill(lx, cy, lx + 2, cy + rowH - 2, r.group.color)
				// Status dot, name, Skyparty star.
				g.fill(lx + 7, cy + 7, lx + 11, cy + 11, r.group.color)
				g.text(font, r.name, lx + 15, cy + 5, Theme.TEXT, true)
				var nx = lx + 15 + font.width(r.name) + 4
				if (r.skyparty) {
					g.text(font, "✦", nx, cy + 5, SkypartyBadge.COLOR or 0xFF000000.toInt(), true)
					if (hovered(nx, cy, 8, rowH - 2, mouseX, mouseY)) tooltip("Uses Skyparty – you can play minigames together")
					nx += 10
				}
				val bw = 54
				g.text(font, fit("§7${r.where}", lx + lw - bw - 10 - nx), nx + 4, cy + 5, Theme.TEXT, false)
				if (visible) {
					val sent = (invited[r.name.lowercase()] ?: 0L) > now - 60_000
					button(g, lx + lw - bw - 3, cy + 2, bw, 14, if (sent) "✔ Invited" else "Invite", mouseX, mouseY,
						enabled = !sent && r.group != Group.OFFLINE, color = GameColors.GREEN,
						hint = if (r.group == Group.OFFLINE) "Offline" else "Sends /p invite ${r.name}") {
						invited[r.name.lowercase()] = System.currentTimeMillis()
						Chat.command("p invite ${r.name}")
					}
				}
			}
			cy += rowH
		}
		g.disableScissor()
		if (maxScroll > 0) {
			val barH = (lh * lh / (lh + maxScroll)).coerceAtLeast(12)
			val barY = ly + ((lh - barH) * scroll / maxScroll)
			g.fill(lx + lw + 1, barY, lx + lw + 3, barY + barH, Theme.accent(0xFF))
		}
	}

	override fun onScroll(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		val (x, y, w, h) = listBox
		if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
			scroll = (scroll - (scrollY * 20).toInt()).coerceIn(0, maxScroll)
			return true
		}
		return super.onScroll(mouseX, mouseY, scrollX, scrollY)
	}
}
