package com.rankedbot2.commands;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandBase;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.model.Clan;
import com.rankedbot2.model.Player;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import com.rankedbot2.service.StatsImageService;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.FileUpload;

import java.util.List;

public class ClanCommands extends CommandBase {

    private final StatsImageService statsImages;

    public ClanCommands(BotContext ctx, Embeds embeds, PlayerService playerService,
                        GameService gameService, StatsImageService statsImages) {
        super(ctx, embeds, playerService, gameService);
        this.statsImages = statsImages;
    }

    @Override
    public List<SlashCommandData> data() {
        OptionData setting = new OptionData(OptionType.STRING, "impostazione", "Impostazione da cambiare", true)
                .addChoice("description", "description")
                .addChoice("name", "name")
                .addChoice("open", "open")
                .addChoice("min-elo", "min-elo")
                .addChoice("icon", "icon")
                .addChoice("theme", "theme");

        return List.of(Commands.slash("clan", "Comandi clan")
                .addSubcommands(
                        new SubcommandData("create", "Crea un clan")
                                .addOption(OptionType.STRING, "nome", "Nome del clan", true),
                        new SubcommandData("disband", "Sciogli il tuo clan"),
                        new SubcommandData("invite", "Invita un giocatore nel tuo clan")
                                .addOption(OptionType.USER, "giocatore", "Giocatore da invitare", true),
                        new SubcommandData("join", "Entra in un clan")
                                .addOption(OptionType.STRING, "nome", "Nome del clan", true),
                        new SubcommandData("leave", "Esci dal tuo clan"),
                        new SubcommandData("kick", "Espelli un membro dal clan")
                                .addOption(OptionType.USER, "giocatore", "Membro da espellere", true),
                        new SubcommandData("stats", "Statistiche di un clan")
                                .addOption(OptionType.STRING, "nome", "Nome del clan", false),
                        new SubcommandData("info", "Informazioni e membri di un clan")
                                .addOption(OptionType.STRING, "nome", "Nome del clan", false),
                        new SubcommandData("settings", "Cambia le impostazioni del tuo clan")
                                .addOptions(setting)
                                .addOption(OptionType.STRING, "valore", "Nuovo valore", true),
                        new SubcommandData("list", "Elenca tutti i clan"),
                        new SubcommandData("leaderboard", "Classifica clan per reputazione"),
                        new SubcommandData("forcedisband", "Sciogli forzatamente un clan")
                                .addOption(OptionType.STRING, "nome", "Nome del clan", true)));
    }

    @Override
    public boolean handles(String name) {
        return name.equals("clan");
    }

    @Override
    public String permissionKey(SlashCommandInteractionEvent e) {
        String s = sub(e);
        return s.equals("leaderboard") ? "clanlb" : "clan" + s;
    }

    @Override
    public void execute(SlashCommandInteractionEvent e) {
        switch (sub(e)) {
            case "create" -> create(e);
            case "disband" -> disband(e);
            case "invite" -> invite(e);
            case "join" -> join(e);
            case "leave" -> leave(e);
            case "kick" -> kick(e);
            case "stats" -> stats(e);
            case "info" -> info(e);
            case "settings" -> settings(e);
            case "list" -> list(e);
            case "leaderboard" -> leaderboard(e);
            case "forcedisband" -> forceDisband(e);
            default -> fail(e, "Sottocomando sconosciuto");
        }
    }

    private Clan clanOf(Player p) {
        return p == null || p.clanId < 0 ? null : ctx.clans.get(p.clanId);
    }

