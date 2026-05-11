package com.meshchat;

import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import meshchat.Chat;

public class JavaServer {
    private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,20}$");
    private static final String REPLICATION_TOPIC = "__replication__";

    public void run() {
        String serverName = System.getenv().getOrDefault("SERVER_NAME", "java-server");
        String backendAddr = System.getenv().getOrDefault("BROKER_BACKEND_ADDR", "tcp://broker:5556");
        String pubsubXsubAddr = System.getenv().getOrDefault("PUBSUB_XSUB_ADDR", "tcp://pubsub-proxy:5557");
        String pubsubXpubAddr = System.getenv().getOrDefault("PUBSUB_XPUB_ADDR", "tcp://pubsub-proxy:5558");
        String referenceAddr = System.getenv().getOrDefault("REFERENCE_ADDR", "tcp://reference:5560");
        String dbPath = System.getenv().getOrDefault("DB_PATH", "/data/chat.db");

        ChatRepository repo = new ChatRepository(dbPath);
        Set<String> activeUsers = new HashSet<>();

        long logicalClock = 0;
        long clockOffsetMs = 0;
        int serverRank = 0;
        int receivedClientMessages = 0;

        try (ZContext context = new ZContext()) {
            ZMQ.Socket repSocket = context.createSocket(SocketType.REP);
            repSocket.connect(backendAddr);

            ZMQ.Socket pubSocket = context.createSocket(SocketType.PUB);
            pubSocket.connect(pubsubXsubAddr);

            ZMQ.Socket subSocket = context.createSocket(SocketType.SUB);
            subSocket.connect(pubsubXpubAddr);
            subSocket.subscribe(REPLICATION_TOPIC.getBytes(ZMQ.CHARSET));

            ZMQ.Socket refSocket = context.createSocket(SocketType.REQ);
            refSocket.connect(referenceAddr);

            ZMQ.Poller poller = context.createPoller(2);
            int repIndex = poller.register(repSocket, ZMQ.Poller.POLLIN);
            int subIndex = poller.register(subSocket, ZMQ.Poller.POLLIN);

            RefCallResult registerResult = callReference(
                refSocket,
                logicalClock,
                Chat.ReferenceRequest.newBuilder()
                    .setTimestampMs(nowMs(clockOffsetMs))
                    .setRegisterServer(Chat.RefRegisterServerRequest.newBuilder().setServerName(serverName).build())
                    .build()
            );
            logicalClock = registerResult.logicalClock;
            if (registerResult.response.getOk()) {
                serverRank = registerResult.response.getRegisterServer().getRank();
                clockOffsetMs = registerResult.response.getReferenceTimestampMs() - System.currentTimeMillis();
            }

            RefCallResult listResult = callReference(
                refSocket,
                logicalClock,
                Chat.ReferenceRequest.newBuilder()
                    .setTimestampMs(nowMs(clockOffsetMs))
                    .setListServers(Chat.RefListServersRequest.newBuilder().build())
                    .build()
            );
            logicalClock = listResult.logicalClock;

            System.out.printf(
                "[%s] connected backend=%s pubsub_xsub=%s reference=%s rank=%d known_servers=%d db=%s%n",
                serverName,
                backendAddr,
                pubsubXsubAddr,
                referenceAddr,
                serverRank,
                listResult.response.getListServers().getServersCount(),
                dbPath
            );

            while (!Thread.currentThread().isInterrupted()) {
                poller.poll();

                if (poller.pollin(subIndex)) {
                    logicalClock = drainReplicationEvents(subSocket, repo, activeUsers, logicalClock);
                }

                if (!poller.pollin(repIndex)) {
                    continue;
                }

                byte[] raw = repSocket.recv(0);
                if (raw == null) {
                    continue;
                }

                Chat.ClientRequest req;
                try {
                    req = Chat.ClientRequest.parseFrom(raw);
                } catch (Exception e) {
                    logicalClock++;
                    Chat.ServerResponse response = Chat.ServerResponse.newBuilder()
                        .setRequestId(0)
                        .setTimestampMs(nowMs(clockOffsetMs))
                        .setLogicalClock(logicalClock)
                        .setOk(false)
                        .setErrorCode("BAD_PROTOBUF")
                        .setErrorMessage("failed to parse request")
                        .build();
                    repSocket.send(response.toByteArray());
                    continue;
                }

                logicalClock = Math.max(logicalClock, req.getLogicalClock());
                receivedClientMessages++;

                Chat.ServerResponse.Builder res = Chat.ServerResponse.newBuilder()
                    .setRequestId(req.getRequestId())
                    .setTimestampMs(nowMs(clockOffsetMs))
                    .setOk(false);
                String operation = "unknown";
                String username = "";
                String details = "";

                switch (req.getPayloadCase()) {
                    case LOGIN -> {
                        operation = "login";
                        username = req.getLogin().getUsername();
                        details = "username=" + username;
                        if (activeUsers.contains(username)) {
                            res.setErrorCode("USER_ACTIVE");
                            res.setErrorMessage("username '" + username + "' already active");
                        } else {
                            activeUsers.add(username);
                            long loginTsMs = nowMs(clockOffsetMs);
                            repo.registerLogin(username, loginTsMs);
                            res.setOk(true);
                            res.setLogin(Chat.LoginResponse.newBuilder().setUsername(username).build());
                            publishReplicationEvent(
                                pubSocket,
                                "LOGIN",
                                encodeField(username),
                                Long.toString(loginTsMs),
                                Long.toString(logicalClock)
                            );
                        }
                    }
                    case CREATE_CHANNEL -> {
                        operation = "create_channel";
                        String channelName = req.getCreateChannel().getChannelName();
                        String requestedBy = req.getCreateChannel().getRequestedBy();
                        username = requestedBy;
                        details = "channel=" + channelName;
                        if (!CHANNEL_PATTERN.matcher(channelName).matches()) {
                            res.setErrorCode("INVALID_CHANNEL_NAME");
                            res.setErrorMessage("channel must match ^[A-Za-z0-9_-]{3,20}$");
                        } else {
                            long createdTsMs = nowMs(clockOffsetMs);
                            boolean created = repo.createChannel(channelName, requestedBy, createdTsMs);
                            if (!created) {
                                res.setErrorCode("CHANNEL_EXISTS");
                                res.setErrorMessage("channel '" + channelName + "' already exists");
                            } else {
                                res.setOk(true);
                                res.setCreateChannel(Chat.CreateChannelResponse.newBuilder().setChannelName(channelName).build());
                                publishReplicationEvent(
                                    pubSocket,
                                    "CREATE_CHANNEL",
                                    encodeField(channelName),
                                    encodeField(requestedBy),
                                    Long.toString(createdTsMs),
                                    Long.toString(logicalClock)
                                );
                            }
                        }
                    }
                    case LIST_CHANNELS -> {
                        operation = "list_channels";
                        details = "all";
                        res.setOk(true);
                        res.setListChannels(Chat.ListChannelsResponse.newBuilder().addAllChannels(repo.listChannels()).build());
                    }
                    case PUBLISH_IN_CHANNEL -> {
                        operation = "publish_in_channel";
                        String channelName = req.getPublishInChannel().getChannelName();
                        String messageText = req.getPublishInChannel().getMessage();
                        String requestedBy = req.getPublishInChannel().getRequestedBy();
                        username = requestedBy;
                        details = "channel=" + channelName + ";message_len=" + messageText.length();

                        if (!repo.channelExists(channelName)) {
                            res.setErrorCode("CHANNEL_NOT_FOUND");
                            res.setErrorMessage("channel '" + channelName + "' does not exist");
                        } else if (messageText == null || messageText.isBlank()) {
                            res.setErrorCode("EMPTY_MESSAGE");
                            res.setErrorMessage("message must not be empty");
                        } else {
                            logicalClock++;
                            long publishTs = nowMs(clockOffsetMs);
                            Chat.ChannelMessageEvent event = Chat.ChannelMessageEvent.newBuilder()
                                .setChannelName(channelName)
                                .setMessage(messageText)
                                .setSentBy(requestedBy)
                                .setRequestTimestampMs(req.getTimestampMs())
                                .setPublishedTimestampMs(publishTs)
                                .setLogicalClock(logicalClock)
                                .build();

                            pubSocket.sendMore(channelName);
                            pubSocket.send(event.toByteArray());

                            repo.savePublication(channelName, messageText, requestedBy, req.getTimestampMs(), publishTs);
                            publishReplicationEvent(
                                pubSocket,
                                "PUBLICATION",
                                encodeField(channelName),
                                encodeField(messageText),
                                encodeField(requestedBy),
                                Long.toString(req.getTimestampMs()),
                                Long.toString(publishTs),
                                Long.toString(logicalClock)
                            );
                            res.setOk(true);
                            res.setPublishInChannel(
                                Chat.PublishInChannelResponse.newBuilder()
                                    .setChannelName(channelName)
                                    .setPublishedTimestampMs(publishTs)
                                    .build()
                            );
                        }
                    }
                    case PAYLOAD_NOT_SET -> {
                        res.setErrorCode("UNKNOWN_REQUEST");
                        res.setErrorMessage("request payload not recognized");
                    }
                }

                logicalClock++;
                Chat.ServerResponse response = res
                    .setTimestampMs(nowMs(clockOffsetMs))
                    .setLogicalClock(logicalClock)
                    .build();
                repo.logRequest(
                    req.getRequestId(),
                    operation,
                    username,
                    req.getTimestampMs(),
                    response.getTimestampMs(),
                    response.getOk(),
                    response.getErrorCode(),
                    details
                );
                System.out.printf("[%s] recv=%s send=%s local_lc=%d%n", serverName, summarize(req), summarize(response), logicalClock);
                repSocket.send(response.toByteArray());

                if (receivedClientMessages % 10 == 0) {
                    RefCallResult hbResult = callReference(
                        refSocket,
                        logicalClock,
                        Chat.ReferenceRequest.newBuilder()
                            .setTimestampMs(nowMs(clockOffsetMs))
                            .setHeartbeat(
                                Chat.RefHeartbeatRequest.newBuilder()
                                    .setServerName(serverName)
                                    .setRank(serverRank)
                                    .build())
                            .build()
                    );
                    logicalClock = hbResult.logicalClock;
                    if (hbResult.response.getOk()) {
                        clockOffsetMs = hbResult.response.getReferenceTimestampMs() - System.currentTimeMillis();
                    }
                }
            }
        }
    }

    private static RefCallResult callReference(ZMQ.Socket refSocket, long logicalClock, Chat.ReferenceRequest req) {
        long nextClock = logicalClock + 1;
        Chat.ReferenceRequest sendReq = req.toBuilder().setLogicalClock(nextClock).build();
        refSocket.send(sendReq.toByteArray());

        byte[] raw = refSocket.recv(0);
        if (raw == null) {
            return new RefCallResult(nextClock, Chat.ReferenceResponse.newBuilder().setOk(false).build());
        }

        try {
            Chat.ReferenceResponse response = Chat.ReferenceResponse.parseFrom(raw);
            long mergedClock = Math.max(nextClock, response.getLogicalClock());
            return new RefCallResult(mergedClock, response);
        } catch (Exception e) {
            return new RefCallResult(nextClock, Chat.ReferenceResponse.newBuilder().setOk(false).build());
        }
    }

    private static long nowMs(long offsetMs) {
        return Instant.now().toEpochMilli() + offsetMs;
    }

    private static String summarize(Chat.ClientRequest req) {
        return "ClientRequest{requestId=" + req.getRequestId() + ", ts=" + req.getTimestampMs() + ", lc=" + req.getLogicalClock() + ", payload=" + req.getPayloadCase() + "}";
    }

    private static String summarize(Chat.ServerResponse res) {
        return "ServerResponse{requestId=" + res.getRequestId() + ", ts=" + res.getTimestampMs() + ", lc=" + res.getLogicalClock() + ", ok=" + res.getOk() + ", errorCode='" + res.getErrorCode() + "', payload=" + res.getPayloadCase() + "}";
    }

    private static long drainReplicationEvents(ZMQ.Socket subSocket, ChatRepository repo, Set<String> activeUsers, long logicalClock) {
        while (true) {
            byte[] topicBytes = subSocket.recv(ZMQ.DONTWAIT);
            if (topicBytes == null) {
                break;
            }

            byte[] payloadBytes = subSocket.recv(0);
            if (payloadBytes == null) {
                break;
            }

            String topic = new String(topicBytes, ZMQ.CHARSET);
            if (!REPLICATION_TOPIC.equals(topic)) {
                continue;
            }

            try {
                String payload = new String(payloadBytes, ZMQ.CHARSET);
                String[] parts = payload.split("\\|", -1);
                String kind = parts[0];

                switch (kind) {
                    case "LOGIN" -> {
                        if (parts.length == 4) {
                            String username = decodeField(parts[1]);
                            long loginTsMs = Long.parseLong(parts[2]);
                            long receivedClock = Long.parseLong(parts[3]);
                            activeUsers.add(username);
                            repo.registerLogin(username, loginTsMs);
                            logicalClock = Math.max(logicalClock, receivedClock);
                        }
                    }
                    case "CREATE_CHANNEL" -> {
                        if (parts.length == 5) {
                            String channelName = decodeField(parts[1]);
                            String createdBy = decodeField(parts[2]);
                            long createdTsMs = Long.parseLong(parts[3]);
                            long receivedClock = Long.parseLong(parts[4]);
                            repo.createChannel(channelName, createdBy, createdTsMs);
                            logicalClock = Math.max(logicalClock, receivedClock);
                        }
                    }
                    case "PUBLICATION" -> {
                        if (parts.length == 7) {
                            String channelName = decodeField(parts[1]);
                            String messageText = decodeField(parts[2]);
                            String sentBy = decodeField(parts[3]);
                            long requestTsMs = Long.parseLong(parts[4]);
                            long publishedTsMs = Long.parseLong(parts[5]);
                            long receivedClock = Long.parseLong(parts[6]);
                            repo.savePublication(channelName, messageText, sentBy, requestTsMs, publishedTsMs);
                            logicalClock = Math.max(logicalClock, receivedClock);
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception e) {
                System.err.printf("[replication] failed to apply event: %s%n", e.getMessage());
            }
        }

        return logicalClock;
    }

    private static void publishReplicationEvent(ZMQ.Socket pubSocket, String kind, String... fields) {
        StringBuilder payload = new StringBuilder(kind);
        for (String field : fields) {
            payload.append('|').append(field);
        }
        pubSocket.sendMore(REPLICATION_TOPIC);
        pubSocket.send(payload.toString());
    }

    private static String encodeField(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(ZMQ.CHARSET));
    }

    private static String decodeField(String value) {
        return new String(Base64.getDecoder().decode(value), ZMQ.CHARSET);
    }

    private static final class RefCallResult {
        private final long logicalClock;
        private final Chat.ReferenceResponse response;

        private RefCallResult(long logicalClock, Chat.ReferenceResponse response) {
            this.logicalClock = logicalClock;
            this.response = response;
        }
    }
}
