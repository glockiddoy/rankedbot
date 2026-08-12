package com.rankedbot2.commands;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandBase;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.model.Clan;
import com.rankedbot2.model.ClanWar;
import com.rankedbot2.model.Player;
import com.rankedbot2.service.ClanWarService;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.ArrayList;
import java.util.List;

public class ClanWarCommands extends CommandBase {

    private final ClanWarService clanWarService;

    public ClanWarCommands(BotContext ctx, Embeds embeds, PlayerService playerService,
                           GameService gameService, ClanWarService clanWarService) {
        super(ctx, embeds, playerService, gameService);
        this.clanWarService = clanWarService;
    }

    @Override
    public List<SlashCommandData> data() {
        return List.of(Commands.slash("cw", "Comandi clan war")
                .addSubcommands(
                        new SubcommandData("create", "Avvia le iscrizioni a una clan war")
                                .addOption(OptionType.INTEGER, "giocatori", "Giocatori per team", true)
                                .addOption(OptionType.INTEGER, "minclan", "Numero minimo di clan", true)
                                .addOption(OptionType.INTEGER, "maxclan", "Numero massimo di clan", true)
                                .addOption(OptionType.INTEGER, "winxp", "XP clan per la vittoria", false)
                                .addOption(OptionType.INTEGER, "wingold", "Gold per la vittoria", false),
                        new SubcommandData("cancel", "Annulla una clan war")
                                .addOption(OptionType.INTEGER, "numero", "Numero della clan war", true),
                        new SubcommandData("register", "Registra il tuo clan e i suoi giocatori")
                                .addOption(OptionType.INTEGER, "numero", "Numero della clan war", true)
                                .addOption(OptionType.STRING, "giocatori", "Menzioni o ID separati da spazio", true),
                        new SubcommandData("unregister", "Ritira il tuo clan dalla clan war")
                                .addOption(OptionType.INTEGER, "numero", "Numero della clan war", true),
                        new SubcommandData("start", "Avvia le partite della clan war")
                                .addOption(OptionType.INTEGER, "numero", "Numero della clan war", true)));
    }

    @Override
    public boolean handles(String name) {
        return name.equals("cw");
    }

    @Override
    public String permissionKey(SlashCommandInteractionEvent e) {
        return "cw" + sub(e);
    }

    @Override
    public void execute(SlashCommandInteractionEvent e) {
        switch (sub(e)) {
            case "create" -> create(e);
            case "cancel" -> cancel(e);
            case "register" -> register(e);
            case "unregister" -> unregister(e);
            case "start" -> start(e);
            default -> fail(e, "Sottocomando sconosciuto");
        }
    }

    private void create(SlashCommandInteractionEvent e) {
        ClanWar war = new ClanWar();
        war.number = clanWarService.nextNumber();
        war.playersInTeam = (int) e.getOption("giocatori").getAsLong();
        war.minClans = (int) e.getOption("minclan").getAsLong();
        war.maxClans = (int) e.getOption("maxclan").getAsLong();
        war.winXp = e.getOption("winxp") == null ? 0 : (int) e.getOption("winxp").getAsLong();
        war.winGold = e.getOption("wingold") == null ? 0 : (int) e.getOption("wingold").getAsLong();

        if (war.playersInTeam < 1) {
            fail(e, ctx.msg("q-more-players"));
            return;
        }
        if (war.minClans < 2 || war.maxClans < war.minClans) {
            fail(e, "Servono almeno 2 clan e il massimo non può essere minore del minimo");
            return;
        }

        ctx.clanWars.put(war.number, war);
        ok(e, "Clan war **#" + war.number + "** aperta — " + war.playersInTeam + "v" + war.playersInTeam
                + "\nI leader registrano il clan con `/cw register numero:" + war.number + " giocatori:@a @b ...`");
    }

    private void cancel(SlashCommandInteractionEvent e) {
        int number = (int) e.getOption("numero").getAsLong();
        ClanWar war = ctx.clanWars.remove(number);
        if (war == null) {
            fail(e, "Questa clan war non esiste");
            return;
        }
        ok(e, "Clan war #" + number + " annullata");
    }

