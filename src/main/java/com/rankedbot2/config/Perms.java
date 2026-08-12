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

    public boolean canUse(String command, Member member) {
        if (member == null) return false;
        if (member.isOwner()) return true;
        if (isDisabled(command)) return false;

        String raw = config.getString(command);
        if (raw.equalsIgnoreCase("everyone")) return true;

        List<Long> allowed = config.getIdList(command);
        if (allowed.isEmpty()) return false;

        for (Role role : member.getRoles()) {
            if (allowed.contains(role.getIdLong())) return true;
        }
        return false;
    }
}
