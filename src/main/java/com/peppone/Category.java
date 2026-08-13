package com.peppone;

public enum Category {
    APPEALS("appeals", "⚖️", "Appeals", "Appelli", "Click here to appeal a staff decision.", "Clicca qui se vuoi appellare una decisione dello Staff."),
    SCORING("scoring", "📊", "Scoring", "Scoring", "Click here to appeal the result of a game.", "Clicca qui se vuoi appellare il risultato di una partita."),
    OTHER("other", "❓", "Other", "Altro", "Click here if you need general support.", "Clicca qui se hai bisogno di supporto generale.");

    public final String id;
    public final String emoji;
    private final String labelEn;
    private final String labelIt;
    private final String descriptionEn;
    private final String descriptionIt;

    Category(String id, String emoji, String labelEn, String labelIt, String descriptionEn, String descriptionIt) {
        this.id = id;
        this.emoji = emoji;
        this.labelEn = labelEn;
        this.labelIt = labelIt;
        this.descriptionEn = descriptionEn;
        this.descriptionIt = descriptionIt;
    }

    public String label(Texts.Lang lang) {
        return lang == Texts.Lang.EN ? labelEn : labelIt;
    }

    public String description(Texts.Lang lang) {
        return lang == Texts.Lang.EN ? descriptionEn : descriptionIt;
    }

    public static Category byId(String id) {
        if (id == null) return OTHER;
        for (Category c : values()) {
            if (c.id.equalsIgnoreCase(id)) return c;
        }
        return OTHER;
    }
}