    private void register(SlashCommandInteractionEvent e) {
        int number = (int) e.getOption("numero").getAsLong();
        ClanWar war = ctx.clanWars.get(number);
        if (war == null) {
            fail(e, "Questa clan war non esiste");
            return;
        }
        if (war.started) {
            fail(e, "Questa clan war è già iniziata");
            return;
        }

        Player leaderPlayer = player(e.getUser());
        Clan clan = leaderPlayer.clanId < 0 ? null : ctx.clans.get(leaderPlayer.clanId);
        if (clan == null) {
            fail(e, ctx.msg("not-in-clan"));
            return;
        }
        if (!clan.leader.equals(leaderPlayer.id)) {
            fail(e, ctx.msg("not-clan-leader"));
            return;
        }
        if (war.isRegistered(clan.id)) {
            fail(e, "Il tuo clan è già registrato a questa clan war");
            return;
        }
        if (war.clanCount() >= war.maxClans) {
            fail(e, "Questa clan war ha già il numero massimo di clan");
            return;
        }

        List<String> roster = parseMembers(e.getOption("giocatori").getAsString(), clan.id);
        if (roster.size() != war.playersInTeam) {
            fail(e, "Devi registrare esattamente `" + war.playersInTeam
                    + "` giocatori del tuo clan (trovati `" + roster.size() + "`)");
            return;
        }

        war.registrations.put(clan.id, roster);
        ok(e, "Clan **" + clan.name + "** registrato alla clan war #" + number
                + " con " + roster.size() + " giocatori");
    }

    /** Estrae gli ID utente da menzioni o ID grezzi, tenendo solo i membri del clan. */
    private List<String> parseMembers(String raw, int clanId) {
        List<String> out = new ArrayList<>();
        for (String token : raw.split("[\\s,]+")) {
            String id = token.replaceAll("[^0-9]", "");
            if (id.isEmpty() || out.contains(id)) continue;

            Player p = ctx.players.get(id);
            if (p != null && p.clanId == clanId) out.add(id);
        }
        return out;
    }

    private void unregister(SlashCommandInteractionEvent e) {
        int number = (int) e.getOption("numero").getAsLong();
        ClanWar war = ctx.clanWars.get(number);
        if (war == null) {
            fail(e, "Questa clan war non esiste");
            return;
        }
        if (war.started) {
            fail(e, "Questa clan war è già iniziata");
            return;
        }

        Player p = player(e.getUser());
        Clan clan = p.clanId < 0 ? null : ctx.clans.get(p.clanId);
        if (clan == null) {
            fail(e, ctx.msg("not-in-clan"));
            return;
        }
        if (!clan.leader.equals(p.id)) {
            fail(e, ctx.msg("not-clan-leader"));
            return;
        }
        if (war.registrations.remove(clan.id) == null) {
            fail(e, "Il tuo clan non è registrato a questa clan war");
            return;
        }
        ok(e, "Clan **" + clan.name + "** ritirato dalla clan war #" + number);
    }

    private void start(SlashCommandInteractionEvent e) {
        int number = (int) e.getOption("numero").getAsLong();
        ClanWar war = ctx.clanWars.get(number);
        if (war == null) {
            fail(e, "Questa clan war non esiste");
            return;
        }
        if (war.started) {
            fail(e, "Questa clan war è già iniziata");
            return;
        }
        if (war.clanCount() < war.minClans) {
            fail(e, "Servono almeno `" + war.minClans + "` clan registrati (ora sono `"
                    + war.clanCount() + "`)");
            return;
        }

        e.deferReply().queue();

        // La creazione dei canali usa chiamate REST bloccanti: fuori dal thread eventi.
        ctx.scheduler.execute(() -> {
            try {
                List<Integer> created = clanWarService.startWar(guild(e), war);
                if (created.isEmpty()) {
                    fail(e, "Nessuna partita creata: servono almeno 2 clan con roster completo");
                    return;
                }

                war.started = true;

                StringBuilder sb = new StringBuilder();
                for (int gameNumber : created) sb.append("Partita #").append(gameNumber).append('\n');

                EmbedBuilder eb = embeds.base(embeds.successColor())
                        .setTitle("Clan war #" + number + " iniziata")
                        .setDescription(sb.toString());
                reply(e, eb.build());
            } catch (Exception ex) {
                fail(e, "Errore durante l'avvio della clan war: " + ex.getMessage());
            }
        });
    }
}
