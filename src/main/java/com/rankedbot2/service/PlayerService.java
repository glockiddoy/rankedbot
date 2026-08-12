package com.rankedbot2.service;

import com.rankedbot2.config.Levels;
import com.rankedbot2.core.BotContext;
import com.rankedbot2.model.Player;
import com.rankedbot2.model.Rank;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.List;

/** Elo, XP, livelli, nickname e ruoli rank. */
public class PlayerService {

    private final BotContext ctx;

    public PlayerService(BotContext ctx) {
        this.ctx = ctx;
    }

    public int startingElo() {
        return ctx.config.getInt("starting-elo", 0);
    }

    /** Applica una variazione di elo, aggiornando anche il picco. Ritorna la variazione reale. */
    public int addElo(Player p, int delta) {
        int before = p.elo;
        p.elo = Math.max(0, p.elo + delta);
        if (p.elo > p.peakElo) p.peakElo = p.elo;
        return p.elo - before;
    }

    /** Aggiunge XP e assegna le ricompense dei livelli superati. Ritorna il nuovo livello. */
    public int addXp(Player p, int amount) {
        if (!ctx.config.getBoolean("levels-enabled", true)) return p.level;

        p.xp += amount;
        int newLevel = ctx.levels.levelFor(p.xp);
        if (newLevel > p.level) {
            for (int lvl = p.level + 1; lvl <= newLevel; lvl++) {
                p.gold += ctx.levels.goldRewardFor(lvl);
            }
            p.level = newLevel;
        }
        return p.level;
    }

    public int xpForNextLevel(Player p) {
        return ctx.levels.xpForNextLevel(p.level);
    }

    /** Elo guadagnato/perso in base al rank corrente del giocatore. */
    public int winEloFor(Player p) {
        Rank rank = ctx.ranks.forElo(p.elo);
        return rank == null ? 0 : rank.winElo;
    }

    public int loseEloFor(Player p) {
        Rank rank = ctx.ranks.forElo(p.elo);
        return rank == null ? 0 : rank.loseElo;
    }

    /**
     * Moltiplicatore elo in base alla dimensione del party del giocatore.
     * config.yml: solo-multiplier, party-multiplier-N
     */
    public double multiplierFor(String userId) {
        var party = ctx.partyOf(userId);
        int size = party == null ? 1 : party.size();
        if (size <= 1) return ctx.config.getDouble("solo-multiplier", 1.0);
        return ctx.config.getDouble("party-multiplier-" + size, 1.0);
    }

    /** Aggiorna nickname (prefisso elo) e ruoli rank del membro. */
    public void updateMember(Guild guild, Member member, Player p) {
        if (guild == null || member == null || p == null) return;
        updateNickname(guild, member, p);
        updateRankRoles(guild, member, p);
        updateRegisteredRole(guild, member);
    }

    private void updateNickname(Guild guild, Member member, Player p) {
        // Senza getRawString lo spazio finale di "[%elo%] " sparirebbe e il
        // nickname uscirebbe attaccato: "[0]Nome".
        String format = ctx.config.getRawString("elo-formatting", "[%elo%] ");
        String nick = format.replace("%elo%", String.valueOf(p.elo)) + p.ign;
        if (nick.length() > 32) nick = nick.substring(0, 32);

        if (!guild.getSelfMember().canInteract(member)) return;
        if (nick.equals(member.getNickname())) return;
        try {
            member.modifyNickname(nick).queue(null, err -> {
            });
        } catch (Exception ignored) {
        }
    }

    private void updateRankRoles(Guild guild, Member member, Player p) {
        List<Rank> allRanks = ctx.ranks.all();
        if (allRanks.isEmpty()) return;

        Rank target = ctx.ranks.forElo(p.elo);
        for (Rank r : allRanks) {
            Role role = guild.getRoleById(r.roleId);
            if (role == null || !guild.getSelfMember().canInteract(role)) continue;

            boolean shouldHave = target != null && target.roleId.equals(r.roleId);
            boolean hasRole = member.getRoles().contains(role);

            if (shouldHave && !hasRole) {
                guild.addRoleToMember(member, role).queue(null, err -> {
                });
            } else if (!shouldHave && hasRole) {
                guild.removeRoleFromMember(member, role).queue(null, err -> {
                });
            }
        }
    }

    private void updateRegisteredRole(Guild guild, Member member) {
        long roleId = ctx.config.getId("registered-role");
        if (roleId == 0) return;
        Role role = guild.getRoleById(roleId);
        if (role == null || !guild.getSelfMember().canInteract(role)) return;
        if (!member.getRoles().contains(role)) {
            guild.addRoleToMember(member, role).queue(null, err -> {
            });
        }
    }

    /** Nome del rank corrente, per l'immagine stats e gli embed. */
    public String rankNameOf(Guild guild, Player p) {
        Rank rank = ctx.ranks.forElo(p.elo);
        if (rank == null || guild == null) return "";
        Role role = guild.getRoleById(rank.roleId);
        return role == null ? "" : role.getName();
    }

    public Levels levels() {
        return ctx.levels;
    }
}
