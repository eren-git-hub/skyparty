import { bearer, devMode, issueChallenge, mintToken, readToken, secret, type Session, TOKEN_TTL_S, verifyLogin } from "./auth";
import { type LimitName, STAGES, VISIBILITIES } from "./hub";
import { type Env, clientIp, error, json, normUuid } from "./util";

export { Hub } from "./hub";
export { Room } from "./room";

const VERSION = "1.0.0";
const ROOM_ID_RE = /^[A-Za-z0-9:_.\-]{1,64}$/;
const MAX_BATCH = 50;
const MAX_BODY = 2048;

/**
 * HTTP API
 *   GET    /                        health/info
 *   GET    /auth/challenge          -> { challenge, serverId, expiresIn }
 *   POST   /auth/verify             { username, challenge, uuid, publicKey, expiresAt, keySignature, signature }
 *                                    (legacy: { username, challenge } via Mojang hasJoined; DEV_MODE: { username: "dev:<name>" })
 *                                   -> { token, uuid, name, expiresIn }
 *   GET    /rooms/<roomId>          WebSocket upgrade, header "Authorization: Bearer <token>"
 *   PUT    /pets/me                 { name, stage, bodyColor, accentColor, visibility }
 *   DELETE /pets/me
 *   GET    /pets?uuids=a,b,c        (max 50) -> { pets: { <uuid>: Pet } }
 *   POST   /scores                  { game, value, display } (best per day and per week is kept)
 *   GET    /scores?game=x&period=day|week -> { top: [{ name, value, display, me }], me: { rank, display }, total, resetsIn }
 */
export default {
	async fetch(req: Request, env: Env): Promise<Response> {
		try {
			return await route(req, env);
		} catch (e) {
			console.error(e);
			return error(500, "internal");
		}
	},
} satisfies ExportedHandler<Env>;

const hub = (env: Env) => env.HUB.get(env.HUB.idFromName("global"));

async function limited(env: Env, checks: [LimitName, string][]): Promise<boolean> {
	return !(await hub(env).allowAll(checks));
}

async function route(req: Request, env: Env): Promise<Response> {
	const url = new URL(req.url);
	const path = url.pathname.replace(/\/+$/, "") || "/";
	const ip = clientIp(req);
	const key = secret(env, req);

	if (path === "/" && req.method === "GET") {
		return json({ name: "skyparty-api", version: VERSION, dev: devMode(env, req), configured: key != null });
	}
	if (!key) return error(503, "not_configured", "SESSION_SECRET is not set (npx wrangler secret put SESSION_SECRET)");

	// ---- auth ---------------------------------------------------------------------------------
	if (path === "/auth/challenge" && req.method === "GET") {
		if (await limited(env, [["auth", ip]])) return error(429, "rate_limited");
		return json(await issueChallenge(key));
	}
	if (path === "/auth/verify" && req.method === "POST") {
		if (await limited(env, [["auth", ip]])) return error(429, "rate_limited");
		const body = await readJson(req);
		if (body === undefined) return error(400, "bad_json");
		const result = await verifyLogin(env, req, key, body);
		if (!result.ok) return error(result.status, result.code);
		const s = result.session;
		return json({ token: await mintToken(key, s), uuid: s.uuid, name: s.name, expiresIn: TOKEN_TTL_S, dev: !!s.dev });
	}

	// Everything below needs a session.
	const session = await readToken(key, bearer(req) ?? url.searchParams.get("token"));
	if (!session) return error(401, "unauthorized");
	if (session.dev && !devMode(env, req)) return error(401, "unauthorized");

	// ---- rooms --------------------------------------------------------------------------------
	if (path.startsWith("/rooms/")) {
		let roomId: string;
		try {
			roomId = decodeURIComponent(path.slice("/rooms/".length));
		} catch {
			return error(400, "bad_room");
		}
		if (!ROOM_ID_RE.test(roomId)) return error(400, "bad_room");
		if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") return error(426, "expected_websocket");
		if (await limited(env, [["ws", session.uuid], ["wsIp", ip]])) return error(429, "rate_limited");
		const headers = new Headers({
			Upgrade: "websocket",
			"X-Sl-Uuid": session.uuid,
			"X-Sl-Name": session.name,
			"X-Sl-Ip": ip,
		});
		const stub = env.ROOMS.get(env.ROOMS.idFromName(roomId));
		return stub.fetch(new Request("https://room/" + encodeURIComponent(roomId), { headers }));
	}

	// ---- pets ---------------------------------------------------------------------------------
	if (path === "/pets/me" && req.method === "PUT") {
		if (await limited(env, [["petPut", session.uuid]])) return error(429, "rate_limited");
		const pet = validatePet(await readJson(req));
		if (!pet) return error(400, "bad_pet");
		const res = await hub(env).putPet(session.uuid, pet);
		return json(res);
	}
	if (path === "/pets/me" && req.method === "DELETE") {
		if (await limited(env, [["petPut", session.uuid]])) return error(429, "rate_limited");
		await hub(env).deletePet(session.uuid);
		return json({ ok: true });
	}
	if (path === "/pets" && req.method === "GET") {
		const raw = (url.searchParams.get("uuids") ?? "").split(",").filter((s) => s.length > 0);
		if (raw.length > MAX_BATCH) return error(400, "too_many", `at most ${MAX_BATCH} uuids`);
		const uuids = [...new Set(raw.map(normUuid))];
		if (uuids.some((u) => u == null)) return error(400, "bad_uuid");
		if (await limited(env, [["petGet", session.uuid], ["petGetIp", ip]])) return error(429, "rate_limited");
		const pets = await hub(env).getPets(uuids as string[], session.uuid);
		return json({ pets }, 200, { "cache-control": "private, max-age=60" });
	}

	// ---- leaderboards -------------------------------------------------------------------------
	if (path === "/scores" && req.method === "POST") {
		if (await limited(env, [["scorePut", session.uuid]])) return error(429, "rate_limited");
		const b = await readJson(req);
		const game = GAMES[b?.game as string];
		const value = b?.value;
		if (!game || typeof value !== "number" || !Number.isFinite(value) || value < game.min || value > game.max) return error(400, "bad_score");
		const display = typeof b.display === "string" ? clean(b.display).slice(0, 24) : String(value);
		const h = hub(env);
		const day = h.submitScore(boardKey(b.game, "day"), session.uuid, session.name, value, display, game.lower);
		const week = h.submitScore(boardKey(b.game, "week"), session.uuid, session.name, value, display, game.lower);
		return json({ ok: true, day: await day, week: await week });
	}
	if (path === "/scores" && req.method === "GET") {
		if (await limited(env, [["scoreGet", session.uuid]])) return error(429, "rate_limited");
		const id = url.searchParams.get("game") ?? "";
		const period = url.searchParams.get("period") === "day" ? "day" : "week";
		const game = GAMES[id];
		if (!game) return error(400, "bad_game");
		const res = await hub(env).topScores(boardKey(id, period), game.lower, 10, session.uuid);
		return json({ ...res, period, resetsIn: resetsIn(period) }, 200, { "cache-control": "private, max-age=30" });
	}

	return error(404, "not_found");
}

