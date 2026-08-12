package com.rankedbot2.db;

import com.rankedbot2.model.Rank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RankRepo {

    private final Connection conn;

    public RankRepo(Database database) {
        this.conn = database.connection();
    }

    public void add(Rank rank) {
        String sql = "INSERT OR REPLACE INTO ranks (role_id, start_elo, end_elo, win_elo, lose_elo) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rank.roleId);
            ps.setInt(2, rank.startElo);
            ps.setInt(3, rank.endElo);
            ps.setInt(4, rank.winElo);
            ps.setInt(5, rank.loseElo);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore creazione rank: " + e.getMessage(), e);
        }
    }

    public boolean exists(String roleId) {
        return get(roleId) != null;
    }

    public Rank get(String roleId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ranks WHERE role_id = ?")) {
            ps.setString(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura rank: " + e.getMessage(), e);
        }
    }

    public boolean delete(String roleId) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ranks WHERE role_id = ?")) {
            ps.setString(1, roleId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Errore eliminazione rank: " + e.getMessage(), e);
        }
    }

    public List<Rank> all() {
        List<Rank> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ranks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura ranks: " + e.getMessage(), e);
        }
        out.sort(Comparator.comparingInt(r -> r.startElo));
        return out;
    }

    /** Rank che copre l'elo indicato, altrimenti il più alto sotto quell'elo. */
    public Rank forElo(int elo) {
        Rank best = null;
        for (Rank r : all()) {
            if (r.covers(elo)) return r;
            if (elo > r.endElo && (best == null || r.endElo > best.endElo)) best = r;
        }
        return best;
    }

    private Rank map(ResultSet rs) throws SQLException {
        return new Rank(
                rs.getString("role_id"),
                rs.getInt("start_elo"),
                rs.getInt("end_elo"),
                rs.getInt("win_elo"),
                rs.getInt("lose_elo"));
    }
}
