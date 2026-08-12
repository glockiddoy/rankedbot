package com.rankedbot2.commands;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandBase;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.db.PlayerRepo;
import com.rankedbot2.model.Clan;
import com.rankedbot2.model.Player;
import com.rankedbot2.service.CardImageService;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import com.rankedbot2.service.StatsImageService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;

import java.util.List;

public class PlayerCommands extends CommandBase {

    private final StatsImageService statsImages;
    private final CardImageService cards;

    public PlayerCommands(BotContext ctx, Embeds embeds, PlayerService playerService,
                          GameService gameService, StatsImageService statsImages, CardImageService cards) {
        super(ctx, embeds, playerService, gameService);
        this.statsImages = statsImages;
        this.cards = cards;
    }

    @Override
    public List<SlashCommandData> data() {
        OptionData statistic = new OptionData(OptionType.STRING, "statistica",
                "Statistica su cui ordinare", true);
        for (String col : PlayerRepo.ALLOWED_SORT_COLUMNS) {
            statistic.addChoice(col, col);
        }

        return List.of(
                Commands.slash("register", "Registrati sul server ranked")
                        .addOption(OptionType.STRING, "ign", "Il tuo nome in-game", true),

                Commands.slash("rename", "Cambia il tuo nome in-game")
                        .addOption(OptionType.STRING, "ign", "Il nuovo nome in-game", true),

                Commands.slash("fix", "Ricalcola nickname e ruoli di un giocatore")
                        .addOption(OptionType.USER, "giocatore", "Giocatore da sistemare", false),

                Commands.slash("stats", "Mostra le statistiche di un giocatore")
                        .addOption(OptionType.USER, "giocatore", "Giocatore da controllare", false)
                        .addOption(OptionType.BOOLEAN, "completo", "Mostra tutte le statistiche in testo", false),

                Commands.slash("leaderboard", "Classifica per una statistica")
                        .addOptions(statistic)
                        .addOption(OptionType.INTEGER, "quanti", "Quanti giocatori mostrare (default 10)", false),

                Commands.slash("transfergold", "Trasferisci gold a un altro giocatore")
                        .addOption(OptionType.USER, "giocatore", "Destinatario", true)
                        .addOption(OptionType.INTEGER, "quantita", "Quanto gold trasferire", true),

                Commands.slash("theme", "Scegli o elenca i tuoi temi per l'immagine stats")
                        .addOption(OptionType.STRING, "tema", "Nome del tema, oppure vuoto per la lista", false));
    }

    @Override
    public boolean handles(String name) {
        return names("register", "rename", "fix", "stats", "leaderboard", "transfergold", "theme").contains(name);
    }

    @Override
    public String permissionKey(SlashCommandInteractionEvent e) {
        return e.getName();
    }

    @Override
    public boolean requiresRegistration(SlashCommandInteractionEvent e) {
        return !e.getName().equals("register");
    }

    @Override
    public void execute(SlashCommandInteractionEvent e) {
        switch (e.getName()) {
            case "register" -> register(e);
            case "rename" -> rename(e);
            case "fix" -> fix(e);
            case "stats" -> stats(e);
            case "leaderboard" -> leaderboard(e);
            case "transfergold" -> transferGold(e);
            case "theme" -> theme(e);
            default -> fail(e, "Comando sconosciuto");
        }
    }

    private void register(SlashCommandInteractionEvent e) {
        String ign = e.getOption("ign").getAsString().trim();

        if (ctx.players.exists(e.getUser().getId())) {
            fail(e, ctx.msg("already-registered"));
            return;
        }
        if (ign.isEmpty() || ign.length() > 16 || !ign.matches("^[a-zA-Z0-9_]{1,16}$")) {
            fail(e, "IGN Minecraft non valido! Deve contenere da 1 a 16 caratteri (lettere, numeri e underscore).");
            return;
        }

        ctx.players.create(e.getUser().getId(), ign, playerService.startingElo());
        Player p = player(e.getUser());
        playerService.updateMember(guild(e), e.getMember(), p);
        ok(e, ctx.msg("successfully-registered"));
    }

