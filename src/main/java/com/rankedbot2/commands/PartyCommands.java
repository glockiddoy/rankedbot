package com.rankedbot2.commands;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.CommandBase;
import com.rankedbot2.core.Embeds;
import com.rankedbot2.model.Party;
import com.rankedbot2.model.Player;
import com.rankedbot2.service.GameService;
import com.rankedbot2.service.PlayerService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.List;

public class PartyCommands extends CommandBase {

    public PartyCommands(BotContext ctx, Embeds embeds, PlayerService playerService, GameService gameService) {
        super(ctx, embeds, playerService, gameService);
    }

    @Override
    public List<SlashCommandData> data() {
        return List.of(Commands.slash("party", "Comandi party")
                .addSubcommands(
                        new SubcommandData("create", "Crea un party"),
                        new SubcommandData("invite", "Invita un giocatore nel tuo party")
                                .addOption(OptionType.USER, "giocatore", "Giocatore da invitare", true),
                        new SubcommandData("join", "Entra nel party di un giocatore")
                                .addOption(OptionType.USER, "leader", "Leader del party", true),
                        new SubcommandData("leave", "Esci dal tuo party"),
                        new SubcommandData("list", "Mostra le info di un party")
                                .addOption(OptionType.USER, "giocatore", "Membro del party", false),
                        new SubcommandData("promote", "Passa la leadership a un membro")
                                .addOption(OptionType.USER, "giocatore", "Nuovo leader", true),
                        new SubcommandData("warp", "Sposta i membri del party nel tuo vocale"),
                        new SubcommandData("kick", "Espelli un membro dal party")
                                .addOption(OptionType.USER, "giocatore", "Membro da espellere", true)));
    }

    @Override
    public boolean handles(String name) {
        return name.equals("party");
    }

    @Override
    public String permissionKey(SlashCommandInteractionEvent e) {
        return "party" + sub(e);
    }

    @Override
    public void execute(SlashCommandInteractionEvent e) {
        switch (sub(e)) {
            case "create" -> create(e);
            case "invite" -> invite(e);
            case "join" -> join(e);
            case "leave" -> leave(e);
            case "list" -> list(e);
            case "promote" -> promote(e);
            case "warp" -> warp(e);
            case "kick" -> kick(e);
            default -> fail(e, "Sottocomando sconosciuto");
        }
    }

    private void create(SlashCommandInteractionEvent e) {
        String userId = e.getUser().getId();
        if (ctx.partyOf(userId) != null) {
            fail(e, ctx.msg("already-in-party"));
            return;
        }
        ctx.parties.put(userId, new Party(userId));
        ok(e, ctx.msg("party-created"));
    }

    private void invite(SlashCommandInteractionEvent e) {
        Party party = ctx.partyOf(e.getUser().getId());
        if (party == null) {
            fail(e, ctx.msg("not-in-party"));
            return;
        }
        if (!party.leader.equals(e.getUser().getId())) {
            fail(e, ctx.msg("not-party-leader"));
            return;
        }

        int max = ctx.config.getInt("max-party-members", 3);
        if (party.size() >= max) {
            fail(e, ctx.msg("party-full"));
            return;
        }

        User target = e.getOption("giocatore").getAsUser();
        Player targetPlayer = player(target);
        if (targetPlayer == null) {
            fail(e, ctx.msg("invalid-player"));
            return;
        }
        if (ctx.partyOf(target.getId()) != null) {
            fail(e, "Questo giocatore è già in un party");
            return;
        }
        if (party.hasValidInvite(target.getId())) {
            fail(e, ctx.msg("player-already-invited"));
            return;
        }

        int minutes = ctx.config.getInt("invite-expiration", 3);
        party.invites.put(target.getId(), System.currentTimeMillis() + minutes * 60_000L);

        ok(e, target.getAsMention() + " è stato invitato. L'invito scade tra "
                + minutes + " minuti. Usa `/party join leader:" + e.getUser().getName() + "` per accettare.");
    }

    private void join(SlashCommandInteractionEvent e) {
        String userId = e.getUser().getId();
        if (ctx.partyOf(userId) != null) {
            fail(e, ctx.msg("already-in-party"));
            return;
        }

        User leader = e.getOption("leader").getAsUser();
        Party party = ctx.parties.get(leader.getId());
        if (party == null) {
            fail(e, ctx.msg("player-not-in-party"));
            return;
        }
        if (!party.hasValidInvite(userId)) {
            fail(e, ctx.msg("not-invited"));
            return;
        }

        int max = ctx.config.getInt("max-party-members", 3);
        if (party.size() >= max) {
            fail(e, ctx.msg("this-party-full"));
            return;
        }

        int maxElo = ctx.config.getInt("max-party-elo", Integer.MAX_VALUE);
        int totalElo = 0;
        for (String memberId : party.members) {
            Player member = player(memberId);
            if (member != null) totalElo += member.elo;
        }
        Player joining = player(userId);
        if (joining != null) totalElo += joining.elo;

        if (totalElo > maxElo) {
            fail(e, "Elo combinato del party troppo alto (max `" + maxElo + "`, sareste a `" + totalElo + "`)");
            return;
        }

        party.invites.remove(userId);
        party.members.add(userId);
        ok(e, ctx.msg("joined-party"));
    }

