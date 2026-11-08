import { DurableObject } from "cloudflare:workers";
import { type Bucket, type Env, takeToken } from "./util";

/**
 * One Durable Object per room id. Uses the WebSocket Hibernation API, so an idle room costs no
 * duration: the object is evicted from memory while sockets stay open, and per-connection state
 * lives in the socket attachment.
 *
 * Wire protocol (JSON text frames):
 *   client -> server  {"type": "wheel.spin", "payload": {...}}       (plain "ping" -> "pong" keepalive)
 *   server -> clients {"type", "payload", "from", "fromName", "serverTime"}  to ALL members incl. sender
 *     - payload.serverTime is always set; for types ending in ".rng" payload.roll = crypto random [0,1)
 *   server events     {"type": "room.members", "payload": {"members": [{"uuid","name"}]}, ...}
 *                     {"type": "room.error",   "payload": {"code": "rate_limited" | ...}}
 * Close codes: 4000 replaced by a newer connection of the same player, 4002 idle, 4003 banned,
 *              4008 kicked for abuse, 4009 room full.
 */

export const MAX_MEMBERS = 16;
/** "presence:*" rooms only list who is online (Active users); clients never send there, so they may be big. */
export const PRESENCE_MAX_MEMBERS = 2000;
export const MAX_MESSAGE_BYTES = 4096;
const BUCKET_CAPACITY = 20; // burst
const BUCKET_REFILL = 5; // messages per second sustained
const STRIKE_DECAY_MS = 1_000; // one strike forgiven per second
const STRIKES_TO_BAN = 30; // e.g. sustained flooding at ~3x the allowed rate for 15s, or 3 oversized frames
const BAN_MS = 10 * 60_000;
const IDLE_MS = 3 * 60_000; // no message and no "ping" for this long -> closed
const SWEEP_MS = 2 * 60_000; // alarm interval while the room has members (each setAlarm = 1 row write)
const BANS_TABLE = "CREATE TABLE IF NOT EXISTS bans (key TEXT PRIMARY KEY, until INTEGER NOT NULL)";
const TYPE_RE = /^[A-Za-z0-9][A-Za-z0-9_.\-]{0,47}$/;

interface Attachment {
	uuid: string;
	name: string;
	ip: string;
	bucket: Bucket;
	strikes: number;
	strikeAt: number;
	last: number;
	/** Room id ("party:…", "presence:all", …). */
	room?: string;
}

