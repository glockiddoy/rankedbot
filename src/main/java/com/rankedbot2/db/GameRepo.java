package com.rankedbot2.db;

import com.rankedbot2.model.Game;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GameRepo {

    private final Connection conn;

    public GameRepo(Database database) {
        this.conn = database.connection();
    }

    public int nextNumber() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(number), 0) + 1 FROM games");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Errore numerazione partita: " + e.getMessage(), e);
        }
    }

    public void save(Game g) {
        String sql = """
                INSERT OR REPLACE INTO games (number, queue_vc, state, players_each_team, team1, team2,
                    remaining, captain1, captain2, pick_turn, picks_left, map, text_channel, vc1, vc2,
                    winner, mvp, casual, scored_by, elo_changes, created_at, clan_war, clan1, clan2,
                    kill_changes, death_changes, coral_match)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, g.number);
            ps.setString(i++, g.queueVc);
            ps.setString(i++, g.state.name());
            ps.setInt(i++, g.playersEachTeam);
            ps.setString(i++, String.join(",", g.team1));
            ps.setString(i++, String.join(",", g.team2));
            ps.setString(i++, String.join(",", g.remaining));
            ps.setString(i++, g.captain1);
            ps.setString(i++, g.captain2);
            ps.setInt(i++, g.pickTurn);
            ps.setInt(i++, g.picksLeft);
            ps.setString(i++, g.map);
            ps.setString(i++, g.textChannel);
            ps.setString(i++, g.vc1);
            ps.setString(i++, g.vc2);
            ps.setInt(i++, g.winner);
            ps.setString(i++, g.mvp);
            ps.setInt(i++, g.casual ? 1 : 0);
            ps.setString(i++, g.scoredBy);
            ps.setString(i++, serializeChanges(g.eloChanges));
            ps.setLong(i++, g.createdAt);
            ps.setInt(i++, g.clanWar);
            ps.setInt(i++, g.clan1);
            ps.setInt(i++, g.clan2);
            ps.setString(i++, serializeChanges(g.killChanges));
            ps.setString(i++, serializeChanges(g.deathChanges));
            ps.setLong(i, g.coralMatch);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore salvataggio partita: " + e.getMessage(), e);
        }
    }

    public Game get(int number) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM games WHERE number = ?")) {
            ps.setInt(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura partita: " + e.getMessage(), e);
        }
    }

    public Game getByChannel(String channelId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM games WHERE text_channel = ?")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura partita da canale: " + e.getMessage(), e);
        }
    }

    /**
     * Numero della partita già scorata con questo match CoralMC, 0 se nessuna.
     * Serve a impedire che lo stesso link venga riusato su più partite.
     */
    public int scoredWithCoralMatch(long coralMatch, int excludeNumber) {
        String sql = "SELECT number FROM games WHERE coral_match = ? AND number <> ? AND state = 'SCORED' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, coralMatch);
            ps.setInt(2, excludeNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura partita da match CoralMC: " + e.getMessage(), e);
        }
    }

    public List<Game> byState(Game.State state) {
        List<Game> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM games WHERE state = ?")) {
            ps.setString(1, state.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura partite: " + e.getMessage(), e);
        }
        return out;
    }

    /** Partite non ancora concluse (in picking o iniziate). */
    public List<Game> active() {
        List<Game> out = new ArrayList<>();
        String sql = "SELECT * FROM games WHERE state IN ('PICKING','STARTED','SUBMITTED')";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura partite attive: " + e.getMessage(), e);
        }
        return out;
    }

    public Game activeGameOf(String userId) {
        for (Game g : active()) {
            if (g.allPlayers().contains(userId)) return g;
        }
        return null;
    }

    private static String serializeChanges(Map<String, Integer> changes) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : changes.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, Integer> parseChanges(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String entry : raw.split(",")) {
            String[] pair = entry.split(":");
            if (pair.length == 2) {
                try {
                    out.put(pair[0], Integer.parseInt(pair[1]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private static List<String> parseList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String s : raw.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private Game map(ResultSet rs) throws SQLException {
        Game g = new Game();
        g.number = rs.getInt("number");
        g.queueVc = rs.getString("queue_vc");
        g.state = Game.State.valueOf(rs.getString("state"));
        g.playersEachTeam = rs.getInt("players_each_team");
        g.team1 = parseList(rs.getString("team1"));
        g.team2 = parseList(rs.getString("team2"));
        g.remaining = parseList(rs.getString("remaining"));
        g.captain1 = rs.getString("captain1");
        g.captain2 = rs.getString("captain2");
        g.pickTurn = rs.getInt("pick_turn");
        g.picksLeft = rs.getInt("picks_left");
        g.map = rs.getString("map");
        g.textChannel = rs.getString("text_channel");
        g.vc1 = rs.getString("vc1");
        g.vc2 = rs.getString("vc2");
        g.winner = rs.getInt("winner");
        g.mvp = rs.getString("mvp");
        g.casual = rs.getInt("casual") == 1;
        g.scoredBy = rs.getString("scored_by");
        g.eloChanges = parseChanges(rs.getString("elo_changes"));
        g.createdAt = rs.getLong("created_at");
        g.clanWar = rs.getInt("clan_war");
        g.clan1 = rs.getInt("clan1");
        g.clan2 = rs.getInt("clan2");
        g.killChanges = parseChanges(rs.getString("kill_changes"));
        g.deathChanges = parseChanges(rs.getString("death_changes"));
        g.coralMatch = rs.getLong("coral_match");
        return g;
    }
}
