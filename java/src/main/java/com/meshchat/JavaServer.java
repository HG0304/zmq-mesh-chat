package com.meshchat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import meshchat.Chat;

public class JavaServer {
    private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,20}$");

    public void run() {
        String serverName = System.getenv().getOrDefault("SERVER_NAME", "java-server");
        String backendAddr = System.getenv().getOrDefault("BROKER_BACKEND_ADDR", "tcp://broker:5556");
        String dbPath = System.getenv().getOrDefault("DB_PATH", "/data/chat.db");

        ChatRepository repo = new ChatRepository(dbPath);
        Set<String> activeUsers = new HashSet<>();

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REP);
            socket.connect(backendAddr);
            System.out.printf("[%s] connected backend=%s db=%s%n", serverName, backendAddr, dbPath);

            while (!Thread.currentThread().isInterrupted()) {
                byte[] raw = socket.recv(0);
                if (raw == null) {
                    continue;
                }

                Chat.ClientRequest req;
                try {
                    req = Chat.ClientRequest.parseFrom(raw);
                } catch (Exception e) {
                    Chat.ServerResponse response = Chat.ServerResponse.newBuilder()
                        .setRequestId(0)
                        .setTimestampMs(nowMs())
                        .setOk(false)
                        .setErrorCode("BAD_PROTOBUF")
                        .setErrorMessage("failed to parse request")
                        .build();
                    socket.send(response.toByteArray());
                    continue;
                }

                long ts = nowMs();
                Chat.ServerResponse.Builder res = Chat.ServerResponse.newBuilder()
                    .setRequestId(req.getRequestId())
                    .setTimestampMs(ts)
                    .setOk(false);

                switch (req.getPayloadCase()) {
                    case LOGIN -> {
                        String username = req.getLogin().getUsername();
                        if (activeUsers.contains(username)) {
                            res.setErrorCode("USER_ACTIVE");
                            res.setErrorMessage("username '" + username + "' already active");
                        } else {
                            activeUsers.add(username);
                            repo.registerLogin(username, ts);
                            res.setOk(true);
                            res.setLogin(Chat.LoginResponse.newBuilder().setUsername(username).build());
                        }
                    }
                    case CREATE_CHANNEL -> {
                        String channelName = req.getCreateChannel().getChannelName();
                        String requestedBy = req.getCreateChannel().getRequestedBy();
                        if (!CHANNEL_PATTERN.matcher(channelName).matches()) {
                            res.setErrorCode("INVALID_CHANNEL_NAME");
                            res.setErrorMessage("channel must match ^[A-Za-z0-9_-]{3,20}$");
                        } else {
                            boolean created = repo.createChannel(channelName, requestedBy, ts);
                            if (!created) {
                                res.setErrorCode("CHANNEL_EXISTS");
                                res.setErrorMessage("channel '" + channelName + "' already exists");
                            } else {
                                res.setOk(true);
                                res.setCreateChannel(Chat.CreateChannelResponse.newBuilder().setChannelName(channelName).build());
                            }
                        }
                    }
                    case LIST_CHANNELS -> {
                        res.setOk(true);
                        res.setListChannels(Chat.ListChannelsResponse.newBuilder().addAllChannels(repo.listChannels()).build());
                    }
                    case PAYLOAD_NOT_SET -> {
                        res.setErrorCode("UNKNOWN_REQUEST");
                        res.setErrorMessage("request payload not recognized");
                    }
                }

                Chat.ServerResponse response = res.build();
                System.out.printf("[%s] recv=%s send=%s%n", serverName, summarize(req), summarize(response));
                socket.send(response.toByteArray());
            }
        }
    }

    private static long nowMs() {
        return Instant.now().toEpochMilli();
    }

    private static String summarize(Chat.ClientRequest req) {
        return "ClientRequest{requestId=" + req.getRequestId() + ", ts=" + req.getTimestampMs() + ", payload=" + req.getPayloadCase() + "}";
    }

    private static String summarize(Chat.ServerResponse res) {
        return "ServerResponse{requestId=" + res.getRequestId() + ", ts=" + res.getTimestampMs() + ", ok=" + res.getOk() + ", errorCode='" + res.getErrorCode() + "', payload=" + res.getPayloadCase() + "}";
    }
}
