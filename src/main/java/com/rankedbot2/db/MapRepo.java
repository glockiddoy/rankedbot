package com.rankedbot2.db;

import com.rankedbot2.model.GameMap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class MapRepo {

    private final Connection conn;

    public MapRepo(Database database) {
        this.conn = database.connection();
    }

    public void add(GameMap map) {
        String sql = "INSERT OR REPLACE INTO maps (name, height, team1, team2, players_each_team) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, map.name);
            ps.setInt(2, map.height);
            ps.setString(3, map.team1);
            ps.setString(4, map.team2);
            ps.setInt(5, map.playersEachTeam);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore creazione mappa: " + e.getMessage(), e);
        }
    }

    public boolean exists(String name) {
        return get(name) != null;
    }

    public GameMap get(String name) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM maps WHERE lower(name) = lower(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura mappa: " + e.getMessage(), e);
        }
    }

    public boolean delete(String name) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM maps WHERE lower(name) = lower(?)")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Errore eliminazione mappa: " + e.getMessage(), e);
        }
    }

    public List<GameMap> all() {
        List<GameMap> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM maps ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura mappe: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Mappe adatte a una modalità: quelle marcate per quel numero di giocatori
     * più quelle senza modalità specifica. Se non ce n'è nessuna ritorna tutte
     * le mappe, così una coda nuova non resta mai senza mappa.
     */
    public List<GameMap> forMode(int playersEachTeam) {
        List<GameMap> out = new ArrayList<>();
        String sql = "SELECT * FROM maps WHERE players_each_team = ? OR players_each_team <= 0 ORDER BY name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, playersEachTeam);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura mappe per modalità: " + e.getMessage(), e);
        }
        return out.isEmpty() ? all() : out;
    }

    private GameMap map(ResultSet rs) throws SQLException {
        return new GameMap(
                rs.getString("name"),
                rs.getInt("height"),
                rs.getString("team1"),
                rs.getString("team2"),
                rs.getInt("players_each_team"));
    }
}
