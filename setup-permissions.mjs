/**
 * Crea il ruolo Staff e riscrive permissions.yml assegnando ogni comando a chi
 * deve poterlo usare. Di base il file era tutto "everyone": chiunque poteva
 * bannare, azzerare statistiche e modificarsi l'elo.
 *
 * Uso: node setup-permissions.mjs [--dry-run]
 */

import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const CONFIG_DIR = join(import.meta.dirname, "run", "RankedBot");
const CONFIG_PATH = join(CONFIG_DIR, "config.yml");
const PERMS_PATH = join(CONFIG_DIR, "permissions.yml");
const API = "https://discord.com/api/v10";
const DRY_RUN = process.argv.includes("--dry-run");

const token = readFileSync(CONFIG_PATH, "utf8").match(/^token:\s*(.+)$/m)[1].trim();

async function api(path, method = "GET", body) {
  const res = await fetch(API + path, {
    method,
    headers: { Authorization: `Bot ${token}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}

/** Comandi liberi: li usa qualunque giocatore registrato. */
const EVERYONE = [
  "help", "info",
  "register", "rename", "fix", "stats", "leaderboard", "transfergold", "theme",
  "queuestats", "gameinfo", "pick", "submit", "void", "call",
  "partycreate", "partyinvite", "partyjoin", "partyleave",
  "partylist", "partypromote", "partywarp", "partykick",
  "clancreate", "clandisband", "claninvite", "clanjoin", "clanleave", "clankick",
  "clanstats", "claninfo", "clansettings", "clanlist", "clanlb",
  "cwregister", "cwunregister",
  "queues", "ranks", "maps", "levels",
];

/** Assegna i risultati delle partite: tocca l'elo, quindi resta ristretto. */
const SCORING = ["score", "undogame", "win", "lose"];

/** Gestione delle richieste di screenshare. */
const SCREENSHARE = ["screenshare"];

/** Roba a basso impatto: consultazione o azioni annullabili. */
const LOW_IMPACT = ["baninfo", "forcevoid"];

/** Moderazione vera: toglie elo e blocca i giocatori. */
const MODERATION = ["ban", "unban", "strike"];

/** Comandi distruttivi o di configurazione: solo chi amministra. */
const ADMIN_ONLY = [
  "wipe", "modify", "forceregister", "forcerename",
  "clanforcedisband", "cwcreate", "cwcancel", "cwstart",
  "addqueue", "deletequeue", "addrank", "deleterank", "addmap", "deletemap",
  "givetheme", "removetheme", "reloadconfig", "savedata",
];

function buildPermissionsFile(r) {
  const section = (title, commands, value) =>
    `# ${title}\n` + commands.map((c) => `${c}: ${value}`).join("\n") + "\n";

  return [
    "# Chi puo' usare quale comando.",
    "# Valori ammessi: \"everyone\", uno o piu' ID ruolo separati da virgola,",
    "# oppure vuoto per disabilitare del tutto il comando.",
    "# Il proprietario del server puo' sempre usare tutto.",
    "#",
    "# Livelli: Admin > Staff > Moderator > Helper.",
    "# Piu' un comando e' difficile da annullare, meno ruoli lo possono usare.",
    "",
    section("Aperti a tutti i giocatori registrati", EVERYONE, "everyone"),
    section("Scoring partite — Scorer, Staff, Admin", SCORING, `${r.scorer},${r.staff},${r.admin}`),
    section(
      "Screenshare — Screensharer, Helper, Moderator, Staff, Admin",
      SCREENSHARE,
      `${r.screensharer},${r.helper},${r.moderator},${r.staff},${r.admin}`
    ),
    section(
      "Basso impatto (consultazione, annullare partite) — da Helper in su",
      LOW_IMPACT,
      `${r.helper},${r.moderator},${r.staff},${r.admin}`
    ),
    section("Moderazione (ban, strike) — Staff e Admin", MODERATION, `${r.staff},${r.admin}`),
    section("Distruttivi e configurazione — solo Admin", ADMIN_ONLY, r.admin),
  ].join("\n");
}

async function main() {
  const guilds = await api("/users/@me/guilds");
  const guildId = guilds[0].id;
  const roles = await api(`/guilds/${guildId}/roles`);

  const byName = (name) => roles.find((r) => r.name.toLowerCase() === name.toLowerCase());

  let staff = byName("Staff");
  if (!staff) {
    if (DRY_RUN) {
      console.log("[dry-run] creerei il ruolo Staff");
      staff = { id: "STAFF_ID", name: "Staff" };
    } else {
      staff = await api(`/guilds/${guildId}/roles`, "POST", {
        name: "Staff",
        color: 0xe67e22,
        hoist: true,
        mentionable: true,
        permissions: "0",
      });
      console.log("ruolo creato: Staff");
    }
  } else {
    console.log("ruolo Staff esistente");
  }

  const required = {
    scorer: byName("Scorer"),
    screensharer: byName("Screensharer"),
    admin: byName("Admin"),
    moderator: byName("Moderator"),
    helper: byName("Helper"),
  };

  const missing = Object.entries(required)
    .filter(([, role]) => !role)
    .map(([name]) => name);
  if (missing.length > 0) {
    throw new Error(`Ruoli mancanti (${missing.join(", ")}): lancia prima setup-discord.mjs e setup-roles.mjs`);
  }

  const content = buildPermissionsFile({
    staff: staff.id,
    scorer: required.scorer.id,
    screensharer: required.screensharer.id,
    admin: required.admin.id,
    moderator: required.moderator.id,
    helper: required.helper.id,
  });

  if (DRY_RUN) {
    console.log("\n--- permissions.yml ---\n");
    console.log(content);
    console.log("[dry-run] file non scritto");
    return;
  }

  writeFileSync(PERMS_PATH, content, "utf8");
  console.log(
    `permissions.yml riscritto: ${EVERYONE.length} liberi, ${MODERATION.length} moderazione, ` +
    `${ADMIN_ONLY.length} solo Admin`
  );

  // Con i permessi in ordine non serve piu' lasciare i comandi ai non registrati.
  let config = readFileSync(CONFIG_PATH, "utf8");
  config = config.replace(/^(unregistered-cmd-usage:)[ \t]*.*$/m, "$1 false");
  writeFileSync(CONFIG_PATH, config, "utf8");
  console.log("config.yml: unregistered-cmd-usage impostato a false");

  console.log(`\nRuolo Staff: ${staff.id} — assegnalo al tuo team dalle impostazioni del server.`);
}

main().catch((err) => {
  console.error("\nErrore:", err.message);
  process.exit(1);
});
