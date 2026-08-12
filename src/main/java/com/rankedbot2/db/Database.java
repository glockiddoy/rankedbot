package com.rankedbot2.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private final Connection connection;

    public Database(String path) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            createSchema();
        } catch (Exception e) {
            throw new IllegalStateException("Impossibile aprire il database: " + e.getMessage(), e);
        }
    }

    public Connection connection() {
        return connection;
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                        id TEXT PRIMARY KEY,
                        ign TEXT NOT NULL,
                        elo INTEGER NOT NULL DEFAULT 0,
                        peak_elo INTEGER NOT NULL DEFAULT 0,
                        wins INTEGER NOT NULL DEFAULT 0,
                        losses INTEGER NOT NULL DEFAULT 0,
                        games INTEGER NOT NULL DEFAULT 0,
                        winstreak INTEGER NOT NULL DEFAULT 0,
                        highest_ws INTEGER NOT NULL DEFAULT 0,
                        lossstreak INTEGER NOT NULL DEFAULT 0,
                        highest_ls INTEGER NOT NULL DEFAULT 0,
                        mvp INTEGER NOT NULL DEFAULT 0,
                        kills INTEGER NOT NULL DEFAULT 0,
                        deaths INTEGER NOT NULL DEFAULT 0,
                        strikes INTEGER NOT NULL DEFAULT 0,
                        scored INTEGER NOT NULL DEFAULT 0,
                        gold INTEGER NOT NULL DEFAULT 0,
                        xp INTEGER NOT NULL DEFAULT 0,
                        level INTEGER NOT NULL DEFAULT 0,
                        clan_id INTEGER NOT NULL DEFAULT -1,
                        theme TEXT NOT NULL DEFAULT '',
                        owned_themes TEXT NOT NULL DEFAULT '',
                        banned_until INTEGER NOT NULL DEFAULT 0,
                        ban_reason TEXT NOT NULL DEFAULT ''
                    )""");

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ranks (
                        role_id TEXT PRIMARY KEY,
                        start_elo INTEGER NOT NULL,
                        end_elo INTEGER NOT NULL,
                        win_elo INTEGER NOT NULL,
                        lose_elo INTEGER NOT NULL
                    )""");

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS maps (
                        name TEXT PRIMARY KEY,
                        height INTEGER NOT NULL DEFAULT 0,
                        team1 TEXT NOT NULL DEFAULT '',
                        team2 TEXT NOT NULL DEFAULT '',
                        players_each_team INTEGER NOT NULL DEFAULT 0
                    )""");

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS queues (
                        vc_id TEXT PRIMARY KEY,
                        players_each_team INTEGER NOT NULL,
                        picking_mode TEXT NOT NULL,
                        casual INTEGER NOT NULL DEFAULT 0
                    )""");

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS games (
                        number INTEGER PRIMARY KEY,
                        queue_vc TEXT NOT NULL DEFAULT '',
                        state TEXT NOT NULL DEFAULT 'PICKING',
                        players_each_team INTEGER NOT NULL DEFAULT 0,
                        team1 TEXT NOT NULL DEFAULT '',
                        team2 TEXT NOT NULL DEFAULT '',
                        remaining TEXT NOT NULL DEFAULT '',
                        captain1 TEXT NOT NULL DEFAULT '',
                        captain2 TEXT NOT NULL DEFAULT '',
                        pick_turn INTEGER NOT NULL DEFAULT 1,
                        picks_left INTEGER NOT NULL DEFAULT 1,
                        map TEXT NOT NULL DEFAULT '',
                        text_channel TEXT NOT NULL DEFAULT '',
                        vc1 TEXT NOT NULL DEFAULT '',
                        vc2 TEXT NOT NULL DEFAULT '',
                        winner INTEGER NOT NULL DEFAULT 0,
                        mvp TEXT NOT NULL DEFAULT '',
                        casual INTEGER NOT NULL DEFAULT 0,
                        scored_by TEXT NOT NULL DEFAULT '',
                        elo_changes TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        clan_war INTEGER NOT NULL DEFAULT -1,
                        clan1 INTEGER NOT NULL DEFAULT -1,
                        clan2 INTEGER NOT NULL DEFAULT -1
                    )""");

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS clans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        description TEXT NOT NULL DEFAULT '',
                        leader TEXT NOT NULL,
                        reputation INTEGER NOT NULL DEFAULT 0,
                        xp INTEGER NOT NULL DEFAULT 0,
                        level INTEGER NOT NULL DEFAULT 0,
                        icon TEXT NOT NULL DEFAULT '',
                        theme TEXT NOT NULL DEFAULT '',
                        open INTEGER NOT NULL DEFAULT 0,
                        min_elo INTEGER NOT NULL DEFAULT 0,
                        invited INTEGER NOT NULL DEFAULT 0,
                        cw_played INTEGER NOT NULL DEFAULT 0,
                        cw_wins INTEGER NOT NULL DEFAULT 0,
                        cw_losses INTEGER NOT NULL DEFAULT 0
                    )""");

            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS counters (
                        name TEXT PRIMARY KEY,
                        value INTEGER NOT NULL DEFAULT 0
                    )""");
        }

        addColumnIfMissing("maps", "players_each_team", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("games", "picks_left", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing("games", "kill_changes", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("games", "death_changes", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("games", "coral_match", "INTEGER NOT NULL DEFAULT 0");
    }

    /** Migrazione leggera per i database creati da versioni precedenti. */
    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (Statement st = connection.createStatement();
             var rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
        }
    }
}
