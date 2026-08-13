package com.peppone;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class TicketService {

    private static final String TOPIC_PREFIX = "ticket:";
    private final Config config;
    private final File counterFile;

    public TicketService(Config config, File baseDir) {
        this.config = config;
        this.counterFile = new File(baseDir, "counter.txt");
    }

    public TextChannel openTicketOf(Guild guild, String userId) {
        if (guild == null || userId == null) return null;
        for (TextChannel channel : guild.getTextChannels()) {
            String topic = channel.getTopic();
            if (topic != null && topic.startsWith(TOPIC_PREFIX + userId)) {
                return channel;
            }
        }
        return null;
    }

    public boolean isTicket(TextChannel channel) {
        return channel != null && channel.getTopic() != null && channel.getTopic().startsWith(TOPIC_PREFIX);
    }

    public String openerOf(TextChannel channel) {
        if (!isTicket(channel)) return null;
        String sub = channel.getTopic().substring(TOPIC_PREFIX.length());
        int idx = sub.indexOf(':');
        return idx != -1 ? sub.substring(0, idx) : sub;
    }

    public Texts.Lang langOf(TextChannel channel) {
        if (!isTicket(channel)) return Texts.Lang.IT;
        String topic = channel.getTopic();
        return topic != null && topic.endsWith(":en") ? Texts.Lang.EN : Texts.Lang.IT;
    }

    public TextChannel create(Guild guild, Member member, com.peppone.Category category, Texts.Lang lang, String problem, String extra) {
        Category catChannel = categoryChannel(guild);

        int number = nextNumber();
        String channelName = config.getString("ticket-name", "ticket-%number%")
                .replace("%number%", String.valueOf(number))
                .replace("%user%", member.getUser().getName())
                .replace("%category%", category.id);

        var action = (catChannel != null)
                ? catChannel.createTextChannel(channelName)
                : guild.createTextChannel(channelName);

        String topic = TOPIC_PREFIX + member.getId() + ":" + (lang == Texts.Lang.EN ? "en" : "it");
        action.setTopic(topic);

        // Permessi: nascondi a @everyone
        action.addPermissionOverride(guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL));

        // Permessi utente: visualizza e invia messaggi
        action.addPermissionOverride(member,
                EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY),
                null);

        // Permessi Staff
        List<Role> staff = staffRoles(guild);
        for (Role r : staff) {
            action.addPermissionOverride(r,
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE),
                    null);
        }

        TextChannel textChannel = action.complete();
        sendWelcome(textChannel, member, category, lang, problem, extra, number);
        return textChannel;
    }

    private void sendWelcome(TextChannel channel, Member member, com.peppone.Category category, Texts.Lang lang, String problem, String extra, int number) {
        Color embedColor = config.getColor("embed-color", new Color(88, 101, 242));

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(embedColor)
                .setTitle(Texts.welcomeTitle(lang, number))
                .setDescription(Texts.welcomeBody(lang))
                .addField(category.emoji + " " + Texts.fieldCategory(lang), category.label(lang), true)
                .addField("👤 " + Texts.fieldOpenedBy(lang), member.getAsMention(), true)
                .addField("📝 " + Texts.problemLabel(lang), cut(problem, 1000), false);

        if (extra != null && !extra.isBlank()) {
            eb.addField("ℹ️ " + Texts.extraLabel(lang), cut(extra, 1000), false);
        }

        String footerText = config.getString("footer", "Peppone · Supporto");
        if (!footerText.isEmpty()) {
            eb.setFooter(footerText);
        }

        Button closeBtn = Button.danger("close_ticket", Texts.closeButton(lang)).withEmoji(Emoji.fromUnicode("🔒"));

        String mentions = staffMentions(channel.getGuild());
        if (!mentions.isEmpty()) {
            channel.sendMessage(mentions).setEmbeds(eb.build()).setActionRow(closeBtn).queue();
        } else {
            channel.sendMessageEmbeds(eb.build()).setActionRow(closeBtn).queue();
        }
    }

    public void close(TextChannel channel, Member closedBy, Texts.Lang lang) {
        long logChannelId = config.getId("log-channel");
        if (logChannelId != 0L && channel.getGuild() != null) {
            TextChannel logCh = channel.getGuild().getTextChannelById(logChannelId);
            if (logCh != null) {
                String openerId = openerOf(channel);
                Member opener = openerId != null ? channel.getGuild().getMemberById(openerId) : null;
                String openerName = opener != null ? opener.getAsMention() : (openerId != null ? "<@" + openerId + ">" : "Sconosciuto");

                EmbedBuilder eb = new EmbedBuilder()
                        .setColor(Color.RED)
                        .setTitle("📁 Ticket Chiuso — " + channel.getName())
                        .addField("👤 Aperto da", openerName, true)
                        .addField("🛡️ Chiuso da", closedBy.getAsMention(), true)
                        .setTimestamp(java.time.Instant.now());

                logCh.sendMessageEmbeds(eb.build()).queue(null, err -> {});
            }
        }

        String username = closedBy.getEffectiveName();
        channel.sendMessage(Texts.closedBy(lang, username)).queue();

        int delay = config.getInt("close-delay", 5);
        channel.delete().queueAfter(delay, TimeUnit.SECONDS, null, err -> {});
    }

    public boolean canClose(TextChannel channel, Member member) {
        if (!isTicket(channel) || member == null) return false;
        if (member.hasPermission(Permission.MANAGE_CHANNEL) || member.hasPermission(Permission.ADMINISTRATOR)) return true;

        String openerId = openerOf(channel);
        if (member.getId().equals(openerId)) return true;

        List<Role> staff = staffRoles(channel.getGuild());
        for (Role r : staff) {
            if (member.getRoles().contains(r)) return true;
        }
        return false;
    }

    public Category categoryChannel(Guild guild) {
        if (guild == null) return null;
        long id = config.getId("ticket-category");
        if (id != 0L) {
            Category cat = guild.getCategoryById(id);
            if (cat != null) return cat;
        }

        String name = config.getString("ticket-category-name", "ticket and support");
        for (Category cat : guild.getCategories()) {
            if (cat.getName().equalsIgnoreCase(name)) {
                return cat;
            }
        }
        return null;
    }

    public List<Role> staffRoles(Guild guild) {
        List<Role> list = new ArrayList<>();
        if (guild == null) return list;

        List<String> rawRoles = config.getList("staff-roles");
        if (rawRoles.isEmpty()) {
            rawRoles = Arrays.asList("Admin", "Moderator", "Helper");
        }

        for (String entry : rawRoles) {
            String clean = entry.replaceAll("[^0-9]", "");
            if (!clean.isEmpty()) {
                try {
                    Role r = guild.getRoleById(Long.parseLong(clean));
                    if (r != null && !list.contains(r)) {
                        list.add(r);
                        continue;
                    }
                } catch (NumberFormatException ignored) {}
            }
            for (Role r : guild.getRoles()) {
                if (r.getName().equalsIgnoreCase(entry) && !list.contains(r)) {
                    list.add(r);
                }
            }
        }
        return list;
    }

    private String staffMentions(Guild guild) {
        if (!config.getBoolean("ping-staff", true)) return "";
        StringBuilder sb = new StringBuilder();
        for (Role r : staffRoles(guild)) {
            sb.append(r.getAsMention()).append(" ");
        }
        return sb.toString().trim();
    }

    private synchronized int nextNumber() {
        int count = 0;
        if (counterFile.exists()) {
            try {
                String str = Files.readString(counterFile.toPath(), StandardCharsets.UTF_8).trim();
                count = Integer.parseInt(str);
            } catch (Exception ignored) {}
        }
        count++;
        try {
            if (counterFile.getParentFile() != null) counterFile.getParentFile().mkdirs();
            Files.writeString(counterFile.toPath(), String.valueOf(count), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[TicketService] Errore nella scrittura di counter.txt: " + e.getMessage());
        }
        return count;
    }

    private static String cut(String text, int maxLen) {
        if (text == null || text.isBlank()) return "—";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "…";
    }
}
