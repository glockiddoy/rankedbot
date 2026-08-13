package com.peppone;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PepponeBot {

    public static void main(String[] args) {
        start(new File("Peppone"));
    }

    public static void start(File pepponeFolder) {
        if (!pepponeFolder.isDirectory()) {
            pepponeFolder.mkdirs();
        }

        File configFile = new File(pepponeFolder, "config.yml");
        if (!configFile.isFile()) {
            System.err.println("[Peppone] Manca Peppone/config.yml");
            return;
        }

        Config config = new Config(configFile);
        String token = config.getString("token", "");
        if (token.isEmpty()) {
            System.err.println("[Peppone] Token mancante: scrivilo in Peppone/config.yml alla voce 'token:'");
            return;
        }

        OnlineStatus status;
        try {
            status = OnlineStatus.valueOf(config.getString("status", "ONLINE").toUpperCase());
        } catch (IllegalArgumentException e) {
            status = OnlineStatus.ONLINE;
        }

        ExecutorService worker = Executors.newFixedThreadPool(2);
        TicketService ticketService = new TicketService(config, pepponeFolder);

        try {
            JDABuilder.createLight(token)
                    .setAutoReconnect(true)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .setStatus(status)
                    .setActivity(Activity.watching(config.getString("activity", "i vostri ticket")))
                    .addEventListeners(new TicketListener(config, ticketService, worker))
                    .build();

            Runtime.getRuntime().addShutdownHook(new Thread(worker::shutdownNow));
            System.out.println("[Peppone] Ticket Bot avviato con successo sul suo token dedicato!");
        } catch (Exception e) {
            System.err.println("[Peppone] Avvio fallito: " + e.getMessage());
            System.err.println("[Peppone] Se l'errore parla di 'disallowed intents', attiva SERVER MEMBERS INTENT nel Discord Developer Portal.");
            worker.shutdownNow();
        }
    }
}