export class Room extends DurableObject<Env> {
	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		// Keepalive pings are answered by the runtime without waking the object.
		ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
	}

	async fetch(req: Request): Promise<Response> {
		if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("expected websocket", { status: 426 });
		const uuid = req.headers.get("X-Sl-Uuid")!;
		const name = req.headers.get("X-Sl-Name")!;
		const ip = req.headers.get("X-Sl-Ip") ?? "local";
		const room = decodeURIComponent(new URL(req.url).pathname.slice(1));
		const presence = room.startsWith("presence:");
		const now = Date.now();

		const { 0: client, 1: server } = new WebSocketPair();
		this.ctx.acceptWebSocket(server);
		const att: Attachment = { uuid, name, ip, bucket: { tokens: BUCKET_CAPACITY, at: now }, strikes: 0, strikeAt: now, last: now, room };
		server.serializeAttachment(att);

		if (this.bannedUntil(uuid) > now) {
			this.sendError(server, "banned");
			server.close(4003, "banned");
			return new Response(null, { status: 101, webSocket: client });
		}

		for (const ws of this.ctx.getWebSockets()) {
			if (ws === server) continue;
			if (this.att(ws)?.uuid === uuid) {
				// Same player reconnected (e.g. half-dead socket): keep only the newest connection.
				ws.serializeAttachment({ ...this.att(ws)!, uuid: "" });
				ws.close(4000, "replaced");
			}
		}
		if (this.open().length > (presence ? PRESENCE_MAX_MEMBERS : MAX_MEMBERS)) {
			this.sendError(server, "room_full");
			server.close(4009, "room full");
			return new Response(null, { status: 101, webSocket: client });
		}

		this.broadcastMembers();
		await this.ensureAlarm();
		return new Response(null, { status: 101, webSocket: client });
	}

	async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
		const a = this.att(ws);
		if (!a || !a.uuid) return;
		const now = Date.now();
		a.last = now;

		const size = typeof message === "string" ? message.length : message.byteLength;
		if (typeof message !== "string" || size > MAX_MESSAGE_BYTES) {
			this.sendError(ws, "too_large");
			return this.strike(ws, a, 10);
		}
		if (!takeToken(a.bucket, BUCKET_CAPACITY, BUCKET_REFILL, now)) {
			this.sendError(ws, "rate_limited");
			return this.strike(ws, a, 1);
		}

		let msg: any;
		try {
			msg = JSON.parse(message);
		} catch {
			this.sendError(ws, "bad_json");
			return this.strike(ws, a, 1);
		}
		// Presence rooms are a member list only: nothing is relayed there.
		if (a.room?.startsWith("presence:")) return;
		const type = msg?.type;
		if (typeof type !== "string" || !TYPE_RE.test(type) || type.startsWith("room.")) {
			this.sendError(ws, "bad_type");
			return this.strike(ws, a, 1);
		}
		const payload = msg.payload && typeof msg.payload === "object" && !Array.isArray(msg.payload) ? msg.payload : {};
		if (type.endsWith(".rng")) payload.roll = crypto.getRandomValues(new Uint32Array(1))[0] / 2 ** 32;
		payload.serverTime = now;

		ws.serializeAttachment(a);
		this.broadcast(JSON.stringify({ type, payload, from: a.uuid, fromName: a.name, serverTime: now }));
	}

	async webSocketClose(ws: WebSocket, code: number, reason: string) {
		try {
			ws.close(code === 1005 || code === 1006 ? 1000 : code, reason);
		} catch {
			/* already closed */
		}
		this.broadcastMembers(ws);
	}

	async webSocketError(ws: WebSocket) {
		this.broadcastMembers(ws);
	}

	async alarm() {
		const now = Date.now();
		for (const ws of this.open()) {
			const a = this.att(ws);
			const pinged = this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? 0;
			if (!a || now - Math.max(a.last, pinged) > IDLE_MS) ws.close(4002, "idle");
		}
		const bans = this.activeBans(now);
		if (this.open().length > 0) {
			await this.ctx.storage.setAlarm(now + SWEEP_MS);
		} else if (bans === 0) {
			// Empty room without bans: drop all storage so nothing lingers.
			await this.ctx.storage.deleteAll();
		} else {
			await this.ctx.storage.setAlarm(now + BAN_MS);
		}
	}

	// ---------------------------------------------------------------------------------------------

	private att(ws: WebSocket): Attachment | null {
		return ws.deserializeAttachment() as Attachment | null;
	}

	private open(except?: WebSocket): WebSocket[] {
		return this.ctx.getWebSockets().filter((ws) => ws !== except && ws.readyState === WebSocket.OPEN && !!this.att(ws)?.uuid);
	}

	private broadcast(text: string, except?: WebSocket) {
		for (const ws of this.open(except)) {
			try {
				ws.send(text);
			} catch {
				/* socket went away; its close handler updates members */
			}
		}
	}

	private broadcastMembers(except?: WebSocket) {
		const members = this.open(except).map((ws) => {
			const a = this.att(ws)!;
			return { uuid: a.uuid, name: a.name };
		});
		const now = Date.now();
		this.broadcast(JSON.stringify({ type: "room.members", payload: { members, serverTime: now }, from: "", fromName: "", serverTime: now }), except);
	}

	private sendError(ws: WebSocket, code: string) {
		try {
			ws.send(JSON.stringify({ type: "room.error", payload: { code, serverTime: Date.now() }, from: "", fromName: "", serverTime: Date.now() }));
		} catch {
			/* ignore */
		}
	}

	private strike(ws: WebSocket, a: Attachment, weight: number) {
		const now = Date.now();
		// Forgive one strike per full second since the last forgiveness. (Resetting the clock on every strike
		// meant steady light overuse was never forgiven at all and always ended in a ban.)
		const forgiven = Math.floor((now - a.strikeAt) / STRIKE_DECAY_MS);
		const left = Math.max(0, a.strikes - forgiven);
		a.strikeAt = left === 0 ? now : a.strikeAt + forgiven * STRIKE_DECAY_MS;
		a.strikes = left + weight;
		ws.serializeAttachment(a);
		if (a.strikes >= STRIKES_TO_BAN) {
			const until = now + BAN_MS;
			const sql = this.ctx.storage.sql;
			sql.exec(BANS_TABLE);
			sql.exec("INSERT OR REPLACE INTO bans (key, until) VALUES (?, ?)", "u:" + a.uuid, until);
			ws.close(4008, "kicked for abuse");
			this.broadcastMembers(ws);
		}
	}

	/** The bans table only exists after the first ban, so rooms without abuse store nothing at all. */
	private hasBans(): boolean {
		return this.ctx.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'bans'").toArray().length > 0;
	}

	/** Bans are per player (uuid), not per IP: many players can share one IP (NAT, schools). */
	private bannedUntil(uuid: string): number {
		if (!this.hasBans()) return 0;
		const rows = this.ctx.storage.sql.exec("SELECT MAX(until) AS until FROM bans WHERE key = ?", "u:" + uuid).toArray();
		return (rows[0]?.until as number | null) ?? 0;
	}

	private activeBans(now: number): number {
		if (!this.hasBans()) return 0;
		this.ctx.storage.sql.exec("DELETE FROM bans WHERE until < ?", now);
		return this.ctx.storage.sql.exec("SELECT COUNT(*) AS n FROM bans").one().n as number;
	}

	private async ensureAlarm() {
		if ((await this.ctx.storage.getAlarm()) == null) await this.ctx.storage.setAlarm(Date.now() + SWEEP_MS);
	}
}
