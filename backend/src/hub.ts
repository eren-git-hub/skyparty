import { DurableObject } from "cloudflare:workers";
import { type Bucket, type Env, takeToken } from "./util";

/**
 * One global Durable Object ("hub") that owns:
 *  - the pet table (SQLite inside the DO: free plan, no KV writes),
 *  - in-memory token buckets for per-uuid / per-IP rate limits (a single instance sees every request,
 *    so limits are exact; they reset if the object is evicted, which is fine for abuse protection).
 * All methods are called via Workers RPC from the main worker.
 */

export const STAGES = ["EGG", "HATCHLING", "JUVENILE", "ADULT"] as const;
export const VISIBILITIES = ["Everyone", "Only Me"] as const;

export interface Pet {
	name: string;
	stage: (typeof STAGES)[number];
	bodyColor: number;
	accentColor: number;
	visibility: (typeof VISIBILITIES)[number];
	updated: number;
}

/** Rate limit presets: [capacity, refill per second]. */
export const LIMITS = {
	auth: [10, 10 / 60], // per IP: 10 burst, 10/min
	ws: [20, 20 / 60], // per uuid: room connects
	wsIp: [60, 60 / 60], // per IP: room connects
	petPut: [4, 1 / 15], // per uuid: 4 burst, then one per 15s
	petGet: [20, 30 / 60], // per uuid: 20 burst, 30/min
	petGetIp: [60, 60 / 60], // per IP
} as const satisfies Record<string, readonly [number, number]>;

export type LimitName = keyof typeof LIMITS;

const MAX_BUCKETS = 20_000;

export class Hub extends DurableObject<Env> {
	private buckets = new Map<string, Bucket>();

	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS pets (
			uuid TEXT PRIMARY KEY,
			name TEXT NOT NULL,
			stage TEXT NOT NULL,
			body INTEGER NOT NULL,
			accent INTEGER NOT NULL,
			visibility TEXT NOT NULL,
			updated INTEGER NOT NULL
		)`);
	}

	/** Returns false when [key] is over the [limit]. Keys look like "petPut:<uuid>". */
	allow(limit: LimitName, key: string): boolean {
		const [cap, rate] = LIMITS[limit];
		const id = `${limit}:${key}`;
		const now = Date.now();
		let b = this.buckets.get(id);
		if (!b) {
			if (this.buckets.size >= MAX_BUCKETS) this.prune(now);
			b = { tokens: cap, at: now };
			this.buckets.set(id, b);
		}
		return takeToken(b, cap, rate, now);
	}

	/** Several limits at once; all must pass (tokens are only taken while passing). */
	allowAll(checks: [LimitName, string][]): boolean {
		for (const [limit, key] of checks) if (!this.allow(limit, key)) return false;
		return true;
	}

	private prune(now: number) {
		// Drop buckets that have refilled completely; they carry no information.
		for (const [id, b] of this.buckets) {
			const [cap, rate] = LIMITS[id.slice(0, id.indexOf(":")) as LimitName];
			if (b.tokens + ((now - b.at) / 1000) * rate >= cap) this.buckets.delete(id);
		}
		if (this.buckets.size >= MAX_BUCKETS) this.buckets.clear();
	}

	putPet(uuid: string, pet: Omit<Pet, "updated">): { ok: true; changed: boolean } {
		const sql = this.ctx.storage.sql;
		const row = sql.exec("SELECT name, stage, body, accent, visibility FROM pets WHERE uuid = ?", uuid).toArray()[0];
		if (
			row &&
			row.name === pet.name &&
			row.stage === pet.stage &&
			row.body === pet.bodyColor &&
			row.accent === pet.accentColor &&
			row.visibility === pet.visibility
		) {
			return { ok: true, changed: false }; // unchanged: skip the write (rows written are the scarcest free resource)
		}
		sql.exec(
			"INSERT OR REPLACE INTO pets (uuid, name, stage, body, accent, visibility, updated) VALUES (?, ?, ?, ?, ?, ?, ?)",
			uuid,
			pet.name,
			pet.stage,
			pet.bodyColor,
			pet.accentColor,
			pet.visibility,
			Date.now(),
		);
		return { ok: true, changed: true };
	}

	deletePet(uuid: string): void {
		this.ctx.storage.sql.exec("DELETE FROM pets WHERE uuid = ?", uuid);
	}

	/** Only pets with visibility "Everyone" are ever returned to other players. */
	getPets(uuids: string[], self: string): Record<string, Pet> {
		if (uuids.length === 0) return {};
		const marks = uuids.map(() => "?").join(",");
		const rows = this.ctx.storage.sql
			.exec(`SELECT uuid, name, stage, body, accent, visibility, updated FROM pets WHERE uuid IN (${marks})`, ...uuids)
			.toArray();
		const out: Record<string, Pet> = {};
		for (const r of rows) {
			if (r.visibility !== "Everyone" && r.uuid !== self) continue;
			out[r.uuid as string] = {
				name: r.name as string,
				stage: r.stage as Pet["stage"],
				bodyColor: r.body as number,
				accentColor: r.accent as number,
				visibility: r.visibility as Pet["visibility"],
				updated: r.updated as number,
			};
		}
		return out;
	}
}
