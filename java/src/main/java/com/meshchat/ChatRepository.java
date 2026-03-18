package com.meshchat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ChatRepository {
    private final String jdbcUrl;

    public ChatRepository(String dbPath) {
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to create db directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initSchema() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS logins (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    login_ts_ms INTEGER NOT NULL
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS channels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    created_ts_ms INTEGER NOT NULL,
                    created_by TEXT NOT NULL
                )
            """);
        } catch (SQLException e) {
            throw new RuntimeException("failed to initialize schema", e);
        }
    }

    public synchronized void registerLogin(String username, long tsMs) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO logins (username, login_ts_ms) VALUES (?, ?)")) {
            ps.setString(1, username);
            ps.setLong(2, tsMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("failed to insert login", e);
        }
    }

    public synchronized boolean createChannel(String channelName, String createdBy, long tsMs) {
        try (Connection conn = connect()) {
            try (PreparedStatement select = conn.prepareStatement("SELECT 1 FROM channels WHERE name = ?")) {
                select.setString(1, channelName);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO channels (name, created_ts_ms, created_by) VALUES (?, ?, ?)")) {
                insert.setString(1, channelName);
                insert.setLong(2, tsMs);
                insert.setString(3, createdBy);
                insert.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to create channel", e);
        }
    }

    public synchronized List<String> listChannels() {
        List<String> channels = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM channels ORDER BY name ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                channels.add(rs.getString("name"));
            }
            return channels;
        } catch (SQLException e) {
            throw new RuntimeException("failed to list channels", e);
        }
    }
}
