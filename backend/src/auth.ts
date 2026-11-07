import {
	type Env,
	b64url,
	b64urlDecodeText,
	b64urlText,
	enc,
	hex,
	hmac,
	isMcName,
	normUuid,
	randomHex,
	safeEqual,
} from "./util";
import { verifyKeyLogin } from "./keyauth";

/**
 * Authentication without ever seeing the Minecraft access token:
 *  1. GET /auth/challenge -> { challenge, serverId }   (stateless: serverId = HMAC(secret, challenge))
 *  2. The client calls Mojang's session server itself:
 *     POST https://sessionserver.mojang.com/session/minecraft/join { accessToken, selectedProfile, serverId }
 *  3. POST /auth/verify { username, challenge } -> we ask Mojang
 *     GET  https://sessionserver.mojang.com/session/minecraft/hasJoined?username=..&serverId=..
 *     and, if Mojang confirms, return a signed session token (24h).
 * This is the same handshake vanilla Minecraft servers use (the server never gets the token).
 */

export const TOKEN_TTL_S = 24 * 3600;
const CHALLENGE_TTL_S = 120;
const DEV_FALLBACK_SECRET = "local-dev-secret-not-for-production";

export interface Session {
	uuid: string;
	name: string;
	exp: number;
	dev?: boolean;
}

export function devMode(env: Env, req: Request): boolean {
	if (env.DEV_MODE !== "1") return false;
	// Second guard: even if DEV_MODE leaked into a deployment, only local hosts may use it.
	const host = new URL(req.url).hostname;
	return host === "localhost" || host === "127.0.0.1" || host === "[::1]" || host.endsWith(".localhost");
}

export function secret(env: Env, req: Request): string | null {
	if (env.SESSION_SECRET && env.SESSION_SECRET.length >= 16) return env.SESSION_SECRET;
	return devMode(env, req) ? DEV_FALLBACK_SECRET : null;
}

async function serverIdFor(key: string, challenge: string): Promise<string> {
	return hex(await hmac(key, "serverId:" + challenge)).slice(0, 40);
}

export async function issueChallenge(key: string) {
	const challenge = `${randomHex(16)}.${Math.floor(Date.now() / 1000) + CHALLENGE_TTL_S}`;
	return { challenge, serverId: await serverIdFor(key, challenge), expiresIn: CHALLENGE_TTL_S };
}

export async function mintToken(key: string, s: Session): Promise<string> {
	const body = b64urlText(JSON.stringify({ u: s.uuid, n: s.name, e: s.exp, d: s.dev ? 1 : 0 }));
	return `${body}.${b64url(await hmac(key, "session:" + body))}`;
}

export async function readToken(key: string, token: string | null | undefined): Promise<Session | null> {
	if (!token || token.length > 512) return null;
	const dot = token.indexOf(".");
	if (dot <= 0) return null;
	const body = token.slice(0, dot);
	const sig = token.slice(dot + 1);
	if (!safeEqual(sig, b64url(await hmac(key, "session:" + body)))) return null;
	try {
		const p = JSON.parse(b64urlDecodeText(body));
		if (typeof p.e !== "number" || p.e < Date.now() / 1000) return null;
		const uuid = normUuid(p.u);
		if (!uuid || typeof p.n !== "string") return null;
		return { uuid, name: p.n, exp: p.e, dev: p.d === 1 };
	} catch {
		return null;
	}
}

export function bearer(req: Request): string | null {
	const h = req.headers.get("Authorization");
	if (h?.startsWith("Bearer ")) return h.slice(7).trim();
	return null;
}

/** Offline-mode UUID (what vanilla uses for cracked/dev accounts): v3 of "OfflinePlayer:<name>". */
async function offlineUuid(name: string): Promise<string> {
	const md5 = new Uint8Array(await crypto.subtle.digest("MD5", enc.encode("OfflinePlayer:" + name)));
	md5[6] = (md5[6] & 0x0f) | 0x30;
	md5[8] = (md5[8] & 0x3f) | 0x80;
	return normUuid(hex(md5))!;
}

export type VerifyResult = { ok: true; session: Session } | { ok: false; status: number; code: string };

export async function verifyLogin(env: Env, req: Request, key: string, body: any): Promise<VerifyResult> {
	const username = body?.username;
	const exp = Math.floor(Date.now() / 1000) + TOKEN_TTL_S;

	if (typeof username === "string" && username.startsWith("dev:")) {
		if (!devMode(env, req)) return { ok: false, status: 403, code: "dev_login_disabled" };
		const name = username.slice(4);
		if (!isMcName(name)) return { ok: false, status: 400, code: "bad_username" };
		const uuid = normUuid(body.uuid) ?? (await offlineUuid(name));
		return { ok: true, session: { uuid, name, exp, dev: true } };
	}

	if (!isMcName(username)) return { ok: false, status: 400, code: "bad_username" };
	const challenge = body?.challenge;
	if (typeof challenge !== "string" || !/^[0-9a-f]{32}\.\d{10}$/.test(challenge)) {
		return { ok: false, status: 400, code: "bad_challenge" };
	}
	if (Number(challenge.split(".")[1]) < Date.now() / 1000) return { ok: false, status: 401, code: "challenge_expired" };

	const serverId = await serverIdFor(key, challenge);
	// Preferred: signed profile key (no request to Mojang, whose session server blocks Cloudflare).
	if (body?.keySignature !== undefined) return verifyKeyLogin(body, serverId, exp);

	const url =
		"https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" +
		encodeURIComponent(username) +
		"&serverId=" +
		encodeURIComponent(serverId);
	// Mojang sometimes rate-limits (429) or hiccups (5xx), and Workers share outgoing IPs: retry twice.
	let res: Response | null = null;
	for (let attempt = 0; attempt < 3; attempt++) {
		if (attempt > 0) await new Promise((r) => setTimeout(r, 400 * attempt));
		try {
			res = await fetch(url, { headers: { "User-Agent": "skyparty-api", Accept: "application/json" } });
		} catch (e) {
			console.log("hasJoined unreachable", String(e));
			res = null;
			continue;
		}
		if (res.status !== 429 && res.status < 500) break;
		console.log("hasJoined retry", attempt, res.status);
	}
	if (!res) return { ok: false, status: 502, code: "mojang_unreachable" };
	if (res.status === 204) return { ok: false, status: 401, code: "not_joined" };
	if (!res.ok) {
		const text = (await res.text().catch(() => "")).slice(0, 200);
		console.log("hasJoined failed", res.status, text);
		return { ok: false, status: 502, code: "mojang_" + res.status };
	}
	const profile: any = await res.json().catch(() => null);
	const uuid = normUuid(profile?.id);
	if (!uuid || !isMcName(profile?.name)) return { ok: false, status: 502, code: "mojang_bad_response" };
	return { ok: true, session: { uuid, name: profile.name, exp } };
}
