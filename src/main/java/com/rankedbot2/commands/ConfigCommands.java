package com.rankedbot2.commands;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandBase;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.model.GameMap;
import com.rankedbot2.model.GameQueue;
import com.rankedbot2.model.Player;
import com.rankedbot2.model.Rank;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.EnumSet;
import java.util.List;

public class ConfigCommands extends CommandBase {

    public ConfigCommands(BotContext ctx, Embeds embeds, PlayerService playerService, GameService gameService) {
        super(ctx, embeds, playerService, gameService);
    }

    @Override
    public List<SlashCommandData> data() {
        OptionData pickingMode = new OptionData(OptionType.STRING, "modo", "Come si formano i team", true)
                .addChoice("automatic", "AUTOMATIC")
                .addChoice("captains", "CAPTAINS");

        return List.of(Commands.slash("config", "Configurazione del bot ranked")
                .addSubcommands(
                        new SubcommandData("addqueue", "Crea una coda su un canale vocale")
                                .addOption(OptionType.CHANNEL, "vocale", "Canale vocale della coda", true)
                                .addOption(OptionType.INTEGER, "giocatori", "Giocatori per team", true)
                                .addOptions(pickingMode)
                                .addOption(OptionType.BOOLEAN, "casual", "Coda casual (nessun elo)", false),
                        new SubcommandData("deletequeue", "Elimina una coda")
                                .addOption(OptionType.CHANNEL, "vocale", "Canale vocale della coda", true),
                        new SubcommandData("queues", "Elenca le code configurate"),

                        new SubcommandData("addrank", "Crea un rank basato sull'elo")
                                .addOption(OptionType.ROLE, "ruolo", "Ruolo del rank", true)
                                .addOption(OptionType.INTEGER, "elo_iniziale", "Elo minimo del rank", true)
                                .addOption(OptionType.INTEGER, "elo_finale", "Elo massimo del rank", true)
                                .addOption(OptionType.INTEGER, "elo_vittoria", "Elo guadagnato vincendo", true)
                                .addOption(OptionType.INTEGER, "elo_sconfitta", "Elo perso perdendo", true),
                        new SubcommandData("deleterank", "Elimina un rank")
                                .addOption(OptionType.ROLE, "ruolo", "Ruolo del rank", true),
                        new SubcommandData("ranks", "Elenca i rank configurati"),

                        new SubcommandData("addmap", "Aggiungi una mappa")
                                .addOption(OptionType.STRING, "nome", "Nome della mappa", true)
                                .addOption(OptionType.INTEGER, "altezza", "Altezza build limit", false)
                                .addOption(OptionType.STRING, "team1", "Colore/lato del team 1", false)
                                .addOption(OptionType.STRING, "team2", "Colore/lato del team 2", false)
                                .addOption(OptionType.INTEGER, "giocatori",
                                        "Giocatori per team (vuoto = va bene per ogni modalità)", false),
                        new SubcommandData("deletemap", "Elimina una mappa")
                                .addOption(OptionType.STRING, "nome", "Nome della mappa", true),
                        new SubcommandData("maps", "Elenca le mappe"),

                        new SubcommandData("levels", "Mostra i livelli e le ricompense"),
                        new SubcommandData("givetheme", "Dai a un giocatore l'accesso a un tema")
                                .addOption(OptionType.USER, "giocatore", "Giocatore", true)
                                .addOption(OptionType.STRING, "tema", "Nome del tema", true),
                        new SubcommandData("removetheme", "Togli a un giocatore l'accesso a un tema")
                                .addOption(OptionType.USER, "giocatore", "Giocatore", true)
                                .addOption(OptionType.STRING, "tema", "Nome del tema", true),

                        new SubcommandData("addhangout", "Crea il canale vocale Community Hangout nella categoria RBW System")
                                .addOption(OptionType.STRING, "nome", "Nome del canale (default: Community Hangout)", false),

                        new SubcommandData("reload", "Ricarica i file di configurazione")));
    }

    @Override
    public boolean handles(String name) {
        return name.equals("config");
    }

    @Override
    public String permissionKey(SlashCommandInteractionEvent e) {
        return switch (sub(e)) {
            case "reload" -> "reloadconfig";
            default -> sub(e);
        };
    }

    @Override
    public boolean requiresRegistration(SlashCommandInteractionEvent e) {
        return false;
    }

    @Override
    public void execute(SlashCommandInteractionEvent e) {
        switch (sub(e)) {
            case "addqueue" -> addQueue(e);
            case "deletequeue" -> deleteQueue(e);
            case "queues" -> listQueues(e);
            case "addrank" -> addRank(e);
            case "deleterank" -> deleteRank(e);
            case "ranks" -> listRanks(e);
            case "addmap" -> addMap(e);
            case "deletemap" -> deleteMap(e);
            case "maps" -> listMaps(e);
            case "levels" -> listLevels(e);
            case "givetheme" -> giveTheme(e);
            case "removetheme" -> removeTheme(e);
            case "addhangout" -> addHangout(e);
            case "reload" -> reload(e);
            default -> fail(e, "Sottocomando sconosciuto");
        }
    }

