package com.rankedbot2.core;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.List;

/** Un gruppo di comandi slash registrati e gestiti insieme. */
public interface SlashCommand {

    /** Definizioni registrate su Discord da questo gruppo. */
    List<SlashCommandData> data();

    /** True se questo gruppo gestisce il comando top-level indicato. */
    boolean handles(String commandName);

    void execute(SlashCommandInteractionEvent event) throws Exception;

    /**
     * Chiave di permissions.yml da verificare per questa interazione,
     * così da restare compatibili con i nomi comando del bot originale.
     */
    String permissionKey(SlashCommandInteractionEvent event);

    /** Se false il comando è usabile anche da chi non è registrato. */
    default boolean requiresRegistration(SlashCommandInteractionEvent event) {
        return true;
    }
}
