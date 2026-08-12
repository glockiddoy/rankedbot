/**
 * Crea la gerarchia staff standard e i ruoli community del server.
 * Applica anche gli override del ruolo Muted su tutti i canali.
 *
 * Uso: node setup-roles.mjs [--dry-run]
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

const CONFIG_PATH = join(import.meta.dirname, "run", "RankedBot", "config.yml");
const API = "https://discord.com/api/v10";
const DRY_RUN = process.argv.includes("--dry-run");

const token = readFileSync(CONFIG_PATH, "utf8").match(/^token:\s*(.+)$/m)[1].trim();

// Permessi Discord usati per la gerarchia staff.
const PERM = {
  KICK: 1n << 1n,
  BAN: 1n << 2n,
  MANAGE_CHANNELS: 1n << 4n,
  MANAGE_GUILD: 1n << 5n,
  VIEW_AUDIT_LOG: 1n << 7n,
  MANAGE_MESSAGES: 1n << 13n,
  MUTE_MEMBERS: 1n << 22n,
  DEAFEN_MEMBERS: 1n << 23n,
  MOVE_MEMBERS: 1n << 24n,
  MANAGE_NICKNAMES: 1n << 27n,
  MANAGE_ROLES: 1n << 28n,
  MANAGE_WEBHOOKS: 1n << 29n,
  TIMEOUT_MEMBERS: 1n << 40n,
  // Usati negli override del ruolo Muted.
  SEND: 1n << 11n,
  ADD_REACTIONS: 1n << 6n,
  SPEAK: 1n << 21n,
  CREATE_THREADS: 1n << 35n,
  SEND_IN_THREADS: 1n << 38n,
};

const bits = (...list) => list.reduce((a, b) => a | b, 0n).toString();

/**
 * Nessun ruolo riceve ADMINISTRATOR: è un permesso che scavalca ogni override
 * di canale. Admin ha comunque tutto ciò che serve per gestire il server.
 */
const ROLES = [
  {
    name: "Admin",
    color: 0xe11d48,
    hoist: true,
    permissions: bits(
      PERM.KICK, PERM.BAN, PERM.MANAGE_CHANNELS, PERM.MANAGE_GUILD, PERM.VIEW_AUDIT_LOG,
      PERM.MANAGE_MESSAGES, PERM.MUTE_MEMBERS, PERM.DEAFEN_MEMBERS, PERM.MOVE_MEMBERS,
      PERM.MANAGE_NICKNAMES, PERM.MANAGE_ROLES, PERM.MANAGE_WEBHOOKS, PERM.TIMEOUT_MEMBERS
    ),
  },
  {
    name: "Moderator",
    color: 0x3b82f6,
    hoist: true,
    permissions: bits(
      PERM.KICK, PERM.BAN, PERM.VIEW_AUDIT_LOG, PERM.MANAGE_MESSAGES,
      PERM.MUTE_MEMBERS, PERM.DEAFEN_MEMBERS, PERM.MOVE_MEMBERS,
      PERM.MANAGE_NICKNAMES, PERM.TIMEOUT_MEMBERS
    ),
  },
  {
    name: "Helper",
    color: 0x22c55e,
    hoist: true,
    permissions: bits(PERM.MANAGE_MESSAGES, PERM.MUTE_MEMBERS, PERM.MOVE_MEMBERS, PERM.TIMEOUT_MEMBERS),
  },
  { name: "Media", color: 0xf472b6, hoist: false, permissions: "0" },
  { name: "Bots", color: 0x99aab5, hoist: false, permissions: "0" },
  { name: "Muted", color: 0x4b5563, hoist: false, permissions: "0" },

  // Ruoli community richiesti, con i colori indicati.
  { name: "PUBS", color: 0xfacc15, hoist: false, permissions: "0" },
  { name: "PUPS", color: 0x2dd4bf, hoist: false, permissions: "0" },
  { name: "PUGS", color: 0x8b5cf6, hoist: false, permissions: "0" },
  { name: "Premium PUBS", color: 0xec4899, hoist: false, permissions: "0" },
];

async function api(path, method = "GET", body) {
  const res = await fetch(API + path, {
    method,
    headers: { Authorization: `Bot ${token}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (res.status === 429) {
    const info = await res.json();
    await new Promise((r) => setTimeout(r, Math.ceil((info.retry_after ?? 1) * 1000) + 250));
    return api(path, method, body);
  }
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}

async function main() {
  const guilds = await api("/users/@me/guilds");
  const guildId = guilds[0].id;
  const existing = await api(`/guilds/${guildId}/roles`);

  let mutedId = null;

  for (const spec of ROLES) {
    const found = existing.find((r) => r.name.toLowerCase() === spec.name.toLowerCase());
    if (found) {
      console.log(`ruolo esistente: ${spec.name}`);
      if (spec.name === "Muted") mutedId = found.id;
      continue;
    }
    if (DRY_RUN) {
      console.log(`[dry-run] creerei ruolo: ${spec.name}`);
      continue;
    }
    const role = await api(`/guilds/${guildId}/roles`, "POST", {
      name: spec.name,
      color: spec.color,
      hoist: spec.hoist,
      mentionable: true,
      permissions: spec.permissions,
    });
    console.log(`ruolo creato: ${spec.name}`);
    if (spec.name === "Muted") mutedId = role.id;
  }

  if (!mutedId) {
    if (DRY_RUN) console.log("\n[dry-run] applicherei gli override di Muted sui canali");
    return;
  }

  // Muted funziona solo se ogni canale gli nega esplicitamente la parola.
  console.log("\nOverride del ruolo Muted:");
  const channels = await api(`/guilds/${guildId}/channels`);
  const deny = bits(PERM.SEND, PERM.ADD_REACTIONS, PERM.SPEAK, PERM.CREATE_THREADS, PERM.SEND_IN_THREADS);

  let applied = 0;
  for (const channel of channels) {
    const already = (channel.permission_overwrites ?? []).find((o) => o.id === mutedId);
    if (already && already.deny === deny) continue;

    if (DRY_RUN) {
      applied++;
      continue;
    }
    await api(`/channels/${channel.id}/permissions/${mutedId}`, "PUT", {
      type: 0,
      allow: "0",
      deny,
    });
    applied++;
  }
  console.log(`  applicati su ${applied} canali`);
}

main().catch((err) => {
  console.error("\nErrore:", err.message);
  process.exit(1);
});