    private void create(SlashCommandInteractionEvent e) {
        Player p = player(e.getUser());
        if (p.clanId >= 0) {
            fail(e, ctx.msg("already-in-clan"));
            return;
        }

        String name = e.getOption("nome").getAsString().trim();
        int maxName = ctx.config.getInt("clan-name-max", 20);
        if (name.length() > maxName) {
            fail(e, ctx.msg("name-too-long"));
            return;
        }
        if (ctx.clans.getByName(name) != null) {
            fail(e, ctx.msg("clan-already-exists"));
            return;
        }

        int goldCost = ctx.config.getInt("gold-to-create", 0);
        int eloRequired = ctx.config.getInt("elo-to-create", 0);
        if (p.gold < goldCost) {
            fail(e, ctx.msg("not-enough-gold"));
            return;
        }
        if (p.elo < eloRequired) {
            fail(e, ctx.msg("not-enough-elo"));
            return;
        }

        Clan clan = ctx.clans.create(name, p.id, ctx.config.getInt("clan-starting-rep", 0));
        if (clan == null) {
            fail(e, "Creazione clan fallita");
            return;
        }

        p.gold -= goldCost;
        p.clanId = clan.id;
        ctx.players.save(p);
        ok(e, ctx.msg("clan-created"));
    }

    private void disband(SlashCommandInteractionEvent e) {
        Player p = player(e.getUser());
        Clan clan = clanOf(p);
        if (clan == null) {
            fail(e, ctx.msg("not-in-clan"));
            return;
        }
        if (!clan.leader.equals(p.id)) {
            fail(e, ctx.msg("not-clan-leader"));
            return;
        }

        ctx.clans.delete(clan.id);
        ok(e, ctx.msg("clan-disbanded"));
    }

    private void invite(SlashCommandInteractionEvent e) {
        Player p = player(e.getUser());
        Clan clan = clanOf(p);
        if (clan == null) {
            fail(e, ctx.msg("not-in-clan"));
            return;
        }
        if (!clan.leader.equals(p.id)) {
            fail(e, ctx.msg("not-clan-leader"));
            return;
        }

        User target = e.getOption("giocatore").getAsUser();
        Player targetPlayer = player(target);
        if (targetPlayer == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }
        if (targetPlayer.clanId >= 0) {
            fail(e, "Questo giocatore è già in un clan");
            return;
        }
        if (ctx.players.inClan(clan.id).size() >= maxMembers(clan)) {
            fail(e, ctx.msg("clan-max-players"));
            return;
        }

        ctx.clanInvites.put(target.getId(), clan.id);
        clan.invited++;
        ctx.clans.save(clan);
        ok(e, ctx.msg("player-invited"));
    }

    private int maxMembers(Clan clan) {
        return ctx.config.getInt("l" + clan.level, 10);
    }

    private void join(SlashCommandInteractionEvent e) {
        Player p = player(e.getUser());
        if (p.clanId >= 0) {
            fail(e, ctx.msg("already-in-clan"));
            return;
        }

        String name = e.getOption("nome").getAsString().trim();
        Clan clan = ctx.clans.getByName(name);
        if (clan == null) {
            fail(e, ctx.msg("clan-doesnt-exist"));
            return;
        }

        Integer invitedTo = ctx.clanInvites.get(p.id);
        boolean invited = invitedTo != null && invitedTo == clan.id;
        if (!clan.open && !invited) {
            fail(e, ctx.msg("clan-not-invited"));
            return;
        }
        if (clan.open && p.elo < clan.minElo) {
            fail(e, ctx.msg("not-enough-elo-join"));
            return;
        }
        if (ctx.players.inClan(clan.id).size() >= maxMembers(clan)) {
            fail(e, ctx.msg("clan-max-players"));
            return;
        }

        p.clanId = clan.id;
        ctx.players.save(p);
        ctx.clanInvites.remove(p.id);
        ok(e, ctx.msg("clan-joined"));
    }

    private void leave(SlashCommandInteractionEvent e) {
        Player p = player(e.getUser());
        Clan clan = clanOf(p);
        if (clan == null) {
            fail(e, ctx.msg("not-in-clan"));
            return;
        }
        if (clan.leader.equals(p.id)) {
            fail(e, ctx.msg("clan-leader"));
            return;
        }

        p.clanId = -1;
        ctx.players.save(p);
        ok(e, ctx.msg("clan-left"));
    }

