package com.rankedbot2.core;

import com.rankedbot2.model.Player;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import com.rankedbot2.service.GameService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CommandListener extends ListenerAdapter {

    private final BotContext ctx;
    private final Embeds embeds;
    private final GameService gameService;
    private final List<SlashCommand> commands;

    public CommandListener(BotContext ctx, Embeds embeds, GameService gameService, List<SlashCommand> commands) {
        this.ctx = ctx;
        this.embeds = embeds;
        this.gameService = gameService;
        this.commands = commands;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        List<SlashCommandData> all = new ArrayList<>();
        for (SlashCommand command : commands) all.addAll(command.data());

        event.getJDA().getGuilds().forEach(guild -> {
            guild.updateCommands().addCommands(all).queue(
                    ok -> System.out.println("Registrati " + all.size()
                            + " comandi slash su " + guild.getName()),
                    err -> System.err.println("Registrazione comandi fallita su "
                            + guild.getName() + ": " + err.getMessage()));
            ensureCommunityHangout(guild);
        });

        System.out.println("Bot pronto: " + event.getJDA().getSelfUser().getAsTag());
    }

    public static void ensureCommunityHangout(net.dv8tion.jda.api.entities.Guild guild) {
        net.dv8tion.jda.api.entities.channel.concrete.Category category = null;
        for (net.dv8tion.jda.api.entities.channel.concrete.Category c : guild.getCategories()) {
            if (c.getName().equalsIgnoreCase("RBW System") || c.getName().equalsIgnoreCase("rbw system")) {
                category = c;
                break;
            }
        }
        if (category == null) {
            try {
                category = guild.createCategory("RBW System").complete();
            } catch (Exception ignored) {}
        }
        if (category == null) return;

        boolean exists = false;
        for (net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel vc : category.getVoiceChannels()) {
            if (vc.getName().toLowerCase().contains("community hangout") || vc.getName().toLowerCase().contains("hangout")) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            category.createVoiceChannel("Community Hangout")
                    .addPermissionOverride(guild.getPublicRole(),
                            java.util.EnumSet.of(net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.VOICE_CONNECT, net.dv8tion.jda.api.Permission.VOICE_SPEAK),
                            null)
                    .queue(vc -> System.out.println("Canale Community Hangout creato in RBW System su " + guild.getName()),
                            err -> {});
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (event.getChannelJoined() == null) return;
        if (event.getMember().getUser().isBot()) return;
        gameService.onVoiceJoin(event.getGuild(), event.getChannelJoined());
    }

    @Override
    public void onMessageReceived(@NotNull net.dv8tion.jda.api.events.message.MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getGuild() == null) return;

        String raw = event.getMessage().getContentRaw();
        String lower = raw.toLowerCase();
        if (lower.contains("coralmc.it") && (lower.contains("/match/") || lower.contains("match="))) {
            com.rankedbot2.model.Game game = ctx.games.getByChannel(event.getChannel().getId());
            if (game != null && game.state != com.rankedbot2.model.Game.State.SCORED && game.state != com.rankedbot2.model.Game.State.VOIDED) {
                String matchId = gameService.getCoralMcService().extractMatchId(raw);
                if (matchId != null) {
                    ctx.scheduler.execute(() -> {
                        synchronized (game) {
                            if (game.state == com.rankedbot2.model.Game.State.SCORED || game.state == com.rankedbot2.model.Game.State.VOIDED) {
                                return;
                            }
                        }
                        String err = gameService.autoScoreFromMatchLink(event.getGuild(), game, event.getAuthor().getId(), matchId);
                        if (err != null) {
                            event.getChannel().sendMessageEmbeds(embeds.error(err)).queue();
                        }
                    });
                }
            }
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.replyEmbeds(embeds.error("Questo bot funziona solo in un server")).setEphemeral(true).queue();
            return;
        }

        SlashCommand handler = null;
        for (SlashCommand command : commands) {
            if (command.handles(event.getName())) {
                handler = command;
                break;
            }
        }

        if (handler == null) {
            event.replyEmbeds(embeds.error("Comando non riconosciuto")).setEphemeral(true).queue();
            return;
        }

        String permKey = handler.permissionKey(event);
        if (!ctx.perms.canUse(permKey, event.getMember())) {
            event.replyEmbeds(embeds.error(ctx.msg("no-perms"))).setEphemeral(true).queue();
            return;
        }

        if (handler.requiresRegistration(event) && !allowsUnregistered()) {
            Player p = ctx.players.get(event.getUser().getId());
            if (p == null) {
                event.replyEmbeds(embeds.error(ctx.msg("not-registered"))).setEphemeral(true).queue();
                return;
            }
        }

        if (ctx.config.getBoolean("log-commands", false)) {
            System.out.println("[cmd] " + event.getUser().getAsTag() + " -> " + event.getFullCommandName());
        }

        try {
            handler.execute(event);
        } catch (Exception e) {
            e.printStackTrace();
            String message = "Errore durante l'esecuzione del comando: " + e.getMessage();
            if (event.isAcknowledged()) {
                event.getHook().sendMessageEmbeds(embeds.error(message)).queue();
            } else {
                event.replyEmbeds(embeds.error(message)).setEphemeral(true).queue();
            }
        }
    }

    private boolean allowsUnregistered() {
        return ctx.config.getBoolean("unregistered-cmd-usage", false);
    }
}
