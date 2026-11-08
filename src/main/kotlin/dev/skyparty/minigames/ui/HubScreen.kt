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

	override fun init() {
		super.init()
		dev.skyparty.party.PartyTracker.autoCheck()
	}
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
		// Name, then how long a match takes (Short / Medium / Long) as a small colored tag.
		val len = game.length
		val tagW = font.width(len.label) + 6
		// The tag sits after the name; if both do not fit, it moves to the bottom line so the name stays whole.
		val nameW = boldWidth(game.name)
		val tagOnTop = nameW + tagW + 7 <= textW
		if (tagOnTop) g.text(font, bold(game.name), textX, y + 6, Theme.TEXT, true)
		else g.text(font, bold(fitBold(game.name, textW)), textX, y + 6, Theme.TEXT, true)
		val tagX = if (tagOnTop) textX + nameW + 6 else textX
		val tagY = if (tagOnTop) y + 5 else y + 40
		g.fill(tagX, tagY, tagX + tagW, tagY + 10, ColorUtil.withAlpha(len.color, 0x30))
		g.outline(tagX, tagY, tagW, 10, ColorUtil.withAlpha(len.color, 0xA0))
		g.text(font, len.label, tagX + 3, tagY + 1, len.color, false)
		if (hover && hovered(tagX, tagY, tagW, 10, mx, my)) tooltip("${len.label} game: ${len.hint}")
		val infoX = if (tagOnTop) textX else textX + tagW + 4
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
		g.text(font, fit(info, textW - (infoX - textX)), infoX, y + 41, Theme.TEXT, false)

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
			val mine = lobby?.mine
			if (mine != null) {
				// In a room: the host switches its game here, everyone else waits for the host.
				val selected = mine.gameId == game.id
				val tooMany = mine.joined.size > game.maxPlayers
				val ok = mine.isHost && !mine.inGame && !selected && !tooMany && !blocked
				val hint = when {
					selected -> "Your room plays this"
					!mine.isHost -> "${mine.hostName} picks the game. Leave your room to open your own."
					mine.inGame -> "Wait until the game is over"
					tooMany -> "For at most ${game.maxPlayers} players – your room has ${mine.joined.size}"
					else -> "Switch your room to ${game.name} (everyone stays)"
				}
				button(g, bx, partyY, btnW, 14, if (selected) "✔ Picked" else "Pick", mx, my, enabled = ok,
					color = if (selected) GameColors.GREEN else Theme.accent, hint = hint) { MinigameHub.changeGame(lobby, mine, game) }
				if (selected) g.outline(x, y, w, h, Theme.accent(0xC0))
			} else {
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
		// A newer Skyparty is out: one click to the download page.
		dev.skyparty.core.Compat.newerVersion?.let { v ->
			button(g, ix, cy, iw, 14, "⬆ Skyparty $v is out", mx, my, color = GameColors.GOLD, hint = "You have ${dev.skyparty.core.Compat.VERSION}. Opens the download page.") {
				net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, dev.skyparty.core.Compat.RELEASES_PAGE)
			}
			cy += 18
		}
		// A paused solo game: the way back in.
		MinigameHub.activeGame?.takeIf { it.session == null && it.paused }?.let {
			button(g, ix, cy, iw, 14, "▶ Back to your paused game", mx, my, color = GameColors.GOLD) { MinigameHub.backToGame() }
			cy += 18
		}
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
		// The party's / code room's wheel is listed like a game room (but is not one of the games).
		val wheel = dev.skyparty.wheel.WheelSession
		if (wheel.mode != dev.skyparty.wheel.WheelSession.Mode.LOCAL) {
			g.fill(x, cy, x + w, cy + 15, GameColors.CARD)
			g.fill(x, cy, x + 2, cy + 15, Theme.accent(0xFF))
			g.text(font, font.plainSubstrByWidth("§f◎ Decision Wheel", w - 76), x + 5, cy + 4, Theme.TEXT, false)
			val st = "§7${wheel.players.size}"
			g.text(font, st, x + w - 40 - font.width(st), cy + 4, Theme.TEXT, false)
			button(g, x + w - 36, cy + 1, 34, 13, "Open", mx, my, hint = "Host: ${wheel.host ?: "-"}") { minecraft.gui.setScreen(dev.skyparty.wheel.WheelScreen()) }
			cy += 18
		}
		if (sessions.isEmpty()) {
			return wrap(g, "No games open. Press \"${if (lobby.isCode) "Invite room" else "With party"}\" on a game to open one – several can run at once.", x, cy, w, Theme.TEXT_DIM)
		}
		val mine = lobby.mine
		// Your own room first, in full: players with ready marks and the buttons.
		if (mine != null) {
			val game = mine.game
			g.fill(x - 2, cy - 2, x + w + 2, cy, Theme.accent(0xC0))
			g.text(font, bold(fitBold(game?.name ?: mine.gameId, w - 46)), x, cy + 2, Theme.accent, true)
			if (mine.inGame) {
				val st = if (mine.paused) "§6❚❚ paused" else "§ein game"
				g.text(font, st, x + w - font.width(st), cy + 2, Theme.TEXT, false)
			} else {
				button(g, x + w - 40, cy, 40, 12, "Leave", mx, my, color = GameColors.RED,
					hint = if (mine.isHost) "Closes this room for everyone" else "Leave this room") { MinigameHub.leave(lobby, mine) }
			}
			cy += 13
			// Space for the buttons below (Ready/Leave + Start) and at least one other room.
			val rows = ((bottom - 40 - cy) / 10).coerceIn(minOf(mine.joined.size, 3), 8)
			for ((i, entry) in mine.joined.entries.withIndex()) {
				if (i >= rows) { g.text(font, "§8+${mine.joined.size - rows} more", x + 4, cy, Theme.TEXT, false); cy += 10; break }
				val (id, name) = entry
				val mark = when { id in mine.playing -> "§e▶"; id in mine.ready -> "§a✔"; else -> "§8○" }
				val host = (if (id == mine.host) " §6★" else "") + if (!lobby.compatible(id)) " §c⚠ update" else ""
				g.text(font, font.plainSubstrByWidth("$mark §f$name$host", w - 4), x + 4, cy, Theme.TEXT, false)
				cy += 10
			}
			cy += 3
			val readyCount = mine.ready.size
			if (!mine.inGame) {
				val bw = (w - 4) / 2
				button(g, x, cy, bw, 14, if (mine.selfReady) "✔ Ready" else "Ready", mx, my, color = if (mine.selfReady) GameColors.GREEN else 0xFF3A3050.toInt(),
					hint = if (mine.selfReady) "Click to be not ready" else "Mark yourself ready") { MinigameHub.setReady(lobby, mine, !mine.selfReady) }
				if (mine.isHost && game != null) {
					val outdated = MinigameHub.incompatibleReady(lobby, mine)
					val ok = mine.selfReady && readyCount >= game.minPlayers && !CombatGuard.blocked && outdated.isEmpty()
					val hint = when {
						!mine.selfReady -> "Ready up first"
						outdated.isNotEmpty() -> "${outdated.joinToString()}: different Skyparty version"
						readyCount < game.minPlayers -> "Needs ${game.minPlayers} ready players"
						else -> "Start with the ${readyCount} ready players"
					}
					button(g, x + bw + 4, cy, bw, 14, "Start ($readyCount)", mx, my, enabled = ok, color = GameColors.GREEN, hint = hint) { MinigameHub.start(lobby, mine) }
				} else {
					g.text(font, font.plainSubstrByWidth("§7Waiting for ${mine.hostName}…", bw), x + bw + 6, cy + 3, Theme.TEXT, false)
				}
				cy += 17
			} else {
				val mineActive = lobby.active?.session == mine.id
				if (mine.paused) {
					g.text(font, font.plainSubstrByWidth("§7Paused by §f${mine.pausedBy ?: "someone"}", w), x, cy + 1, Theme.TEXT, false)
					cy += 11
				}
				val bw = (w - 4) / 2
				if (mineActive) {
					button(g, x, cy, bw, 14, "▶ Open game", mx, my, color = GameColors.GREEN, hint = "Reopen your game") { MinigameHub.backToGame() }
				} else {
					g.text(font, "§7Others playing…", x, cy + 3, Theme.TEXT, false)
				}
				button(g, x + bw + 4, cy, bw, 14, "Leave", mx, my, color = GameColors.RED,
					hint = if (mineActive) "Quit the game and leave the room" else "Leave this room") { MinigameHub.leave(lobby, mine) }
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
			val full = s.joined.size >= (s.game?.maxPlayers ?: 8)
			val versionOk = lobby.compatible(s.host)
			button(g, x + w - 36, cy + 1, 34, 13, if (!versionOk) "⚠" else if (full) "Full" else "Join", mx, my, enabled = !full && versionOk, color = GameColors.GREEN,
				hint = if (!versionOk) MinigameHub.versionHint(lobby, s.host, s.hostName).removePrefix("§c") else "${s.hostName}'s room" + if (s.inGame) " (playing – you join the next round)" else "") { MinigameHub.join(lobby, s) }
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