    private void kick(SlashCommandInteractionEvent e) {
        Player p = player(e.getUser());
        Clan clan = clanOf(p);
        if (clan == null) {
            fail(e, ctx.msg("not-in-clan"));
            return;
        }
        if (!clan.leader.equals(p.id)) {
            fail(e, ctx.msg("not-clan-leader"));
            return;
        }

        User target = e.getOption("giocatore").getAsUser();
        Player targetPlayer = player(target);
        if (targetPlayer == null || targetPlayer.clanId != clan.id) {
            fail(e, ctx.msg("player-not-in-clan"));
            return;
        }
        if (targetPlayer.id.equals(clan.leader)) {
            fail(e, "Non puoi espellere te stesso");
            return;
        }

        targetPlayer.clanId = -1;
        ctx.players.save(targetPlayer);
        ok(e, ctx.msg("player-kicked"));
    }

    private Clan resolveClan(SlashCommandInteractionEvent e) {
        if (e.getOption("nome") != null) {
            return ctx.clans.getByName(e.getOption("nome").getAsString().trim());
        }
        return clanOf(player(e.getUser()));
    }

    private void stats(SlashCommandInteractionEvent e) {
        Clan clan = resolveClan(e);
        if (clan == null) {
            fail(e, e.getOption("nome") == null ? ctx.msg("not-in-clan") : ctx.msg("clan-doesnt-exist"));
            return;
        }

        e.deferReply().queue();

        // Il disegno scarica la skin del leader: fuori dal thread eventi.
        async(e, () -> {
            if (ctx.config.getBoolean("s-enabled", false)) {
                try {
                    byte[] image = statsImages.renderClan(guild(e), clan);
                    if (image != null) {
                        e.getHook().sendFiles(FileUpload.fromData(image, "clanstats.png")).queue();
                        return;
                    }
                } catch (Exception ex) {
                    System.err.println("Immagine clan stats non generata: " + ex.getMessage());
                }
            }

            e.getHook().sendMessageEmbeds(clanEmbed(clan)).queue();
        });
    }

    private net.dv8tion.jda.api.entities.MessageEmbed clanEmbed(Clan clan) {
        List<Player> members = ctx.players.inClan(clan.id);
        int allElo = 0;
        int allGold = 0;
        for (Player m : members) {
            allElo += m.elo;
            allGold += m.gold;
        }

        Player leader = ctx.players.get(clan.leader);
        return embeds.builder()
                .setTitle("Clan " + clan.name)
                .setDescription(clan.description.isEmpty() ? "*Nessuna descrizione*" : clan.description)
                .addField("Reputazione", String.valueOf(clan.reputation), true)
                .addField("Posizione", "#" + ctx.clans.ranking(clan.id), true)
                .addField("Livello", clan.level + " (" + clan.xp + " XP)", true)
                .addField("Leader", leader == null ? "—" : leader.ign, true)
                .addField("Membri", members.size() + "/" + maxMembers(clan), true)
                .addField("Inviti mandati", String.valueOf(clan.invited), true)
                .addField("Elo totale", String.valueOf(allElo), true)
                .addField("Gold totale", String.valueOf(allGold), true)
                .addField("Clan war", clan.cwWins + "V / " + clan.cwLosses + "S ("
                        + String.format(java.util.Locale.ROOT, "%.2f", clan.cwWlr()) + ")", true)
                .build();
    }

    private void info(SlashCommandInteractionEvent e) {
        Clan clan = resolveClan(e);
        if (clan == null) {
            fail(e, e.getOption("nome") == null ? ctx.msg("not-in-clan") : ctx.msg("clan-doesnt-exist"));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Player m : ctx.players.inClan(clan.id)) {
            sb.append(GameService.mention(m.id));
            if (m.id.equals(clan.leader)) sb.append(" *(leader)*");
            sb.append(" `").append(m.elo).append("`\n");
        }

        reply(e, embeds.builder()
                .setTitle("Clan " + clan.name)
                .setDescription(clan.description.isEmpty() ? "*Nessuna descrizione*" : clan.description)
                .addField("Membri", sb.length() == 0 ? "—" : sb.toString(), false)
                .addField("Aperto a tutti", clan.open ? "Sì (elo minimo " + clan.minElo + ")" : "No", true)
                .build());
    }

