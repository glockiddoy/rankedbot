package com.rankedbot2.service;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.model.Clan;
import com.rankedbot2.model.ClanWar;
import com.rankedbot2.model.Game;
import com.rankedbot2.model.GameMap;
import com.rankedbot2.model.Player;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/** Accoppia i clan registrati e crea le partite di clan war. */
public class ClanWarService {

    private final BotContext ctx;
    private final GameService gameService;
    private final Embeds embeds;
    private final Random random = new Random();
    private int lastWarNumber;

    public ClanWarService(BotContext ctx, GameService gameService, Embeds embeds) {
        this.ctx = ctx;
        this.gameService = gameService;
        this.embeds = embeds;
    }

    public int nextNumber() {
        return ++lastWarNumber;
    }

    /**
     * Accoppia i clan a due a due nell'ordine di registrazione e crea una partita
     * per ogni coppia. Ritorna i numeri delle partite create.
     */
    public List<Integer> startWar(Guild guild, ClanWar war) {
        List<Integer> created = new ArrayList<>();
        List<Integer> clanIds = war.clanIds();

        for (int i = 0; i + 1 < clanIds.size(); i += 2) {
            int clan1Id = clanIds.get(i);
            int clan2Id = clanIds.get(i + 1);

            List<String> team1 = war.registrations.get(clan1Id);
            List<String> team2 = war.registrations.get(clan2Id);
            if (team1 == null || team2 == null) continue;

            Game game = createBattle(guild, war, clan1Id, clan2Id, team1, team2);
            if (game != null) created.add(game.number);
        }
        return created;
    }

    private Game createBattle(Guild guild, ClanWar war, int clan1Id, int clan2Id,
                              List<String> team1, List<String> team2) {
        Clan clan1 = ctx.clans.get(clan1Id);
        Clan clan2 = ctx.clans.get(clan2Id);
        if (clan1 == null || clan2 == null) return null;

        Game game = new Game();
        game.number = ctx.games.nextNumber();
        game.playersEachTeam = war.playersInTeam;
        game.state = Game.State.STARTED;
        game.createdAt = System.currentTimeMillis();
        game.clanWar = war.number;
        game.clan1 = clan1Id;
        game.clan2 = clan2Id;
        game.team1.addAll(team1);
        game.team2.addAll(team2);

        List<GameMap> maps = ctx.maps.forMode(war.playersInTeam);
        game.map = maps.isEmpty() ? "" : maps.get(random.nextInt(maps.size())).name;

        Category textCategory = categoryOrNull(guild, "game-channels-category");
        Category vcCategory = categoryOrNull(guild, "game-vcs-category");

        String channelName = "cw" + war.number + "-" + game.number;
        var textAction = textCategory != null
                ? guild.createTextChannel(channelName, textCategory)
                : guild.createTextChannel(channelName);

        textAction = textAction.addPermissionOverride(
                guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL));
        for (net.dv8tion.jda.api.entities.Role staffRole : gameService.getStaffRoles(guild)) {
            textAction = textAction.addRolePermissionOverride(
                    staffRole.getIdLong(),
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND),
                    null);
        }
        for (String id : game.allPlayers()) {
            textAction = textAction.addMemberPermissionOverride(
                    Long.parseLong(id),
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND),
                    null);
        }

        TextChannel textChannel = textAction.complete();
        game.textChannel = textChannel.getId();

        VoiceChannel vc1 = createVc(guild, vcCategory, "CW#" + game.number + " | " + clan1.name);
        VoiceChannel vc2 = createVc(guild, vcCategory, "CW#" + game.number + " | " + clan2.name);
        game.vc1 = vc1.getId();
        game.vc2 = vc2.getId();

        ctx.games.save(game);

        gameService.setupVcPermissions(guild, game);
        moveAll(guild, game.team1, vc1);
        moveAll(guild, game.team2, vc2);

        textChannel.sendMessageEmbeds(embeds.builder()
                .setTitle("Clan war #" + war.number + " — Partita #" + game.number)
                .addField(clan1.name, gameService.listPlayers(game.team1), true)
                .addField(clan2.name, gameService.listPlayers(game.team2), true)
                .addField("Mappa", game.map.isEmpty() ? "—" : game.map, false)
                .addField("Come chiudere", "Il vincitore usa `/submit` allegando gli screenshot.", false)
                .build()).queue();

        return game;
    }

    private VoiceChannel createVc(Guild guild, Category category, String name) {
        var action = category != null
                ? guild.createVoiceChannel(name, category)
                : guild.createVoiceChannel(name);
        return action.complete();
    }

    private Category categoryOrNull(Guild guild, String key) {
        long id = ctx.config.getId(key);
        return id == 0 ? null : guild.getCategoryById(id);
    }

    private void moveAll(Guild guild, List<String> ids, VoiceChannel target) {
        for (String id : ids) {
            Member m = guild.getMemberById(id);
            if (m != null && m.getVoiceState() != null && m.getVoiceState().inAudioChannel()) {
                guild.moveVoiceMember(m, target).queue(null, err -> {
                });
            }
        }
    }

    /** Gold di vittoria della clan war, assegnato ai giocatori del clan vincitore. */
    public void rewardWinners(ClanWar war, int winningClanId) {
        List<String> roster = war.registrations.get(winningClanId);
        if (roster == null) return;

        for (String id : roster) {
            Player p = ctx.players.get(id);
            if (p == null) continue;
            p.gold += war.winGold;
            ctx.players.save(p);
        }

        Clan clan = ctx.clans.get(winningClanId);
        if (clan != null) {
            clan.xp += war.winXp;
            clan.level = ctx.clanLevels.levelFor(clan.xp);
            ctx.clans.save(clan);
        }
    }
}
