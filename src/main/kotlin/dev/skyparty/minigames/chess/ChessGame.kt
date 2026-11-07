package dev.skyparty.minigames.chess

import dev.skyparty.Skyparty
import dev.skyparty.core.SkypartyCommands
import dev.skyparty.minigames.Minigame
import dev.skyparty.minigames.MinigameRegistry
import dev.skyparty.net.Net
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Chess as a lobby minigame: registers itself, adds /sp chess and drives the active session. */
object ChessGame : Minigame {
	override val id = "chess"
	override val name = "Chess"
	override val description = "Classic chess with clocks. Play your party, the built-in AI or hot-seat."
	override val minPlayers = 1
	override val maxPlayers = 2
	override val realtime = false

	/** The running session (kept when the screen closes so online games survive). */
	var session: ChessSession? = null
		private set

	/** Solo settings remembered between games (screen setup panel edits these). */
	var soloMode = ChessSession.Mode.AI
	var soloSettings = ChessSession.Settings()

	private var pendingScreen: Screen? = null

	fun init() {
		MinigameRegistry.register(this)
		ClientTickEvents.END_CLIENT_TICK.register { mc ->
			pendingScreen?.let { pendingScreen = null; mc.gui.setScreen(it) }
			session?.tick()
		}
		SkypartyCommands.sub {
			ClientCommands.literal("chess")
				.executes {
					val cur = session
					pendingScreen = if (cur != null && cur.state?.over == false) ChessScreen(cur) else ChessScreen(null)
					1
				}
				.then(ClientCommands.literal("new").executes { pendingScreen = ChessScreen(null); 1 })
				.then(ClientCommands.literal("perft").executes {
					Thread({
						val lines = runPerftSuite()
						Minecraft.getInstance().execute { lines.forEach { say(it) } }
					}, "Skyparty-Perft").apply { isDaemon = true }.start()
					say("§7Running perft…")
					1
				})
		}
	}

	override fun open(room: Net.Room?) {
		val mc = Minecraft.getInstance()
		if (room == null) {
			mc.execute { mc.gui.setScreen(ChessScreen(null)) }
			return
		}
		session?.takeIf { it.room !== room }?.leave()
		val s = ChessSession(room, ChessSession.Mode.ONLINE, ChessSession.Settings())
		session = s
		mc.execute { mc.gui.setScreen(ChessScreen(s)) }
	}

	/** Starts a new solo session with the remembered settings. */
	fun startSolo(): ChessSession {
		session?.takeIf { it.online }?.leave()
		val s = ChessSession(null, soloMode, soloSettings)
		session = s
		return s
	}

	/** Forgets the current session (e.g. returning to the setup panel). */
	fun clearSession() {
		session?.leave()
		session = null
	}

	private fun say(text: String) = dev.skyparty.util.Chat.info(text)

	data class PerftCase(val name: String, val fen: String, val depth: Int, val expected: Long)

	val PERFT_CASES = listOf(
		PerftCase("start d1", Position.START_FEN, 1, 20),
		PerftCase("start d2", Position.START_FEN, 2, 400),
		PerftCase("start d3", Position.START_FEN, 3, 8902),
		PerftCase("start d4", Position.START_FEN, 4, 197281),
		PerftCase("kiwipete d1", KIWIPETE, 1, 48),
		PerftCase("kiwipete d2", KIWIPETE, 2, 2039),
		PerftCase("kiwipete d3", KIWIPETE, 3, 97862),
		PerftCase("pos3 d4", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 4, 43238),
		PerftCase("pos4 d3", "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 3, 9467),
		PerftCase("pos5 d3", "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 3, 62379),
	)
	const val KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"

	/** Runs all perft cases; returns one chat line per case. */
	fun runPerftSuite(): List<String> = PERFT_CASES.map { c ->
		val t = System.currentTimeMillis()
		val n = Position.perft(Position.fromFen(c.fen), c.depth)
		val ok = n == c.expected
		"${if (ok) "§aPASS" else "§cFAIL"} §f${c.name}: §7$n ${if (ok) "" else "(expected ${c.expected}) "}§8${System.currentTimeMillis() - t} ms"
	}
}
