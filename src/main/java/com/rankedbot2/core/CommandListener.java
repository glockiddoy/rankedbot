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
            autoSetupRanks(guild);
            autoSetupQueuesAndMaps(guild);
        });

        ctx.scheduler.scheduleAtFixedRate(() -> {
            try {
                event.getJDA().getGuilds().forEach(gameService::checkAllQueues);
            } catch (Exception ignored) {}
        }, 3, 3, java.util.concurrent.TimeUnit.SECONDS);

        System.out.println("Bot pronto: " + event.getJDA().getSelfUser().getAsTag());
    }

    private void autoSetupRanks(net.dv8tion.jda.api.entities.Guild guild) {
        if (!ctx.ranks.all().isEmpty()) return;

        record DefaultRank(String name, int start, int end, int win, int lose) {}
        List<DefaultRank> defaults = List.of(
                new DefaultRank("Coal", 0, 199, 35, 10),
                new DefaultRank("Bronze", 200, 399, 30, 10),
                new DefaultRank("Silver", 400, 599, 30, 10),
                new DefaultRank("Gold", 600, 799, 30, 15),
                new DefaultRank("Platinum", 800, 999, 25, 15),
                new DefaultRank("Emerald", 1000, 1199, 25, 15),
                new DefaultRank("Sapphire", 1200, 1399, 20, 20),
                new DefaultRank("Amethyst", 1400, 1599, 15, 25),
                new DefaultRank("Ruby", 1600, 1799, 10, 25),
                new DefaultRank("Pearl", 1800, 1999, 5, 35),
                new DefaultRank("Diamond", 2000, 2199, 5, 40)
        );

        for (DefaultRank def : defaults) {
            for (net.dv8tion.jda.api.entities.Role role : guild.getRoles()) {
                if (role.getName().equalsIgnoreCase(def.name)) {
                    ctx.ranks.add(new com.rankedbot2.model.Rank(role.getId(), def.start, def.end, def.win, def.lose));
                    System.out.println("Auto-mappato rank: " + def.name + " -> " + role.getId());
                    break;
                }
            }
        }
    }

    private void autoSetupQueuesAndMaps(net.dv8tion.jda.api.entities.Guild guild) {
        if (ctx.queues.all().isEmpty()) {
            for (net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel vc : guild.getVoiceChannels()) {
                String name = vc.getName().toLowerCase();
                if (name.contains("1v1")) {
                    ctx.queues.add(new com.rankedbot2.model.GameQueue(vc.getId(), 1, com.rankedbot2.model.GameQueue.PickingMode.AUTOMATIC, false));
                    System.out.println("Auto-configurata coda 1v1 sul vocale: " + vc.getName());
                } else if (name.contains("2v2")) {
                    ctx.queues.add(new com.rankedbot2.model.GameQueue(vc.getId(), 2, com.rankedbot2.model.GameQueue.PickingMode.AUTOMATIC, false));
                    System.out.println("Auto-configurata coda 2v2 sul vocale: " + vc.getName());
                } else if (name.contains("3v3")) {
                    ctx.queues.add(new com.rankedbot2.model.GameQueue(vc.getId(), 3, com.rankedbot2.model.GameQueue.PickingMode.AUTOMATIC, false));
                    System.out.println("Auto-configurata coda 3v3 sul vocale: " + vc.getName());
                } else if (name.contains("4v4")) {
                    ctx.queues.add(new com.rankedbot2.model.GameQueue(vc.getId(), 4, com.rankedbot2.model.GameQueue.PickingMode.CAPTAINS, false));
                    System.out.println("Auto-configurata coda 4v4 sul vocale: " + vc.getName());
                }
            }
        }

        if (ctx.maps.all().isEmpty()) {
            List<com.rankedbot2.model.GameMap> defaultMaps = List.of(
                    new com.rankedbot2.model.GameMap("antenna", 112, "Yellow", "Green", 4),
                    new com.rankedbot2.model.GameMap("archway", 87, "Red", "Green", 4),
                    new com.rankedbot2.model.GameMap("boletum", 105, "Red", "Green", 4),
                    new com.rankedbot2.model.GameMap("katsu", 96, "Red", "Green", 4),
                    new com.rankedbot2.model.GameMap("swashbuckle", 85, "Red", "Green", 4),
                    new com.rankedbot2.model.GameMap("nebuc", 106, "Gray", "Pink", 2),
                    new com.rankedbot2.model.GameMap("apollo", 89, "Gray", "Pink", 2),
                    new com.rankedbot2.model.GameMap("speedway", 82, "Gray", "Pink", 2)
            );
            for (var map : defaultMaps) {
                ctx.maps.add(map);
            }
            System.out.println("Auto-popolate " + defaultMaps.size() + " mappe Bedwars di default nel database.");
        }
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
        if (event.getMember().getUser().isBot()) return;
        gameService.checkAllQueues(event.getGuild());
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
