/**
 * Pubblica la guida in #how-to-play: un embed con le regole base e un secondo
 * messaggio con le due immagini sulla difesa del letto.
 *
 * Uso: node post-howtoplay.mjs [--dry-run]
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = import.meta.dirname;
const CONFIG_PATH = join(ROOT, "run", "RankedBot", "config.yml");
const API = "https://discord.com/api/v10";
const DRY_RUN = process.argv.includes("--dry-run");
/** Pubblica solo il messaggio con le immagini, lasciando intatta la guida. */
const SECOND_ONLY = process.argv.includes("--second-only");
/** ID di un messaggio da cancellare prima di pubblicare, per sostituire un post vecchio. */
const DELETE_ID = process.argv.find((a) => a.startsWith("--delete="))?.split("=")[1];

const IMAGES = [
  { path: "C:/Users/donti/Downloads/image1png.png", name: "difesa-legno.png" },
  { path: "C:/Users/donti/Downloads/image.png", name: "difesa-endstone.png" },
];

const token = readFileSync(CONFIG_PATH, "utf8").match(/^token:\s*(.+)$/m)[1].trim();
const authHeader = { Authorization: `Bot ${token}` };

async function api(path, method = "GET", body) {
  const res = await fetch(API + path, {
    method,
    headers: { ...authHeader, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${await res.text()}`);
  return res.status === 204 ? null : res.json();
}

const GUIDE_EMBED = {
  title: "📖 Come si gioca — Guida rapida",
  color: 0x5865f2,
  description:
    "Benvenuto nel ranked bedwars. Qui trovi tutto quello che serve per iniziare.",
  fields: [
    {
      name: "1️⃣ Registrati",
      value:
        "Usa `/register ign:IlTuoNomeMinecraft` in <#COMMANDS_ID>.\n" +
        "Ricevi il ruolo **Registered**, il rank **Coal** e il nickname diventa `[elo] Nome`.",
    },
    {
      name: "2️⃣ Entra in coda",
      value:
        "Entra in uno dei vocali della categoria **RBW QUEUES**:\n" +
        "🥈 **2v2** e 🥉 **3v3** — team bilanciati in automatico per elo\n" +
        "⚔️ **4v4** — i due giocatori con elo più alto fanno da capitani e scelgono con `/pick`\n\n" +
        "Quando il vocale si riempie, il bot crea da solo il canale della partita, i vocali dei team e sorteggia la mappa.",
    },
    {
      name: "3️⃣ Gioca e chiudi la partita",
      value:
        "Finita la partita, **chi ha vinto** usa `/submit` nel canale della partita allegando lo screenshot.\n" +
        "Uno scorer conferma con `/score` e l'elo viene assegnato.",
    },
    {
      name: "4️⃣ Elo e rank",
      value:
        "Si parte da **0 elo** (Coal). Ogni rank dà elo diverso per vittoria e sconfitta: " +
        "ai livelli bassi si sale in fretta, ai livelli alti si guadagna poco e si perde molto.\n" +
        "Coal → Bronze → Silver → Gold → Platinum → Emerald → Sapphire → Amethyst → Ruby → Pearl → Diamond",
    },
    {
      name: "🤝 Party e clan",
      value:
        "`/party create` e `/party invite` per giocare con gli amici — l'elo guadagnato viene ridotto " +
        "in base a quanti siete.\n`/clan create` per fondare un clan e partecipare alle clan war.",
    },
    {
      name: "🚫 Regole",
      value:
        "Vietato cheat, teaming, stall e uscire dalla partita.\n" +
        "Se sospetti qualcuno usa `/screenshare` allegando le prove: verrà congelato finché non fa lo SS.\n" +
        "Chi viene beccato prende **strike**: elo tolto e ban dalle code, sempre più lungo a ogni strike.",
    },
    {
      name: "❓ Comandi utili",
      value:
        "`/stats` le tue statistiche · `/leaderboard` la classifica\n" +
        "`/gameinfo` info sulla partita · `/queuestats` chi è in coda\n" +
        "`/help` la lista completa",
    },
  ],
};

const PICKING_TEXT =
  "⚠️ __**How does the picking phase work?**__\n" +
  "As soon as the game starts, you will be teleported to a lobby where two captains select their teams.\n" +
  "The selection follows a specific **pick order** (typically 1-2-2-1):\n" +
  "- 1st Captain: Picks 1 player.\n" +
  "- 2nd Captain: Picks 2 players.\n" +
  "- 1st Captain: Picks 2 players.\n" +
  "- Last Player: Automatically joins the 2nd Captain's team.\n" +
  "\n" +
  "When the game starts, coordinate with your teammates to assign the following roles:\n" +
  "- **1st Bridger**: Wait for 28-32 Iron, buy Wool, and build a high bridge toward the top of the map.\n" +
  "- **2nd PvPer**: Collect 22 Gold and about 40-45 Iron. Buy Iron Armor, an Iron Sword, a Golden Apple, " +
  "tools, and blocks, then reach the Bridger at the top as soon as possible.\n" +
  "- **3rd PvPer**: Collect 15 Gold and about 48 Iron. Buy Iron Armor, a Golden Apple, tools, and blocks, " +
  "then immediately join your other two teammates at the top.\n" +
  "- **4th Defender**: Collect 1 stack + 8 Iron. Buy 12 Endstone and 16 Glass to cover the bed with the " +
  "classic Butterfly defense.";

async function main() {
  const guilds = await api("/users/@me/guilds");
  const channels = await api(`/guilds/${guilds[0].id}/channels`);

  const target = channels.find((c) => c.name.toLowerCase().includes("how-to-play"));
  if (!target) throw new Error("Canale how-to-play non trovato");

  const commandsChannel = channels.find((c) => c.name.toLowerCase().includes("commands"));

  const embed = structuredClone(GUIDE_EMBED);
  embed.fields[0].value = embed.fields[0].value.replace(
    "<#COMMANDS_ID>",
    commandsChannel ? `<#${commandsChannel.id}>` : "#commands"
  );

  if (DRY_RUN) {
    console.log(`Canale: ${target.name} (${target.id})`);
    console.log("\n--- embed ---");
    console.log(embed.title);
    for (const f of embed.fields) console.log(`\n[${f.name}]\n${f.value}`);
    console.log("\n--- secondo messaggio ---");
    console.log(PICKING_TEXT);
    console.log("\nImmagini:", IMAGES.map((i) => i.name).join(", "));
    if (DELETE_ID) console.log(`\nCancellerei il messaggio ${DELETE_ID}`);
    console.log("\n[dry-run] niente pubblicato");
    return;
  }

  if (DELETE_ID) {
    await api(`/channels/${target.id}/messages/${DELETE_ID}`, "DELETE");
    console.log(`Messaggio ${DELETE_ID} cancellato`);
  }

  if (!SECOND_ONLY) {
    const first = await api(`/channels/${target.id}/messages`, "POST", { embeds: [embed] });
    console.log(`Guida pubblicata (messaggio ${first.id})`);
  }

  const form = new FormData();
  form.append("payload_json", JSON.stringify({ content: PICKING_TEXT }));
  IMAGES.forEach((image, index) => {
    const bytes = readFileSync(image.path);
    form.append(`files[${index}]`, new Blob([bytes], { type: "image/png" }), image.name);
  });

  const res = await fetch(`${API}/channels/${target.id}/messages`, {
    method: "POST",
    headers: authHeader,
    body: form,
  });
  if (!res.ok) throw new Error(`upload immagini -> ${res.status} ${await res.text()}`);

  const second = await res.json();
  console.log(`Immagini pubblicate (messaggio ${second.id})`);
}

main().catch((err) => {
  console.error("\nErrore:", err.message);
  process.exit(1);
});
