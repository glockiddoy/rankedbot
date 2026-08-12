package com.rankedbot2.db;

import com.rankedbot2.model.Clan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ClanRepo {

    private final Connection conn;

    public ClanRepo(Database database) {
        this.conn = database.connection();
    }

    public Clan create(String name, String leader, int startingRep) {
        String sql = "INSERT INTO clans (name, leader, reputation) VALUES (?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, leader);
            ps.setInt(3, startingRep);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return get(keys.getInt(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore creazione clan: " + e.getMessage(), e);
        }
        return null;
    }

    public Clan get(int id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM clans WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura clan: " + e.getMessage(), e);
        }
    }

    public Clan getByName(String name) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM clans WHERE lower(name) = lower(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura clan per nome: " + e.getMessage(), e);
        }
    }

    public void save(Clan c) {
        String sql = """
                UPDATE clans SET name=?, description=?, leader=?, reputation=?, xp=?, level=?,
                    icon=?, theme=?, open=?, min_elo=?, invited=?, cw_played=?, cw_wins=?, cw_losses=?
                WHERE id=?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, c.name);
            ps.setString(i++, c.description);
            ps.setString(i++, c.leader);
            ps.setInt(i++, c.reputation);
            ps.setInt(i++, c.xp);
            ps.setInt(i++, c.level);
            ps.setString(i++, c.icon);
            ps.setString(i++, c.theme);
            ps.setInt(i++, c.open ? 1 : 0);
            ps.setInt(i++, c.minElo);
            ps.setInt(i++, c.invited);
            ps.setInt(i++, c.cwPlayed);
            ps.setInt(i++, c.cwWins);
            ps.setInt(i++, c.cwLosses);
            ps.setInt(i, c.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore salvataggio clan: " + e.getMessage(), e);
        }
    }

    public void delete(int id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM clans WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore eliminazione clan: " + e.getMessage(), e);
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET clan_id = -1 WHERE clan_id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore rimozione membri clan: " + e.getMessage(), e);
        }
    }

    public List<Clan> all() {
        List<Clan> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM clans ORDER BY reputation DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura clan: " + e.getMessage(), e);
        }
        return out;
    }

    /** Posizione in classifica per reputazione, 1-based. */
    public int ranking(int clanId) {
        List<Clan> clans = all();
        for (int i = 0; i < clans.size(); i++) {
            if (clans.get(i).id == clanId) return i + 1;
        }
        return 0;
    }

    private Clan map(ResultSet rs) throws SQLException {
        Clan c = new Clan();
        c.id = rs.getInt("id");
        c.name = rs.getString("name");
        c.description = rs.getString("description");
        c.leader = rs.getString("leader");
        c.reputation = rs.getInt("reputation");
        c.xp = rs.getInt("xp");
        c.level = rs.getInt("level");
        c.icon = rs.getString("icon");
        c.theme = rs.getString("theme");
        c.open = rs.getInt("open") == 1;
        c.minElo = rs.getInt("min_elo");
        c.invited = rs.getInt("invited");
        c.cwPlayed = rs.getInt("cw_played");
        c.cwWins = rs.getInt("cw_wins");
        c.cwLosses = rs.getInt("cw_losses");
        return c;
    }
}