    private void rename(SlashCommandInteractionEvent e) {
        String ign = e.getOption("ign").getAsString().trim();
        if (ign.isEmpty() || ign.length() > 16 || !ign.matches("^[a-zA-Z0-9_]{1,16}$")) {
            fail(e, "IGN Minecraft non valido! Deve contenere da 1 a 16 caratteri (lettere, numeri e underscore).");
            return;
        }

        Player p = player(e.getUser());
        p.ign = ign;
        ctx.players.save(p);
        playerService.updateMember(guild(e), e.getMember(), p);
        ok(e, "Nome in-game aggiornato a `" + ign + "`");
    }

    private void fix(SlashCommandInteractionEvent e) {
        User target = targetOrSelf(e, "giocatore");
        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("player-not-registered"));
            return;
        }
        Member member = memberOf(guild(e), target.getId());
        playerService.updateMember(guild(e), member, p);
        ok(e, "Nickname e ruoli di " + target.getAsMention() + " sistemati");
    }

    private void stats(SlashCommandInteractionEvent e) {
        User target = targetOrSelf(e, "giocatore");
        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }

        boolean full = e.getOption("completo") != null && e.getOption("completo").getAsBoolean();
        e.deferReply().queue();

        if (!full && ctx.config.getBoolean("s-enabled", false)) {
            try {
                // Con un tema personalizzato in themes/ vince il layout a coordinate
                // di config.yml, altrimenti si usa la card generata dal bot.
                byte[] image = statsImages.renderPlayer(guild(e), p);
                if (image == null) image = cards.renderStats(guild(e), p, target.getName());

                if (image != null) {
                    e.getHook().sendFiles(FileUpload.fromData(image, "stats.png")).queue();
                    return;
                }
            } catch (Exception ex) {
                System.err.println("Immagine stats non generata: " + ex.getMessage());
            }
        }

        e.getHook().sendMessageEmbeds(statsEmbed(e, target, p)).queue();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed statsEmbed(SlashCommandInteractionEvent e, User user, Player p) {
        String rank = playerService.rankNameOf(guild(e), p);
        EmbedBuilder eb = embeds.builder()
                .setTitle("Statistiche di " + p.ign)
                .setThumbnail(user.getEffectiveAvatarUrl())
                .addField("Elo", String.valueOf(p.elo), true)
                .addField("Elo massimo", String.valueOf(p.peakElo), true)
                .addField("Rank", rank.isEmpty() ? "—" : rank, true)
                .addField("Vittorie", String.valueOf(p.wins), true)
                .addField("Sconfitte", String.valueOf(p.losses), true)
                .addField("Partite", String.valueOf(p.games), true)
                .addField("W/L", String.format(java.util.Locale.ROOT, "%.2f", p.wlr()), true)
                .addField("Winstreak", p.winstreak + " (max " + p.highestWs + ")", true)
                .addField("Lossstreak", p.lossstreak + " (max " + p.highestLs + ")", true)
                .addField("MVP", String.valueOf(p.mvp), true)
                .addField("Kill / Morti", p.kills + " / " + p.deaths, true)
                .addField("K/D", String.format(java.util.Locale.ROOT, "%.2f", p.kdr()), true)
                .addField("Gold", String.valueOf(p.gold), true)
                .addField("Livello", p.level + " (" + p.xp + " XP)", true)
                .addField("Strike", String.valueOf(p.strikes), true)
                .addField("Partite scorate", String.valueOf(p.scored), true);

        if (p.clanId >= 0) {
            Clan clan = ctx.clans.get(p.clanId);
            if (clan != null) eb.addField("Clan", clan.name, true);
        }
        if (p.isBanned()) {
            eb.addField("Stato", "BANNATO — <t:" + (p.bannedUntil / 1000) + ":R>", false);
        }
        return eb.build();
    }

    private void leaderboard(SlashCommandInteractionEvent e) {
        String stat = e.getOption("statistica").getAsString();
        int limit = e.getOption("quanti") == null ? 10 : (int) e.getOption("quanti").getAsLong();
        limit = Math.max(1, Math.min(25, limit));

        List<Player> top;
        try {
            top = ctx.players.topBy(stat, limit);
        } catch (IllegalArgumentException ex) {
            fail(e, ex.getMessage());
            return;
        }

        if (top.isEmpty()) {
            info(e, "Nessun giocatore registrato");
            return;
        }

        if (ctx.config.getBoolean("s-enabled", false)) {
            e.deferReply().queue();
            try {
                byte[] image = cards.renderLeaderboard(top, stat);
                if (image != null) {
                    e.getHook().sendFiles(FileUpload.fromData(image, "leaderboard.png")).queue();
                    return;
                }
            } catch (Exception ex) {
                System.err.println("Immagine leaderboard non generata: " + ex.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            Player p = top.get(i);
            sb.append("**").append(i + 1).append(".** ")
                    .append(p.ign).append(" — `").append(statValue(p, stat)).append("`\n");
        }
        reply(e, embeds.builder().setTitle("Classifica — " + stat).setDescription(sb.toString()).build());
    }

    private String statValue(Player p, String stat) {
        return switch (stat) {
            case "elo" -> String.valueOf(p.elo);
            case "peak_elo" -> String.valueOf(p.peakElo);
            case "wins" -> String.valueOf(p.wins);
            case "losses" -> String.valueOf(p.losses);
            case "games" -> String.valueOf(p.games);
            case "winstreak" -> String.valueOf(p.winstreak);
            case "highest_ws" -> String.valueOf(p.highestWs);
            case "lossstreak" -> String.valueOf(p.lossstreak);
            case "highest_ls" -> String.valueOf(p.highestLs);
            case "mvp" -> String.valueOf(p.mvp);
            case "kills" -> String.valueOf(p.kills);
            case "deaths" -> String.valueOf(p.deaths);
            case "strikes" -> String.valueOf(p.strikes);
            case "scored" -> String.valueOf(p.scored);
            case "gold" -> String.valueOf(p.gold);
            case "xp" -> String.valueOf(p.xp);
            case "level" -> String.valueOf(p.level);
            default -> "?";
        };
    }

    private void transferGold(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        int amount = (int) e.getOption("quantita").getAsLong();

        if (amount <= 0) {
            fail(e, ctx.msg("too-little-gold"));
            return;
        }
        if (target.getId().equals(e.getUser().getId())) {
            fail(e, "Non puoi trasferire gold a te stesso");
            return;
        }

        Player from = player(e.getUser());
        Player to = player(target);
        if (to == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }
        if (from.gold < amount) {
            fail(e, ctx.msg("not-enough-gold"));
            return;
        }

        from.gold -= amount;
        to.gold += amount;
        ctx.players.save(from);
        ctx.players.save(to);
        ok(e, "Hai trasferito `" + amount + "` gold a " + target.getAsMention());
    }

    private void theme(SlashCommandInteractionEvent e) {
        Player p = player(e.getUser());
        var option = e.getOption("tema");

        if (option == null || option.getAsString().equalsIgnoreCase("list")) {
            List<String> owned = p.ownedThemeList();
            info(e, owned.isEmpty()
                    ? "Non possiedi nessun tema"
                    : "Temi che possiedi:\n" + String.join("\n", owned)
                      + "\n\nTema attivo: `" + (p.theme.isEmpty() ? "default" : p.theme) + "`");
            return;
        }

        String wanted = option.getAsString().trim();
        if (!p.ownedThemeList().contains(wanted)) {
            fail(e, ctx.msg("theme-access-denied"));
            return;
        }

        p.theme = wanted;
        ctx.players.save(p);
        ok(e, "Tema impostato su `" + wanted + "`");
    }
}
