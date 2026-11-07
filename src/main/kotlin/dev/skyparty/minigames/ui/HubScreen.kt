package dev.skyparty.minigames.ui

import dev.skyparty.minigames.CombatGuard
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameHub
import dev.skyparty.minigames.MinigameRegistry
import dev.skyparty.minigames.MinigameStore
import dev.skyparty.net.Net
import dev.skyparty.party.PartyTracker
import dev.skyparty.ui.Theme
import dev.skyparty.util.ColorUtil
import net.minecraft.client.gui.GuiGraphicsExtractor

/** "/sp games": list of registered minigames plus the room/lobby panel. */
class HubScreen : GameScreen("Skyparty Games") {
	override val backToHub = false
	private var scroll = 0
	private var maxScroll = 0
	private var listBox = intArrayOf(0, 0, 0, 0)

	override fun draw(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = hubChrome(g, HubTabs.GAMES)
		val margin = 8
		val sideW = (width * 0.40f).toInt().coerceIn(150, 220)
		val listX = margin
		val listY = top + margin
		val listW = width - sideW - margin * 3
		val listH = height - listY - margin
		drawGames(g, listX, listY, listW, listH, mouseX, mouseY)
		drawRoom(g, width - sideW - margin, listY, sideW, listH, mouseX, mouseY)
		if (CombatGuard.blocked) {
			val msg = "§cPaused – you're in combat"
			g.text(font, msg, width / 2 - font.width(msg) / 2, 7, Theme.TEXT, true)
		}
	}

	// ------------------------------------------------------------------ game list

	private fun drawGames(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
		listBox = intArrayOf(x, y, w, h)
		val games = MinigameRegistry.all.toList()
		val cardH = 56
		val gap = 4
		maxScroll = (games.size * (cardH + gap) - gap - h).coerceAtLeast(0)
		scroll = scroll.coerceIn(0, maxScroll)
		if (games.isEmpty()) {
			centered(g, "No games registered.", x + w / 2, y + h / 2, Theme.TEXT_DIM)
			return
		}
		g.enableScissor(x, y, x + w, y + h)
		for ((i, game) in games.withIndex()) {
			val cy = y + i * (cardH + gap) - scroll
			if (cy + cardH < y || cy > y + h) continue
			drawCard(g, game, x, cy, w, cardH, mx, my, visibleTop = y, visibleBottom = y + h)
		}
		g.disableScissor()
		if (maxScroll > 0) {
			val barH = (h * h / (h + maxScroll)).coerceAtLeast(12)
			val barY = y + ((h - barH) * scroll / maxScroll)
			g.fill(x + w + 2, y, x + w + 4, y + h, 0x40FFFFFF)
			g.fill(x + w + 2, barY, x + w + 4, barY + barH, Theme.accent(0xFF))
		}
	}

