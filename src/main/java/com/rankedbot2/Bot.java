package com.rankedbot2;

import com.rankedbot2.commands.ClanCommands;
import com.rankedbot2.commands.ClanWarCommands;
import com.rankedbot2.commands.ConfigCommands;
import com.rankedbot2.commands.GameCommands;
import com.rankedbot2.commands.ModCommands;
import com.rankedbot2.commands.PartyCommands;
import com.rankedbot2.commands.PlayerCommands;
import com.rankedbot2.commands.ServerCommands;
import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandListener;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.core.SlashCommand;
import com.rankedbot2.service.CardImageService;
import com.rankedbot2.service.ClanWarService;
import com.rankedbot2.service.CoralMcService;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import com.rankedbot2.service.StatsImageService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.io.File;
import java.util.List;

public class Bot {

    public static void main(String[] args) {
        File dataFolder = new File("RankedBot");
        if (!dataFolder.isDirectory()) {
            System.err.println("Cartella RankedBot/ non trovata accanto al jar. "
                    + "Copia config.yml, messages.yml, permissions.yml, levels.yml e clanlevels.yml lì dentro.");
            return;
        }

        BotContext ctx = new BotContext(dataFolder);

        String token = ctx.config.getString("token");
        if (token.isEmpty()) {
            System.err.println("Token mancante: scrivilo in RankedBot/config.yml alla voce 'token:'");
            return;
        }

        Embeds embeds = new Embeds(ctx);
        PlayerService playerService = new PlayerService(ctx);
        CoralMcService coralMcService = new CoralMcService();
        GameService gameService = new GameService(ctx, playerService, embeds, coralMcService);
        StatsImageService statsImages = new StatsImageService(ctx);
        CardImageService cards = new CardImageService(ctx);
        ClanWarService clanWarService = new ClanWarService(ctx, gameService, embeds);
        com.rankedbot2.service.AntiNukeService antiNukeService = new com.rankedbot2.service.AntiNukeService(ctx, embeds);

        List<SlashCommand> commands = List.of(
                new PlayerCommands(ctx, embeds, playerService, gameService, statsImages, cards),
                new ServerCommands(ctx, embeds, playerService, gameService),
                new GameCommands(ctx, embeds, playerService, gameService),
                new PartyCommands(ctx, embeds, playerService, gameService),
                new ModCommands(ctx, embeds, playerService, gameService),
                new ClanCommands(ctx, embeds, playerService, gameService, statsImages),
                new ClanWarCommands(ctx, embeds, playerService, gameService, clanWarService),
                new ConfigCommands(ctx, embeds, playerService, gameService));

        OnlineStatus status;
        try {
            status = OnlineStatus.valueOf(ctx.config.getString("status", "ONLINE").toUpperCase());
        } catch (IllegalArgumentException e) {
            status = OnlineStatus.ONLINE;
        }

        try {
            JDA jda = JDABuilder.createDefault(token)
                    .enableIntents(
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.GUILD_MODERATION,
                            GatewayIntent.GUILD_VOICE_STATES,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT)
                    .enableCache(CacheFlag.VOICE_STATE)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .setStatus(status)
                    .setActivity(net.dv8tion.jda.api.entities.Activity.competing("Bedwars Ranked ⚔️"))
                    .addEventListeners(new CommandListener(ctx, embeds, gameService, commands), antiNukeService)
                    .build();

            ctx.setJda(jda);
            Runtime.getRuntime().addShutdownHook(new Thread(ctx::shutdown));
        } catch (Exception e) {
            System.err.println("Avvio fallito: " + e.getMessage());
            System.err.println("Se l'errore parla di 'disallowed intents', attiva SERVER MEMBERS INTENT "
                    + "e MESSAGE CONTENT INTENT nel Discord Developer Portal, sezione Bot.");
            ctx.shutdown();
        }
    }
}
