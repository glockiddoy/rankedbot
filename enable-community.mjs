/**
 * Abilita la modalità Community sul server e converte #announcements in un
 * canale annunci. Discord richiede, per attivare Community:
 *   - un canale regole e un canale aggiornamenti moderatori
 *   - livello di verifica almeno LOW
 *   - filtro contenuti espliciti su tutti i membri
 *   - notifiche predefinite solo su menzione
 *
 * Uso: node enable-community.mjs [--dry-run]
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

const CONFIG_PATH = join(import.meta.dirname, "run", "RankedBot", "config.yml");
const API = "https://discord.com/api/v10";
const DRY_RUN = process.argv.includes("--dry-run");

const CHANNEL = { TEXT: 0, CATEGORY: 4, ANNOUNCEMENT: 5 };
const P = { VIEW: 1024n, SEND: 2048n, HISTORY: 65536n };
const sum = (...bits) => bits.reduce((a, b) => a | b, 0n).toString();

const token = readFileSync(CONFIG_PATH, "utf8").match(/^token:\s*(.+)$/m)[1].trim();

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

function findChannel(channels, needle, type) {
  return channels.find(
    (c) => c.name.toLowerCase().includes(needle.toLowerCase()) && (type === undefined || c.type === type)
  );
}

async function main() {
  const guilds = await api("/users/@me/guilds");
  const guild = await api(`/guilds/${guilds[0].id}`);
  console.log(`Server: ${guild.name} (${guild.id})\n`);

  if ((guild.features ?? []).includes("COMMUNITY")) {
    console.log("Community già attiva.");
  }

  const channels = await api(`/guilds/${guild.id}/channels`);
  const everyone = guild.id;
  const importantCategory = findChannel(channels, "IMPORTANT", CHANNEL.CATEGORY);

  // Canale regole: pubblico, in sola lettura.
  let rules = findChannel(channels, "rules", CHANNEL.TEXT);
  if (!rules) {
    if (DRY_RUN) {
      console.log("[dry-run] creerei 📜・rules");
    } else {
      rules = await api(`/guilds/${guild.id}/channels`, "POST", {
        name: "📜・rules",
        type: CHANNEL.TEXT,
        parent_id: importantCategory?.id,
        topic: "📜 Regole del server",
        permission_overwrites: [
          { id: everyone, type: 0, allow: sum(P.VIEW, P.HISTORY), deny: sum(P.SEND) },
        ],
      });
      console.log(`canale creato: ${rules.name}`);
    }
  } else {
    console.log(`canale regole esistente: ${rules.name}`);
  }

  // Canale aggiornamenti moderatori: lo usa Discord per avvisi allo staff, va nascosto.
  let modUpdates = findChannel(channels, "moderator-only", CHANNEL.TEXT);
  if (!modUpdates) {
    if (DRY_RUN) {
      console.log("[dry-run] creerei 🛡️・moderator-only");
    } else {
      modUpdates = await api(`/guilds/${guild.id}/channels`, "POST", {
        name: "🛡️・moderator-only",
        type: CHANNEL.TEXT,
        parent_id: importantCategory?.id,
        topic: "🛡️ Avvisi di Discord per lo staff",
        permission_overwrites: [{ id: everyone, type: 0, allow: "0", deny: sum(P.VIEW) }],
      });
      console.log(`canale creato: ${modUpdates.name}`);
    }
  } else {
    console.log(`canale aggiornamenti moderatori esistente: ${modUpdates.name}`);
  }

  if (DRY_RUN) {
    console.log("\n[dry-run] Community non attivata, nessun canale convertito");
    return;
  }

  // Se Community è già attiva le impostazioni del server sono a posto:
  // toccarle di nuovo cambierebbe scelte già fatte dall'amministratore.
  if ((guild.features ?? []).includes("COMMUNITY")) {
    console.log("\nImpostazioni server già conformi, non le tocco");
  } else {
    // Discord accetta LOW su alcuni server e pretende MEDIUM su altri: si prova in ordine.
    const features = [...new Set([...(guild.features ?? []), "COMMUNITY"])];
    let enabled = false;
    for (const verificationLevel of [1, 2]) {
      try {
        await api(`/guilds/${guild.id}`, "PATCH", {
          features,
          rules_channel_id: rules.id,
          public_updates_channel_id: modUpdates.id,
          verification_level: verificationLevel,
          explicit_content_filter: 2,
          default_message_notifications: 1,
        });
        console.log(`\nCommunity attivata (livello di verifica ${verificationLevel === 1 ? "LOW" : "MEDIUM"})`);
        enabled = true;
        break;
      } catch (err) {
        console.log(`  livello ${verificationLevel} rifiutato, riprovo`);
        if (verificationLevel === 2) throw err;
      }
    }
    if (!enabled) return;
  }

  // Ora che il server è Community, il canale annunci diventa possibile.
  const announcements = findChannel(channels, "announcements");
  if (!announcements) {
    console.log("#announcements non trovato, niente da convertire");
    return;
  }
  if (announcements.type === CHANNEL.ANNOUNCEMENT) {
    console.log(`${announcements.name} è già un canale annunci`);
    return;
  }

  await api(`/channels/${announcements.id}`, "PATCH", { type: CHANNEL.ANNOUNCEMENT });
  console.log(`${announcements.name} convertito in canale annunci`);
}

main().catch((err) => {
  console.error("\nErrore:", err.message);
  process.exit(1);
});