	private fun drawCard(
		g: GuiGraphicsExtractor, game: Minigame, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int,
		visibleTop: Int, visibleBottom: Int,
	) {
		val inList = my in visibleTop until visibleBottom
		val hover = inList && hovered(x, y, w, h, mx, my)
		g.fill(x, y, x + w, y + h, if (hover) GameColors.CARD_HOVER else GameColors.CARD)
		val hue = (game.id.hashCode() and 0x7FFFFFFF) % 360 / 360f
		val color = ColorUtil.hsbToArgb(hue, 0.55f, 0.9f)
		g.fill(x, y, x + 2, y + h, color)

		// Icon tile with the game's initial
		val ix = x + 8
		val iy = y + (h - 22) / 2
		g.fill(ix, iy, ix + 22, iy + 22, ColorUtil.shade(color, 0.45f))
		g.outline(ix, iy, 22, 22, color)
		bigText(g, game.name.take(1).uppercase(), ix + 11, iy + 4, 2f, Theme.TEXT)

		val btnW = 64
		val textX = ix + 30
		val textW = w - (textX - x) - btnW - 10
		g.text(font, "§l" + font.plainSubstrByWidth(game.name, textW - 4), textX, y + 6, Theme.TEXT, true)
		val players = if (game.minPlayers == game.maxPlayers) "${game.minPlayers} players" else "${game.minPlayers}–${game.maxPlayers} players"
		val tags = players + if (game.realtime) " · real-time" else " · turn-based"
		// Description wraps onto two lines; anything longer ends in "…" and shows in full on hover.
		val lines = wrapLines(game.description, textW)
		for ((li, line) in lines.take(2).withIndex()) {
			var text = line
			if (li == 1 && lines.size > 2) text = font.plainSubstrByWidth(line, textW - font.width("…")).trimEnd() + "…"
			g.text(font, text, textX, y + 17 + li * 10, Theme.TEXT_DIM, false)
		}
		if (lines.size > 2 && hover && mx < textX + textW) tooltip(game.description)
		val best = MinigameStore.best(game.id)
		val info = if (best != null) "§6Best: ${best.display}  §8$tags" else "§8$tags"
		g.text(font, font.plainSubstrByWidth(info, textW), textX, y + 41, Theme.TEXT, false)

		// Buttons only clickable when inside the visible list area.
		val bx = x + w - btnW - 6
		val soloY = y + 10
		val partyY = y + 30
		val lobby = MinigameHub.lobby
		val blocked = CombatGuard.blocked
		if (soloY >= visibleTop && soloY + 14 <= visibleBottom) {
			button(g, bx, soloY, btnW, 14, "Play solo", mx, my, enabled = !blocked && game.minPlayers <= 1, color = GameColors.GREEN,
				hint = if (game.minPlayers > 1) "Needs at least ${game.minPlayers} players" else null) {
				MinigameHub.playSolo(game)
			}
		}
		if (partyY >= visibleTop && partyY + 14 <= visibleBottom) {
			val label = if (lobby?.isCode == true) "Invite room" else "With party"
			val hint = when {
				lobby == null -> "Join a party or create a room code first"
				lobby.isCode -> "Open a ${game.name} room that everyone in ${lobby.code} can join"
				else -> "Open a ${game.name} room your party can join (other games can run at the same time)"
			}
			button(g, bx, partyY, btnW, 14, label, mx, my, enabled = lobby != null && !blocked, hint = hint) {
				MinigameHub.hostGame(game)
			}
		}
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		val (x, y, w, h) = listBox
		if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
			scroll = (scroll - (scrollY * 20).toInt()).coerceIn(0, maxScroll)
			return true
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
	}

	// ------------------------------------------------------------------ room / lobby panel

