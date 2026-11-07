import type { Session } from "./auth";
import { isMcName, normUuid } from "./util";

/**
 * Login without asking Mojang (Mojang's session server blocks requests from Cloudflare's network).
 *
 * Every real Minecraft account gets a chat-signing key pair from Mojang. Its public key comes with
 * Mojang's signature over (uuid, expiresAt, key), the same "keySignature" vanilla servers check:
 *   SHA1withRSA by one of Mojang's player certificate keys (https://api.minecraftservices.com/publickeys)
 * The client proves it holds the private key by signing "skyparty-login:<serverId>" (SHA256withRSA),
 * where serverId = HMAC(secret, challenge) from /auth/challenge, so a signature only works once, here.
 *
 * Result: the UUID is proven. The name is what the client sends (only checked to be a valid name).
 */
const MOJANG_PLAYER_CERT_KEYS = [
	"MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAylB4B6m5lz7jwrcFz6Fd/fnfUhcvlxsTSn5kIK/2aGG1C3kMy4VjhwlxF6BFUSnfxhNswPjh3ZitkBxEAFY25uzkJFRwHwVA9mdwjashXILtR6OqdLXXFVyUPIURLOSWqGNBtb08EN5fMnG8iFLgEJIBMxs9BvF3s3/FhuHyPKiVTZmXY0WY4ZyYqvoKR+XjaTRPPvBsDa4WI2u1zxXMeHlodT3lnCzVvyOYBLXL6CJgByuOxccJ8hnXfF9yY4F0aeL080Jz/3+EBNG8RO4ByhtBf4Ny8NQ6stWsjfeUIvH7bU/4zCYcYOq4WrInXHqS8qruDmIl7P5XXGcabuzQstPf/h2CRAUpP/PlHXcMlvewjmGU6MfDK+lifScNYwjPxRo4nKTGFZf/0aqHCh/EAsQyLKrOIYRE0lDG3bzBh8ogIMLAugsAfBb6M3mqCqKaTMAf/VAjh5FFJnjS+7bE+bZEV0qwax1CEoPPJL1fIQjOS8zj086gjpGRCtSy9+bTPTfTR/SJ+VUB5G2IeCItkNHpJX2ygojFZ9n5Fnj7R9ZnOM+L8nyIjPu3aePvtcrXlyLhH/hvOfIOjPxOlqW+O5QwSFP4OEcyLAUgDdUgyW36Z5mB285uKW/ighzZsOTevVUG2QwDItObIV6i8RCxFbN2oDHyPaO5j1tTaBNyVt8CAwEAAQ==",
	"MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAt4t9NPuu7cktclnaH7eZj0omkLcJHeLz5MKsyJEntHZ0INtuBjSSul3Pp3pBeJN8k3ADdcdBLUN90bcAi7WsQqTx3Ft363q3W7TbM8j2iTEdp/0uVspoRt/DP1tkaWFs/w2WwUv9jbVoBUzfUc4pSTIxRwdjmqjZQfvjwKNDbOx3IhP2H0WXodbISejPi1wBZqNW4m1rnZAXp/EpUguxA8mobCa4vUCBkyFDyXdl69/wUSJHyCPmgcMJ364OlAhIqtwVPShBZObvrK/f0BYk6ShJD3N7TFDatSYsIIdcTKRknaIm91s+EsMrdB9U4Yw+ZJ/pyCB4S3vk8zfDCnb0DWIxYH3/EMzaxl77djmTmMzi/JDITup5z3jfWtRZmrAhU2/+W5IO5hEpo3/bCS9PXIY5xb41Lmp2ZO8dXKtyD66Chchy0W129n8vPl2GIruOdrxsjZAHnneyAb9jm0uaGaphwnEnuecX/qgHY6ZMtayvLLsPst8PO6R1vufMy8WqjK+j7LnC1krL7CPDg0NEhyQTmw5l+NCNjSlvB1juM9V4PARg0bYCOkGXm7ydRCjSSH8CJXZpwnd5cBB5WKAX3KPzutRgMi/LFwNSMZzFuUyXaYOZPpD259yqph1LmGqegEdDriACVU+dVEONFMm8eIuBofe7ljmsAFKW9BINwK0CAwEAAQ==",
];

function b64ToBytes(b64: string): Uint8Array {
	const bin = atob(b64);
	const out = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
}

function uuidBytes(uuid: string): Uint8Array {
	const hexStr = uuid.replace(/-/g, "");
	const out = new Uint8Array(16);
	for (let i = 0; i < 16; i++) out[i] = parseInt(hexStr.slice(i * 2, i * 2 + 2), 16);
	return out;
}

async function verifyRsa(spki: Uint8Array, hash: "SHA-1" | "SHA-256", sig: Uint8Array, data: Uint8Array): Promise<boolean> {
	try {
		const key = await crypto.subtle.importKey("spki", spki, { name: "RSASSA-PKCS1-v1_5", hash }, false, ["verify"]);
		return await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, sig, data);
	} catch {
		return false;
	}
}

export type KeyLoginResult = { ok: true; session: Session } | { ok: false; status: number; code: string };

export async function verifyKeyLogin(
	body: any, serverId: string, exp: number, trustedKeys: string[] = MOJANG_PLAYER_CERT_KEYS,
): Promise<KeyLoginResult> {
	const uuid = normUuid(body?.uuid);
	const name = body?.username;
	if (!uuid || !isMcName(name)) return { ok: false, status: 400, code: "bad_profile" };
	const expiresAt = Number(body?.expiresAt);
	if (!Number.isSafeInteger(expiresAt) || expiresAt < Date.now()) return { ok: false, status: 401, code: "key_expired" };
	let publicKey: Uint8Array, keySignature: Uint8Array, signature: Uint8Array;
	try {
		publicKey = b64ToBytes(String(body.publicKey));
		keySignature = b64ToBytes(String(body.keySignature));
		signature = b64ToBytes(String(body.signature));
	} catch {
		return { ok: false, status: 400, code: "bad_key" };
	}
	if (publicKey.length > 1024 || keySignature.length > 1024 || signature.length > 1024) return { ok: false, status: 400, code: "bad_key" };

	// 1) Mojang vouches for this key belonging to this uuid: payload = uuid (16) + expiresAt millis (8, big endian) + key DER.
	const payload = new Uint8Array(24 + publicKey.length);
	payload.set(uuidBytes(uuid), 0);
	new DataView(payload.buffer).setBigInt64(16, BigInt(expiresAt));
	payload.set(publicKey, 24);
	let mojangOk = false;
	for (const k of trustedKeys) {
		if (await verifyRsa(b64ToBytes(k), "SHA-1", keySignature, payload)) { mojangOk = true; break; }
	}
	if (!mojangOk) return { ok: false, status: 401, code: "key_not_signed_by_mojang" };

	// 2) The client holds the private key: it signed this login's serverId.
	const message = new TextEncoder().encode("skyparty-login:" + serverId);
	if (!(await verifyRsa(publicKey, "SHA-256", signature, message))) return { ok: false, status: 401, code: "bad_signature" };

	return { ok: true, session: { uuid, name, exp } };
}
