package com.rankedbot2.service;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.model.Clan;
import com.rankedbot2.model.Game;
import com.rankedbot2.model.GameMap;
import com.rankedbot2.model.GameQueue;
import com.rankedbot2.model.Party;
import com.rankedbot2.model.Player;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.utils.FileUpload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class GameService {

    private final BotContext ctx;
    private final PlayerService playerService;
    private final Embeds embeds;
    private final CoralMcService coralMcService;
    private final ScoreImageService scoreImages;
    private final Random random = new Random();
    /** Code già in fase di creazione partita, per evitare doppie creazioni concorrenti. */
    private final Set<String> creating = Collections.synchronizedSet(new HashSet<>());
    /** Partite con un autoscore in corso: due link incollati insieme non devono scorare due volte. */
    private final Set<Integer> autoScoring = Collections.synchronizedSet(new HashSet<>());

    public GameService(BotContext ctx, PlayerService playerService, Embeds embeds, CoralMcService coralMcService) {
        this.ctx = ctx;
        this.playerService = playerService;
        this.embeds = embeds;
        this.coralMcService = coralMcService != null ? coralMcService : new CoralMcService();
        this.scoreImages = new ScoreImageService(ctx);
    }

    public GameService(BotContext ctx, PlayerService playerService, Embeds embeds) {
        this(ctx, playerService, embeds, new CoralMcService());
    }

    public CoralMcService getCoralMcService() {
        return coralMcService;
    }

    /**
     * Chiamato quando qualcuno entra in un canale vocale. Il lavoro vero gira su
     * un thread separato: la creazione dei canali usa chiamate REST bloccanti che
     * non devono girare sul thread eventi del gateway.
     */
    public void onVoiceJoin(Guild guild, AudioChannel channel) {
        GameQueue queue = ctx.queues.get(channel.getId());
        if (queue == null) return;
        if (!creating.add(queue.vcId)) return;

        ctx.scheduler.execute(() -> buildGameIfReady(guild, queue));
    }

    private void buildGameIfReady(Guild guild, GameQueue queue) {
        try {
            VoiceChannel vc = guild.getVoiceChannelById(queue.vcId);
            if (vc == null) return;

            List<Member> present = new ArrayList<>();
            for (Member m : vc.getMembers()) {
                if (m.getUser().isBot()) continue;
                Player p = ctx.players.get(m.getId());
                if (p == null || p.isBanned()) continue;
                if (ctx.games.activeGameOf(m.getId()) != null) continue;
                present.add(m);
            }

            if (present.size() < queue.totalPlayers()) return;
            createGame(guild, queue, present.subList(0, queue.totalPlayers()));
        } catch (Exception e) {
            System.err.println("Errore creazione partita: " + e.getMessage());
        } finally {
            creating.remove(queue.vcId);
        }
    }

    private void createGame(Guild guild, GameQueue queue, List<Member> members) {
        Game game = new Game();
        game.number = ctx.games.nextNumber();
        game.queueVc = queue.vcId;
        game.playersEachTeam = queue.playersEachTeam;
        game.casual = queue.casual;
        game.createdAt = System.currentTimeMillis();

        List<GameMap> maps = ctx.maps.forMode(queue.playersEachTeam);
        game.map = maps.isEmpty() ? "" : maps.get(random.nextInt(maps.size())).name;

        List<String> ids = new ArrayList<>();
        for (Member m : members) ids.add(m.getId());

        if (queue.pickingMode == GameQueue.PickingMode.AUTOMATIC) {
            balanceTeams(game, ids);
            game.state = Game.State.STARTED;
        } else {
            setupCaptains(game, ids);
            // In un 1v1 i due capitani sono già i due team: non c'è nulla da scegliere.
            game.state = game.remaining.isEmpty() ? Game.State.STARTED : Game.State.PICKING;
        }

        boolean needsPicking = game.state == Game.State.PICKING;

        Category textCategory = categoryOrNull(guild, "game-channels-category");
        Category vcCategory = categoryOrNull(guild, "game-vcs-category");

        String textName = ctx.config.getString("game-channel-names", "Game%number%")
                .replace("%number%", String.valueOf(game.number))
                .replace("%mode%", queue.modeName());

        var textAction = textCategory != null
                ? guild.createTextChannel(sanitize(textName), textCategory)
                : guild.createTextChannel(sanitize(textName));

        textAction = textAction.addPermissionOverride(
                guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL));

        for (Role staffRole : getStaffRoles(guild)) {
            textAction = textAction.addRolePermissionOverride(
                    staffRole.getIdLong(),
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND),
                    null);
        }

        for (String id : ids) {
            textAction = textAction.addMemberPermissionOverride(
                    Long.parseLong(id),
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND),
                    null);
        }

        TextChannel textChannel = textAction.complete();
        game.textChannel = textChannel.getId();

        String vcNameFormat = ctx.config.getString("game-vc-names", "Game#%number% | T%team%");
        VoiceChannel vc1 = createGameVc(guild, vcCategory, vcNameFormat, game.number, queue.modeName(), 1);
        VoiceChannel vc2 = createGameVc(guild, vcCategory, vcNameFormat, game.number, queue.modeName(), 2);
        game.vc1 = vc1.getId();
        game.vc2 = vc2.getId();

        ctx.games.save(game);
        setupVcPermissions(guild, game);

        if (!needsPicking) {
            moveTeamsToVcs(guild, game);
            textChannel.sendMessageEmbeds(gameStartedEmbed(guild, game)).queue();
            announceGame(guild, game);
        } else {
            for (String id : ids) {
                Member m = guild.getMemberById(id);
                if (m != null && m.getVoiceState() != null && m.getVoiceState().inAudioChannel()) {
                    guild.moveVoiceMember(m, vc1).queue(null, err -> {
                    });
                }
            }
            textChannel.sendMessageEmbeds(pickingEmbed(guild, game)).queue();
        }
    }

    private VoiceChannel createGameVc(Guild guild, Category category, String format, int number, String mode, int team) {
        String name = format
                .replace("%number%", String.valueOf(number))
                .replace("%mode%", mode)
                .replace("%team%", String.valueOf(team));
        var action = category != null
                ? guild.createVoiceChannel(name, category)
                : guild.createVoiceChannel(name);
        return action.complete();
    }

    private Category categoryOrNull(Guild guild, String key) {
        if (guild == null) return null;
        long id = ctx.config.getId(key);
        Category cat = id == 0 ? null : guild.getCategoryById(id);
        if (cat != null) return cat;

        for (Category c : guild.getCategories()) {
            String name = c.getName().toLowerCase();
            if (name.contains("rbw games") || name.contains("game channels") || name.contains("rbw queues") || name.contains("game")) {
                return c;
            }
        }
        return null;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9-_ ]", "").trim();
    }

    /**
     * Divide i giocatori in due team bilanciati per elo, tenendo insieme i membri
     * dello stesso party.
     */
    private void balanceTeams(Game game, List<String> ids) {
        List<List<String>> units = groupByParty(ids);
        units.sort(Comparator.comparingInt((List<String> u) -> totalElo(u)).reversed());

        int max = game.playersEachTeam;
        for (List<String> unit : units) {
            boolean fitsTeam1 = game.team1.size() + unit.size() <= max;
            boolean fitsTeam2 = game.team2.size() + unit.size() <= max;

            if (fitsTeam1 && (!fitsTeam2 || totalElo(game.team1) <= totalElo(game.team2))) {
                game.team1.addAll(unit);
            } else if (fitsTeam2) {
                game.team2.addAll(unit);
            } else {
                // Party troppo grande per stare unito: si divide riempiendo i posti liberi.
                for (String id : unit) {
                    if (game.team1.size() < max) game.team1.add(id);
                    else if (game.team2.size() < max) game.team2.add(id);
                }
            }
        }
    }

    private List<List<String>> groupByParty(List<String> ids) {
        List<List<String>> units = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String id : ids) {
            if (used.contains(id)) continue;
            Party party = ctx.partyOf(id);
            List<String> unit = new ArrayList<>();
            if (party != null) {
                for (String member : party.members) {
                    if (ids.contains(member) && used.add(member)) unit.add(member);
                }
            }
            if (unit.isEmpty() && used.add(id)) unit.add(id);
            if (!unit.isEmpty()) units.add(unit);
        }
        return units;
    }

    private int totalElo(List<String> ids) {
        int sum = 0;
        for (String id : ids) {
            Player p = ctx.players.get(id);
            if (p != null) sum += p.elo;
        }
        return sum;
    }

    private void setupCaptains(Game game, List<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparingInt((String id) -> {
            Player p = ctx.players.get(id);
            return p == null ? 0 : p.elo;
        }).reversed());

        game.captain1 = sorted.get(0);
        game.captain2 = sorted.get(1);
        game.team1.add(game.captain1);
        game.team2.add(game.captain2);
        game.remaining.addAll(sorted.subList(2, sorted.size()));
        game.pickTurn = 1;
        // Ordine 1-2-2-1: il primo capitano apre con una sola scelta.
        game.picksLeft = 1;
    }

    /** Posti ancora liberi nel team indicato. */
    private int freeSlots(Game game, int team) {
        List<String> roster = team == 1 ? game.team1 : game.team2;
        return game.playersEachTeam - roster.size();
    }

    /**
     * Passa il turno all'altro capitano assegnandogli le pick in base alla modalità.
     * Per il 3v3 (3 giocatori per team) l'ordine è 1-1-1-1 (1 sola pick per turno).
     * Per le altre modalità l'ordine è 1-2-2-1 (fino a 2 pick per turno).
     */
    private void advanceTurn(Game game) {
        int next = game.pickTurn == 1 ? 2 : 1;
        if (freeSlots(game, next) <= 0) next = game.pickTurn;

        game.pickTurn = next;
        int maxPicks = (game.playersEachTeam == 3) ? 1 : 2;
        game.picksLeft = Math.min(maxPicks, Math.min(freeSlots(game, next), game.remaining.size()));
    }

    /** L'ultimo giocatore rimasto non viene scelto: entra da solo nel team con posto. */
    private void assignLastPlayer(Game game) {
        String last = game.remaining.remove(0);
        if (freeSlots(game, 1) > 0) game.team1.add(last);
        else game.team2.add(last);
    }

    /** Esegue una pick. Ritorna null se ok, altrimenti il messaggio di errore. */
    public String pick(Guild guild, Game game, String captainId, String targetId) {
        if (game.state != Game.State.PICKING) return ctx.msg("game-started");
        if (!game.isCaptain(captainId)) return ctx.msg("not-captain");
        if (captainId.equals(targetId)) return ctx.msg("picked-yourself");
        if (!game.remaining.contains(targetId)) return "Questo giocatore non è disponibile per la pick";

        int captainTeam = game.teamOf(captainId);
        if (captainTeam != game.pickTurn) return "Non è il tuo turno di pick";

        game.remaining.remove(targetId);
        if (captainTeam == 1) game.team1.add(targetId);
        else game.team2.add(targetId);

        game.picksLeft--;

        // Con un solo giocatore rimasto non c'è più nulla da scegliere.
        if (game.remaining.size() == 1) {
            assignLastPlayer(game);
        } else if (game.picksLeft <= 0) {
            advanceTurn(game);
        }

        if (game.remaining.isEmpty()) {
            game.state = Game.State.STARTED;
            ctx.games.save(game);
            moveTeamsToVcs(guild, game);
            TextChannel channel = guild.getTextChannelById(game.textChannel);
            if (channel != null) channel.sendMessageEmbeds(gameStartedEmbed(guild, game)).queue();
            announceGame(guild, game);
        } else {
            ctx.games.save(game);
        }
        return null;
    }

    public List<Role> getStaffRoles(Guild guild) {
        List<Role> roles = new ArrayList<>();
        Set<Long> roleIds = new LinkedHashSet<>();

        roleIds.addAll(ctx.config.getIdList("staff-roles"));
        roleIds.addAll(ctx.config.getIdList("ss-roles"));
        long scorerId = ctx.config.getId("scorer-role");
        if (scorerId != 0) roleIds.add(scorerId);

        for (Long id : roleIds) {
            Role r = guild.getRoleById(id);
            if (r != null) roles.add(r);
        }
        return roles;
    }

    public void setupVcPermissions(Guild guild, Game game) {
        VoiceChannel vc1 = guild.getVoiceChannelById(game.vc1);
        VoiceChannel vc2 = guild.getVoiceChannelById(game.vc2);
        if (vc1 == null || vc2 == null) return;

        List<Role> staffRoles = getStaffRoles(guild);

        if (game.state == Game.State.PICKING) {
            vc1.upsertPermissionOverride(guild.getPublicRole())
                    .deny(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                    .queue(null, err -> {});
            for (Role staffRole : staffRoles) {
                vc1.upsertPermissionOverride(staffRole)
                        .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                        .queue(null, err -> {});
            }
            for (String id : game.team1) {
                Member m = guild.getMemberById(id);
                if (m != null) {
                    vc1.upsertPermissionOverride(m)
                            .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                            .queue(null, err -> {});
                }
            }
            for (String id : game.remaining) {
                Member m = guild.getMemberById(id);
                if (m != null) {
                    vc1.upsertPermissionOverride(m)
                            .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                            .queue(null, err -> {});
                }
            }
            return;
        }

        // Partita iniziata o in corso: Team 1 ha permessi su VC1, Team 2 su VC2. Solo staff e player vedono e connettono
        vc1.upsertPermissionOverride(guild.getPublicRole())
                .deny(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                .queue(null, err -> {});
        vc2.upsertPermissionOverride(guild.getPublicRole())
                .deny(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                .queue(null, err -> {});

        for (Role staffRole : staffRoles) {
            vc1.upsertPermissionOverride(staffRole)
                    .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                    .queue(null, err -> {});
            vc2.upsertPermissionOverride(staffRole)
                    .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                    .queue(null, err -> {});
        }

        for (String id : game.team1) {
            Member m = guild.getMemberById(id);
            if (m != null) {
                vc1.upsertPermissionOverride(m)
                        .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                        .queue(null, err -> {});
            }
        }
        for (String id : game.team2) {
            Member m = guild.getMemberById(id);
            if (m != null) {
                vc2.upsertPermissionOverride(m)
                        .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                        .queue(null, err -> {});
            }
        }
    }

    private void moveTeamsToVcs(Guild guild, Game game) {
        setupVcPermissions(guild, game);
        VoiceChannel vc1 = guild.getVoiceChannelById(game.vc1);
        VoiceChannel vc2 = guild.getVoiceChannelById(game.vc2);
        moveAll(guild, game.team1, vc1);
        moveAll(guild, game.team2, vc2);
    }

    private void moveAll(Guild guild, List<String> ids, VoiceChannel target) {
        if (target == null) return;
        for (String id : ids) {
            Member m = guild.getMemberById(id);
            if (m != null && m.getVoiceState() != null && m.getVoiceState().inAudioChannel()) {
                guild.moveVoiceMember(m, target).queue(null, err -> {
                });
            }
        }
    }

    public net.dv8tion.jda.api.entities.MessageEmbed pickingEmbed(Guild guild, Game game) {
        EmbedBuilder eb = embeds.builder()
                .setTitle("👑 FASE PICK — PARTITA #" + game.number + " (" + game.modeName() + ")")
                .setDescription("Capitani, scegliete i compagni di squadra utilizzando `/pick`.")
                .addField("🔴 Capitano Team 1", mention(game.captain1), true)
                .addField("🔵 Capitano Team 2", mention(game.captain2), true)
                .addField("🎯 Turno Attuale", "**Team " + game.pickTurn + "** — `" + game.picksLeft + " pick`", true)
                .addField("📋 Giocatori Disponibili", listPlayers(game.remaining), false);
        if (!game.map.isEmpty()) {
            String mapName = game.map.substring(0, 1).toUpperCase() + game.map.substring(1).toLowerCase();
            eb.addField("🗺️ Mappa Sorteggiata", "**" + mapName + "**", true);
        }
        return eb.build();
    }

    public net.dv8tion.jda.api.entities.MessageEmbed gameStartedEmbed(Guild guild, Game game) {
        EmbedBuilder eb = embeds.builder()
                .setTitle("⚔️ PARTITA #" + game.number + " — " + game.modeName().toUpperCase())
                .setDescription("🎮 **Partita iniziata!** Buona fortuna a entrambi i team.\n")
                .addField("🔴 Team 1", listPlayers(game.team1), true)
                .addField("🔵 Team 2", listPlayers(game.team2), true);

        if (!game.map.isEmpty()) {
            GameMap map = ctx.maps.get(game.map);
            String mapName = game.map.substring(0, 1).toUpperCase() + game.map.substring(1).toLowerCase();
            String mapInfo = "🗺️ **" + mapName + "**";
            if (map != null && map.height > 0) mapInfo += " *(Altezza build: `" + map.height + "`)*";
            eb.addField("Mappa", mapInfo, false);
        }

        String partyCmd = ctx.config.getString("party-invite-cmd", "/p invite");
        if (!partyCmd.isEmpty()) {
            eb.addField("📌 Invito Party", "`" + partyCmd + " <nome>`", false);
        }

        if (game.casual) {
            eb.addField("🎮 Modalità", "Casual — Nessun ELO in palio", false);
        } else {
            eb.addField("⚡ Come Chiudere la Partita",
                    "A fine partita, incolla il **link CoralMC** (es. `https://www.coralmc.it/stats/bedwars/match/...`) in questo canale per lo **scoring automatico**!", false);
        }
        return eb.build();
    }

    private void announceGame(Guild guild, Game game) {
        long channelId = ctx.config.getId("games-announcing");
        if (channelId == 0) return;
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel != null) channel.sendMessageEmbeds(gameStartedEmbed(guild, game)).queue();
    }

    /** Registra il submit e avvisa gli scorer. */
    public void submit(Guild guild, Game game, String submitterId, List<String> attachmentUrls) {
        game.state = Game.State.SUBMITTED;
        ctx.games.save(game);

        long scorerRole = ctx.config.getId("scorer-role");
        TextChannel channel = guild.getTextChannelById(game.textChannel);
        if (channel == null) return;

        EmbedBuilder eb = embeds.base(embeds.successColor())
                .setTitle("Partita #" + game.number + " inviata")
                .setDescription("Inviata da " + mention(submitterId)
                        + "\nUno scorer deve usare `/score numero:" + game.number + "`.");
        if (!attachmentUrls.isEmpty()) eb.setImage(attachmentUrls.get(0));

        String ping = scorerRole == 0 ? "" : "<@&" + scorerRole + ">";
        channel.sendMessage(ping).setEmbeds(eb.build()).queue();
    }

    /**
     * Assegna la vittoria e applica elo, XP, gold e statistiche.
     * Ritorna null se ok, altrimenti il messaggio di errore.
     */
    public String score(Guild guild, Game game, String scorerId, int winningTeam, String mvpId) {
        if (game.state == Game.State.SCORED) return "Questa partita è già stata scorata";
        if (game.state == Game.State.VOIDED) return "Questa partita è stata annullata";
        if (game.casual) return ctx.msg("casual-game");
        if (winningTeam != 1 && winningTeam != 2) return "Il team vincente deve essere 1 o 2";

        List<String> winners = winningTeam == 1 ? game.team1 : game.team2;
        List<String> losers = winningTeam == 1 ? game.team2 : game.team1;

        game.eloChanges.clear();

        int winXp = ctx.config.getInt("win-xp", 0);
        int playXp = ctx.config.getInt("play-xp", 0);
        int clanXpWin = ctx.config.getInt("clanxp-win", 0);
        int clanXpPlay = ctx.config.getInt("clanxp-play", 0);

        for (String id : winners) {
            Player p = ctx.players.get(id);
            if (p == null) continue;

            int gain = (int) Math.round(playerService.winEloFor(p) * playerService.multiplierFor(id));
            int applied = playerService.addElo(p, gain);
            game.eloChanges.put(id, applied);

            p.wins++;
            p.games++;
            p.winstreak++;
            p.lossstreak = 0;
            if (p.winstreak > p.highestWs) p.highestWs = p.winstreak;
            if (id.equals(mvpId)) p.mvp++;

            playerService.addXp(p, winXp + playXp);
            addClanXp(p, clanXpWin + clanXpPlay);
            ctx.players.save(p);
            refreshMember(guild, p);
        }

        for (String id : losers) {
            Player p = ctx.players.get(id);
            if (p == null) continue;

            int loss = (int) Math.round(playerService.loseEloFor(p) * playerService.multiplierFor(id));
            int applied = playerService.addElo(p, -Math.abs(loss));
            game.eloChanges.put(id, applied);

            p.losses++;
            p.games++;
            p.lossstreak++;
            p.winstreak = 0;
            if (p.lossstreak > p.highestLs) p.highestLs = p.lossstreak;

            playerService.addXp(p, playXp);
            addClanXp(p, clanXpPlay);
            ctx.players.save(p);
            refreshMember(guild, p);
        }

        Player scorer = ctx.players.get(scorerId);
        if (scorer != null) {
            scorer.scored++;
            ctx.players.save(scorer);
        }

        game.winner = winningTeam;
        game.mvp = mvpId == null ? "" : mvpId;
        game.scoredBy = scorerId;
        game.state = Game.State.SCORED;
        ctx.games.save(game);

        applyClanWarResult(game, winningTeam);
        announceScored(guild, game);
        scheduleCleanup(guild, game);
        return null;
    }

    /**
     * Esegue l'autoscore recuperando le statistiche della partita tramite il link CoralMC.
     * Ritorna null se ok, altrimenti il messaggio di errore.
     */
    public String autoScoreFromMatchLink(Guild guild, Game game, String submitterId, String matchUrlOrId) {
        String matchId = coralMcService.extractMatchId(matchUrlOrId);
        if (matchId == null) {
            return "Link o ID partita non valido. Esempio: https://www.coralmc.it/stats/bedwars/match/4763808";
        }

        if (!autoScoring.add(game.number)) {
            return "C'è già un autoscore in corso per la partita #" + game.number + ", attendi.";
        }
        try {
            return runAutoScore(guild, game.number, submitterId, matchId);
        } finally {
            autoScoring.remove(game.number);
        }
    }

    private String runAutoScore(Guild guild, int gameNumber, String submitterId, String matchId) {
        // Rilettura dal database: l'oggetto passato dal comando può essere vecchio
        // di qualche secondo e nel frattempo la partita può essere stata chiusa.
        Game game = ctx.games.get(gameNumber);
        if (game == null) return ctx.msg("invalid-game");
        if (game.state == Game.State.SCORED) return "Questa partita è già stata scorata";
        if (game.state == Game.State.VOIDED) return "Questa partita è stata annullata";
        if (game.casual) return ctx.msg("casual-game");

        CoralMcService.CoralMatchData matchData;
        try {
            matchData = coralMcService.fetchFinishedMatch(matchId, 4, 4000);
        } catch (Exception e) {
            return "Errore recupero partita CoralMC: " + e.getMessage();
        }

        if (matchData.perPlayerStats.isEmpty()) {
            return "Nessuna statistica giocatore trovata nel match su CoralMC";
        }
        if (matchData.isOngoing()) {
            return "⏳ La partita risulta ancora in corso su CoralMC. Attendi che il server salvi il "
                    + "finale di partita e reinvia il link.";
        }

        int alreadyUsedBy = ctx.games.scoredWithCoralMatch(matchData.matchId, game.number);
        if (alreadyUsedBy != 0) {
            return "Questo match CoralMC è già stato usato per scorare la partita #" + alreadyUsedBy + ".";
        }

        Map<String, String> igns = new HashMap<>();
        for (String id : game.allPlayers()) {
            Player p = ctx.players.get(id);
            if (p != null && p.ign != null && !p.ign.isBlank()) igns.put(p.ign.toLowerCase(), id);
        }

        // Ogni giocatore della partita va ritrovato nel match, altrimenti il link
        // è di un'altra partita o qualcuno ha l'IGN sbagliato.
        List<String> unmatched = new ArrayList<>();
        for (String id : game.allPlayers()) {
            Player p = ctx.players.get(id);
            String ign = (p == null || p.ign == null || p.ign.isBlank()) ? null : p.ign;
            if (ign == null || matchData.byUsername(ign) == null) {
                unmatched.add("• " + mention(id) + " → IGN: `" + (ign == null ? "non registrato" : ign) + "`");
            }
        }
        if (!unmatched.isEmpty()) {
            return "Questi giocatori della partita #" + game.number + " non compaiono nel match CoralMC #"
                    + matchData.matchId + ".\nControlla di aver inviato il link giusto e che l'IGN sia "
                    + "registrato correttamente (`/rename`):\n" + String.join("\n", unmatched);
        }

        int winningTeam = winningTeamOf(game, matchData, igns);
        if (winningTeam == 0) {
            return "Impossibile determinare il team vincitore dai dati di CoralMC (team vincente: `"
                    + matchData.winningTeamName + "`).";
        }

        List<String> mvps = calculateMvps(game, matchData, igns);
        game.mvp = String.join(",", mvps);

        if ((game.map == null || game.map.isBlank()) && matchData.arenaName != null && !matchData.arenaName.isBlank()) {
            game.map = matchData.arenaName;
        }

        String scoreErr = score(guild, game, submitterId, winningTeam, game.mvp);
        if (scoreErr != null) return scoreErr;

        applyMatchStats(game, matchData, igns, mvps);
        game.coralMatch = matchData.matchId;
        ctx.games.save(game);

        sendAutoScoreDetails(guild, game, matchData, winningTeam, igns);
        return null;
    }

    /**
     * Team vincitore: il nome del team su CoralMC è la fonte autorevole, il
     * conteggio degli esiti serve solo se quel campo non è utilizzabile.
     */
    private int winningTeamOf(Game game, CoralMcService.CoralMatchData matchData, Map<String, String> igns) {
        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            if (!ps.teamName.equalsIgnoreCase(matchData.winningTeamName)) continue;
            String id = igns.get(ps.username.toLowerCase());
            if (id != null) {
                int team = game.teamOf(id);
                if (team != 0) return team;
            }
        }

        int team1Wins = 0;
        int team2Wins = 0;
        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            if (!ps.isWinner()) continue;
            String id = igns.get(ps.username.toLowerCase());
            if (id == null) continue;
            if (game.teamOf(id) == 1) team1Wins++;
            else if (game.teamOf(id) == 2) team2Wins++;
        }
        if (team1Wins > team2Wins) return 1;
        if (team2Wins > team1Wins) return 2;
        return 0;
    }

    /**
     * Calcola gli MVP del match:
     * 1. 1 MVP al giocatore che ha distrutto il letto (bedsBroken > 0).
     * 2. 1 MVP al giocatore che ha fatto più kill regolari (kills) nel game.
     */
    private List<String> calculateMvps(Game game, CoralMcService.CoralMatchData matchData, Map<String, String> igns) {
        Set<String> mvps = new LinkedHashSet<>();

        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            if (ps.bedsBroken > 0) {
                String id = igns.get(ps.username.toLowerCase());
                if (id != null) mvps.add(id);
            }
        }

        int maxKills = -1;
        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            if (ps.kills > maxKills) maxKills = ps.kills;
        }

        if (maxKills > 0) {
            for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
                if (ps.kills == maxKills) {
                    String id = igns.get(ps.username.toLowerCase());
                    if (id != null) mvps.add(id);
                }
            }
        }

        return new ArrayList<>(mvps);
    }

    private void applyMatchStats(Game game, CoralMcService.CoralMatchData matchData, Map<String, String> igns, List<String> mvps) {
        game.killChanges.clear();
        game.deathChanges.clear();

        int maxKills = -1;
        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            if (ps.kills > maxKills) maxKills = ps.kills;
        }

        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            String id = igns.get(ps.username.toLowerCase());
            if (id == null) continue;
            Player p = ctx.players.get(id);
            if (p == null) continue;

            int totalKills = ps.kills + ps.finalKills;
            p.kills += totalKills;
            p.deaths += ps.deaths;

            if (ps.bedsBroken > 0) {
                p.mvp += 1;
            }
            if (maxKills > 0 && ps.kills == maxKills) {
                p.mvp += 1;
            }

            ctx.players.save(p);

            game.killChanges.put(id, totalKills);
            game.deathChanges.put(id, ps.deaths);
        }
    }

    private void sendAutoScoreDetails(Guild guild, Game game, CoralMcService.CoralMatchData matchData,
                                      int winningTeam, Map<String, String> igns) {
        TextChannel channel = guild.getTextChannelById(game.textChannel);
        if (channel == null) return;

        EmbedBuilder eb = embeds.base(embeds.successColor())
                .setTitle("⚡ Autoscore partita #" + game.number + " (CoralMC #" + matchData.matchId + ")")
                .setUrl("https://www.coralmc.it/stats/bedwars/match/" + matchData.matchId)
                .setDescription("Partita verificata e scorata automaticamente tramite CoralMC.");

        if (!matchData.arenaName.isEmpty()) eb.addField("Mappa", matchData.arenaName, true);
        if (matchData.durationSeconds > 0) {
            eb.addField("Durata", (matchData.durationSeconds / 60) + "m " + (matchData.durationSeconds % 60) + "s", true);
        }
        eb.addField("Vincitori (Team " + winningTeam + ")",
                listPlayers(winningTeam == 1 ? game.team1 : game.team2), false);
        eb.addField("Sconfitti (Team " + (winningTeam == 1 ? 2 : 1) + ")",
                listPlayers(winningTeam == 1 ? game.team2 : game.team1), false);

        StringBuilder mvpInfo = new StringBuilder();
        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            String id = igns.get(ps.username.toLowerCase());
            if (id == null) continue;
            if (ps.bedsBroken > 0) {
                mvpInfo.append("🛌 **Bed MVP**: ").append(mention(id)).append(" (`").append(ps.username).append("` - ").append(ps.bedsBroken).append(" letto/i)\n");
            }
        }

        int maxKills = -1;
        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            if (ps.kills > maxKills) maxKills = ps.kills;
        }
        if (maxKills > 0) {
            for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
                if (ps.kills == maxKills) {
                    String id = igns.get(ps.username.toLowerCase());
                    if (id != null) {
                        mvpInfo.append("⚔️ **Top Kills MVP**: ").append(mention(id)).append(" (`").append(ps.username).append("` - ").append(ps.kills).append(" Kills regolari)\n");
                    }
                }
            }
        }

        if (mvpInfo.length() > 0) {
            eb.addField("⭐ MVP della Partita", mvpInfo.toString(), false);
        }

        StringBuilder stats = new StringBuilder();
        for (String id : game.allPlayers()) {
            Player p = ctx.players.get(id);
            if (p == null) continue;
            CoralMcService.CoralPlayerStats ps = matchData.byUsername(p.ign);
            if (ps == null) continue;
            stats.append("• **").append(ps.username).append("**: ")
                    .append(ps.kills).append(" ⚔️ K | ")
                    .append(ps.finalKills).append(" 💀 FK | ")
                    .append(ps.deaths).append(" ☠️ D | ")
                    .append(ps.bedsBroken).append(" 🛌 Letti\n");
        }
        if (stats.length() > 0) eb.addField("📊 Statistiche match", stats.toString(), false);

        byte[] banner = renderScoreBanner(game);
        if (banner != null) {
            eb.setImage("attachment://recap.png");
            channel.sendMessageEmbeds(eb.build())
                    .addFiles(FileUpload.fromData(banner, "recap.png"))
                    .queue();
        } else {
            channel.sendMessageEmbeds(eb.build()).queue();
        }
    }

    private void addClanXp(Player p, int amount) {
        if (p.clanId < 0 || amount == 0) return;
        Clan clan = ctx.clans.get(p.clanId);
        if (clan == null) return;
        clan.xp += amount;
        clan.level = ctx.clanLevels.levelFor(clan.xp);
        ctx.clans.save(clan);
    }

    private void applyClanWarResult(Game game, int winningTeam) {
        if (game.clanWar < 0 || game.clan1 < 0 || game.clan2 < 0) return;

        int winnerClanId = winningTeam == 1 ? game.clan1 : game.clan2;
        int loserClanId = winningTeam == 1 ? game.clan2 : game.clan1;

        Clan winner = ctx.clans.get(winnerClanId);
        Clan loser = ctx.clans.get(loserClanId);

        if (winner != null) {
            winner.reputation += ctx.config.getInt("cw-rep-win", 0);
            winner.cwWins++;
            winner.cwPlayed++;
            ctx.clans.save(winner);
        }
        if (loser != null) {
            loser.reputation += ctx.config.getInt("cw-rep-loss", 0);
            loser.cwLosses++;
            loser.cwPlayed++;
            ctx.clans.save(loser);
        }
    }

    private void announceScored(Guild guild, Game game) {
        long channelId = ctx.config.getId("scored-announcing");
        if (channelId == 0) return;
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) return;

        EmbedBuilder eb = embeds.base(embeds.successColor())
                .setTitle("Partita #" + game.number + " scorata")
                .addField("Vincitori", listPlayers(game.winner == 1 ? game.team1 : game.team2), true)
                .addField("Sconfitti", listPlayers(game.winner == 1 ? game.team2 : game.team1), true);
        if (!game.mvp.isEmpty()) eb.addField("MVP", mention(game.mvp), false);

        byte[] banner = renderScoreBanner(game);
        if (banner != null) {
            eb.setImage("attachment://recap.png");
            channel.sendMessageEmbeds(eb.build())
                    .addFiles(FileUpload.fromData(banner, "recap.png"))
                    .queue();
        } else {
            channel.sendMessageEmbeds(eb.build()).queue();
        }
    }

    private List<ScoreImageService.Entry> buildEntries(Game game, List<String> playerIds) {
        List<ScoreImageService.Entry> entries = new ArrayList<>();
        for (String id : playerIds) {
            Player p = ctx.players.get(id);
            if (p == null) continue;
            int change = game.eloChanges.getOrDefault(id, 0);
            int currentElo = p.elo;
            int prevElo = currentElo - change;
            boolean isMvp = id.equals(game.mvp);
            entries.add(new ScoreImageService.Entry(p.ign, prevElo, currentElo, change, isMvp));
        }
        return entries;
    }

    public byte[] renderScoreBanner(Game game) {
        try {
            List<String> winners = game.winner == 1 ? game.team1 : game.team2;
            List<String> losers = game.winner == 1 ? game.team2 : game.team1;
            var winEntries = buildEntries(game, winners);
            var lossEntries = buildEntries(game, losers);
            return scoreImages.render(game.map, winEntries, lossEntries);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Annulla una partita non scorata. */
    public void voidGame(Guild guild, Game game) {
        game.state = Game.State.VOIDED;
        ctx.games.save(game);

        TextChannel channel = guild.getTextChannelById(game.textChannel);
        if (channel != null) {
            channel.sendMessageEmbeds(embeds.error("Partita #" + game.number + " annullata.")).queue();
        }
        scheduleCleanup(guild, game);
    }

    /** Annulla lo scoring di una partita già scorata, ripristinando l'elo. */
    public String undo(Guild guild, Game game) {
        if (game.state != Game.State.SCORED) return ctx.msg("not-scored");

        List<String> winners = game.winner == 1 ? game.team1 : game.team2;
        List<String> losers = game.winner == 1 ? game.team2 : game.team1;

        for (var entry : game.eloChanges.entrySet()) {
            Player p = ctx.players.get(entry.getKey());
            if (p == null) continue;

            playerService.addElo(p, -entry.getValue());
            p.games = Math.max(0, p.games - 1);

            if (winners.contains(p.id)) {
                p.wins = Math.max(0, p.wins - 1);
                p.winstreak = Math.max(0, p.winstreak - 1);
                if (game.mvp != null && game.mvp.contains(p.id)) p.mvp = Math.max(0, p.mvp - 1);
            } else if (losers.contains(p.id)) {
                p.losses = Math.max(0, p.losses - 1);
                p.lossstreak = Math.max(0, p.lossstreak - 1);
            }

            Integer killAdd = game.killChanges.get(p.id);
            if (killAdd != null && killAdd > 0) p.kills = Math.max(0, p.kills - killAdd);

            Integer deathAdd = game.deathChanges.get(p.id);
            if (deathAdd != null && deathAdd > 0) p.deaths = Math.max(0, p.deaths - deathAdd);

            ctx.players.save(p);
            refreshMember(guild, p);
        }

        Player scorer = ctx.players.get(game.scoredBy);
        if (scorer != null) {
            scorer.scored = Math.max(0, scorer.scored - 1);
            ctx.players.save(scorer);
        }

        game.eloChanges.clear();
        game.killChanges.clear();
        game.deathChanges.clear();
        game.state = Game.State.VOIDED;
        game.winner = 0;
        ctx.games.save(game);
        scheduleCleanup(guild, game);
        return null;
    }

    /** Cancella canali e vocali della partita dopo il tempo configurato (default 10s). */
    public void scheduleCleanup(Guild guild, Game game) {
        int seconds = ctx.config.getInt("game-deleting-time", 10);
        ctx.scheduler.schedule(() -> {
            deleteChannel(guild.getTextChannelById(game.textChannel));
            deleteChannel(guild.getVoiceChannelById(game.vc1));
            deleteChannel(guild.getVoiceChannelById(game.vc2));
        }, Math.max(1, seconds), TimeUnit.SECONDS);
    }

    private void deleteChannel(net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel channel) {
        if (channel == null) return;
        channel.delete().queue(null, err -> {
        });
    }

    private void refreshMember(Guild guild, Player p) {
        guild.retrieveMemberById(p.id).queue(
                member -> playerService.updateMember(guild, member, p),
                err -> {
                });
    }

    public String listPlayers(List<String> ids) {
        if (ids.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            Player p = ctx.players.get(id);
            sb.append(mention(id));
            if (p != null) {
                sb.append(" ").append(p.rankBadge()).append(" `").append(p.elo).append(" ELO`");
                if (p.winstreak >= 3) {
                    sb.append(" 🔥 `").append(p.winstreak).append(" Ws`");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String mention(String userId) {
        return userId == null || userId.isEmpty() ? "—" : "<@" + userId + ">";
    }
}