	private fun drawRoom(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
		panel(g, x, y, w, h, "Room")
		val ix = x + 6
		val iw = w - 12
		var cy = y + 21

		MinigameHub.invite?.let { inv ->
			val game = MinigameRegistry.byId(inv.gameId)
			g.fill(ix, cy, ix + iw, cy + 30, ColorUtil.withAlpha(Theme.accent, 0x30))
			g.outline(ix, cy, iw, 30, Theme.accent(0xA0))
			g.text(font, font.plainSubstrByWidth("§e${inv.hostName} §7invites you", iw - 4), ix + 3, cy + 3, Theme.TEXT, false)
			val bw = (iw - 9) / 2
			button(g, ix + 3, cy + 14, bw, 13, "Accept", mx, my, color = GameColors.GREEN, hint = game?.name) { MinigameHub.accept() }
			button(g, ix + 6 + bw, cy + 14, bw, 13, "Deny", mx, my, color = GameColors.RED) { MinigameHub.deny() }
			cy += 36
		}

		val lobby = MinigameHub.lobby
		if (lobby == null) {
			cy = wrap(g, "Not in a party. Create a room code and share it so friends can join:", ix, cy, iw, Theme.TEXT_DIM)
			cy += 3
			button(g, ix, cy, iw, 14, "Create code", mx, my) { MinigameHub.createCode() }
			cy += 18
			g.text(font, "§8or §7/sp games join <code>", ix, cy, Theme.TEXT, false)
			cy += 14
		} else {
			if (lobby.isCode) {
				g.text(font, "Code room", ix, cy, Theme.TEXT_DIM, false)
				cy += 11
				val code = lobby.code ?: ""
				val cw = iw
				val hover = hovered(ix, cy, cw, 16, mx, my)
				g.fill(ix, cy, ix + cw, cy + 16, if (hover) GameColors.CARD_HOVER else GameColors.CARD)
				g.outline(ix, cy, cw, 16, Theme.accent(if (hover) 0xFF else 0x80))
				bigText(g, code.toCharArray().joinToString(" "), ix + cw / 2 - 12, cy + 4, 1f, Theme.accent)
				g.text(font, "§7copy", ix + cw - font.width("copy") - 4, cy + 4, Theme.TEXT, false)
				clickArea(ix, cy, cw, 16) { MinigameHub.copyCode() }
				if (hover) tooltip("Click to copy. Friends type /sp games join $code")
				cy += 20
				if (MinigameHub.aloneInCodeWithParty) {
					// Nobody else is here, but you have a party: make the way back obvious.
					button(g, ix, cy, iw, 14, "◀ Back to party room", mx, my, color = GameColors.GREEN,
						hint = "Your party isn't in this room. Leave it to play with your party.") { MinigameHub.leaveCode() }
					cy += 18
				} else {
					val bw = (iw - 4) / 2
					button(g, ix, cy, bw, 13, "New code", mx, my) { MinigameHub.createCode() }
					button(g, ix + bw + 4, cy, bw, 13, "Leave", mx, my, color = GameColors.RED) { MinigameHub.leaveCode() }
					cy += 17
				}
			} else {
				g.text(font, "Party room", ix, cy, Theme.TEXT, false)
				cy += 11
				g.text(font, font.plainSubstrByWidth("§7${PartyTracker.members.size} party members", iw), ix, cy, Theme.TEXT, false)
				cy += 12
				button(g, ix, cy, iw, 13, "Create code instead", mx, my) { MinigameHub.createCode() }
				cy += 17
			}
			g.text(font, "§7${lobby.room.members.size} in room", ix, cy, Theme.TEXT, false)
			cy += 12
			g.fill(ix, cy, ix + iw, cy + 1, GameColors.LINE)
			cy += 5
			// Leave room for the connection status at the bottom (it can wrap onto up to 3 lines).
			val statusH = wrapLines(dev.skyparty.module.impl.client.OnlineModule.status(), iw).take(3).size * 10
			cy = drawLobby(g, lobby, ix, cy, iw, y + h - 8 - statusH, mx, my)
		}

		val status = dev.skyparty.module.impl.client.OnlineModule.status()
		// Status can be a long error: wrap it upwards from the bottom (max 3 lines).
		val statusColor = Regex("^(§.)+").find(status)?.value ?: ""
		val statusLines = wrapLines(status, iw).take(3)
		for ((i, line) in statusLines.withIndex()) g.text(font, if (i == 0) line else statusColor + line, ix, y + h - 12 - (statusLines.size - 1 - i) * 10, Theme.TEXT, false)
	}

