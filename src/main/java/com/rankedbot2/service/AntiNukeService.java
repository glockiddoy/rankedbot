package com.rankedbot2.service;

import com.rankedbot2.core.BotContext;
import com.rankedbot2.core.Embeds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class AntiNukeService extends ListenerAdapter {

    private final BotContext ctx;
    private final Embeds embeds;

    private final Map<String, List<Long>> channelDeletions = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> roleDeletions = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> banActions = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> kickActions = new ConcurrentHashMap<>();

    public AntiNukeService(BotContext ctx, Embeds embeds) {
        this.ctx = ctx;
        this.embeds = embeds;
    }

    private boolean isEnabled() {
        return ctx.config.getBoolean("anti-nuke.enabled", true);
    }

    private boolean isWhitelisted(Guild guild, User user) {
        if (user == null) return true;
        if (user.isBot() && user.getId().equals(guild.getJDA().getSelfUser().getId())) return true;
        if (guild.getOwnerId().equals(user.getId())) return true;

        List<String> whitelisted = ctx.config.getStringList("anti-nuke.whitelist-users");
        return whitelisted != null && whitelisted.contains(user.getId());
    }

    private int trackAndCheck(Map<String, List<Long>> tracker, String userId, int timeWindowSeconds) {
        long now = System.currentTimeMillis();
        long windowMillis = TimeUnit.SECONDS.toMillis(timeWindowSeconds);

        List<Long> timestamps = tracker.computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (timestamps) {
            timestamps.add(now);
            timestamps.removeIf(t -> (now - t) > windowMillis);
            return timestamps.size();
        }
    }

    @Override
    public void onChannelDelete(@NotNull ChannelDeleteEvent event) {
        if (!isEnabled()) return;
        Guild guild = event.getGuild();

        String channelName = event.getChannel().getName().toLowerCase();
        if (channelName.startsWith("game") || channelName.contains("team") || channelName.contains("party")) {
            return;
        }

        guild.retrieveAuditLogs().type(ActionType.CHANNEL_DELETE).limit(1).queue(logs -> {
            if (logs.isEmpty()) return;
            AuditLogEntry entry = logs.get(0);
            User user = entry.getUser();
            if (user == null || isWhitelisted(guild, user)) return;

            int maxAllowed = ctx.config.getInt("anti-nuke.max-channel-deletions", 3);
            int window = ctx.config.getInt("anti-nuke.time-window-seconds", 10);
            int count = trackAndCheck(channelDeletions, user.getId(), window);

            if (count >= maxAllowed) {
                punish(guild, user, "Eliminazione di massa di canali (" + count + " canali in " + window + "s)");
            }
        }, err -> {});
    }

    @Override
    public void onRoleDelete(@NotNull RoleDeleteEvent event) {
        if (!isEnabled()) return;
        Guild guild = event.getGuild();

        guild.retrieveAuditLogs().type(ActionType.ROLE_DELETE).limit(1).queue(logs -> {
            if (logs.isEmpty()) return;
            AuditLogEntry entry = logs.get(0);
            User user = entry.getUser();
            if (user == null || isWhitelisted(guild, user)) return;

            int maxAllowed = ctx.config.getInt("anti-nuke.max-role-deletions", 3);
            int window = ctx.config.getInt("anti-nuke.time-window-seconds", 10);
            int count = trackAndCheck(roleDeletions, user.getId(), window);

            if (count >= maxAllowed) {
                punish(guild, user, "Eliminazione di massa di ruoli (" + count + " ruoli in " + window + "s)");
            }
        }, err -> {});
    }

    @Override
    public void onGuildBan(@NotNull GuildBanEvent event) {
        if (!isEnabled()) return;
        Guild guild = event.getGuild();

        guild.retrieveAuditLogs().type(ActionType.BAN).limit(1).queue(logs -> {
            if (logs.isEmpty()) return;
            AuditLogEntry entry = logs.get(0);
            User user = entry.getUser();
            if (user == null || isWhitelisted(guild, user)) return;

            int maxAllowed = ctx.config.getInt("anti-nuke.max-bans", 3);
            int window = ctx.config.getInt("anti-nuke.time-window-seconds", 10);
            int count = trackAndCheck(banActions, user.getId(), window);

            if (count >= maxAllowed) {
                punish(guild, user, "Ban di massa di utenti (" + count + " ban in " + window + "s)");
            }
        }, err -> {});
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        if (!isEnabled()) return;
        Guild guild = event.getGuild();

        guild.retrieveAuditLogs().type(ActionType.KICK).limit(1).queue(logs -> {
            if (logs.isEmpty()) return;
            AuditLogEntry entry = logs.get(0);
            User user = entry.getUser();
            if (user == null || isWhitelisted(guild, user)) return;

            int maxAllowed = ctx.config.getInt("anti-nuke.max-kicks", 3);
            int window = ctx.config.getInt("anti-nuke.time-window-seconds", 10);
            int count = trackAndCheck(kickActions, user.getId(), window);

            if (count >= maxAllowed) {
                punish(guild, user, "Kick di massa di utenti (" + count + " kick in " + window + "s)");
            }
        }, err -> {});
    }

    private void punish(Guild guild, User targetUser, String reason) {
        guild.retrieveMember(targetUser).queue(member -> {
            if (member == null) return;

            List<Role> rolesToStrip = new ArrayList<>();
            for (Role r : member.getRoles()) {
                if (r.hasPermission(Permission.ADMINISTRATOR) || r.hasPermission(Permission.MANAGE_SERVER) ||
                        r.hasPermission(Permission.MANAGE_CHANNEL) || r.hasPermission(Permission.MANAGE_ROLES) ||
                        r.hasPermission(Permission.BAN_MEMBERS) || r.hasPermission(Permission.KICK_MEMBERS)) {
                    rolesToStrip.add(r);
                }
            }

            if (!rolesToStrip.isEmpty()) {
                guild.modifyMemberRoles(member, null, rolesToStrip).queue(null, err -> {});
            }

            String action = ctx.config.getString("anti-nuke.action", "STRIP_ROLES").toUpperCase();
            if ("BAN".equals(action)) {
                guild.ban(member, 7, TimeUnit.DAYS).reason("Anti-Nuke Protection: " + reason).queue(null, err -> {});
            } else if ("KICK".equals(action)) {
                guild.kick(member).reason("Anti-Nuke Protection: " + reason).queue(null, err -> {});
            }

            alertStaff(guild, targetUser, reason, action);
        }, err -> alertStaff(guild, targetUser, reason, "NON_DISPONIBILE"));
    }

    private void alertStaff(Guild guild, User user, String reason, String actionTaken) {
        long alertsChannelId = ctx.config.getId("alerts-channel");
        if (alertsChannelId == 0) alertsChannelId = ctx.config.getId("ban-channel");

        TextChannel channel = alertsChannelId != 0 ? guild.getTextChannelById(alertsChannelId) : null;
        if (channel == null) return;

        EmbedBuilder eb = embeds.base(embeds.errorColor())
                .setTitle("🚨 ALLERTA ANTI-NUKE SERVER")
                .setDescription("Rilevata attività sospetta o tentativo di nuke sul server!")
                .addField("🔴 Utente Sospetto", user.getAsMention() + " (`" + user.getAsTag() + "`)", true)
                .addField("📌 Motivo", reason, false)
                .addField("⚡ Azione Intrappresa", "`" + actionTaken + "` (Ruoli amministrativi revocati)", false);

        channel.sendMessage("<@" + guild.getOwnerId() + ">").setEmbeds(eb.build()).queue();
    }
}
