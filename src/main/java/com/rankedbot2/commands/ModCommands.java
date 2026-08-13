package com.rankedbot2.commands;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandBase;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.model.Player;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.TimeFormat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModCommands extends CommandBase {

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)([mhd])", Pattern.CASE_INSENSITIVE);

    public ModCommands(BotContext ctx, Embeds embeds, PlayerService playerService, GameService gameService) {
        super(ctx, embeds, playerService, gameService);
    }

    @Override
    public List<SlashCommandData> data() {
        return List.of(
                Commands.slash("mod", "Comandi di moderazione")
                        .addSubcommands(
                                new SubcommandData("ban", "Banna un giocatore dalle code")
                                        .addOption(OptionType.USER, "giocatore", "Giocatore da bannare", true)
                                        .addOption(OptionType.STRING, "tempo", "Durata: 30m, 2h, 1d", true)
                                        .addOption(OptionType.STRING, "motivo", "Motivo del ban", true),
                                new SubcommandData("unban", "Sbanna un giocatore")
                                        .addOption(OptionType.USER, "giocatore", "Giocatore da sbannare", true),
                                new SubcommandData("baninfo", "Informazioni sul ban di un giocatore")
                                        .addOption(OptionType.USER, "giocatore", "Giocatore", true),
                                new SubcommandData("strike", "Assegna uno strike (elo + ban progressivo)")
                                        .addOption(OptionType.USER, "giocatore", "Giocatore", true)
                                        .addOption(OptionType.STRING, "motivo", "Motivo dello strike", true),
                                new SubcommandData("wipe", "Azzera le statistiche (mantiene registrazione, IGN, clan e temi)")
                                        .addOption(OptionType.USER, "giocatore", "Giocatore da azzerare", false)
                                        .addOption(OptionType.BOOLEAN, "tutti", "Azzera TUTTI i giocatori", false),
                                new SubcommandData("modify", "Modifica una statistica di un giocatore")
                                        .addOption(OptionType.USER, "giocatore", "Giocatore", true)
                                        .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "statistica", "Statistica da modificare", true)
                                                .addChoice("elo", "elo")
                                                .addChoice("wins", "wins")
                                                .addChoice("losses", "losses")
                                                .addChoice("games", "games")
                                                .addChoice("mvp", "mvp")
                                                .addChoice("kills", "kills")
                                                .addChoice("deaths", "deaths")
                                                .addChoice("gold", "gold")
                                                .addChoice("xp", "xp")
                                                .addChoice("strikes", "strikes")
                                                .addChoice("scored", "scored"))
                                        .addOption(OptionType.INTEGER, "valore", "Nuovo valore", true),
                                new SubcommandData("forceregister", "Registra forzatamente un giocatore")
                                        .addOption(OptionType.USER, "giocatore", "Giocatore", true)
                                        .addOption(OptionType.STRING, "ign", "Nome in-game", true),
                                new SubcommandData("forcerename", "Rinomina forzatamente un giocatore")
                                        .addOption(OptionType.USER, "giocatore", "Giocatore", true)
                                        .addOption(OptionType.STRING, "ign", "Nuovo nome in-game", true)),

                Commands.slash("screenshare", "Richiedi lo screenshare di un giocatore")
                        .addOption(OptionType.USER, "giocatore", "Giocatore da screensharare", true)
                        .addOption(OptionType.STRING, "motivo", "Motivo del sospetto", true)
                        .addOption(OptionType.ATTACHMENT, "prova1", "Screenshot come prova", true)
                        .addOption(OptionType.ATTACHMENT, "prova2", "Screenshot aggiuntivo", false),

                Commands.slash("mecca", "Segnala una mecca / infrazione di un giocatore allo staff")
                        .addOption(OptionType.USER, "giocatore", "Giocatore da meccare", true)
                        .addOption(OptionType.STRING, "motivo", "Motivo (es. Dodge, No VC, Macro, Tossicità)", true)
                        .addOption(OptionType.ATTACHMENT, "prova", "Screenshot o prova dell'infrazione", true));
    }

    @Override
    public boolean handles(String name) {
        return name.equals("mod") || name.equals("screenshare") || name.equals("mecca");
    }

    @Override
    public String permissionKey(SlashCommandInteractionEvent e) {
        if (e.getName().equals("mecca")) return "mecca";
        return e.getName().equals("screenshare") ? "screenshare" : sub(e);
    }

    @Override
    public void execute(SlashCommandInteractionEvent e) {
        if (e.getName().equals("screenshare")) {
            screenshare(e);
            return;
        }
        if (e.getName().equals("mecca")) {
            mecca(e);
            return;
        }

        switch (sub(e)) {
            case "ban" -> ban(e);
            case "unban" -> unban(e);
            case "baninfo" -> banInfo(e);
            case "strike" -> strike(e);
            case "wipe" -> wipe(e);
            case "modify" -> modify(e);
            case "forceregister" -> forceRegister(e);
            case "forcerename" -> forceRename(e);
            default -> fail(e, "Sottocomando sconosciuto");
        }
    }

    /** Converte "30m", "2h", "1d" in millisecondi. Ritorna -1 se il formato non è valido. */
    private long parseTime(String raw) {
        Matcher matcher = TIME_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) return -1;

        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase()) {
            case "m" -> TimeUnit.MINUTES.toMillis(amount);
            case "h" -> TimeUnit.HOURS.toMillis(amount);
            case "d" -> TimeUnit.DAYS.toMillis(amount);
            default -> -1;
        };
    }

    private void ban(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String timeRaw = e.getOption("tempo").getAsString();
        String reason = e.getOption("motivo").getAsString();

        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }

        long duration = parseTime(timeRaw);
        if (duration < 0) {
            fail(e, ctx.msg("incorrect-time-format"));
            return;
        }

        applyBan(guild(e), target, p, duration, reason);
        ok(e, target.getAsMention() + " bannato per `" + timeRaw + "` — " + reason);
    }

    private void applyBan(Guild guild, User target, Player p, long durationMillis, String reason) {
        p.bannedUntil = System.currentTimeMillis() + durationMillis;
        p.banReason = reason;
        ctx.players.save(p);

        long bannedRoleId = ctx.config.getId("banned-role");
        Member member = memberOf(guild, target.getId());
        if (bannedRoleId != 0 && member != null) {
            Role role = guild.getRoleById(bannedRoleId);
            if (role != null && guild.getSelfMember().canInteract(role)) {
                guild.addRoleToMember(member, role).queue(null, err -> {
                });
            }
        }

        scheduleUnban(guild, target.getId(), durationMillis);
        announceBan(guild, target, p, reason);
    }

    private void scheduleUnban(Guild guild, String userId, long delayMillis) {
        ctx.scheduler.schedule(() -> {
            Player p = ctx.players.get(userId);
            if (p == null || p.isBanned()) return;
            removeBannedRole(guild, userId);
        }, Math.max(1, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void removeBannedRole(Guild guild, String userId) {
        long bannedRoleId = ctx.config.getId("banned-role");
        if (bannedRoleId == 0) return;
        Role role = guild.getRoleById(bannedRoleId);
        if (role == null) return;

        guild.retrieveMemberById(userId).queue(member -> {
            if (member.getRoles().contains(role) && guild.getSelfMember().canInteract(role)) {
                guild.removeRoleFromMember(member, role).queue(null, err -> {
                });
            }
        }, err -> {
        });
    }

    private void announceBan(Guild guild, User target, Player p, String reason) {
        long channelId = ctx.config.getId("ban-channel");
        if (channelId == 0) return;
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) return;

        channel.sendMessageEmbeds(embeds.base(embeds.errorColor())
                .setTitle("Giocatore bannato")
                .addField("Giocatore", target.getAsMention(), true)
                .addField("Scade", TimeFormat.RELATIVE.format(p.bannedUntil), true)
                .addField("Motivo", reason, false)
                .build()).queue();
    }

    private void unban(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }
        if (!p.isBanned()) {
            fail(e, ctx.msg("player-not-banned"));
            return;
        }

        p.bannedUntil = 0;
        p.banReason = "";
        ctx.players.save(p);
        removeBannedRole(guild(e), target.getId());
        ok(e, ctx.msg("player-unbanned"));
    }

    private void banInfo(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }
        if (!p.isBanned()) {
            info(e, ctx.msg("player-not-banned"));
            return;
        }

        reply(e, embeds.builder()
                .setTitle("Ban di " + p.ign)
                .addField("Scade", TimeFormat.RELATIVE.format(p.bannedUntil), true)
                .addField("Strike totali", String.valueOf(p.strikes), true)
                .addField("Motivo", p.banReason.isEmpty() ? "—" : p.banReason, false)
                .build());
    }

    private void strike(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String reason = e.getOption("motivo").getAsString();

        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }

        p.strikes++;
        int hours = strikeHours(p.strikes);
        int eloLoss = Math.abs(playerService.loseEloFor(p));
        playerService.addElo(p, -eloLoss);
        ctx.players.save(p);

        if (hours > 0) {
            applyBan(guild(e), target, p, TimeUnit.HOURS.toMillis(hours), "Strike #" + p.strikes + ": " + reason);
        }

        Member member = memberOf(guild(e), target.getId());
        playerService.updateMember(guild(e), member, p);

        ok(e, target.getAsMention() + " ha ricevuto lo strike #" + p.strikes
                + " — `-" + eloLoss + "` elo"
                + (hours > 0 ? ", ban di `" + hours + "h`" : ", nessun ban")
                + "\nMotivo: " + reason);
    }

    private int strikeHours(int strikeCount) {
        String key = strikeCount >= 5 ? "strike-5" : "strike-" + strikeCount;
        return ctx.config.getInt(key, 0);
    }

    private void wipe(SlashCommandInteractionEvent e) {
        boolean all = e.getOption("tutti") != null && e.getOption("tutti").getAsBoolean();

        int startingElo = playerService.startingElo();

        if (all) {
            int count = ctx.players.resetAllStats(startingElo);
            ok(e, "Statistiche azzerate per **" + count + "** giocatori.\n"
                    + "Registrazione, IGN, clan, temi e ban sono stati mantenuti.");
            refreshAll(guild(e));
            return;
        }

        if (e.getOption("giocatore") == null) {
            fail(e, "Specifica un giocatore, oppure metti `tutti: true`");
            return;
        }

        User target = e.getOption("giocatore").getAsUser();
        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }

        ctx.players.resetStats(target.getId(), startingElo);

        Player updated = player(target);
        Member member = memberOf(guild(e), target.getId());
        if (updated != null && member != null) {
            playerService.updateMember(guild(e), member, updated);
        }
        ok(e, ctx.msg("successfully-wiped"));
    }

    /**
     * Riallinea nickname e ruoli rank dopo un wipe totale. Fuori dal thread
     * eventi: sono due chiamate REST per giocatore.
     */
    private void refreshAll(Guild guild) {
        if (guild == null) return;

        ctx.scheduler.execute(() -> {
            for (Player p : ctx.players.all()) {
                Member member = guild.getMemberById(p.id);
                if (member != null) playerService.updateMember(guild, member, p);
            }
        });
    }

    private void modify(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String stat = e.getOption("statistica").getAsString();
        int value = (int) e.getOption("valore").getAsLong();

        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }

        switch (stat) {
            case "elo" -> {
                p.elo = Math.max(0, value);
                if (p.elo > p.peakElo) p.peakElo = p.elo;
            }
            case "wins" -> p.wins = value;
            case "losses" -> p.losses = value;
            case "games" -> p.games = value;
            case "mvp" -> p.mvp = value;
            case "kills" -> p.kills = value;
            case "deaths" -> p.deaths = value;
            case "gold" -> p.gold = value;
            case "xp" -> {
                p.xp = value;
                p.level = ctx.levels.levelFor(p.xp);
            }
            case "strikes" -> p.strikes = value;
            case "scored" -> p.scored = value;
            default -> {
                fail(e, "Statistica non modificabile: " + stat);
                return;
            }
        }

        ctx.players.save(p);
        playerService.updateMember(guild(e), memberOf(guild(e), target.getId()), p);
        ok(e, "`" + stat + "` di " + target.getAsMention() + " impostato a `" + value + "`");
    }

    private void forceRegister(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String ign = e.getOption("ign").getAsString().trim();

        if (ctx.players.exists(target.getId())) {
            fail(e, ctx.msg("player-already-registered"));
            return;
        }
        if (ign.length() > 16) {
            fail(e, ctx.msg("ign-too-long"));
            return;
        }

        ctx.players.create(target.getId(), ign, playerService.startingElo());
        Player p = player(target);
        playerService.updateMember(guild(e), memberOf(guild(e), target.getId()), p);
        ok(e, target.getAsMention() + " registrato come `" + ign + "`");
    }

    private void forceRename(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String ign = e.getOption("ign").getAsString().trim();

        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("player-not-registered"));
            return;
        }
        if (ign.length() > 16) {
            fail(e, ctx.msg("ign-too-long"));
            return;
        }

        p.ign = ign;
        ctx.players.save(p);
        playerService.updateMember(guild(e), memberOf(guild(e), target.getId()), p);
        ok(e, target.getAsMention() + " rinominato in `" + ign + "`");
    }

    private void screenshare(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String reason = e.getOption("motivo").getAsString();

        if (target.getId().equals(e.getUser().getId())) {
            fail(e, ctx.msg("ss-self"));
            return;
        }

        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }

        int required = ctx.config.getInt("ss-attachments", 1);
        int provided = 0;
        String firstUrl = null;
        for (String option : new String[]{"prova1", "prova2"}) {
            var mapping = e.getOption(option);
            if (mapping != null) {
                provided++;
                if (firstUrl == null) firstUrl = mapping.getAsAttachment().getUrl();
            }
        }
        if (provided < required) {
            fail(e, "Devi allegare almeno " + required + " screenshot come prova");
            return;
        }

        Guild guild = guild(e);
        Member targetMember = memberOf(guild, target.getId());
        long frozenRoleId = ctx.config.getId("frozen-role");
        if (frozenRoleId != 0 && targetMember != null) {
            Role frozen = guild.getRoleById(frozenRoleId);
            if (frozen != null && guild.getSelfMember().canInteract(frozen)) {
                guild.addRoleToMember(targetMember, frozen).queue(null, err -> {
                });

                int minutes = ctx.config.getInt("time-till-unfrozen", 10);
                ctx.scheduler.schedule(() -> guild.retrieveMemberById(target.getId()).queue(
                        m -> guild.removeRoleFromMember(m, frozen).queue(null, err -> {
                        }),
                        err -> {
                        }), minutes, TimeUnit.MINUTES);
            }
        }

        long ssChannelId = ctx.config.getId("ssreq-channel");
        if (ssChannelId != 0) {
            TextChannel channel = guild.getTextChannelById(ssChannelId);
            if (channel != null) {
                EmbedBuilder eb = embeds.base(embeds.errorColor())
                        .setTitle("Richiesta screenshare")
                        .addField("Sospetto", target.getAsMention(), true)
                        .addField("Richiesto da", e.getUser().getAsMention(), true)
                        .addField("Motivo", reason, false);
                if (firstUrl != null) eb.setImage(firstUrl);

                List<Long> ssRoles = ctx.config.getIdList("ss-roles");
                StringBuilder ping = new StringBuilder();
                for (Long roleId : ssRoles) ping.append("<@&").append(roleId).append("> ");
                channel.sendMessage(ping.toString()).setEmbeds(eb.build()).queue();
            }
        }

        ok(e, "Screenshare richiesto per " + target.getAsMention());
    }

    private void mecca(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String reason = e.getOption("motivo").getAsString();
        var attachment = e.getOption("prova").getAsAttachment();

        if (target.getId().equals(e.getUser().getId())) {
            fail(e, "Non puoi meccare te stesso!");
            return;
        }

        Player targetPlayer = player(target);
        if (targetPlayer == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }

        Guild guild = guild(e);
        long channelId = ctx.config.getId("mecca-channel");
        if (channelId == 0) channelId = ctx.config.getId("ban-channel");
        if (channelId == 0) channelId = ctx.config.getId("ssreq-channel");

        TextChannel channel = channelId != 0 ? guild.getTextChannelById(channelId) : e.getChannel().asTextChannel();

        EmbedBuilder eb = embeds.base(embeds.errorColor())
                .setTitle("🚨 SEGNALAZIONE MECCA")
                .addField("🔴 Segnalato", target.getAsMention() + " (`" + targetPlayer.ign + "`)", true)
                .addField("👮 Segnalato da", e.getUser().getAsMention(), true)
                .addField("📌 Motivo", reason, false)
                .addField("📊 Stats Attuali", "`" + targetPlayer.elo + " ELO` | `" + targetPlayer.strikes + " Strike`", false)
                .setImage(attachment.getUrl());

        if (channel != null && channel.getIdLong() != e.getChannel().getIdLong()) {
            channel.sendMessageEmbeds(eb.build()).queue();
        }

        reply(e, eb.build());
    }
}
