package com.rankedbot2.db;

import com.rankedbot2.model.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class PlayerRepo {

    private final Connection conn;

    public PlayerRepo(Database database) {
        this.conn = database.connection();
    }

    public boolean exists(String id) {
        return get(id) != null;
    }

    public Player get(String id) {
        String sql = "SELECT * FROM players WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura player: " + e.getMessage(), e);
        }
    }

    public Player getByIgn(String ign) {
        String sql = "SELECT * FROM players WHERE lower(ign) = lower(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ign);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura player per ign: " + e.getMessage(), e);
        }
    }

    public void create(String id, String ign, int startingElo) {
        String sql = "INSERT INTO players (id, ign, elo, peak_elo) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, ign);
            ps.setInt(3, startingElo);
            ps.setInt(4, startingElo);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore creazione player: " + e.getMessage(), e);
        }
    }

    public void save(Player p) {
        String sql = """
                UPDATE players SET ign=?, elo=?, peak_elo=?, wins=?, losses=?, games=?,
                    winstreak=?, highest_ws=?, lossstreak=?, highest_ls=?, mvp=?, kills=?, deaths=?,
                    strikes=?, scored=?, gold=?, xp=?, level=?, clan_id=?, theme=?, owned_themes=?,
                    banned_until=?, ban_reason=?
                WHERE id=?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, p.ign);
            ps.setInt(i++, p.elo);
            ps.setInt(i++, p.peakElo);
            ps.setInt(i++, p.wins);
            ps.setInt(i++, p.losses);
            ps.setInt(i++, p.games);
            ps.setInt(i++, p.winstreak);
            ps.setInt(i++, p.highestWs);
            ps.setInt(i++, p.lossstreak);
            ps.setInt(i++, p.highestLs);
            ps.setInt(i++, p.mvp);
            ps.setInt(i++, p.kills);
            ps.setInt(i++, p.deaths);
            ps.setInt(i++, p.strikes);
            ps.setInt(i++, p.scored);
            ps.setInt(i++, p.gold);
            ps.setInt(i++, p.xp);
            ps.setInt(i++, p.level);
            ps.setInt(i++, p.clanId);
            ps.setString(i++, p.theme);
            ps.setString(i++, p.ownedThemes);
            ps.setLong(i++, p.bannedUntil);
            ps.setString(i++, p.banReason);
            ps.setString(i, p.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore salvataggio player: " + e.getMessage(), e);
        }
    }

    public void delete(String id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM players WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore eliminazione player: " + e.getMessage(), e);
        }
    }

    /**
     * Azzera le statistiche lasciando intatto il resto: registrazione, IGN,
     * clan, temi posseduti e ban restano dov'erano. Cancellare la riga farebbe
     * sparire il giocatore, che dovrebbe rifare `/register`.
     */
    private static final String RESET_COLUMNS = """
            elo=?, peak_elo=?, wins=0, losses=0, games=0, winstreak=0, highest_ws=0,
            lossstreak=0, highest_ls=0, mvp=0, kills=0, deaths=0, scored=0, gold=0,
            xp=0, level=0""";

    public void resetStats(String id, int startingElo) {
        String sql = "UPDATE players SET " + RESET_COLUMNS + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, startingElo);
            ps.setInt(2, startingElo);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore azzeramento statistiche: " + e.getMessage(), e);
        }
    }

    /** Come resetStats ma per tutti. Ritorna quanti giocatori sono stati azzerati. */
    public int resetAllStats(int startingElo) {
        String sql = "UPDATE players SET " + RESET_COLUMNS;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, startingElo);
            ps.setInt(2, startingElo);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore azzeramento statistiche: " + e.getMessage(), e);
        }
    }

    public List<Player> all() {
        List<Player> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM players");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura players: " + e.getMessage(), e);
        }
        return out;
    }

    public List<Player> inClan(int clanId) {
        List<Player> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM players WHERE clan_id = ?")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura membri clan: " + e.getMessage(), e);
        }
        return out;
    }

    /** Ordina per una colonna consentita. La colonna è validata contro una whitelist. */
    public List<Player> topBy(String column, int limit) {
        if (!ALLOWED_SORT_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("Statistica non valida: " + column);
        }
        List<Player> out = new ArrayList<>();
        String sql = "SELECT * FROM players ORDER BY " + column + " DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore leaderboard: " + e.getMessage(), e);
        }
        return out;
    }

    public static final List<String> ALLOWED_SORT_COLUMNS = List.of(
            "elo", "peak_elo", "wins", "losses", "games", "winstreak", "highest_ws",
            "lossstreak", "highest_ls", "mvp", "kills", "deaths", "strikes", "scored",
            "gold", "xp", "level");

    private Player map(ResultSet rs) throws SQLException {
        Player p = new Player();
        p.id = rs.getString("id");
        p.ign = rs.getString("ign");
        p.elo = rs.getInt("elo");
        p.peakElo = rs.getInt("peak_elo");
        p.wins = rs.getInt("wins");
        p.losses = rs.getInt("losses");
        p.games = rs.getInt("games");
        p.winstreak = rs.getInt("winstreak");
        p.highestWs = rs.getInt("highest_ws");
        p.lossstreak = rs.getInt("lossstreak");
        p.highestLs = rs.getInt("highest_ls");
        p.mvp = rs.getInt("mvp");
        p.kills = rs.getInt("kills");
        p.deaths = rs.getInt("deaths");
        p.strikes = rs.getInt("strikes");
        p.scored = rs.getInt("scored");
        p.gold = rs.getInt("gold");
        p.xp = rs.getInt("xp");
        p.level = rs.getInt("level");
        p.clanId = rs.getInt("clan_id");
        p.theme = rs.getString("theme");
        p.ownedThemes = rs.getString("owned_themes");
        p.bannedUntil = rs.getLong("banned_until");
        p.banReason = rs.getString("ban_reason");
        return p;
    }
}
