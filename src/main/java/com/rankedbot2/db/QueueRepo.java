package com.rankedbot2.db;

import com.rankedbot2.model.GameQueue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class QueueRepo {

    private final Connection conn;

    public QueueRepo(Database database) {
        this.conn = database.connection();
    }

    public void add(GameQueue queue) {
        String sql = "INSERT OR REPLACE INTO queues (vc_id, players_each_team, picking_mode, casual) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, queue.vcId);
            ps.setInt(2, queue.playersEachTeam);
            ps.setString(3, queue.pickingMode.name());
            ps.setInt(4, queue.casual ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Errore creazione coda: " + e.getMessage(), e);
        }
    }

    public boolean exists(String vcId) {
        return get(vcId) != null;
    }

    public GameQueue get(String vcId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM queues WHERE vc_id = ?")) {
            ps.setString(1, vcId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura coda: " + e.getMessage(), e);
        }
    }

    public boolean delete(String vcId) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM queues WHERE vc_id = ?")) {
            ps.setString(1, vcId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Errore eliminazione coda: " + e.getMessage(), e);
        }
    }

    public List<GameQueue> all() {
        List<GameQueue> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM queues");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Errore lettura code: " + e.getMessage(), e);
        }
        return out;
    }

    private GameQueue map(ResultSet rs) throws SQLException {
        return new GameQueue(
                rs.getString("vc_id"),
                rs.getInt("players_each_team"),
                GameQueue.PickingMode.valueOf(rs.getString("picking_mode")),
                rs.getInt("casual") == 1);
    }
}
