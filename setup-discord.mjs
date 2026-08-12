/**
 * Crea ruoli, categorie e canali richiesti dal bot ranked, poi scrive gli ID
 * dentro RankedBot/config.yml. Idempotente: se un ruolo o canale con lo stesso
 * nome esiste già, viene riusato invece di crearne un duplicato.
 *
 * Uso: node setup-discord.mjs [--dry-run]
 */

import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";

const RUN_DIR = join(import.meta.dirname, "run");
const CONFIG_PATH = join(RUN_DIR, "RankedBot", "config.yml");
const DB_PATH = join(RUN_DIR, "data.db");
const API = "https://discord.com/api/v10";
const DRY_RUN = process.argv.includes("--dry-run");

/** Rank competitivi: elo coperto, elo guadagnato vincendo e perso perdendo. */
const RANKS = [
  { name: "Coal",      color: 0x6b6f76, start: 0,    end: 199,  win: 35, lose: 10 },
  { name: "Bronze",    color: 0xcd7f32, start: 200,  end: 399,  win: 30, lose: 10 },
  { name: "Silver",    color: 0xc0c0c0, start: 400,  end: 599,  win: 30, lose: 10 },
  { name: "Gold",      color: 0xffd700, start: 600,  end: 799,  win: 30, lose: 15 },
  { name: "Platinum",  color: 0x1abc9c, start: 800,  end: 999,  win: 25, lose: 15 },
  { name: "Emerald",   color: 0x2ecc71, start: 1000, end: 1199, win: 25, lose: 15 },
  { name: "Sapphire",  color: 0x3498db, start: 1200, end: 1399, win: 20, lose: 20 },
  { name: "Amethyst",  color: 0x9b59b6, start: 1400, end: 1599, win: 15, lose: 25 },
  { name: "Ruby",      color: 0xe74c3c, start: 1600, end: 1799, win: 10, lose: 25 },
  { name: "Pearl",     color: 0x76d7c4, start: 1800, end: 1999, win: 5,  lose: 35 },
  { name: "Diamond",   color: 0x00d4ff, start: 2000, end: 2199, win: 5,  lose: 40 },
];

/** Mappe di gioco. `players` = giocatori per team per cui la mappa è pensata. */
const MAPS = [
  { name: "antenna",     height: 112, team1: "Yellow", team2: "Green", players: 4 },
  { name: "antenna1",    height: 112, team1: "Yellow", team2: "Green", players: 4 },
  { name: "archway",     height: 87,  team1: "Red",    team2: "Green", players: 4 },
  { name: "archway1",    height: 87,  team1: "Red",    team2: "Green", players: 4 },
  { name: "boletum",     height: 105, team1: "Red",    team2: "Green", players: 4 },
  { name: "katsu",       height: 96,  team1: "Red",    team2: "Green", players: 4 },
  { name: "swashbuckle", height: 85,  team1: "Red",    team2: "Green", players: 4 },
  { name: "nebuc",       height: 106, team1: "Gray",   team2: "Pink",  players: 2 },
  { name: "apollo",      height: 89,  team1: "Gray",   team2: "Pink",  players: 2 },
  { name: "speedway",    height: 82,  team1: "Gray",   team2: "Pink",  players: 2 },
];

/** Code: un canale vocale ciascuna, la partita parte quando si riempie. */
const QUEUES = [
  { label: "2v2", emoji: "🥈", players: 2, mode: "AUTOMATIC", casual: false },
  { label: "3v3", emoji: "🥉", players: 3, mode: "AUTOMATIC", casual: false },
  { label: "4v4", emoji: "⚔️", players: 4, mode: "CAPTAINS",  casual: false },
];

// Bitfield dei permessi Discord usati qui.
const P = {
  VIEW: 1024n,
  SEND: 2048n,
  HISTORY: 65536n,
  ATTACH: 32768n,
  ADD_REACTIONS: 64n,
  CONNECT: 1048576n,
  SPEAK: 2097152n,
  USE_APP_COMMANDS: 2147483648n,
};

const CHANNEL = { TEXT: 0, VOICE: 2, CATEGORY: 4, ANNOUNCEMENT: 5 };