    private void leave(SlashCommandInteractionEvent e) {
        String userId = e.getUser().getId();
        Party party = ctx.partyOf(userId);
        if (party == null) {
            fail(e, ctx.msg("not-in-party"));
            return;
        }

        if (party.leader.equals(userId)) {
            ctx.parties.remove(userId);
            ok(e, ctx.msg("party-disbanded"));
            return;
        }

        party.members.remove(userId);
        ok(e, ctx.msg("party-left"));
    }

    private void list(SlashCommandInteractionEvent e) {
        User target = targetOrSelf(e, "giocatore");
        Party party = ctx.partyOf(target.getId());
        if (party == null) {
            fail(e, target.getId().equals(e.getUser().getId())
                    ? ctx.msg("not-in-party")
                    : ctx.msg("player-not-in-party"));
            return;
        }

        StringBuilder sb = new StringBuilder();
        int totalElo = 0;
        for (String memberId : party.members) {
            Player p = player(memberId);
            sb.append(GameService.mention(memberId));
            if (memberId.equals(party.leader)) sb.append(" *(leader)*");
            if (p != null) {
                sb.append(" `").append(p.elo).append("`");
                totalElo += p.elo;
            }
            sb.append('\n');
        }

        EmbedBuilder eb = embeds.builder()
                .setTitle("Party")
                .setDescription(sb.toString())
                .addField("Membri", party.size() + "/" + ctx.config.getInt("max-party-members", 3), true)
                .addField("Elo combinato", totalElo + "/" + ctx.config.getInt("max-party-elo", 0), true);
        reply(e, eb.build());
    }

    private void promote(SlashCommandInteractionEvent e) {
        Party party = ctx.partyOf(e.getUser().getId());
        if (party == null) {
            fail(e, ctx.msg("not-in-party"));
            return;
        }
        if (!party.leader.equals(e.getUser().getId())) {
            fail(e, ctx.msg("not-party-leader"));
            return;
        }

        User target = e.getOption("giocatore").getAsUser();
        if (!party.contains(target.getId())) {
            fail(e, ctx.msg("player-not-in-ur-party"));
            return;
        }

        ctx.parties.remove(party.leader);
        party.leader = target.getId();
        ctx.parties.put(party.leader, party);
        ok(e, target.getAsMention() + " è il nuovo leader del party");
    }

    private void warp(SlashCommandInteractionEvent e) {
        Party party = ctx.partyOf(e.getUser().getId());
        if (party == null) {
            fail(e, ctx.msg("not-in-party"));
            return;
        }
        if (!party.leader.equals(e.getUser().getId())) {
            fail(e, ctx.msg("not-party-leader"));
            return;
        }

        Guild guild = guild(e);
        Member self = e.getMember();
        if (self == null || self.getVoiceState() == null || !self.getVoiceState().inAudioChannel()) {
            fail(e, ctx.msg("couldnt-warp"));
            return;
        }

        AudioChannel target = self.getVoiceState().getChannel();
        int moved = 0;
        for (String memberId : party.members) {
            if (memberId.equals(party.leader)) continue;
            Member member = memberOf(guild, memberId);
            if (member != null && member.getVoiceState() != null && member.getVoiceState().inAudioChannel()) {
                guild.moveVoiceMember(member, target).queue(null, err -> {
                });
                moved++;
            }
        }

        if (moved == 0) {
            fail(e, ctx.msg("couldnt-warp"));
            return;
        }
        ok(e, "Spostati `" + moved + "` membri del party nel tuo vocale");
    }

    private void kick(SlashCommandInteractionEvent e) {
        Party party = ctx.partyOf(e.getUser().getId());
        if (party == null) {
            fail(e, ctx.msg("not-in-party"));
            return;
        }
        if (!party.leader.equals(e.getUser().getId())) {
            fail(e, ctx.msg("not-party-leader"));
            return;
        }

        User target = e.getOption("giocatore").getAsUser();
        if (target.getId().equals(party.leader)) {
            fail(e, "Non puoi espellere te stesso, usa `/party leave`");
            return;
        }
        if (!party.members.remove(target.getId())) {
            fail(e, ctx.msg("player-not-in-your-party"));
            return;
        }
        ok(e, ctx.msg("player-kicked-party"));
    }
}
