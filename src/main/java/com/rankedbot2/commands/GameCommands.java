package com.rankedbot2.commands;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandBase;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.model.Game;
import com.rankedbot2.model.GameQueue;
import com.rankedbot2.model.Player;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class GameCommands extends CommandBase {

    public GameCommands(BotContext ctx, Embeds embeds, PlayerService playerService, GameService gameService) {
        super(ctx, embeds, playerService, gameService);
    }

    @Override
    public List<SlashCommandData> data() {
        return List.of(
                Commands.slash("pick", "Scegli un giocatore per il tuo team (solo capitani)")
                        .addOption(OptionType.USER, "giocatore", "Giocatore da scegliere", true),

                Commands.slash("submit", "Invia le prove della vittoria per la tua partita o il link CoralMC")
                        .addOption(OptionType.ATTACHMENT, "prova1", "Screenshot della vittoria", false)
                        .addOption(OptionType.ATTACHMENT, "prova2", "Screenshot aggiuntivo", false)
                        .addOption(OptionType.ATTACHMENT, "prova3", "Screenshot aggiuntivo", false)
                        .addOption(OptionType.STRING, "link", "Link CoralMC per autoscore", false),

                Commands.slash("autoscore", "Scora automaticamente una partita tramite link CoralMC")
                        .addOption(OptionType.STRING, "link", "Link o ID della partita su CoralMC", true)
                        .addOption(OptionType.INTEGER, "numero", "Numero della partita (opzionale nel canale partita)", false),

                Commands.slash("score", "Assegna la vittoria a un team")
                        .addOption(OptionType.INTEGER, "numero", "Numero della partita", true)
                        .addOption(OptionType.INTEGER, "team", "Team vincitore (1 o 2)", true)
                        .addOption(OptionType.USER, "mvp", "Giocatore MVP", false),

                Commands.slash("void", "Annulla la partita del canale corrente"),

                Commands.slash("forcevoid", "Annulla forzatamente una partita")
                        .addOption(OptionType.INTEGER, "numero", "Numero della partita", true),

                Commands.slash("undogame", "Annulla lo scoring di una partita e ripristina l'elo")
                        .addOption(OptionType.INTEGER, "numero", "Numero della partita", true),

                Commands.slash("win", "Segna manualmente una vittoria a un giocatore")
                        .addOption(OptionType.USER, "giocatore", "Giocatore", true),

                Commands.slash("lose", "Segna manualmente una sconfitta a un giocatore")
                        .addOption(OptionType.USER, "giocatore", "Giocatore", true),

                Commands.slash("call", "Dai a un giocatore l'accesso al tuo vocale di partita")
                        .addOption(OptionType.USER, "giocatore", "Giocatore da chiamare", true),

                Commands.slash("gameinfo", "Informazioni su una partita")
                        .addOption(OptionType.INTEGER, "numero", "Numero della partita", false),

                Commands.slash("recap", "Genera e mostra il banner recap grafico di una partita")
                        .addOption(OptionType.INTEGER, "numero", "Numero della partita", false),

                Commands.slash("queuestats", "Mostra le code attive e chi è in coda"));
    }

    @Override
    public boolean handles(String name) {
        return names("pick", "submit", "autoscore", "score", "void", "forcevoid", "undogame",
                "win", "lose", "call", "gameinfo", "recap", "queuestats").contains(name);
    }

    @Override
    public String permissionKey(SlashCommandInteractionEvent e) {
        return e.getName();
    }

    @Override
    public void execute(SlashCommandInteractionEvent e) {
        switch (e.getName()) {
            case "pick" -> pick(e);
            case "submit" -> submit(e);
            case "autoscore" -> autoscore(e);
            case "score" -> score(e);
            case "void" -> voidCurrent(e);
            case "forcevoid" -> forceVoid(e);
            case "undogame" -> undo(e);
            case "win" -> manualResult(e, true);
            case "lose" -> manualResult(e, false);
            case "call" -> call(e);
            case "gameinfo" -> gameInfo(e);
            case "recap" -> recap(e);
            case "queuestats" -> queueStats(e);
            default -> fail(e, "Comando sconosciuto");
        }
    }

    private Game gameOfChannel(SlashCommandInteractionEvent e) {
        return ctx.games.getByChannel(e.getChannel().getId());
    }

    private void pick(SlashCommandInteractionEvent e) {
        Game game = gameOfChannel(e);
        if (game == null) {
            fail(e, ctx.msg("not-game-channel"));
            return;
        }

        User target = e.getOption("giocatore").getAsUser();
        String error = gameService.pick(guild(e), game, e.getUser().getId(), target.getId());
        if (error != null) {
            fail(e, error);
            return;
        }

        if (game.state == Game.State.PICKING) {
            reply(e, gameService.pickingEmbed(guild(e), game));
        } else {
            ok(e, "Pick completate, la partita è iniziata.");
        }
    }

    private void submit(SlashCommandInteractionEvent e) {
        Game game = gameOfChannel(e);
        if (game == null) {
            fail(e, ctx.msg("not-game-channel"));
            return;
        }
        if (game.casual) {
            fail(e, ctx.msg("casual-game"));
            return;
        }
        if (game.state == Game.State.SCORED || game.state == Game.State.VOIDED) {
            fail(e, "Questa partita è già conclusa");
            return;
        }

        OptionMapping linkOption = e.getOption("link");
        if (linkOption != null && !linkOption.getAsString().isBlank()) {
            e.deferReply().queue();
            String err = gameService.autoScoreFromMatchLink(guild(e), game, e.getUser().getId(), linkOption.getAsString());
            if (err != null) {
                fail(e, err);
            } else {
                ok(e, "Autoscore della partita #" + game.number + " eseguito con successo!");
            }
            return;
        }

        List<String> urls = new ArrayList<>();
        for (String option : new String[]{"prova1", "prova2", "prova3"}) {
            OptionMapping mapping = e.getOption(option);
            if (mapping != null) urls.add(mapping.getAsAttachment().getUrl());
        }

        int required = ctx.config.getInt("submitting-attachments", 1);
        if (urls.size() < required) {
            fail(e, "Devi allegare almeno " + required + " screenshot come prova oppure inserire il link CoralMC");
            return;
        }

        gameService.submit(guild(e), game, e.getUser().getId(), urls);
        ok(e, "Partita inviata, in attesa di uno scorer.");
    }

    private void autoscore(SlashCommandInteractionEvent e) {
        Game game;
        if (e.getOption("numero") != null) {
            game = ctx.games.get((int) e.getOption("numero").getAsLong());
        } else {
            game = gameOfChannel(e);
        }

        if (game == null) {
            fail(e, ctx.msg("invalid-game"));
            return;
        }

        OptionMapping linkOpt = e.getOption("link");
        if (linkOpt == null || linkOpt.getAsString().isBlank()) {
            fail(e, "Inserisci un link o ID di partita CoralMC valido.");
            return;
        }

        e.deferReply().queue();
        String err = gameService.autoScoreFromMatchLink(guild(e), game, e.getUser().getId(), linkOpt.getAsString());
        if (err != null) {
            fail(e, err);
        } else {
            ok(e, "Autoscore della partita #" + game.number + " eseguito con successo!");
        }
    }

    private void score(SlashCommandInteractionEvent e) {
        int number = (int) e.getOption("numero").getAsLong();
        int team = (int) e.getOption("team").getAsLong();
        User mvp = e.getOption("mvp") == null ? null : e.getOption("mvp").getAsUser();

        Game game = ctx.games.get(number);
        if (game == null) {
            fail(e, ctx.msg("invalid-game"));
            return;
        }
        if (game.state == Game.State.PICKING || game.state == Game.State.STARTED) {
            fail(e, ctx.msg("not-submitted"));
            return;
        }

        e.deferReply().queue();
        String error = gameService.score(guild(e), game, e.getUser().getId(), team, mvp == null ? null : mvp.getId());
        if (error != null) {
            fail(e, error);
            return;
        }
        ok(e, "Partita #" + number + " scorata: vince il Team " + team);
    }

    private void voidCurrent(SlashCommandInteractionEvent e) {
        Game game = gameOfChannel(e);
        if (game == null) {
            fail(e, ctx.msg("not-game-channel"));
            return;
        }
        if (game.state == Game.State.SCORED) {
            fail(e, "Questa partita è già stata scorata, usa `/undogame`");
            return;
        }
        gameService.voidGame(guild(e), game);
        ok(e, "Partita #" + game.number + " annullata.");
    }

    private void forceVoid(SlashCommandInteractionEvent e) {
        int number = (int) e.getOption("numero").getAsLong();
        Game game = ctx.games.get(number);
        if (game == null) {
            fail(e, ctx.msg("invalid-game"));
            return;
        }
        if (game.state == Game.State.SCORED) {
            String error = gameService.undo(guild(e), game);
            if (error != null) {
                fail(e, error);
                return;
            }
        } else {
            gameService.voidGame(guild(e), game);
        }
        ok(e, "Partita #" + number + " annullata forzatamente.");
    }

    private void undo(SlashCommandInteractionEvent e) {
        int number = (int) e.getOption("numero").getAsLong();
        Game game = ctx.games.get(number);
        if (game == null) {
            fail(e, ctx.msg("invalid-game"));
            return;
        }

        e.deferReply().queue();
        String error = gameService.undo(guild(e), game);
        if (error != null) {
            fail(e, error);
            return;
        }
        ok(e, "Scoring della partita #" + number + " annullato, elo ripristinato.");
    }

    /** /win e /lose: correzione manuale delle statistiche di un singolo giocatore. */
    private void manualResult(SlashCommandInteractionEvent e, boolean won) {
        User target = e.getOption("giocatore").getAsUser();
        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }

        int delta;
        if (won) {
            delta = playerService.addElo(p, playerService.winEloFor(p));
            p.wins++;
            p.winstreak++;
            p.lossstreak = 0;
            if (p.winstreak > p.highestWs) p.highestWs = p.winstreak;
        } else {
            delta = playerService.addElo(p, -Math.abs(playerService.loseEloFor(p)));
            p.losses++;
            p.lossstreak++;
            p.winstreak = 0;
            if (p.lossstreak > p.highestLs) p.highestLs = p.lossstreak;
        }
        p.games++;
        ctx.players.save(p);

        Member member = memberOf(guild(e), target.getId());
        playerService.updateMember(guild(e), member, p);

        ok(e, target.getAsMention() + (won ? " vittoria registrata" : " sconfitta registrata")
                + " (`" + (delta >= 0 ? "+" : "") + delta + "` elo)");
    }

    private void call(SlashCommandInteractionEvent e) {
        Game game = gameOfChannel(e);
        if (game == null) {
            fail(e, ctx.msg("not-game-channel"));
            return;
        }

        User target = e.getOption("giocatore").getAsUser();
        int team = game.teamOf(e.getUser().getId());
        if (team == 0) {
            fail(e, "Non fai parte di questa partita");
            return;
        }

        Guild guild = guild(e);
        VoiceChannel vc = guild.getVoiceChannelById(team == 1 ? game.vc1 : game.vc2);
        if (vc == null) {
            fail(e, ctx.msg("invalid-vc"));
            return;
        }

        vc.upsertPermissionOverride(guild.getMemberById(target.getId()) == null
                        ? guild.getPublicRole()
                        : guild.getMemberById(target.getId()))
                .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                .queue(null, err -> {
                });

        ok(e, target.getAsMention() + " può ora entrare in " + vc.getAsMention());
    }

    private void gameInfo(SlashCommandInteractionEvent e) {
        Game game;
        if (e.getOption("numero") != null) {
            game = ctx.games.get((int) e.getOption("numero").getAsLong());
        } else {
            game = gameOfChannel(e);
        }

        if (game == null) {
            fail(e, ctx.msg("invalid-game"));
            return;
        }

        EmbedBuilder eb = embeds.builder()
                .setTitle("Partita #" + game.number + " — " + game.modeName())
                .addField("Stato", stateName(game.state), true)
                .addField("Tipo", game.casual ? "Casual" : "Ranked", true)
                .addField("Mappa", game.map.isEmpty() ? "—" : game.map, true)
                .addField("Team 1", gameService.listPlayers(game.team1), true)
                .addField("Team 2", gameService.listPlayers(game.team2), true);

        if (!game.remaining.isEmpty()) {
            eb.addField("Da scegliere", gameService.listPlayers(game.remaining), false);
        }
        if (game.winner != 0) {
            eb.addField("Vincitore", "Team " + game.winner, true);
        }
        if (!game.mvp.isEmpty()) {
            eb.addField("MVP", GameService.mention(game.mvp), true);
        }
        if (!game.scoredBy.isEmpty()) {
            eb.addField("Scorata da", GameService.mention(game.scoredBy), true);
        }
        reply(e, eb.build());
    }

    private String stateName(Game.State state) {
        return switch (state) {
            case PICKING -> "In fase di pick";
            case STARTED -> "In corso";
            case SUBMITTED -> "Inviata, in attesa di scoring";
            case SCORED -> "Scorata";
            case VOIDED -> "Annullata";
        };
    }

    private void queueStats(SlashCommandInteractionEvent e) {
        List<GameQueue> queues = ctx.queues.all();
        if (queues.isEmpty()) {
            info(e, "Non ci sono code configurate");
            return;
        }

        EmbedBuilder eb = embeds.builder().setTitle("Code");
        for (GameQueue q : queues) {
            VoiceChannel vc = guild(e).getVoiceChannelById(q.vcId);
            String name = vc == null ? "(canale mancante: " + q.vcId + ")" : vc.getName();

            int inQueue = 0;
            if (vc != null) {
                for (Member m : vc.getMembers()) {
                    if (!m.getUser().isBot()) inQueue++;
                }
            }

            eb.addField(name,
                    q.modeName() + " · " + q.pickingMode.name().toLowerCase()
                            + (q.casual ? " · casual" : " · ranked")
                            + "\n" + inQueue + "/" + q.totalPlayers() + " in coda",
                    true);
        }
        reply(e, eb.build());
    }

    private void recap(SlashCommandInteractionEvent e) {
        Game game;
        if (e.getOption("numero") != null) {
            game = ctx.games.get((int) e.getOption("numero").getAsLong());
        } else {
            game = gameOfChannel(e);
        }
        if (game == null) {
            fail(e, "Specifica un numero di partita valido o usa il comando nel canale di una partita.");
            return;
        }
        if (game.state != Game.State.SCORED) {
            fail(e, "La partita #" + game.number + " non è ancora stata scorata.");
            return;
        }

        e.deferReply().queue();
        byte[] banner = gameService.renderScoreBanner(game);
        if (banner == null) {
            fail(e, "Impossibile generare il banner per la partita #" + game.number);
            return;
        }

        EmbedBuilder eb = embeds.base(embeds.successColor())
                .setTitle("🏆 Match Recap Partita #" + game.number + " (" + game.modeName() + ")")
                .addField("🔴 Vincitori", gameService.listPlayers(game.winner == 1 ? game.team1 : game.team2), true)
                .addField("🔵 Sconfitti", gameService.listPlayers(game.winner == 1 ? game.team2 : game.team1), true);

        if (!game.mvp.isEmpty()) eb.addField("⭐ MVP", GameService.mention(game.mvp), false);
        if (game.map != null && !game.map.isBlank()) eb.addField("🗺️ Mappa", "`" + game.map + "`", true);

        eb.setImage("attachment://recap.png");
        e.getHook().sendMessageEmbeds(eb.build())
                .addFiles(FileUpload.fromData(banner, "recap.png"))
                .queue();
    }
}