    private VoiceChannel voiceOption(SlashCommandInteractionEvent e) {
        var channel = e.getOption("vocale").getAsChannel();
        if (channel.getType() != ChannelType.VOICE) return null;
        return channel.asVoiceChannel();
    }

    private void addQueue(SlashCommandInteractionEvent e) {
        VoiceChannel vc = voiceOption(e);
        if (vc == null) {
            fail(e, ctx.msg("invalid-vc"));
            return;
        }

        int playersEachTeam = (int) e.getOption("giocatori").getAsLong();
        if (playersEachTeam < 1) {
            fail(e, ctx.msg("q-more-players"));
            return;
        }
        if (ctx.queues.exists(vc.getId())) {
            fail(e, ctx.msg("q-already-exists"));
            return;
        }

        GameQueue.PickingMode mode = GameQueue.PickingMode.valueOf(e.getOption("modo").getAsString());
        boolean casual = e.getOption("casual") != null && e.getOption("casual").getAsBoolean();

        ctx.queues.add(new GameQueue(vc.getId(), playersEachTeam, mode, casual));
        ok(e, "Coda creata su " + vc.getAsMention() + " — " + playersEachTeam + "v" + playersEachTeam
                + ", " + mode.name().toLowerCase() + (casual ? ", casual" : ", ranked"));
    }

    private void deleteQueue(SlashCommandInteractionEvent e) {
        VoiceChannel vc = voiceOption(e);
        if (vc == null) {
            fail(e, ctx.msg("invalid-vc"));
            return;
        }
        if (!ctx.queues.delete(vc.getId())) {
            fail(e, ctx.msg("q-doesnt-exist"));
            return;
        }
        ok(e, ctx.msg("q-deleted"));
    }

