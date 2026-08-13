package com.peppone;

public class Texts {

    public enum Lang {
        EN, IT
    }

    public static String panelTitle(Lang lang) {
        return lang == Lang.EN ? "Support & Tickets" : "Supporto & Ticket";
    }

    public static String panelDescription(Lang lang, String rulesMention, String faqMention) {
        StringBuilder sb = new StringBuilder();
        if (lang == Lang.EN) {
            sb.append("Need help or want to open a report?\n");
            sb.append("Click a button below to select your language and start.\n\n");
            if (rulesMention != null && !rulesMention.isBlank()) {
                sb.append("📜 Read the rules in ").append(rulesMention).append("\n");
            }
            if (faqMention != null && !faqMention.isBlank()) {
                sb.append("❓ Check FAQ in ").append(faqMention).append("\n");
            }
        } else {
            sb.append("Hai bisogno di aiuto o vuoi fare una segnalazione?\n");
            sb.append("Clicca un pulsante qui sotto per scegliere la lingua e iniziare.\n\n");
            if (rulesMention != null && !rulesMention.isBlank()) {
                sb.append("📜 Leggi il regolamento in ").append(rulesMention).append("\n");
            }
            if (faqMention != null && !faqMention.isBlank()) {
                sb.append("❓ Consulta le FAQ in ").append(faqMention).append("\n");
            }
        }
        return sb.toString().trim();
    }

    public static String selectLanguagePlaceholder(Lang lang) {
        return lang == Lang.EN ? "Select your language" : "Seleziona la lingua";
    }

    public static String selectCategoryPlaceholder(Lang lang) {
        return lang == Lang.EN ? "Select category" : "Seleziona la categoria";
    }

    public static String modalTitle(Lang lang) {
        return lang == Lang.EN ? "Choose a category!" : "Scegli la categoria!";
    }

    public static String problemLabel(Lang lang) {
        return lang == Lang.EN ? "Describe the problem" : "Descrivi il problema";
    }

    public static String problemPlaceholder(Lang lang) {
        return lang == Lang.EN ? "Please describe your problem." : "Perfavore descrivi il problema.";
    }

    public static String extraLabel(Lang lang) {
        return lang == Lang.EN ? "Any other information" : "Qualunque altra informazione";
    }

    public static String extraPlaceholder(Lang lang) {
        return lang == Lang.EN ? "Write your answer here..." : "Scrivi qui la tua risposta...";
    }

    public static String ticketOpened(Lang lang, String channelMention) {
        return lang == Lang.EN
                ? "Your ticket has been created: " + channelMention
                : "Il tuo ticket è stato creato: " + channelMention;
    }

    public static String alreadyOpen(Lang lang, String channelMention) {
        return lang == Lang.EN
                ? "You already have an open ticket: " + channelMention
                : "Hai già un ticket aperto: " + channelMention;
    }

    public static String welcomeTitle(Lang lang, int number) {
        return "Ticket #" + number;
    }

    public static String welcomeBody(Lang lang) {
        return lang == Lang.EN
                ? "A staff member will be with you shortly. Add screenshots or match links if you have them."
                : "Uno staffer ti risponderà a breve. Se li hai, allega screenshot o link della partita.";
    }

    public static String fieldCategory(Lang lang) {
        return lang == Lang.EN ? "Category" : "Categoria";
    }

    public static String fieldOpenedBy(Lang lang) {
        return lang == Lang.EN ? "Opened by" : "Aperto da";
    }

    public static String closeButton(Lang lang) {
        return lang == Lang.EN ? "Close ticket" : "Chiudi ticket";
    }

    public static String closedBy(Lang lang, String username) {
        return lang == Lang.EN
                ? "Ticket closing in 5 seconds (closed by " + username + ")..."
                : "Chiusura ticket in 5 secondi (chiuso da " + username + ")...";
    }

    public static String cannotClose(Lang lang) {
        return lang == Lang.EN
                ? "Only the staff or whoever opened the ticket can close it."
                : "Solo lo staff o chi ha aperto il ticket può chiuderlo.";
    }

    public static String noCategoryConfigured(Lang lang) {
        return lang == Lang.EN
                ? "Tickets are not configured yet: no category set. Tell an admin."
                : "I ticket non sono ancora configurati: manca la categoria. Avvisa un admin.";
    }

    public static String creationFailed(Lang lang, String err) {
        return lang == Lang.EN
                ? "Ticket creation failed: " + err
                : "Creazione ticket fallita: " + err;
    }
}
