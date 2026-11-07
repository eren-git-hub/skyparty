import type { Hub } from "./hub";
import type { Room } from "./room";

export interface Env {
	ROOMS: DurableObjectNamespace<Room>;
	HUB: DurableObjectNamespace<Hub>;
	/** HMAC key for session tokens. Set with `npx wrangler secret put SESSION_SECRET`. */
	SESSION_SECRET?: string;
	/** "1" only for local `wrangler dev`: accepts "dev:<name>" logins without Mojang. */
	DEV_MODE?: string;
}

export const enc = new TextEncoder();
const dec = new TextDecoder();

export function b64url(bytes: Uint8Array): string {
	let s = "";
	for (const b of bytes) s += String.fromCharCode(b);
	return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64urlDecode(s: string): Uint8Array {
	const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - (s.length % 4));
	const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + pad);
	const out = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
}

export const b64urlText = (s: string) => b64url(enc.encode(s));
export const b64urlDecodeText = (s: string) => dec.decode(b64urlDecode(s));

export function hex(bytes: Uint8Array): string {
	return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function randomHex(byteCount: number): string {
	return hex(crypto.getRandomValues(new Uint8Array(byteCount)));
}

const keyCache = new Map<string, Promise<CryptoKey>>();

export async function hmac(secret: string, data: string): Promise<Uint8Array> {
	let key = keyCache.get(secret);
	if (!key) {
		key = crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
		keyCache.set(secret, key);
	}
	return new Uint8Array(await crypto.subtle.sign("HMAC", await key, enc.encode(data)));
}

/** Constant-time string comparison (both are short ASCII tokens). */
export function safeEqual(a: string, b: string): boolean {
	if (a.length !== b.length) return false;
	let diff = 0;
	for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
	return diff === 0;
}

export function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
	return new Response(JSON.stringify(data), {
		status,
		headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
	});
}

export const error = (status: number, code: string, message?: string) =>
	json({ error: code, message: message ?? code }, status);

/** Accepts dashed or undashed UUIDs, returns the lowercase dashed form or null. */
export function normUuid(s: unknown): string | null {
	if (typeof s !== "string") return null;
	const h = s.replace(/-/g, "").toLowerCase();
	if (!/^[0-9a-f]{32}$/.test(h)) return null;
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export const clientIp = (req: Request) => req.headers.get("CF-Connecting-IP") ?? "local";

/** Minecraft-style names: 1-16 chars of letters, digits and underscore. */
export const isMcName = (s: unknown): s is string => typeof s === "string" && /^[A-Za-z0-9_]{1,16}$/.test(s);

/** Simple token bucket; plain data so it can live inside a WebSocket attachment. */
export interface Bucket {
	tokens: number;
	at: number;
}

export function takeToken(b: Bucket, capacity: number, perSecond: number, now = Date.now(), cost = 1): boolean {
	b.tokens = Math.min(capacity, b.tokens + ((now - b.at) / 1000) * perSecond);
	b.at = now;
	if (b.tokens < cost) return false;
	b.tokens -= cost;
	return true;
}
