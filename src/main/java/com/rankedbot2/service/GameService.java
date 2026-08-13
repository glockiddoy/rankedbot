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
    /** Ultimo motivo stampato per ogni coda, per non ripetere lo stesso log ogni scansione. */
    private final Map<String, String> lastQueueReport = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SWEEP_INTERVAL_MILLIS = 30_000;
    private volatile long lastSweep;

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

    public void checkAllQueues(Guild guild) {
        if (guild == null) return;

        // Le due pulizie leggono tutte le partite: a ogni scansione sarebbero
        // decine di query al minuto per un lavoro che non ha fretta.
        long now = System.currentTimeMillis();
        if (now - lastSweep > SWEEP_INTERVAL_MILLIS) {
            lastSweep = now;
            releaseAbandonedGames(guild);
            cleanupFinishedGames(guild);
        }
        for (GameQueue queue : ctx.queues.all()) {
            if (creating.add(queue.vcId)) submit(guild, queue);
        }
    }

    /**
     * Chiude le partite rimaste appese. Finché una partita risulta attiva i suoi
     * giocatori non possono entrare in una nuova coda: senza questa pulizia una
     * partita abbandonata blocca la queue per sempre.
     */
    private void releaseAbandonedGames(Guild guild) {
        long now = System.currentTimeMillis();
        long pickingTimeout = ctx.config.getInt("picking-timeout", 10) * 60_000L;
        long gameTimeout = ctx.config.getInt("abandoned-game-timeout", 120) * 60_000L;

        for (Game game : ctx.games.active()) {
            long age = now - game.createdAt;
            // Ignora le partite create negli ultimi 60 secondi per dare tempo a Discord di creare i canali
            if (age < 60_000L) continue;

            boolean channelGone = game.textChannel == null || game.textChannel.isBlank()
                    || textChannelOrNull(guild, game.textChannel) == null;
            boolean pickingExpired = game.state == Game.State.PICKING && age > pickingTimeout;
            boolean gameExpired = age > gameTimeout;

            if (!channelGone && !pickingExpired && !gameExpired) continue;

            String reason = channelGone ? "canale eliminato"
                    : pickingExpired ? "picking scaduto" : "partita abbandonata";
            System.out.println("[queue] partita #" + game.number + " annullata: " + reason);
            voidGame(guild, game);
        }
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

        submit(guild, queue);
    }

    /**
     * Accoda il controllo di una coda. Se l'esecutore rifiuta il task va tolto
     * subito il segnaposto: restando lì quella coda non verrebbe più controllata
     * per il resto della vita del bot.
     */
    private void submit(Guild guild, GameQueue queue) {
        try {
            ctx.scheduler.execute(() -> buildGameIfReady(guild, queue));
        } catch (RuntimeException e) {
            creating.remove(queue.vcId);
        }
    }

    private void buildGameIfReady(Guild guild, GameQueue queue) {
        try {
            VoiceChannel vc = guild.getVoiceChannelById(queue.vcId);
            if (vc == null) {
                report(queue.vcId, "[queue] coda " + queue.vcId + ": canale vocale non trovato");
                return;
            }

            // Chi torna in coda con una partita mai scorata la abbandona: tenerla
            // aperta bloccherebbe lui e tutti gli altri della sua partita.
            List<Game> activeGames = ctx.games.active();
            if (!ctx.config.getBoolean("queue-blocks-unscored", false)
                    && freeUnscoredGamesOf(guild, vc, activeGames)) {
                activeGames = ctx.games.active();
            }

            Eligibility eligibility = eligibilityOf(vc, activeGames);
            List<Member> present = eligibility.ready;

            if (present.size() < queue.totalPlayers()) {
                report(queue.vcId, "[queue] " + vc.getName() + ": " + present.size() + "/"
                        + queue.totalPlayers() + " pronti"
                        + (eligibility.blocked.isEmpty() ? "" : " — esclusi: "
                        + String.join(", ", eligibility.blocked)));
                return;
            }

            report(queue.vcId, null);
            createGame(guild, queue, new ArrayList<>(present.subList(0, queue.totalPlayers())));
        } catch (Exception e) {
            System.err.println("Errore creazione partita: " + e.getMessage());
            e.printStackTrace();
        } finally {
            creating.remove(queue.vcId);
        }
    }

    /**
     * Annulla le partite non scorate dei giocatori che sono rientrati in coda.
     * Una partita in PICKING resta intoccata: lì la scelta dei team è in corso e
     * il vocale di coda è ancora quello dove stanno aspettando.
     */
    private boolean freeUnscoredGamesOf(Guild guild, VoiceChannel vc, List<Game> activeGames) {
        Set<Integer> handled = new HashSet<>();
        long now = System.currentTimeMillis();

        for (Member m : vc.getMembers()) {
            if (m.getUser().isBot()) continue;

            Game active = activeGameIn(activeGames, m.getId());
            if (active == null || active.state == Game.State.PICKING) continue;
            // NON annullare partite create negli ultimi 90 secondi per dare tempo ai giocatori di essere spostati nei vocali di team
            if (now - active.createdAt < 90_000L) continue;
            if (!handled.add(active.number)) continue;

            System.out.println("[queue] partita #" + active.number
                    + " annullata: " + m.getEffectiveName() + " è rientrato in coda senza scorarla");
            voidGame(guild, active);
        }
        return !handled.isEmpty();
    }

    /** Chi in un vocale di coda può giocare e chi no, con il motivo. */
    public static class Eligibility {
        public final List<Member> ready = new ArrayList<>();
        public final List<String> blocked = new ArrayList<>();
    }

    /**
     * Partita attiva di un giocatore cercata in una lista già caricata.
     * Chiedere al database una volta per giocatore significava rileggere tutte
     * le partite attive otto volte per coda, ogni tre secondi.
     */
    private Game activeGameIn(List<Game> activeGames, String userId) {
        for (Game g : activeGames) {
            if (g.allPlayers().contains(userId)) return g;
        }
        return null;
    }

    /**
     * Filtro usato sia per far partire le partite sia da /queuestats: il comando
     * deve mostrare esattamente ciò che vede il bot, altrimenti non serve a nulla.
     */
    public Eligibility eligibilityOf(VoiceChannel vc) {
        return eligibilityOf(vc, ctx.games.active());
    }

    public Eligibility eligibilityOf(VoiceChannel vc, List<Game> activeGames) {
        Eligibility out = new Eligibility();
        if (vc == null) return out;

        for (Member m : vc.getMembers()) {
            if (m.getUser().isBot()) continue;

            Player p = ctx.players.get(m.getId());
            if (p == null) {
                out.blocked.add(m.getEffectiveName() + " (non registrato)");
                continue;
            }
            if (p.isBanned()) {
                out.blocked.add(m.getEffectiveName() + " (bannato)");
                continue;
            }
            Game active = activeGameIn(activeGames, m.getId());
            if (active != null) {
                out.blocked.add(m.getEffectiveName() + " (già nella partita #" + active.number + ")");
                continue;
            }
            out.ready.add(m);
        }
        return out;
    }

    /**
     * Stampa il motivo per cui una coda non parte, ma solo quando cambia: il
     * controllo gira ogni pochi secondi e altrimenti riempirebbe il log.
     */
    private void report(String queueId, String message) {
        if (message == null) {
            lastQueueReport.remove(queueId);
            return;
        }
        if (!message.equals(lastQueueReport.put(queueId, message))) {
            System.out.println(message);
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
        if (maps.isEmpty()) maps = ctx.maps.all();
        game.map = maps.isEmpty() ? "Bedwars" : maps.get(random.nextInt(maps.size())).name;

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
            sendGameStart(textChannel, guild, game);
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
        // Prima i party (dimensione > 1), poi i singoli (dimensione == 1), ordinati per ELO
        units.sort((u1, u2) -> {
            if (u1.size() > 1 && u2.size() == 1) return -1;
            if (u1.size() == 1 && u2.size() > 1) return 1;
            return Integer.compare(totalElo(u2), totalElo(u1));
        });

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
    private void assignLastPlayer(Guild guild, Game game) {
        if (game.remaining.isEmpty()) return;
        String last = game.remaining.remove(0);
        int team = freeSlots(game, 1) > 0 ? 1 : 2;
        if (team == 1) game.team1.add(last);
        else game.team2.add(last);

        VoiceChannel targetVc = team == 1 ? voiceChannelOrNull(guild, game.vc1) : voiceChannelOrNull(guild, game.vc2);
        moveMemberToVc(guild, last, targetVc);
    }

    private void moveMemberToVc(Guild guild, String userId, VoiceChannel target) {
        if (target == null || userId == null || guild == null) return;
        Member m = guild.getMemberById(userId);
        if (m != null) {
            target.upsertPermissionOverride(m)
                    .grant(EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                    .queue(null, err -> {});
            if (m.getVoiceState() != null && m.getVoiceState().inAudioChannel()) {
                guild.moveVoiceMember(m, target).queue(null, err -> {});
            }
        }
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

        VoiceChannel targetVc = captainTeam == 1 ? voiceChannelOrNull(guild, game.vc1) : voiceChannelOrNull(guild, game.vc2);
        moveMemberToVc(guild, targetId, targetVc);

        game.picksLeft--;

        // Con un solo giocatore rimasto non c'è più nulla da scegliere.
        if (game.remaining.size() == 1) {
            assignLastPlayer(guild, game);
        } else if (game.picksLeft <= 0) {
            advanceTurn(game);
        }

        if (game.remaining.isEmpty()) {
            game.state = Game.State.STARTED;
            ctx.games.save(game);
            moveTeamsToVcs(guild, game);
            TextChannel channel = textChannelOrNull(guild, game.textChannel);
            sendGameStart(channel, guild, game);
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
        VoiceChannel vc1 = voiceChannelOrNull(guild, game.vc1);
        VoiceChannel vc2 = voiceChannelOrNull(guild, game.vc2);
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
        VoiceChannel vc1 = voiceChannelOrNull(guild, game.vc1);
        VoiceChannel vc2 = voiceChannelOrNull(guild, game.vc2);
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
                .setDescription("Capitani, scegliete i compagni di squadra utilizzando `/pick`.\n")
                .addField("🔴 Team 1 (Capitano: " + mention(game.captain1) + ")", listPlayers(game.team1), true)
                .addField("🔵 Team 2 (Capitano: " + mention(game.captain2) + ")", listPlayers(game.team2), true)
                .addField("🎯 Turno Attuale", "**Team " + game.pickTurn + "** — `" + game.picksLeft + " pick`", false)
                .addField("📋 Giocatori Disponibili (" + game.remaining.size() + ")", listPlayers(game.remaining), false);
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
        if (channel != null) sendGameStart(channel, guild, game);
    }

    /**
     * Manda il messaggio di inizio partita con l'immagine dei due team. Se il
     * disegno fallisce parte comunque il solo embed: la partita è già creata e
     * i giocatori devono vedere le squadre.
     */
    public void sendGameStart(TextChannel channel, Guild guild, Game game) {
        if (channel == null) return;

        byte[] banner = renderStartBanner(game);
        EmbedBuilder eb = embeds.builder()
                .setTitle("⚔️ PARTITA #" + game.number + " — " + game.modeName().toUpperCase())
                .setDescription("🎮 **Partita iniziata!** Buona fortuna a entrambi i team.");

        if (!game.map.isEmpty()) {
            GameMap map = ctx.maps.get(game.map);
            String mapName = game.map.substring(0, 1).toUpperCase() + game.map.substring(1).toLowerCase();
            eb.addField("🗺️ Mappa", "**" + mapName + "**"
                    + (map != null && map.height > 0 ? " · altezza build `" + map.height + "`" : ""), true);
        }

        String partyCmd = ctx.config.getString("party-invite-cmd", "/p invite");
        if (!partyCmd.isEmpty()) eb.addField("📌 Party", "`" + partyCmd + " <nome>`", true);

        if (game.casual) {
            eb.addField("🎮 Modalità", "Casual — nessun ELO in palio", false);
        } else {
            eb.addField("⚡ Come si chiude",
                    "Incolla il **link CoralMC** in questo canale: lo scoring è automatico.", false);
        }

        // Con l'immagine i roster sono già leggibili: ripeterli nell'embed
        // raddoppierebbe l'altezza del messaggio per niente.
        if (banner == null) {
            eb.addField("🔴 Team 1", listPlayers(game.team1), true);
            eb.addField("🔵 Team 2", listPlayers(game.team2), true);
            channel.sendMessageEmbeds(eb.build()).queue();
            return;
        }

        eb.setImage("attachment://start.png");
        channel.sendMessageEmbeds(eb.build())
                .addFiles(FileUpload.fromData(banner, "start.png"))
                .queue();
    }

    private byte[] renderStartBanner(Game game) {
        try {
            String subtitle = "PARTITA #" + game.number + " · " + game.modeName().toUpperCase();
            return scoreImages.renderGameStart(mapStyle(game), subtitle,
                    startEntries(game, game.team1, game.captain1),
                    startEntries(game, game.team2, game.captain2));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Nome e colori dei letti della mappa, per lo sfondo generato. */
    private ScoreImageService.MapStyle mapStyle(Game game) {
        GameMap map = game.map.isEmpty() ? null : ctx.maps.get(game.map);
        if (map == null) return ScoreImageService.MapStyle.of(game.map);
        return new ScoreImageService.MapStyle(map.name, map.team1, map.team2);
    }

    private List<ScoreImageService.Entry> startEntries(Game game, List<String> ids, String captainId) {
        List<ScoreImageService.Entry> entries = new ArrayList<>();
        for (String id : ids) {
            Player p = ctx.players.get(id);
            if (p == null) continue;
            entries.add(ScoreImageService.Entry.atStart(p.ign, p.elo, id.equals(captainId)));
        }
        return entries;
    }

    /** Registra il submit e avvisa gli scorer. */
    public void submit(Guild guild, Game game, String submitterId, List<String> attachmentUrls) {
        game.state = Game.State.SUBMITTED;
        ctx.games.save(game);

        long scorerRole = ctx.config.getId("scorer-role");
        TextChannel channel = textChannelOrNull(guild, game.textChannel);
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

        // L'autoscore può assegnare più MVP (letto rotto + top kills), quindi qui
        // arriva una lista di id separati da virgola, non un id singolo.
        Set<String> mvpIds = new HashSet<>();
        if (mvpId != null) {
            for (String id : mvpId.split(",")) {
                if (!id.isBlank()) mvpIds.add(id.trim());
            }
        }

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
            if (mvpIds.contains(id)) p.mvp++;

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
            // Il top kills può stare nel team perdente.
            if (mvpIds.contains(id)) p.mvp++;

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
        game.endedAt = System.currentTimeMillis();
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

        long startedAt = System.currentTimeMillis();
        CoralMcService.CoralMatchData matchData;
        try {
            matchData = coralMcService.fetchFinishedMatch(matchId, 5, 3000);
        } catch (Exception e) {
            return "Errore recupero partita CoralMC: " + e.getMessage();
        }
        long fetchMillis = System.currentTimeMillis() - startedAt;

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

        List<String> mvps = calculateMvps(game, matchData, igns, winningTeam);
        game.mvp = String.join(",", mvps);

        if ((game.map == null || game.map.isBlank()) && matchData.arenaName != null && !matchData.arenaName.isBlank()) {
            game.map = matchData.arenaName;
        }

        String scoreErr = score(guild, game, submitterId, winningTeam, game.mvp);
        if (scoreErr != null) return scoreErr;

        applyMatchStats(game, matchData, igns);
        game.coralMatch = matchData.matchId;
        ctx.games.save(game);

        sendAutoScoreDetails(guild, game, matchData, winningTeam, igns);

        System.out.println("[autoscore] partita #" + game.number + " completata in "
                + (System.currentTimeMillis() - startedAt) + " ms (di cui "
                + fetchMillis + " ms di attesa CoralMC)");
        return null;
    }

    /**
     * Team vincitore, guardando solo i giocatori della partita. Su CoralMC serve
     * un host per aprire il game: quello sta spesso in un team suo (giallo o blu)
     * e non deve pesare sul risultato, né entrare nell'elo o negli MVP.
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

        // Il team indicato come vincitore non contiene nessuno della partita:
        // è quello dell'host. Si guarda allora l'esito dei soli giocatori veri.
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

        // Nessun giocatore della partita risulta vincitore (ha vinto l'host, o
        // il game è finito male): decide chi ha rotto il letto avversario.
        int team1Beds = bedsBrokenBy(game.team1, matchData);
        int team2Beds = bedsBrokenBy(game.team2, matchData);
        if (team1Beds > team2Beds) return 1;
        if (team2Beds > team1Beds) return 2;
        return 0;
    }

    private int bedsBrokenBy(List<String> ids, CoralMcService.CoralMatchData matchData) {
        int total = 0;
        for (String id : ids) {
            Player p = ctx.players.get(id);
            if (p == null) continue;
            CoralMcService.CoralPlayerStats ps = matchData.byUsername(p.ign);
            if (ps != null) total += ps.bedsBroken;
        }
        return total;
    }


    /**
     * Calcola gli MVP del match:
     * 1. 1 MVP al giocatore che ha distrutto il letto (bedsBroken > 0).
     * 2. 1 MVP al giocatore che ha fatto più kill regolari (kills) nel game.
     */
    /**
     * Al massimo due MVP, entrambi tra i vincitori: chi ha rotto più letti e chi
     * ha fatto più kill. Premiare chiunque avesse un letto o fosse pari al top
     * kill produceva tre MVP su quattro giocatori, uno dei quali sconfitto.
     */
    private List<String> calculateMvps(Game game, CoralMcService.CoralMatchData matchData,
                                       Map<String, String> igns, int winningTeam) {
        List<String> winners = winningTeam == 1 ? game.team1 : game.team2;

        String bedMvp = null;
        String killMvp = null;
        int bestBeds = 0;
        int bestKills = 0;

        for (String id : winners) {
            Player p = ctx.players.get(id);
            if (p == null) continue;
            CoralMcService.CoralPlayerStats ps = matchData.byUsername(p.ign);
            if (ps == null) continue;

            if (ps.bedsBroken > bestBeds) {
                bestBeds = ps.bedsBroken;
                bedMvp = id;
            }
            int kills = ps.kills + ps.finalKills;
            if (kills > bestKills) {
                bestKills = kills;
                killMvp = id;
            }
        }

        Set<String> mvps = new LinkedHashSet<>();
        if (bedMvp != null) mvps.add(bedMvp);
        if (killMvp != null) mvps.add(killMvp);
        return new ArrayList<>(mvps);
    }

    /**
     * Somma kill e morti del match. Il contatore MVP non si tocca qui: lo
     * assegna score(), altrimenti verrebbe contato due volte.
     */
    private void applyMatchStats(Game game, CoralMcService.CoralMatchData matchData, Map<String, String> igns) {
        game.killChanges.clear();
        game.deathChanges.clear();

        for (CoralMcService.CoralPlayerStats ps : matchData.perPlayerStats) {
            String id = igns.get(ps.username.toLowerCase());
            if (id == null) continue;
            Player p = ctx.players.get(id);
            if (p == null) continue;

            int totalKills = ps.kills + ps.finalKills;
            p.kills += totalKills;
            p.deaths += ps.deaths;
            ctx.players.save(p);

            game.killChanges.put(id, totalKills);
            game.deathChanges.put(id, ps.deaths);
        }
    }

    private void sendAutoScoreDetails(Guild guild, Game game, CoralMcService.CoralMatchData matchData,
                                      int winningTeam, Map<String, String> igns) {
        TextChannel channel = textChannelOrNull(guild, game.textChannel);
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

        // Gli MVP sono quelli già decisi da calculateMvps: ricalcolarli qui
        // sul totale dei giocatori includeva anche l'host del game.
        StringBuilder mvpInfo = new StringBuilder();
        for (String id : game.mvpIds()) {
            Player p = ctx.players.get(id);
            if (p == null) continue;
            CoralMcService.CoralPlayerStats ps = matchData.byUsername(p.ign);
            if (ps == null) continue;

            mvpInfo.append(ps.bedsBroken > 0 ? "🛌 " : "⚔️ ")
                    .append(mention(id))
                    .append(" — ").append(ps.kills + ps.finalKills).append(" kill");
            if (ps.bedsBroken > 0) mvpInfo.append(", ").append(ps.bedsBroken).append(" letti");
            mvpInfo.append('\n');
        }

        if (mvpInfo.length() > 0) {
            eb.addField("⭐ MVP", mvpInfo.toString(), false);
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

        List<String> winners = game.winner == 1 ? game.team1 : game.team2;
        List<String> losers = game.winner == 1 ? game.team2 : game.team1;
        List<String> mvps = game.mvpIds();

        byte[] banner = renderScoreBanner(game);
        EmbedBuilder eb = embeds.base(embeds.successColor())
                .setTitle("🏆 Partita #" + game.number + " — " + game.modeName().toUpperCase());

        // Con l'immagine elo e variazioni sono già lì: nell'embed bastano le
        // menzioni, altrimenti ogni nome va a capo e il messaggio raddoppia.
        if (banner != null) {
            eb.addField("🏆 Vincitori", mentions(winners), true);
            eb.addField("💀 Sconfitti", mentions(losers), true);
        } else {
            eb.addField("🏆 Vincitori", listPlayers(winners), true);
            eb.addField("💀 Sconfitti", listPlayers(losers), true);
        }

        if (!mvps.isEmpty()) eb.addField("⭐ MVP", mentions(mvps), false);

        if (banner != null) {
            eb.setImage("attachment://recap.png");
            channel.sendMessageEmbeds(eb.build())
                    .addFiles(FileUpload.fromData(banner, "recap.png"))
                    .queue();
        } else {
            channel.sendMessageEmbeds(eb.build()).queue();
        }
    }

    /** Solo le menzioni, una per riga. */
    private String mentions(List<String> ids) {
        if (ids.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (String id : ids) sb.append(mention(id)).append('\n');
        return sb.toString();
    }

    private List<ScoreImageService.Entry> buildEntries(Game game, List<String> playerIds) {
        List<ScoreImageService.Entry> entries = new ArrayList<>();
        for (String id : playerIds) {
            Player p = ctx.players.get(id);
            if (p == null) continue;
            int change = game.eloChanges.getOrDefault(id, 0);
            int currentElo = p.elo;
            int prevElo = currentElo - change;
            boolean isMvp = game.isMvp(id);
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
            return scoreImages.render(mapStyle(game), winEntries, lossEntries);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Annulla una partita non scorata. */
    public void voidGame(Guild guild, Game game) {
        game.state = Game.State.VOIDED;
        game.endedAt = System.currentTimeMillis();
        ctx.games.save(game);

        TextChannel channel = textChannelOrNull(guild, game.textChannel);
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
            } else if (losers.contains(p.id)) {
                p.losses = Math.max(0, p.losses - 1);
                p.lossstreak = Math.max(0, p.lossstreak - 1);
            }
            if (game.isMvp(p.id)) p.mvp = Math.max(0, p.mvp - 1);

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
        ctx.scheduler.schedule(() -> deleteGameChannels(guild, game.number),
                Math.max(1, seconds), TimeUnit.SECONDS);
    }

    /**
     * Elimina i canali delle partite già concluse. Il timer di scheduleCleanup
     * vive solo in memoria: se il bot si riavvia prima che scatti, i canali
     * resterebbero lì per sempre. Questa passata gira col controllo code.
     */
    private void cleanupFinishedGames(Guild guild) {
        long delay = ctx.config.getInt("game-deleting-time", 10) * 1000L;
        long now = System.currentTimeMillis();

        for (Game game : ctx.games.withChannelsToDelete()) {
            // endedAt = 0 sono partite chiuse da versioni precedenti: si puliscono subito.
            if (game.endedAt > 0 && now - game.endedAt < delay) continue;
            deleteGameChannels(guild, game.number);
        }
    }

    /** Cancella canale testuale e vocali della partita e li dimentica nel database. */
    private void deleteGameChannels(Guild guild, int gameNumber) {
        Game game = ctx.games.get(gameNumber);
        if (game == null) return;

        deleteChannel(textChannelOrNull(guild, game.textChannel));
        deleteChannel(voiceChannelOrNull(guild, game.vc1));
        deleteChannel(voiceChannelOrNull(guild, game.vc2));

        // Svuotare gli id evita di riprovare la cancellazione a ogni passata.
        game.textChannel = "";
        game.vc1 = "";
        game.vc2 = "";
        ctx.games.save(game);
    }

    /** getXChannelById esplode con un id vuoto, e i canali già puliti lo sono. */
    private TextChannel textChannelOrNull(Guild guild, String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return guild.getTextChannelById(id);
        } catch (Exception e) {
            return null;
        }
    }

    private VoiceChannel voiceChannelOrNull(Guild guild, String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return guild.getVoiceChannelById(id);
        } catch (Exception e) {
            return null;
        }
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
