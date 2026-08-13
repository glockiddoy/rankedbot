package com.rankedbot2.config;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.io.File;
import java.util.List;

/**
 * Controllo permessi a runtime basato su permissions.yml.
 * Volutamente non usa i permessi nativi degli slash command: permissions.yml
 * supporta "everyone", liste di ruoli e comando disabilitato (valore vuoto),
 * semantica che i permessi nativi di Discord non riproducono esattamente.
 */
public class Perms {

    private final Config config;

    public Perms(File file) {
        this.config = new Config(file);
    }

    public void reload() {
        config.reload();
    }

    public boolean isDisabled(String command) {
        return !config.has(command);
    }

    /**
     * Accetta sia ID ruolo sia nomi ("Admin, Staff"). I nomi restano validi se
     * il server viene ricreato o i ruoli rifatti, gli ID no: con gli ID un
     * comando si ritrova disabilitato senza che nessuno abbia toccato nulla.
     */
    public boolean canUse(String command, Member member) {
        if (member == null) return false;
        if (member.isOwner()) return true;
        if (isDisabled(command)) return false;

        String raw = config.getString(command);
        if (raw.equalsIgnoreCase("everyone")) return true;

        List<String> allowed = config.getStringList(command);
        if (allowed.isEmpty()) return false;

        for (Role role : member.getRoles()) {
            for (String entry : allowed) {
                if (entry.equalsIgnoreCase(role.getName())) return true;
                if (entry.equals(role.getId())) return true;
            }
        }
        return false;
    }
}
