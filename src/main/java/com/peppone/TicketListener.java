package com.peppone;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.awt.Color;
import java.util.concurrent.ExecutorService;

public class TicketListener extends ListenerAdapter {

    private final Config config;
    private final TicketService tickets;
    private final ExecutorService worker;

    public TicketListener(Config config, TicketService tickets, ExecutorService worker) {
        this.config = config;
        this.tickets = tickets;
        this.worker = worker;
    }

    @Override
    public void onReady(ReadyEvent event) {
        // Registra il comando slash /panel su tutti i server in cui si trova il bot
        var panelCmd = Commands.slash("panel", "Pubblica il pannello per aprire i ticket di supporto")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));

        event.getJDA().updateCommands().addCommands(panelCmd).queue(
                success -> System.out.println("[Peppone] Comando /panel registrato con successo su Discord!"),
                err -> System.err.println("[Peppone] Errore nella registrazione del comando /panel: " + err.getMessage())
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equalsIgnoreCase("panel")) return;

        if (!event.getMember().hasPermission(Permission.MANAGE_SERVER) && !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            event.reply("Non hai i permessi per usare questo comando (richiesto Gestisci Server).").setEphemeral(true).queue();
            return;
        }

        // Rispondi e crea il pannello nel canale
        event.replyEmbeds(panelEmbed(event.getGuild()).build())
                .addActionRow(
                        Button.primary("lang_it", "🇮🇹 Italiano"),
                        Button.primary("lang_en", "🇬🇧 English")
                )
                .queue();
    }

    private EmbedBuilder panelEmbed(Guild guild) {
        Color color = config.getColor("embed-color", new Color(88, 101, 242));
        String rulesCh = channelMention(config.getString("rules-channel", ""));
        String faqCh = channelMention(config.getString("faq-channel", ""));

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(color)
                .setTitle(Texts.panelTitle(Texts.Lang.IT))
                .setDescription(Texts.panelDescription(Texts.Lang.IT, rulesCh, faqCh));

        String thumb = config.getString("panel-thumbnail", "");
        if (!thumb.isEmpty()) {
            eb.setThumbnail(thumb);
        }

        String footer = config.getString("footer", "Peppone · Supporto");
        if (!footer.isEmpty()) {
            eb.setFooter(footer);
        }
        return eb;
    }

    private String channelMention(String channelId) {
        if (channelId == null || channelId.isBlank()) return null;
        String clean = channelId.replaceAll("[^0-9]", "");
        return clean.isEmpty() ? null : "<#" + clean + ">";
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String btnId = event.getButton().getId();
        if (btnId == null) return;

        if (btnId.equals("lang_it") || btnId.equals("lang_en")) {
            Texts.Lang lang = btnId.equals("lang_en") ? Texts.Lang.EN : Texts.Lang.IT;
            Guild guild = event.getGuild();
            Member member = event.getMember();

            if (guild == null || member == null) return;

            // Verifica se la categoria ticket è configurata
            if (tickets.categoryChannel(guild) == null) {
                event.reply(Texts.noCategoryConfigured(lang)).setEphemeral(true).queue();
                return;
            }

            // Verifica se l'utente ha già un ticket aperto
            TextChannel existing = tickets.openTicketOf(guild, member.getId());
            if (existing != null) {
                event.reply(Texts.alreadyOpen(lang, existing.getAsMention())).setEphemeral(true).queue();
                return;
            }

            // Mostra menu a tendina delle categorie
            StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("select_category:" + (lang == Texts.Lang.EN ? "en" : "it"))
                    .setPlaceholder(Texts.selectCategoryPlaceholder(lang));

            for (Category cat : Category.values()) {
                menuBuilder.addOption(cat.label(lang), cat.id, cat.description(lang), net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode(cat.emoji));
            }

            event.reply(Texts.modalTitle(lang))
                    .setEphemeral(true)
                    .addActionRow(menuBuilder.build())
                    .queue();
            return;
        }

        if (btnId.equals("close_ticket")) {
            TextChannel channel = event.getChannel().asTextChannel();
            Member member = event.getMember();
            Texts.Lang lang = tickets.langOf(channel);

            if (!tickets.canClose(channel, member)) {
                event.reply(Texts.cannotClose(lang)).setEphemeral(true).queue();
                return;
            }

            event.deferEdit().queue();
            tickets.close(channel, member, lang);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String compId = event.getComponentId();
        if (!compId.startsWith("select_category:")) return;

        Texts.Lang lang = compId.endsWith(":en") ? Texts.Lang.EN : Texts.Lang.IT;
        String selectedCategoryId = event.getValues().isEmpty() ? "other" : event.getValues().get(0);
        Category category = Category.byId(selectedCategoryId);

        TextInput problemInput = TextInput.create("problem", Texts.problemLabel(lang), TextInputStyle.PARAGRAPH)
                .setPlaceholder(Texts.problemPlaceholder(lang))
                .setRequired(true)
                .setMinLength(5)
                .setMaxLength(1000)
                .build();

        TextInput extraInput = TextInput.create("extra", Texts.extraLabel(lang), TextInputStyle.PARAGRAPH)
                .setPlaceholder(Texts.extraPlaceholder(lang))
                .setRequired(false)
                .setMaxLength(1000)
                .build();

        Modal modal = Modal.create("ticket_modal:" + (lang == Texts.Lang.EN ? "en" : "it") + ":" + category.id, category.label(lang))
                .addActionRow(problemInput)
                .addActionRow(extraInput)
                .build();

        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String modalId = event.getModalId();
        if (!modalId.startsWith("ticket_modal:")) return;

        String[] parts = modalId.split(":");
        Texts.Lang lang = (parts.length > 1 && parts[1].equalsIgnoreCase("en")) ? Texts.Lang.EN : Texts.Lang.IT;
        Category category = (parts.length > 2) ? Category.byId(parts[2]) : Category.OTHER;

        String problem = value(event, "problem");
        String extra = value(event, "extra");

        Guild guild = event.getGuild();
        Member member = event.getMember();

        if (guild == null || member == null) return;

        event.deferReply(true).queue();

        worker.execute(() -> {
            try {
                TextChannel createdChannel = tickets.create(guild, member, category, lang, problem, extra);
                event.getHook().sendMessage(Texts.ticketOpened(lang, createdChannel.getAsMention())).queue();
            } catch (Exception e) {
                System.err.println("[Peppone] Errore creazione ticket: " + e.getMessage());
                event.getHook().sendMessage(Texts.creationFailed(lang, e.getMessage())).queue();
            }
        });
    }

    private static String value(ModalInteractionEvent event, String id) {
        var opt = event.getValue(id);
        return opt != null ? opt.getAsString() : "";
    }
}