    private void listQueues(SlashCommandInteractionEvent e) {
        List<GameQueue> queues = ctx.queues.all();
        if (queues.isEmpty()) {
            info(e, "Non ci sono code configurate");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (GameQueue q : queues) {
            VoiceChannel vc = guild(e).getVoiceChannelById(q.vcId);
            sb.append(vc == null ? "`" + q.vcId + "` *(canale mancante)*" : vc.getAsMention())
                    .append(" — ").append(q.modeName())
                    .append(", ").append(q.pickingMode.name().toLowerCase())
                    .append(q.casual ? ", casual" : ", ranked")
                    .append('\n');
        }
        reply(e, embeds.builder().setTitle("Code configurate").setDescription(sb.toString()).build());
    }

    private void addRank(SlashCommandInteractionEvent e) {
        Role role = e.getOption("ruolo").getAsRole();
        if (ctx.ranks.exists(role.getId())) {
            fail(e, ctx.msg("rank-already-exists"));
            return;
        }

        int start = (int) e.getOption("elo_iniziale").getAsLong();
        int end = (int) e.getOption("elo_finale").getAsLong();
        int win = (int) e.getOption("elo_vittoria").getAsLong();
        int lose = (int) e.getOption("elo_sconfitta").getAsLong();

        if (end < start) {
            fail(e, "L'elo finale non può essere minore di quello iniziale");
            return;
        }

        ctx.ranks.add(new Rank(role.getId(), start, end, win, Math.abs(lose)));
        ok(e, "Rank " + role.getAsMention() + " creato — elo `" + start + "`-`" + end
                + "`, `+" + win + "` vittoria, `-" + Math.abs(lose) + "` sconfitta");
    }

    private void deleteRank(SlashCommandInteractionEvent e) {
        Role role = e.getOption("ruolo").getAsRole();
        if (!ctx.ranks.delete(role.getId())) {
            fail(e, ctx.msg("rank-doesnt-exist"));
            return;
        }
        ok(e, ctx.msg("rank-deleted"));
    }

    private void listRanks(SlashCommandInteractionEvent e) {
        List<Rank> ranks = ctx.ranks.all();
        if (ranks.isEmpty()) {
            info(e, ctx.msg("no-ranks"));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Rank r : ranks) {
            Role role = guild(e).getRoleById(r.roleId);
            sb.append(role == null ? "`" + r.roleId + "` *(ruolo mancante)*" : role.getAsMention())
                    .append(" — elo `").append(r.startElo).append("`-`").append(r.endElo)
                    .append("`, `+").append(r.winElo).append("` / `-").append(r.loseElo).append("`\n");
        }
        reply(e, embeds.builder().setTitle("Rank configurati").setDescription(sb.toString()).build());
    }

    private void addMap(SlashCommandInteractionEvent e) {
        String name = e.getOption("nome").getAsString().trim();
        if (ctx.maps.exists(name)) {
            fail(e, ctx.msg("map-already-exists"));
            return;
        }

        int height = e.getOption("altezza") == null ? 0 : (int) e.getOption("altezza").getAsLong();
        String team1 = e.getOption("team1") == null ? "" : e.getOption("team1").getAsString();
        String team2 = e.getOption("team2") == null ? "" : e.getOption("team2").getAsString();
        int mode = e.getOption("giocatori") == null ? 0 : (int) e.getOption("giocatori").getAsLong();

        GameMap map = new GameMap(name, height, team1, team2, mode);
        ctx.maps.add(map);
        ok(e, "Mappa `" + name + "` aggiunta (" + map.modeName() + ")");
    }

    private void deleteMap(SlashCommandInteractionEvent e) {
        String name = e.getOption("nome").getAsString().trim();
        if (!ctx.maps.delete(name)) {
            fail(e, ctx.msg("map-doesnt-exist"));
            return;
        }
        ok(e, ctx.msg("map-deleted"));
    }

    private void listMaps(SlashCommandInteractionEvent e) {
        List<GameMap> maps = ctx.maps.all();
        if (maps.isEmpty()) {
            info(e, ctx.msg("no-maps"));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (GameMap m : maps) {
            sb.append("**").append(m.name).append("** `").append(m.modeName()).append("`");
            if (m.height > 0) sb.append(" — altezza `").append(m.height).append("`");
            if (!m.team1.isEmpty() || !m.team2.isEmpty()) {
                sb.append(" — ").append(m.team1).append(" vs ").append(m.team2);
            }
            sb.append('\n');
        }
        reply(e, embeds.builder().setTitle("Mappe").setDescription(sb.toString()).build());
    }

    private void listLevels(SlashCommandInteractionEvent e) {
        var levels = ctx.levels.all();
        if (levels.isEmpty()) {
            info(e, "Nessun livello configurato in levels.yml");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (var l : levels) {
            sb.append("**Livello ").append(l.number).append("** — `").append(l.requiredXp).append("` XP");
            if (l.goldReward > 0) sb.append(" · ricompensa `").append(l.goldReward).append("` gold");
            sb.append('\n');
        }

        EmbedBuilder eb = embeds.builder().setTitle("Livelli").setDescription(sb.toString());
        eb.addField("XP per vittoria", String.valueOf(ctx.config.getInt("win-xp", 0)), true);
        eb.addField("XP per partita", String.valueOf(ctx.config.getInt("play-xp", 0)), true);
        reply(e, eb.build());
    }

    private void giveTheme(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String theme = e.getOption("tema").getAsString().trim();

        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }
        if (p.ownedThemeList().contains(theme)) {
            fail(e, ctx.msg("already-has-theme"));
            return;
        }

        p.addTheme(theme);
        ctx.players.save(p);
        ok(e, target.getAsMention() + " ha ora accesso al tema `" + theme + "`");
    }

    private void removeTheme(SlashCommandInteractionEvent e) {
        User target = e.getOption("giocatore").getAsUser();
        String theme = e.getOption("tema").getAsString().trim();

        Player p = player(target);
        if (p == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }
        if (!p.ownedThemeList().contains(theme)) {
            fail(e, ctx.msg("doesnt-have-theme"));
            return;
        }

        p.removeTheme(theme);
        ctx.players.save(p);
        ok(e, "Tema `" + theme + "` rimosso a " + target.getAsMention());
    }

    private void reload(SlashCommandInteractionEvent e) {
        ctx.reloadConfigs();
        ok(e, "Configurazione ricaricata");
    }

    private void addHangout(SlashCommandInteractionEvent e) {
        String channelName = e.getOption("nome") != null ? e.getOption("nome").getAsString().trim() : "Community Hangout";
        var guild = guild(e);

        Category category = null;
        for (Category c : guild.getCategories()) {
            if (c.getName().equalsIgnoreCase("RBW System") || c.getName().equalsIgnoreCase("rbw system")) {
                category = c;
                break;
            }
        }

        if (category == null) {
            long catId = ctx.config.getId("game-vcs-category");
            if (catId != 0) {
                category = guild.getCategoryById(catId);
            }
        }

        if (category == null) {
            category = guild.createCategory("RBW System").complete();
        }

        for (VoiceChannel vc : category.getVoiceChannels()) {
            if (vc.getName().equalsIgnoreCase(channelName) || vc.getName().equalsIgnoreCase("🔊 " + channelName)) {
                fail(e, "Il canale vocale `" + vc.getName() + "` esiste già nella categoria `" + category.getName() + "`!");
                return;
            }
        }

        final Category targetCategory = category;
        targetCategory.createVoiceChannel(channelName)
                .addPermissionOverride(guild.getPublicRole(),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK),
                        null)
                .queue(vc -> {
                    ok(e, "Canale vocale " + vc.getAsMention() + " creato con successo nella categoria **" + targetCategory.getName() + "**!");
                }, err -> {
                    fail(e, "Errore durante la creazione del canale vocale: " + err.getMessage());
                });
    }
}
