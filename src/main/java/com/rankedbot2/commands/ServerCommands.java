package com.rankedbot2.commands;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandBase;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;

public class ServerCommands extends CommandBase {

    public ServerCommands(BotContext ctx, Embeds embeds, PlayerService playerService, GameService gameService) {
        super(ctx, embeds, playerService, gameService);
    }

    @Override
    public List<SlashCommandData> data() {
        OptionData category = new OptionData(OptionType.STRING, "categoria", "Gruppo di comandi", false)
                .addChoice("giocatore", "player")
                .addChoice("partite", "game")
                .addChoice("party", "party")
                .addChoice("clan", "clan")
                .addChoice("moderazione", "mod")
                .addChoice("configurazione", "config");

        return List.of(
                Commands.slash("help", "Elenco dei comandi disponibili").addOptions(category),
                Commands.slash("info", "Informazioni sul bot e sul server ranked"));
    }

    @Override
    public boolean handles(String name) {
        return name.equals("help") || name.equals("info");
    }

    @Override
    public String permissionKey(SlashCommandInteractionEvent e) {
        return e.getName();
    }

    @Override
    public boolean requiresRegistration(SlashCommandInteractionEvent e) {
        return false;
    }

    @Override
    public void execute(SlashCommandInteractionEvent e) {
        if (e.getName().equals("info")) {
            info(e);
        } else {
            help(e);
        }
    }

    private void help(SlashCommandInteractionEvent e) {
        String category = e.getOption("categoria") == null ? null : e.getOption("categoria").getAsString();
        EmbedBuilder eb = embeds.builder().setTitle("⚙️ Comandi Disponibili — Ranked Bedwars");

        if (category == null || category.equals("player")) {
            eb.addField("👤 Giocatore", """
                    `/register` registrati · `/rename` cambia IGN · `/fix` sistema ruoli
                    `/stats` statistiche · `/leaderboard` classifica · `/recap` banner recap
                    `/transfergold` manda gold · `/theme` scegli tema immagine""", false);
        }
        if (category == null || category.equals("game")) {
            eb.addField("🎮 Partite & Scoring", """
                    `/pick` scegli giocatore (capitani) · `/autoscore` score con link CoralMC
                    `/score` score manuale · `/void` annulla partita · `/undogame` annulla scoring
                    `/gameinfo` info partita · `/queuestats` stato code · `/call` invita nel vocale""", false);
        }
        if (category == null || category.equals("party")) {
            eb.addField("👥 Party", """
                    `/party create` · `/party invite` · `/party join` · `/party leave`
                    `/party list` · `/party promote` · `/party warp` · `/party kick`""", false);
        }
        if (category == null || category.equals("clan")) {
            eb.addField("🏰 Clan", """
                    `/clan create` · `/clan invite` · `/clan join` · `/clan leave` · `/clan kick`
                    `/clan stats` · `/clan info` · `/clan settings` · `/clan list` · `/clan leaderboard`
                    `/cw create` · `/cw register` · `/cw start` — clan war""", false);
        }
        if (category == null || category.equals("mod")) {
            eb.addField("👮 Moderazione & Mecca", """
                    `/mecca` segnala infrazione allo staff · `/mod ban` · `/mod unban`
                    `/mod strike` · `/mod wipe` · `/mod modify` · `/screenshare` richiedi SS""", false);
        }
        if (category == null || category.equals("config")) {
            eb.addField("🛠️ Configurazione", """
                    `/config addqueue` · `/config addrank` · `/config addmap` (+ delete/list)
                    `/config levels` · `/config givetheme` · `/config reload`""", false);
        }

        eb.setDescription("Usa `/help categoria:` per filtrare le categorie.");
        reply(e, eb.build());
    }

    private void info(SlashCommandInteractionEvent e) {
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        Duration uptime = Duration.ofMillis(uptimeMillis);

        EmbedBuilder eb = embeds.builder()
                .setTitle("📖 GUIDA RAPIDA — BEDWARS RANKED")
                .setDescription("Benvenuto nel sistema **Bedwars Ranked**! Qui trovi tutto il necessario per iniziare a giocare.\n")
                .addField("1️⃣ Registrati", "Usa `/register ign:TuoNomeMinecraft` in **#commands**.\nRiceverai il ruolo `Registered` ed il tuo nickname diventerà `[ELO] Nome`.", false)
                .addField("2️⃣ Entra in Coda", "Entra in uno dei vocali nella categoria **RBW QUEUES**:\n• ⚔️ **2v2 & 3v3** — Team bilanciati in automatico per ELO\n• ⚔️ **4v4** — I due giocatori con ELO più alto fanno da capitani e scelgono con `/pick`", false)
                .addField("3️⃣ Gioca & Autoscore", "A fine partita, incolla semplicemente il **link CoralMC** (es. `https://www.coralmc.it/stats/bedwars/match/...`) nel canale della partita. Il bot calcolerà in automatico ELO, Bed MVP e Kills MVP!", false)
                .addField("4️⃣ Elo & Rank Tiers", "• 👑 **Master** (1500+ ELO)\n• 💎 **Diamond** (1200+ ELO)\n• 🥇 **Gold** (1000+ ELO)\n• 🥈 **Silver** (800+ ELO)\n• 🥉 **Bronze** (500+ ELO)\n• 🪵 **Wood** (<500 ELO)", false)
                .addField("🚨 Segnalazioni", "Se un avversario fa dodge o viola il regolamento, usa `/mecca` allegando la prova per avvisare lo staff.", false);

        reply(e, eb.build());
    }
}