    private void settings(SlashCommandInteractionEvent e) {
        Player p = player(e.getUser());
        Clan clan = clanOf(p);
        if (clan == null) {
            fail(e, ctx.msg("not-in-clan"));
            return;
        }
        if (!clan.leader.equals(p.id)) {
            fail(e, ctx.msg("not-clan-leader"));
            return;
        }

        String setting = e.getOption("impostazione").getAsString();
        String value = e.getOption("valore").getAsString().trim();

        switch (setting) {
            case "description" -> {
                int max = ctx.config.getInt("clan-desc-max", 37);
                if (value.length() > max) {
                    fail(e, ctx.msg("desc-too-long"));
                    return;
                }
                clan.description = value;
            }
            case "name" -> {
                int max = ctx.config.getInt("clan-name-max", 20);
                if (value.length() > max) {
                    fail(e, ctx.msg("name-too-long"));
                    return;
                }
                Clan existing = ctx.clans.getByName(value);
                if (existing != null && existing.id != clan.id) {
                    fail(e, ctx.msg("clan-already-exists"));
                    return;
                }
                clan.name = value;
            }
            case "open" -> clan.open = Boolean.parseBoolean(value);
            case "min-elo" -> {
                try {
                    clan.minElo = Math.max(0, Integer.parseInt(value));
                } catch (NumberFormatException ex) {
                    fail(e, "L'elo minimo deve essere un numero");
                    return;
                }
            }
            case "icon" -> {
                int required = ctx.config.getInt("allow-setting-icon", 5);
                if (clan.level < required) {
                    fail(e, "Il clan deve essere almeno livello `" + required + "` per cambiare icona");
                    return;
                }
                clan.icon = value;
            }
            case "theme" -> {
                int required = ctx.config.getInt("allow-setting-theme", 10);
                if (clan.level < required) {
                    fail(e, "Il clan deve essere almeno livello `" + required + "` per cambiare tema");
                    return;
                }
                clan.theme = value;
            }
            default -> {
                fail(e, ctx.msg("invalid-setting"));
                return;
            }
        }

        ctx.clans.save(clan);
        ok(e, "`" + setting + "` aggiornato a `" + value + "`");
    }

    private void list(SlashCommandInteractionEvent e) {
        List<Clan> clans = ctx.clans.all();
        if (clans.isEmpty()) {
            info(e, "Non esiste ancora nessun clan");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Clan c : clans) {
            sb.append("**").append(c.name).append("** — ")
                    .append(ctx.players.inClan(c.id).size()).append(" membri, ")
                    .append(c.reputation).append(" rep")
                    .append(c.open ? " *(aperto)*" : "")
                    .append('\n');
        }
        reply(e, embeds.builder().setTitle("Clan del server").setDescription(sb.toString()).build());
    }

    private void leaderboard(SlashCommandInteractionEvent e) {
        List<Clan> clans = ctx.clans.all();
        if (clans.isEmpty()) {
            info(e, "Non esiste ancora nessun clan");
            return;
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(15, clans.size());
        for (int i = 0; i < limit; i++) {
            Clan c = clans.get(i);
            sb.append("**").append(i + 1).append(".** ").append(c.name)
                    .append(" — `").append(c.reputation).append("` rep (lvl ").append(c.level).append(")\n");
        }
        reply(e, embeds.builder().setTitle("Classifica clan").setDescription(sb.toString()).build());
    }

    private void forceDisband(SlashCommandInteractionEvent e) {
        String name = e.getOption("nome").getAsString().trim();
        Clan clan = ctx.clans.getByName(name);
        if (clan == null) {
            fail(e, ctx.msg("clan-doesnt-exist"));
            return;
        }

        ctx.clans.delete(clan.id);
        ok(e, "Clan `" + name + "` sciolto forzatamente");
    }
}
