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
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_logins_replication_key ON logins (username, login_ts_ms)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS channels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    created_ts_ms INTEGER NOT NULL,
                    created_by TEXT NOT NULL
                )
            """);
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_channels_replication_key ON channels (name, created_ts_ms, created_by)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS publications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    channel_name TEXT NOT NULL,
                    message_text TEXT NOT NULL,
                    sent_by TEXT NOT NULL,
                    request_ts_ms INTEGER NOT NULL,
                    published_ts_ms INTEGER NOT NULL
                )
            """);
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_publications_replication_key ON publications (channel_name, message_text, sent_by, request_ts_ms, published_ts_ms)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS request_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id INTEGER NOT NULL,
                    operation TEXT NOT NULL,
                    username TEXT,
                    request_ts_ms INTEGER NOT NULL,
                    handled_ts_ms INTEGER NOT NULL,
                    ok INTEGER NOT NULL,
                    error_code TEXT,
                    details TEXT
                )
            """);
        } catch (SQLException e) {
            throw new RuntimeException("failed to initialize schema", e);
        }
    }

    public synchronized void registerLogin(String username, long tsMs) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO logins (username, login_ts_ms) VALUES (?, ?)")) {
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
                "INSERT OR IGNORE INTO channels (name, created_ts_ms, created_by) VALUES (?, ?, ?)") ) {
                insert.setString(1, channelName);
                insert.setLong(2, tsMs);
                insert.setString(3, createdBy);
                return insert.executeUpdate() > 0;
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

    public synchronized boolean channelExists(String channelName) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM channels WHERE name = ?")) {
            ps.setString(1, channelName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to check channel", e);
        }
    }

    public synchronized void savePublication(
        String channelName,
        String messageText,
        String sentBy,
        long requestTsMs,
        long publishedTsMs
    ) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR IGNORE INTO publications (channel_name, message_text, sent_by, request_ts_ms, published_ts_ms) VALUES (?, ?, ?, ?, ?)")
        ) {
            ps.setString(1, channelName);
            ps.setString(2, messageText);
            ps.setString(3, sentBy);
            ps.setLong(4, requestTsMs);
            ps.setLong(5, publishedTsMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("failed to insert publication", e);
        }
    }

    public synchronized void logRequest(
        long requestId,
        String operation,
        String username,
        long requestTsMs,
        long handledTsMs,
        boolean ok,
        String errorCode,
        String details
    ) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO request_logs (request_id, operation, username, request_ts_ms, handled_ts_ms, ok, error_code, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        ) {
            ps.setLong(1, requestId);
            ps.setString(2, operation);
            ps.setString(3, username);
            ps.setLong(4, requestTsMs);
            ps.setLong(5, handledTsMs);
            ps.setInt(6, ok ? 1 : 0);
            ps.setString(7, errorCode);
            ps.setString(8, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("failed to insert request log", e);
        }
    }
}