/**
 * Categoria IMPORTANT: canali informativi, in sola lettura salvo dove indicato.
 * `announcement: true` crea un canale annunci (serve un server Community).
 */
const IMPORTANT_CHANNELS = [
  { emoji: "🗞️", name: "spoilers" },
  { emoji: "📦", name: "staff-updates" },
  { emoji: "📣", name: "announcements", announcement: true },
  { emoji: "🎫", name: "ticket-support", writable: true },
  { emoji: "📊", name: "polls" },
  { emoji: "📰", name: "updates" },
  { emoji: "📖", name: "how-to-play" },
];
const OVERWRITE = { ROLE: 0 };

function readToken() {
  const raw = readFileSync(CONFIG_PATH, "utf8");
  const match = raw.match(/^token:\s*(.+)$/m);
  if (!match || !match[1].trim()) {
    throw new Error("Token non trovato in config.yml");
  }
  return match[1].trim();
}

const token = readToken();

async function api(path, method = "GET", body) {
  const res = await fetch(API + path, {
    method,
    headers: {
      Authorization: `Bot ${token}`,
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (res.status === 429) {
    const info = await res.json();
    const waitMs = Math.ceil((info.retry_after ?? 1) * 1000) + 250;
    console.log(`  rate limit, attendo ${waitMs}ms`);
    await new Promise((r) => setTimeout(r, waitMs));
    return api(path, method, body);
  }

  if (!res.ok) {
    throw new Error(`${method} ${path} -> ${res.status} ${await res.text()}`);
  }
  return res.status === 204 ? null : res.json();
}

const sum = (...bits) => bits.reduce((a, b) => a | b, 0n).toString();

async function resolveGuild() {
  const guilds = await api("/users/@me/guilds");
  if (guilds.length === 0) throw new Error("Il bot non è in nessun server");
  if (guilds.length > 1) {
    console.log("Server trovati:");
    guilds.forEach((g) => console.log(`  ${g.id}  ${g.name}`));
    throw new Error("Il bot è in più server: specifica quale usare");
  }
  return guilds[0];
}

/** Crea un ruolo se non ne esiste già uno con lo stesso nome. */
async function ensureRole(guildId, existingRoles, name, color) {
  const found = existingRoles.find((r) => r.name.toLowerCase() === name.toLowerCase());
  if (found) {
    console.log(`  ruolo esistente: ${name}`);
    return found.id;
  }
  if (DRY_RUN) {
    console.log(`  [dry-run] creerei ruolo: ${name}`);
    return "0";
  }
  const role = await api(`/guilds/${guildId}/roles`, "POST", {
    name,
    color,
    hoist: false,
    mentionable: true,
    permissions: "0",
  });
  console.log(`  ruolo creato: ${name}`);
  existingRoles.push(role);
  return role.id;
}

/**
 * Crea un canale se non esiste. `aliases` elenca i nomi precedenti dello stesso
 * canale: se ne trova uno, lo rinomina invece di crearne un duplicato.
 */
async function ensureChannel(guildId, existingChannels, spec, aliases = []) {
  const { aliases: _ignored, ...payload } = spec;
  const wanted = [payload.name, ...aliases].map((n) => n.toLowerCase());

  const found = existingChannels.find(
    (c) => wanted.includes(c.name.toLowerCase()) && c.type === payload.type
  );

  if (found) {
    if (found.name === payload.name) {
      console.log(`  canale esistente: ${payload.name}`);
      return found.id;
    }
    if (DRY_RUN) {
      console.log(`  [dry-run] rinominerei "${found.name}" in "${payload.name}"`);
      return found.id;
    }
    await api(`/channels/${found.id}`, "PATCH", { name: payload.name });
    console.log(`  rinominato: ${found.name} -> ${payload.name}`);
    found.name = payload.name;
    return found.id;
  }

  if (DRY_RUN) {
    console.log(`  [dry-run] creerei canale: ${payload.name}`);
    return "0";
  }
  const channel = await api(`/guilds/${guildId}/channels`, "POST", payload);
  console.log(`  canale creato: ${payload.name}`);
  existingChannels.push(channel);
  return channel.id;
}

/** Sposta un canale sotto una categoria, se non ci sta già. */
async function ensureParent(existingChannels, channelId, parentId, label) {
  const channel = existingChannels.find((c) => c.id === channelId);
  if (!channel || channel.parent_id === parentId) return;

  if (DRY_RUN) {
    console.log(`  [dry-run] sposterei ${label} sotto la categoria`);
    return;
  }
  await api(`/channels/${channelId}`, "PATCH", { parent_id: parentId });
  channel.parent_id = parentId;
  console.log(`  spostato: ${label}`);
}

/** Sostituisce il valore di una chiave in config.yml mantenendo commenti e ordine. */
function patchConfig(raw, key, value) {
  const pattern = new RegExp(`^(${key}:)[ \\t]*.*$`, "m");
  if (!pattern.test(raw)) {
    console.warn(`  chiave non trovata in config.yml: ${key}`);
    return raw;
  }
  return raw.replace(pattern, `$1 ${value}`);
}

/**
 * Scrive rank, mappe e code direttamente nel database del bot, così non serve
 * lanciare decine di comandi a mano. Il bot va tenuto spento durante questo passo.
 */
function writeToDatabase(ranks, queues) {
  if (!existsSync(DB_PATH)) {
    console.log("\ndata.db non trovato: avvia il bot una volta, poi rilancia questo script");
    return;
  }

  const db = new DatabaseSync(DB_PATH);
  try {
    db.exec(`CREATE TABLE IF NOT EXISTS ranks (
        role_id TEXT PRIMARY KEY,
        start_elo INTEGER NOT NULL,
        end_elo INTEGER NOT NULL,
        win_elo INTEGER NOT NULL,
        lose_elo INTEGER NOT NULL)`);

    db.exec(`CREATE TABLE IF NOT EXISTS maps (
        name TEXT PRIMARY KEY,
        height INTEGER NOT NULL DEFAULT 0,
        team1 TEXT NOT NULL DEFAULT '',
        team2 TEXT NOT NULL DEFAULT '',
        players_each_team INTEGER NOT NULL DEFAULT 0)`);

    db.exec(`CREATE TABLE IF NOT EXISTS queues (
        vc_id TEXT PRIMARY KEY,
        players_each_team INTEGER NOT NULL,
        picking_mode TEXT NOT NULL,
        casual INTEGER NOT NULL DEFAULT 0)`);

    // Il database può venire da una versione precedente senza questa colonna.
    const mapColumns = db.prepare("PRAGMA table_info(maps)").all();
    if (!mapColumns.some((c) => c.name === "players_each_team")) {
      db.exec("ALTER TABLE maps ADD COLUMN players_each_team INTEGER NOT NULL DEFAULT 0");
    }

    console.log("\nRank in data.db:");
    const insertRank = db.prepare(
      "INSERT OR REPLACE INTO ranks (role_id, start_elo, end_elo, win_elo, lose_elo) VALUES (?, ?, ?, ?, ?)"
    );
    for (const rank of ranks) {
      insertRank.run(rank.roleId, rank.start, rank.end, rank.win, rank.lose);
      console.log(`  ${rank.name}: ${rank.start}-${rank.end} · +${rank.win} / -${rank.lose}`);
    }

    console.log("\nMappe in data.db:");
    const insertMap = db.prepare(
      "INSERT OR REPLACE INTO maps (name, height, team1, team2, players_each_team) VALUES (?, ?, ?, ?, ?)"
    );
    for (const map of MAPS) {
      insertMap.run(map.name, map.height, map.team1, map.team2, map.players);
      console.log(`  ${map.name} (${map.players}v${map.players}) — h${map.height}, ${map.team1} vs ${map.team2}`);
    }

    console.log("\nCode in data.db:");
    const insertQueue = db.prepare(
      "INSERT OR REPLACE INTO queues (vc_id, players_each_team, picking_mode, casual) VALUES (?, ?, ?, ?)"
    );
    for (const queue of queues) {
      insertQueue.run(queue.vcId, queue.players, queue.mode, queue.casual ? 1 : 0);
      console.log(`  ${queue.label} — ${queue.mode.toLowerCase()}, vocale ${queue.vcId}`);
    }
  } finally {
    db.close();
  }
}

async function main() {
  const guild = await resolveGuild();
  console.log(`Server: ${guild.name} (${guild.id})\n`);

  const everyone = guild.id; // il ruolo @everyone ha lo stesso ID del server

  console.log("Ruoli:");
  const roles = await api(`/guilds/${guild.id}/roles`);
  const registeredRole = await ensureRole(guild.id, roles, "Registered", 0x5865f2);
  const bannedRole = await ensureRole(guild.id, roles, "Banned", 0xed4245);
  const frozenRole = await ensureRole(guild.id, roles, "Frozen", 0x3498db);
  const screensharerRole = await ensureRole(guild.id, roles, "Screensharer", 0x9b59b6);
  const scorerRole = await ensureRole(guild.id, roles, "Scorer", 0xf1c40f);

  console.log("\nRank competitivi:");
  const rankRoleIds = [];
  // Dal più alto al più basso: Discord mette i nuovi ruoli in fondo, così
  // Diamond finisce sopra Coal nella lista del server.
  for (const rank of [...RANKS].reverse()) {
    const id = await ensureRole(guild.id, roles, rank.name, rank.color);
    rankRoleIds.unshift({ ...rank, roleId: id });
  }

  const channels = await api(`/guilds/${guild.id}/channels`);

  // Lettura pubblica, scrittura vietata: i messaggi li manda il bot.
  const readOnly = [
    { id: everyone, type: OVERWRITE.ROLE, allow: sum(P.VIEW, P.HISTORY), deny: sum(P.SEND) },
  ];

  // Nascosto a tutti tranne ai registrati.
  const registeredOnly = [
    { id: everyone, type: OVERWRITE.ROLE, allow: "0", deny: sum(P.VIEW) },
    { id: registeredRole, type: OVERWRITE.ROLE, allow: sum(P.VIEW, P.HISTORY), deny: sum(P.SEND) },
  ];

  console.log("\nCategoria IMPORTANT:");
  const importantCategory = await ensureChannel(
    guild.id,
    channels,
    { name: "📌 IMPORTANT", type: CHANNEL.CATEGORY, permission_overwrites: readOnly },
    ["IMPORTANT"]
  );

  // I canali annunci esistono solo sui server Community: senza, si ripiega su testo.
  const isCommunity = (guild.features ?? []).includes("COMMUNITY");
  if (!isCommunity) {
    console.log("  (server non Community: #announcements creato come canale testuale)");
  }

  for (const spec of IMPORTANT_CHANNELS) {
    const type = spec.announcement && isCommunity ? CHANNEL.ANNOUNCEMENT : CHANNEL.TEXT;
    await ensureChannel(
      guild.id,
      channels,
      {
        name: `${spec.emoji}・${spec.name}`,
        type,
        parent_id: importantCategory,
        topic: `${spec.emoji} ${spec.name.replace(/-/g, " ")}`,
        permission_overwrites: spec.writable
          ? [
              {
                id: everyone,
                type: OVERWRITE.ROLE,
                allow: sum(P.VIEW, P.SEND, P.HISTORY),
                deny: "0",
              },
            ]
          : readOnly,
      },
      [spec.name]
    );
  }

  console.log("\nCategoria RBW SYSTEM:");
  const systemCategory = await ensureChannel(
    guild.id,
    channels,
    { name: "⚙️ RBW SYSTEM", type: CHANNEL.CATEGORY, permission_overwrites: readOnly },
    ["RBW SYSTEM"]
  );

  const alertsChannel = await ensureChannel(
    guild.id,
    channels,
    {
      name: "🔔・bot-alerts",
      type: CHANNEL.TEXT,
      parent_id: systemCategory,
      topic: "🔔 Avvisi del bot ranked",
      permission_overwrites: registeredOnly,
    },
    ["bot-alerts"]
  );

  const banChannel = await ensureChannel(
    guild.id,
    channels,
    {
      name: "🔴・strike-bans",
      type: CHANNEL.TEXT,
      parent_id: systemCategory,
      topic: "🔴 Ban e strike assegnati dal bot",
      permission_overwrites: readOnly,
    },
    ["strike-bans"]
  );

  const ssChannel = await ensureChannel(
    guild.id,
    channels,
    {
      name: "💧・strike-request",
      type: CHANNEL.TEXT,
      parent_id: systemCategory,
      topic: "💧 Richieste di screenshare",
      permission_overwrites: [
        ...readOnly,
        {
          id: screensharerRole,
          type: OVERWRITE.ROLE,
          allow: sum(P.VIEW, P.SEND, P.HISTORY, P.ATTACH),
          deny: "0",
        },
      ],
    },
    ["strike-request"]
  );

  const scoringChannel = await ensureChannel(
    guild.id,
    channels,
    {
      name: "🎯・scoring",
      type: CHANNEL.TEXT,
      parent_id: systemCategory,
      topic: "🎯 Partite scorate",
      permission_overwrites: readOnly,
    },
    ["scoring"]
  );

  const gamesChannel = await ensureChannel(
    guild.id,
    channels,
    {
      name: "🎲・games",
      type: CHANNEL.TEXT,
      parent_id: systemCategory,
      topic: "🎲 Partite create dal bot",
      permission_overwrites: registeredOnly,
    },
    ["games"]
  );

  console.log("\nCategoria RBW QUEUES:");
  const queuesCategory = await ensureChannel(
    guild.id,
    channels,
    {
      name: "🎮 RBW QUEUES",
      type: CHANNEL.CATEGORY,
      permission_overwrites: [
        { id: everyone, type: OVERWRITE.ROLE, allow: sum(P.VIEW), deny: "0" },
        // Chi è bannato o congelato non può entrare nelle code.
        { id: bannedRole, type: OVERWRITE.ROLE, allow: "0", deny: sum(P.CONNECT) },
        { id: frozenRole, type: OVERWRITE.ROLE, allow: "0", deny: sum(P.CONNECT) },
      ],
    },
    ["RBW QUEUES"]
  );

  const lfpChannel = await ensureChannel(
    guild.id,
    channels,
    {
      name: "🍥・looking-for-party",
      type: CHANNEL.TEXT,
      parent_id: queuesCategory,
      topic: "🍥 Cerca gente per giocare",
      permission_overwrites: [
        { id: everyone, type: OVERWRITE.ROLE, allow: "0", deny: sum(P.VIEW) },
        {
          id: registeredRole,
          type: OVERWRITE.ROLE,
          allow: sum(P.VIEW, P.SEND, P.HISTORY, P.USE_APP_COMMANDS),
          deny: "0",
        },
      ],
    },
    ["looking-for-party"]
  );

  const commandsChannel = await ensureChannel(
    guild.id,
    channels,
    {
      name: "🎰・commands",
      type: CHANNEL.TEXT,
      parent_id: queuesCategory,
      topic: "🎰 Usa qui i comandi del bot",
      permission_overwrites: [
        {
          id: everyone,
          type: OVERWRITE.ROLE,
          allow: sum(P.VIEW, P.SEND, P.HISTORY, P.USE_APP_COMMANDS),
          deny: "0",
        },
      ],
    },
    ["commands"]
  );

  // Un vocale per coda: entrano solo i registrati, banditi e congelati esclusi.
  const queueChannels = [];
  for (const queue of QUEUES) {
    const name = `${queue.emoji} ${queue.label} Queue`;
    const id = await ensureChannel(
      guild.id,
      channels,
      {
        name,
        type: CHANNEL.VOICE,
        parent_id: queuesCategory,
        user_limit: queue.players * 2,
        permission_overwrites: [
          { id: everyone, type: OVERWRITE.ROLE, allow: "0", deny: sum(P.CONNECT) },
          {
            id: registeredRole,
            type: OVERWRITE.ROLE,
            allow: sum(P.VIEW, P.CONNECT, P.SPEAK),
            deny: "0",
          },
          { id: bannedRole, type: OVERWRITE.ROLE, allow: "0", deny: sum(P.CONNECT) },
          { id: frozenRole, type: OVERWRITE.ROLE, allow: "0", deny: sum(P.CONNECT) },
        ],
      },
      [`${queue.label} Queue`]
    );
    queueChannels.push({ ...queue, vcId: id });
  }

  console.log("\nCategoria COMMUNITY:");
  const communityCategory = await ensureChannel(
    guild.id,
    channels,
    {
      name: "💬 COMMUNITY",
      type: CHANNEL.CATEGORY,
      permission_overwrites: [
        { id: everyone, type: OVERWRITE.ROLE, allow: sum(P.VIEW, P.SEND, P.HISTORY), deny: "0" },
      ],
    },
    ["COMMUNITY"]
  );

  const writableForAll = [
    {
      id: everyone,
      type: OVERWRITE.ROLE,
      allow: sum(P.VIEW, P.SEND, P.HISTORY, P.ATTACH, P.ADD_REACTIONS),
      deny: "0",
    },
  ];

  await ensureChannel(
    guild.id,
    channels,
    {
      name: "💬・chat",
      type: CHANNEL.TEXT,
      parent_id: communityCategory,
      topic: "💬 Chat generale",
      permission_overwrites: writableForAll,
    },
    ["chat"]
  );

  // suggestions e texture-packs sono canali annunci: pubblicano solo staff e bot.
  for (const spec of [
    { emoji: "📕", name: "suggestions", topic: "📕 Proposte per il server" },
    { emoji: "📁", name: "texture-packs", topic: "📁 Texture pack consigliati" },
  ]) {
    await ensureChannel(
      guild.id,
      channels,
      {
        name: `${spec.emoji}・${spec.name}`,
        type: isCommunity ? CHANNEL.ANNOUNCEMENT : CHANNEL.TEXT,
        parent_id: communityCategory,
        topic: spec.topic,
        permission_overwrites: readOnly,
      },
      [spec.name]
    );
  }

  await ensureChannel(
    guild.id,
    channels,
    {
      name: "🎥・clips",
      type: CHANNEL.TEXT,
      parent_id: communityCategory,
      topic: "🎥 Le vostre clip",
      permission_overwrites: writableForAll,
    },
    ["clips"]
  );

  // #commands era nato sotto RBW QUEUES: nel layout di riferimento sta in COMMUNITY.
  await ensureParent(channels, commandsChannel, communityCategory, "🎰・commands");

  console.log("\nCategoria GAMES:");
  // Qui il bot crea da solo i canali delle partite in corso.
  const gamesCategory = await ensureChannel(
    guild.id,
    channels,
    {
      name: "🏟️ GAMES",
      type: CHANNEL.CATEGORY,
      permission_overwrites: [
        { id: everyone, type: OVERWRITE.ROLE, allow: "0", deny: sum(P.VIEW) },
      ],
    },
    ["GAMES"]
  );

  if (DRY_RUN) {
    console.log("\n[dry-run] config.yml non modificato");
    return;
  }

  console.log("\nAggiorno config.yml:");
  let raw = readFileSync(CONFIG_PATH, "utf8");
  const updates = {
    "registered-role": registeredRole,
    "banned-role": bannedRole,
    "frozen-role": frozenRole,
    "ss-roles": `${screensharerRole}`,
    "scorer-role": scorerRole,
    "alerts-channel": alertsChannel,
    "ban-channel": banChannel,
    "ssreq-channel": ssChannel,
    "scored-announcing": scoringChannel,
    "games-announcing": gamesChannel,
    "game-channels-category": gamesCategory,
    "game-vcs-category": gamesCategory,
    "server-name": guild.name,
  };

  for (const [key, value] of Object.entries(updates)) {
    raw = patchConfig(raw, key, value);
    console.log(`  ${key}: ${value}`);
  }

  writeFileSync(CONFIG_PATH, raw, "utf8");

  writeToDatabase(rankRoleIds, queueChannels);

  console.log("\nFatto. Avvia il bot, poi in Discord:");
  console.log("  /config ranks   /config maps   /config queues   per verificare");
  console.log("  /register ign:TuoNomeMC   per registrarti");
  console.log(`  Canale comandi: ${commandsChannel} · looking-for-party: ${lfpChannel}`);
}

main().catch((err) => {
  console.error("\nErrore:", err.message);
  process.exit(1);
});
