package com.rankedbot2.core;

import com.rankedbot2.model.Player;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.Arrays;
import java.util.List;

public abstract class CommandBase implements SlashCommand {

    protected final BotContext ctx;
    protected final Embeds embeds;
    protected final PlayerService playerService;
    protected final GameService gameService;

    protected CommandBase(BotContext ctx, Embeds embeds, PlayerService playerService, GameService gameService) {
        this.ctx = ctx;
        this.embeds = embeds;
        this.playerService = playerService;
        this.gameService = gameService;
    }

    protected List<String> names(String... names) {
        return Arrays.asList(names);
    }

    /**
     * Esegue il lavoro fuori dal thread eventi di JDA. Generare un'immagine
     * significa scaricare le teste dei giocatori: sul thread del gateway
     * bloccherebbe ogni altro evento del bot finché non finisce.
     * Va usato solo dopo un deferReply().
     */
    protected void async(SlashCommandInteractionEvent e, Runnable work) {
        ctx.scheduler.execute(() -> {
            try {
                work.run();
            } catch (Exception ex) {
                ex.printStackTrace();
                fail(e, "Errore durante l'esecuzione del comando: " + ex.getMessage());
            }
        });
    }

    protected void reply(SlashCommandInteractionEvent e, MessageEmbed embed) {
        if (e.isAcknowledged()) e.getHook().sendMessageEmbeds(embed).queue();
        else e.replyEmbeds(embed).queue();
    }

    protected void ok(SlashCommandInteractionEvent e, String message) {
        reply(e, embeds.success(message));
    }

    protected void info(SlashCommandInteractionEvent e, String message) {
        reply(e, embeds.info(message));
    }

    protected void fail(SlashCommandInteractionEvent e, String message) {
        if (e.isAcknowledged()) e.getHook().sendMessageEmbeds(embeds.error(message)).queue();
        else e.replyEmbeds(embeds.error(message)).setEphemeral(true).queue();
    }

    /** Player del DB per un utente, null se non registrato. */
    protected Player player(User user) {
        return user == null ? null : ctx.players.get(user.getId());
    }

    protected Player player(String id) {
        return ctx.players.get(id);
    }

    /** Utente passato come opzione, altrimenti chi ha lanciato il comando. */
    protected User targetOrSelf(SlashCommandInteractionEvent e, String optionName) {
        var option = e.getOption(optionName);
        return option == null ? e.getUser() : option.getAsUser();
    }

    protected Guild guild(SlashCommandInteractionEvent e) {
        return e.getGuild();
    }

    protected Member memberOf(Guild guild, String userId) {
        return guild == null ? null : guild.getMemberById(userId);
    }

    protected String sub(SlashCommandInteractionEvent e) {
        return e.getSubcommandName() == null ? "" : e.getSubcommandName();
    }

    protected String eloPrefix(Player p) {
        return ctx.config.getRawString("elo-formatting", "[%elo%] ").replace("%elo%", String.valueOf(p.elo));
    }
}