/** Games with a leaderboard: whether lower is better and the plausible range (anything outside is refused). */
const GAMES: Record<string, { lower: boolean; min: number; max: number }> = {
	daily: { lower: true, min: 3, max: 3600 }, // daily parkour, seconds
	dungeon: { lower: true, min: 20, max: 7200 }, // seconds
	reaction: { lower: true, min: 80, max: 5000 }, // ms
	price: { lower: false, min: 1, max: 1000 }, // streak
	petrace: { lower: false, min: 1, max: 1_000_000 },
	bluff: { lower: false, min: 1, max: 10_000 },
	quiz: { lower: false, min: 1, max: 10_000 },
	typing: { lower: false, min: 1, max: 10_000 },
	trader: { lower: false, min: 1, max: 10_000 },
	fishing: { lower: false, min: 1, max: 10_000 },
};

const DAY_MS = 86_400_000;

/** "d:<game>:<day>" (UTC day number) or "w:<game>:<week>" (weeks start Monday 00:00 UTC; day 0 was a Thursday). */
function boardKey(game: string, period: "day" | "week"): string {
	const day = Math.floor(Date.now() / DAY_MS);
	return period === "day" ? `d:${game}:${day}` : `w:${game}:${Math.floor((day + 3) / 7)}`;
}

function resetsIn(period: "day" | "week"): number {
	const now = Date.now();
	const day = Math.floor(now / DAY_MS);
	const end = period === "day" ? (day + 1) * DAY_MS : (Math.floor((day + 3) / 7) + 1) * 7 * DAY_MS - 3 * DAY_MS;
	return end - now;
}

/** Text shown to other players: no formatting codes or control characters. */
const clean = (s: string) => s.replace(/§./g, "").replace(/[\u0000-\u001f\u007f-\u009f]/g, "").trim();

async function readJson(req: Request): Promise<any> {
	const text = await req.text();
	if (text.length > MAX_BODY) return undefined;
	try {
		return JSON.parse(text);
	} catch {
		return undefined;
	}
}

function validatePet(b: any) {
	if (!b || typeof b !== "object") return null;
	// Pet names are shown to other players: strip formatting codes and control characters.
	const name = typeof b.name === "string" ? b.name.replace(/§./g, "").replace(/[\u0000-\u001f\u007f-\u009f]/g, "").trim().slice(0, 16) : "";
	const color = (c: unknown) => (Number.isInteger(c) ? (c as number) & 0xffffff : null);
	const bodyColor = color(b.bodyColor);
	const accentColor = color(b.accentColor);
	if (!STAGES.includes(b.stage) || !VISIBILITIES.includes(b.visibility) || bodyColor == null || accentColor == null) return null;
	return { name: name || "Pet", stage: b.stage, bodyColor, accentColor, visibility: b.visibility } as const;
}

export type { Session };