	private fun drawLobby(
		g: GuiGraphicsExtractor, lobby: MinigameHub.Lobby, x: Int, y: Int, w: Int, bottom: Int, mx: Int, my: Int,
	): Int {
		var cy = y
		val sessions = lobby.sessions.values.toList()
		if (sessions.isEmpty()) {
			return wrap(g, "No games open. Press \"${if (lobby.isCode) "Invite room" else "With party"}\" on a game to open one – several can run at once.", x, cy, w, Theme.TEXT_DIM)
		}
		val mine = lobby.mine
		// Your own room first, in full: players with ready marks and the buttons.
		if (mine != null) {
			val game = mine.game
			g.fill(x - 2, cy - 2, x + w + 2, cy, Theme.accent(0xC0))
			g.text(font, font.plainSubstrByWidth("§l${game?.name ?: mine.gameId}", w - 30), x, cy + 2, Theme.accent, true)
			if (mine.inGame) g.text(font, "§ein game", x + w - font.width("in game"), cy + 2, Theme.TEXT, false)
			cy += 13
			// Space for the buttons below (Ready/Leave + Start) and at least one other room.
			val rows = ((bottom - 56 - cy) / 10).coerceIn(minOf(mine.joined.size, 3), 8)
			for ((i, entry) in mine.joined.entries.withIndex()) {
				if (i >= rows) { g.text(font, "§8+${mine.joined.size - rows} more", x + 4, cy, Theme.TEXT, false); cy += 10; break }
				val (id, name) = entry
				val mark = when { id in mine.playing -> "§e▶"; id in mine.ready -> "§a✔"; else -> "§8○" }
				val host = if (id == mine.host) " §6★" else ""
				g.text(font, font.plainSubstrByWidth("$mark §f$name$host", w - 4), x + 4, cy, Theme.TEXT, false)
				cy += 10
			}
			cy += 3
			val readyCount = mine.ready.size
			if (!mine.inGame) {
				val bw = (w - 4) / 2
				button(g, x, cy, bw, 14, if (mine.selfReady) "✔ Ready" else "Ready", mx, my, color = if (mine.selfReady) GameColors.GREEN else 0xFF3A3050.toInt(),
					hint = if (mine.selfReady) "Click to be not ready" else "Mark yourself ready") { MinigameHub.setReady(lobby, mine, !mine.selfReady) }
				button(g, x + bw + 4, cy, bw, 14, "Leave", mx, my, color = GameColors.RED, hint = if (mine.isHost) "Closes this room for everyone" else "Leave this room") { MinigameHub.leave(lobby, mine) }
				cy += 17
				if (mine.isHost && game != null) {
					val ok = mine.selfReady && readyCount >= game.minPlayers && !CombatGuard.blocked
					val hint = when {
						!mine.selfReady -> "Ready up first"
						readyCount < game.minPlayers -> "Needs ${game.minPlayers} ready players"
						else -> "Start with the ${readyCount} ready players"
					}
					button(g, x, cy, w, 14, "Start ($readyCount ready)", mx, my, enabled = ok, color = GameColors.GREEN, hint = hint) { MinigameHub.start(lobby, mine) }
					cy += 17
				} else if (!mine.isHost) {
					g.text(font, "§7Waiting for ${font.plainSubstrByWidth(mine.hostName, w - 70)} to start…", x, cy + 1, Theme.TEXT, false)
					cy += 12
				}
			} else {
				button(g, x, cy, w, 14, "Leave", mx, my, color = GameColors.RED) { MinigameHub.leave(lobby, mine) }
				cy += 17
			}
			cy += 3
		}
		// Other open rooms: one line each with a Join button.
		val others = sessions.filter { it !== mine }
		if (others.isEmpty()) return cy
		if (cy + 27 > bottom) {
			// No room for the list: a one-line hint at most (leave your room to see and join the others).
			if (cy + 9 <= bottom) g.text(font, "§8+${others.size} other game${if (others.size == 1) "" else "s"} open", x, cy, Theme.TEXT, false)
			return cy + 10
		}
		g.text(font, if (mine == null) "§7Open games" else "§7Other games", x, cy, Theme.TEXT, false)
		cy += 11
		for ((i, s) in others.withIndex()) {
			if (cy + 16 > bottom) { g.text(font, "§8+${others.size - i} more", x, cy, Theme.TEXT, false); break }
			g.fill(x, cy, x + w, cy + 15, GameColors.CARD)
			val name = s.game?.name ?: s.gameId
			val state = if (s.inGame) "§e▶ ${s.playing.size}" else "§7${s.joined.size}§8/${s.game?.maxPlayers ?: "?"}"
			g.text(font, font.plainSubstrByWidth("§f$name", w - 70), x + 3, cy + 4, Theme.TEXT, false)
			g.text(font, state, x + w - 40 - font.width(state), cy + 4, Theme.TEXT, false)
			button(g, x + w - 36, cy + 1, 34, 13, "Join", mx, my, color = GameColors.GREEN,
				hint = "${s.hostName}'s room" + if (s.inGame) " (playing – you join the next round)" else "") { MinigameHub.join(lobby, s) }
			cy += 17
		}
		return cy
	}

	/** Splits [text] into lines of at most [w] pixels at word boundaries. */
	private fun wrapLines(text: String, w: Int): List<String> {
		val out = mutableListOf<String>()
		var line = ""
		for (word in text.split(' ')) {
			val next = if (line.isEmpty()) word else "$line $word"
			if (font.width(next) > w && line.isNotEmpty()) {
				out += line
				line = word
			} else line = next
		}
		if (line.isNotEmpty()) out += line
		return out
	}

	/** Word-wraps [text] into [w] pixels; returns the y below the last line. */
	private fun wrap(g: GuiGraphicsExtractor, text: String, x: Int, y: Int, w: Int, color: Int): Int {
		var cy = y
		var line = ""
		for (word in text.split(' ')) {
			val next = if (line.isEmpty()) word else "$line $word"
			if (font.width(next) > w && line.isNotEmpty()) {
				g.text(font, line, x, cy, color, false)
				cy += 10
				line = word
			} else line = next
		}
		if (line.isNotEmpty()) {
			g.text(font, line, x, cy, color, false)
			cy += 10
		}
		return cy
	}
}
