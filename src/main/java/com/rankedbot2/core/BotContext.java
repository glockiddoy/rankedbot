package com.rankedbot2.core;

import com.rankedbot2.config.Config;
import com.rankedbot2.config.Levels;
import com.rankedbot2.config.Messages;
import com.rankedbot2.config.Perms;
import com.rankedbot2.db.ClanRepo;
import com.rankedbot2.db.Database;
import com.rankedbot2.db.GameRepo;
import com.rankedbot2.db.MapRepo;
import com.rankedbot2.db.PlayerRepo;
import com.rankedbot2.db.QueueRepo;
import com.rankedbot2.db.RankRepo;
import com.rankedbot2.model.ClanWar;
import com.rankedbot2.model.Party;
import net.dv8tion.jda.api.JDA;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Stato condiviso passato a tutti i comandi. */
public class BotContext {

    public static final String VERSION = "2.8.0";

    public final File dataFolder;
    public final Config config;
    public final Messages messages;
    public final Perms perms;
    public final Levels levels;
    public final Levels clanLevels;

    public final Database database;
    public final PlayerRepo players;
    public final RankRepo ranks;
    public final MapRepo maps;
    public final QueueRepo queues;
    public final GameRepo games;
    public final ClanRepo clans;

    /** Party in memoria: userId del leader -> party. */
    public final Map<String, Party> parties = new HashMap<>();
    /** Inviti clan in memoria: userId invitato -> clanId. */
    public final Map<String, Integer> clanInvites = new HashMap<>();
    /** Clan war attive: numero -> clan war. */
    public final Map<Integer, ClanWar> clanWars = new HashMap<>();

    /** Esegue anche la creazione partite, che fa chiamate REST bloccanti. */
    public final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    /**
     * Thread separato per il controllo periodico delle code: se girasse su
     * `scheduler` una creazione partita lenta o un autoscore in attesa di
     * CoralMC potrebbe occupare tutti i thread e fermare la scansione.
     */
    public final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

    private JDA jda;

    public BotContext(File dataFolder) {
        this.dataFolder = dataFolder;
        this.config = new Config(new File(dataFolder, "config.yml"));
        this.messages = new Messages(new File(dataFolder, "messages.yml"));
        this.perms = new Perms(new File(dataFolder, "permissions.yml"));
        this.levels = new Levels(new File(dataFolder, "levels.yml"));
        this.clanLevels = new Levels(new File(dataFolder, "clanlevels.yml"));

        this.database = new Database(new File(dataFolder.getParentFile(), "data.db").getAbsolutePath());
        this.players = new PlayerRepo(database);
        this.ranks = new RankRepo(database);
        this.maps = new MapRepo(database);
        this.queues = new QueueRepo(database);
        this.games = new GameRepo(database);
        this.clans = new ClanRepo(database);
    }

    public void reloadConfigs() {
        config.reload();
        messages.reload();
        perms.reload();
        levels.reload();
        clanLevels.reload();
    }

    public JDA jda() {
        return jda;
    }

    public void setJda(JDA jda) {
        this.jda = jda;
    }

    public String msg(String key) {
        return messages.get(key);
    }

    /** Party a cui appartiene un giocatore, null se in nessuno. */
    public Party partyOf(String userId) {
        for (Party p : parties.values()) {
            if (p.contains(userId)) return p;
        }
        return null;
    }

    public void shutdown() {
        monitor.shutdownNow();
        scheduler.shutdownNow();
        database.close();
    }
}
