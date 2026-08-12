package com.rankedbot2.core;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;

public class Embeds {

    private final BotContext ctx;

    public Embeds(BotContext ctx) {
        this.ctx = ctx;
    }

    public EmbedBuilder base(Color color) {
        EmbedBuilder eb = new EmbedBuilder().setColor(color);
        String footer = ctx.config.getString("footer");
        if (!footer.isEmpty()) {
            eb.setFooter(footer
                    .replace("%version%", BotContext.VERSION)
                    .replace("{%version}", BotContext.VERSION)
                    .replace("%name%", ctx.config.getString("server-name", "RankedBot"))
                    .replace("{%name}", ctx.config.getString("server-name", "RankedBot")));
        }
        return eb;
    }

    public Color defaultColor() {
        return ctx.config.getColor("default", new Color(35, 35, 35));
    }

    public Color successColor() {
        return ctx.config.getColor("success", new Color(116, 227, 121));
    }

    public Color errorColor() {
        return ctx.config.getColor("error", new Color(227, 93, 93));
    }

    public MessageEmbed info(String description) {
        String content = description.startsWith("ℹ️") || description.startsWith("⚡") || description.startsWith("📌")
                ? description : "ℹ️ " + description;
        return base(defaultColor()).setDescription(content).build();
    }

    public MessageEmbed success(String description) {
        String content = description.startsWith("✅") || description.startsWith("🎉") || description.startsWith("🏆")
                ? description : "✅ " + description;
        return base(successColor()).setDescription(content).build();
    }

    public MessageEmbed error(String description) {
        String content = description.startsWith("❌") || description.startsWith("⚠️") || description.startsWith("🔴")
                ? description : "❌ " + description;
        return base(errorColor()).setDescription(content).build();
    }

    public EmbedBuilder builder() {
        return base(defaultColor());
    }
}
