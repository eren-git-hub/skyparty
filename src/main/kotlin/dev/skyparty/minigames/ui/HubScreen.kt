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
		val sideW = (width * 0.36f).toInt().coerceIn(140, 200)
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
				lobby.isCode -> "Invite everyone in room ${lobby.code}"
				else -> "Invite your party (${PartyTracker.members.size} members)"
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
			cy = drawLobby(g, lobby, ix, cy, iw, y + h - 18, mx, my)
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
		val game = lobby.game
		if (game == null) {
			return wrap(g, "Pick a game and press \"${if (lobby.isCode) "Invite room" else "With party"}\" to invite everyone.", x, cy, w, Theme.TEXT_DIM)
		}
		g.text(font, font.plainSubstrByWidth("§l${game.name}", w), x, cy, Theme.accent, true)
		cy += 11
		g.text(font, font.plainSubstrByWidth("§7Host: §f${lobby.hostName}", w), x, cy, Theme.TEXT, false)
		cy += 11
		g.text(font, "§7Ready ${lobby.ready.size}/${game.maxPlayers}", x, cy, Theme.TEXT, false)
		cy += 11
		val rows = ((bottom - 20 - cy) / 10).coerceAtLeast(1)
		for ((i, name) in lobby.ready.values.withIndex()) {
			if (i >= rows) {
				g.text(font, "§8+${lobby.ready.size - rows} more", x + 4, cy, Theme.TEXT, false)
				cy += 10
				break
			}
			g.text(font, font.plainSubstrByWidth("§a✔ §f$name", w - 4), x + 4, cy, Theme.TEXT, false)
			cy += 10
		}
		cy += 3
		when {
			lobby.isHost -> {
				val ok = lobby.ready.size >= game.minPlayers
				button(g, x, cy, w, 14, "Start (${lobby.ready.size} ready)", mx, my, enabled = ok && !CombatGuard.blocked,
					color = GameColors.GREEN, hint = if (!ok) "Needs ${game.minPlayers} players" else null) { MinigameHub.start(lobby) }
			}
			lobby.selfReady -> {
				g.text(font, "§7Waiting for host…", x, cy + 3, Theme.TEXT, false)
				val lw = 38
				button(g, x + w - lw, cy, lw, 14, "Leave", mx, my, color = GameColors.RED) { MinigameHub.setReady(lobby, false) }
			}
			else -> button(g, x, cy, w, 14, "Join game", mx, my, color = GameColors.GREEN) { MinigameHub.setReady(lobby, true) }
		}
		return cy + 18
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
