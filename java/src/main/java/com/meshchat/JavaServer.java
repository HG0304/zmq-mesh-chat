package com.meshchat;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import meshchat.Chat;

public class JavaServer {
    private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,20}$");
    private static final String SERVER_SYNC_TOPIC = "servers";
    private static final String REPLICATION_TOPIC = "__replication__";
    private static final String DEFAULT_SYNC_BIND_ADDR = "tcp://*:5561";
    private static final String DEFAULT_SYNC_CONNECT_TEMPLATE = "tcp://%s:5561";
    private static final int DEFAULT_SYNC_TIMEOUT_MS = 800;
    private static final Logger LOGGER = Logger.getLogger(JavaServer.class.getName());

    public void run() {
        String serverName = System.getenv().getOrDefault("SERVER_NAME", "java-server");
        String backendAddr = System.getenv().getOrDefault("BROKER_BACKEND_ADDR", "tcp://broker:5556");
        String pubsubXsubAddr = System.getenv().getOrDefault("PUBSUB_XSUB_ADDR", "tcp://pubsub-proxy:5557");
        String pubsubXpubAddr = System.getenv().getOrDefault("PUBSUB_XPUB_ADDR", "tcp://pubsub-proxy:5558");
        String referenceAddr = System.getenv().getOrDefault("REFERENCE_ADDR", "tcp://reference:5560");
        String dbPath = System.getenv().getOrDefault("DB_PATH", "/data/chat.db");
        String syncBindAddr = System.getenv().getOrDefault("SERVER_SYNC_BIND_ADDR", DEFAULT_SYNC_BIND_ADDR);
        String syncConnectTemplate = System.getenv().getOrDefault("SERVER_SYNC_CONNECT_TEMPLATE", DEFAULT_SYNC_CONNECT_TEMPLATE);
        int syncTimeoutMs = Integer.parseInt(System.getenv().getOrDefault("SERVER_SYNC_TIMEOUT_MS", String.valueOf(DEFAULT_SYNC_TIMEOUT_MS)));

        ChatRepository repo = new ChatRepository(dbPath);
        Set<String> activeUsers = new HashSet<>();
        ServerState state = new ServerState();

        try (ZContext context = new ZContext()) {
            ZMQ.Socket repSocket = context.createSocket(SocketType.REP);
            repSocket.connect(backendAddr);

            ZMQ.Socket pubSocket = context.createSocket(SocketType.PUB);
            pubSocket.connect(pubsubXsubAddr);

            ZMQ.Socket subSocket = context.createSocket(SocketType.SUB);
            subSocket.connect(pubsubXpubAddr);
            subSocket.subscribe(SERVER_SYNC_TOPIC.getBytes(ZMQ.CHARSET));
            subSocket.subscribe(REPLICATION_TOPIC.getBytes(ZMQ.CHARSET));

            ZMQ.Socket syncRepSocket = context.createSocket(SocketType.REP);
            syncRepSocket.bind(syncBindAddr);
            ZMQ.Socket refSocket = context.createSocket(SocketType.REQ);
            refSocket.connect(referenceAddr);

            RefCallResult registerResult = callReference(
                refSocket,
                state.logicalClock,
                Chat.ReferenceRequest.newBuilder()
                    .setTimestampMs(nowMs(state.clockOffsetMs))
                    .setRegisterServer(Chat.RefRegisterServerRequest.newBuilder().setServerName(serverName).build())
                    .build()
            );
            state.logicalClock = registerResult.logicalClock;
            if (registerResult.response.getOk()) {
                state.serverRank = registerResult.response.getRegisterServer().getRank();
                state.clockOffsetMs = registerResult.response.getReferenceTimestampMs() - System.currentTimeMillis();
            }

            ServerListResult listResult = refreshServers(refSocket, state.logicalClock, state.clockOffsetMs);
            state.logicalClock = listResult.logicalClock;
            state.knownRanks = listResult.ranks;
            if (!state.knownRanks.isEmpty()) {
                state.coordinatorName = getHighestRankServer(state.knownRanks);
            }

            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info(String.format(
                    "[%s] connected backend=%s pubsub_xsub=%s reference=%s rank=%d known_servers=%d db=%s",
                    serverName,
                    backendAddr,
                    pubsubXsubAddr,
                    referenceAddr,
                    state.serverRank,
                    state.knownRanks.size(),
                    dbPath
                ));
            }

            ZMQ.Poller poller = context.createPoller(3);
            int repIdx = poller.register(repSocket, ZMQ.Poller.POLLIN);
            int syncIdx = poller.register(syncRepSocket, ZMQ.Poller.POLLIN);
            int subIdx = poller.register(subSocket, ZMQ.Poller.POLLIN);

            if (serverName.equals(state.coordinatorName)) {
                announceCoordinator(pubSocket, state.coordinatorName);
            }

            while (!Thread.currentThread().isInterrupted()) {
                poller.poll(1000);
                if (poller.pollin(subIdx)) {
                    drainPubSubEvents(subSocket, serverName, state, repo, activeUsers);
                }

                if (poller.pollin(syncIdx)) {
                    processSyncMessage(syncRepSocket, serverName, state, () ->
                        startElection(context, refSocket, pubSocket, serverName, state, syncTimeoutMs, syncConnectTemplate)
                    );
                }

                if (poller.pollin(repIdx)) {
                    byte[] raw = repSocket.recv(0);
                    if (raw == null) {
                        continue;
                    }
                    handleClientRequest(repo, activeUsers, pubSocket, repSocket, raw, serverName, state);
                    maybeSendHeartbeat(refSocket, serverName, state);
                    maybeSyncClock(context, serverName, state, syncTimeoutMs, syncConnectTemplate, () ->
                        startElection(context, refSocket, pubSocket, serverName, state, syncTimeoutMs, syncConnectTemplate)
                    );
                }
            }
        }
    }

    private static void handleClientRequest(
        ChatRepository repo,
        Set<String> activeUsers,
        ZMQ.Socket pubSocket,
        ZMQ.Socket repSocket,
        byte[] raw,
        String serverName,
        ServerState state
    ) {
        Chat.ClientRequest req;
        try {
            req = Chat.ClientRequest.parseFrom(raw);
        } catch (Exception e) {
            state.logicalClock++;
            Chat.ServerResponse response = Chat.ServerResponse.newBuilder()
                .setRequestId(0)
                .setTimestampMs(nowMs(state.clockOffsetMs))
                .setLogicalClock(state.logicalClock)
                .setOk(false)
                .setErrorCode("BAD_PROTOBUF")
                .setErrorMessage("failed to parse request")
                .build();
            repSocket.send(response.toByteArray());
            return;
        }

        state.logicalClock = Math.max(state.logicalClock, req.getLogicalClock());
        state.receivedClientMessages++;

        Chat.ServerResponse.Builder res = Chat.ServerResponse.newBuilder()
            .setRequestId(req.getRequestId())
            .setTimestampMs(nowMs(state.clockOffsetMs))
            .setOk(false);
        String operation = "unknown";
        String username = "";
        String details = "";

        switch (req.getPayloadCase()) {
            case LOGIN -> {
                operation = "login";
                ClientHandlingInfo info = handleLogin(req, res, activeUsers, repo, pubSocket, state);
                username = info.username;
                details = info.details;
            }
            case CREATE_CHANNEL -> {
                operation = "create_channel";
                ClientHandlingInfo info = handleCreateChannel(req, res, repo, pubSocket, state);
                username = info.username;
                details = info.details;
            }
            case LIST_CHANNELS -> {
                operation = "list_channels";
                details = handleListChannels(res, repo);
            }
            case PUBLISH_IN_CHANNEL -> {
                operation = "publish_in_channel";
                ClientHandlingInfo info = handlePublishInChannel(req, res, repo, pubSocket, state);
                username = info.username;
                details = info.details;
            }
            case PAYLOAD_NOT_SET -> {
                res.setErrorCode("UNKNOWN_REQUEST");
                res.setErrorMessage("request payload not recognized");
            }
        }

        state.logicalClock++;
        Chat.ServerResponse response = res
            .setTimestampMs(nowMs(state.clockOffsetMs))
            .setLogicalClock(state.logicalClock)
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
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(String.format("[%s] recv=%s send=%s local_lc=%d", serverName, req.getPayloadCase(), response.getPayloadCase(), state.logicalClock));
        }
        repSocket.send(response.toByteArray());
    }

    private static ClientHandlingInfo handleLogin(
        Chat.ClientRequest req,
        Chat.ServerResponse.Builder res,
        Set<String> activeUsers,
        ChatRepository repo,
        ZMQ.Socket pubSocket,
        ServerState state
    ) {
        String username = req.getLogin().getUsername();
        String details = "username=" + username;
        if (activeUsers.contains(username)) {
            res.setErrorCode("USER_ACTIVE");
            res.setErrorMessage("username '" + username + "' already active");
        } else {
            activeUsers.add(username);
            long loginTsMs = nowMs(state.clockOffsetMs);
            repo.registerLogin(username, loginTsMs);
            res.setOk(true);
            res.setLogin(Chat.LoginResponse.newBuilder().setUsername(username).build());
            publishReplicationEvent(
                pubSocket,
                "LOGIN",
                encodeField(username),
                Long.toString(loginTsMs),
                Long.toString(state.logicalClock)
            );
        }
        return new ClientHandlingInfo(username, details);
    }

    private static ClientHandlingInfo handleCreateChannel(
        Chat.ClientRequest req,
        Chat.ServerResponse.Builder res,
        ChatRepository repo,
        ZMQ.Socket pubSocket,
        ServerState state
    ) {
        String channelName = req.getCreateChannel().getChannelName();
        String requestedBy = req.getCreateChannel().getRequestedBy();
        String details = "channel=" + channelName;
        if (!CHANNEL_PATTERN.matcher(channelName).matches()) {
            res.setErrorCode("INVALID_CHANNEL_NAME");
            res.setErrorMessage("channel must match ^[A-Za-z0-9_-]{3,20}$");
            return new ClientHandlingInfo(requestedBy, details);
        }

        long createdTsMs = nowMs(state.clockOffsetMs);
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
                Long.toString(state.logicalClock)
            );
        }
        return new ClientHandlingInfo(requestedBy, details);
    }

    private static String handleListChannels(Chat.ServerResponse.Builder res, ChatRepository repo) {
        res.setOk(true);
        res.setListChannels(Chat.ListChannelsResponse.newBuilder().addAllChannels(repo.listChannels()).build());
        return "all";
    }

    private static ClientHandlingInfo handlePublishInChannel(
        Chat.ClientRequest req,
        Chat.ServerResponse.Builder res,
        ChatRepository repo,
        ZMQ.Socket pubSocket,
        ServerState state
    ) {
        String channelName = req.getPublishInChannel().getChannelName();
        String messageText = req.getPublishInChannel().getMessage();
        String requestedBy = req.getPublishInChannel().getRequestedBy();
        String details = "channel=" + channelName + ";message_len=" + messageText.length();

        if (!repo.channelExists(channelName)) {
            res.setErrorCode("CHANNEL_NOT_FOUND");
            res.setErrorMessage("channel '" + channelName + "' does not exist");
            return new ClientHandlingInfo(requestedBy, details);
        }
        if (messageText == null || messageText.isBlank()) {
            res.setErrorCode("EMPTY_MESSAGE");
            res.setErrorMessage("message must not be empty");
            return new ClientHandlingInfo(requestedBy, details);
        }

        state.logicalClock++;
        long publishTs = nowMs(state.clockOffsetMs);
        Chat.ChannelMessageEvent event = Chat.ChannelMessageEvent.newBuilder()
            .setChannelName(channelName)
            .setMessage(messageText)
            .setSentBy(requestedBy)
            .setRequestTimestampMs(req.getTimestampMs())
            .setPublishedTimestampMs(publishTs)
            .setLogicalClock(state.logicalClock)
            .build();

        pubSocket.sendMore(channelName);
        pubSocket.send(event.toByteArray());

        repo.savePublication(channelName, messageText, requestedBy, req.getTimestampMs(), publishTs);
        res.setOk(true);
        res.setPublishInChannel(
            Chat.PublishInChannelResponse.newBuilder()
                .setChannelName(channelName)
                .setPublishedTimestampMs(publishTs)
                .build()
        );
        publishReplicationEvent(
            pubSocket,
            "PUBLICATION",
            encodeField(channelName),
            encodeField(messageText),
            encodeField(requestedBy),
            Long.toString(req.getTimestampMs()),
            Long.toString(publishTs),
            Long.toString(state.logicalClock)
        );
        return new ClientHandlingInfo(requestedBy, details);
    }

    private static void drainPubSubEvents(
        ZMQ.Socket subSocket,
        String serverName,
        ServerState state,
        ChatRepository repo,
        Set<String> activeUsers
    ) {
        while (true) {
            byte[] topicBytes = subSocket.recv(ZMQ.DONTWAIT);
            if (topicBytes == null) {
                return;
            }

            byte[] payloadBytes = subSocket.recv(0);
            if (payloadBytes == null) {
                return;
            }

            String topic = new String(topicBytes, ZMQ.CHARSET);
            String payload = new String(payloadBytes, ZMQ.CHARSET);
            handlePubSubMessage(topic, payload, serverName, state, repo, activeUsers);
        }
    }

    private static void handlePubSubMessage(
        String topic,
        String payload,
        String serverName,
        ServerState state,
        ChatRepository repo,
        Set<String> activeUsers
    ) {
        if (SERVER_SYNC_TOPIC.equals(topic)) {
            state.coordinatorName = payload;
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info(String.format("[%s] coordinator=%s", serverName, state.coordinatorName));
            }
            return;
        }

        if (REPLICATION_TOPIC.equals(topic)) {
            applyReplicationEvent(payload, state, repo, activeUsers);
        }
    }

    private static void applyReplicationEvent(
        String payload,
        ServerState state,
        ChatRepository repo,
        Set<String> activeUsers
    ) {
        try {
            String[] parts = payload.split("\\|", -1);
            String kind = parts[0];

            switch (kind) {
                case "LOGIN" -> applyLoginReplication(parts, state, repo, activeUsers);
                case "CREATE_CHANNEL" -> applyChannelReplication(parts, state, repo);
                case "PUBLICATION" -> applyPublicationReplication(parts, state, repo);
                default -> {
                    // ignore unknown replication events
                }
            }
        } catch (Exception e) {
            // ignore malformed replication events
        }
    }

    private static void applyLoginReplication(
        String[] parts,
        ServerState state,
        ChatRepository repo,
        Set<String> activeUsers
    ) {
        if (parts.length != 4) {
            return;
        }
        String username = decodeField(parts[1]);
        long loginTsMs = Long.parseLong(parts[2]);
        long receivedClock = Long.parseLong(parts[3]);
        activeUsers.add(username);
        repo.registerLogin(username, loginTsMs);
        state.logicalClock = Math.max(state.logicalClock, receivedClock);
    }

    private static void applyChannelReplication(
        String[] parts,
        ServerState state,
        ChatRepository repo
    ) {
        if (parts.length != 5) {
            return;
        }
        String channelName = decodeField(parts[1]);
        String createdBy = decodeField(parts[2]);
        long createdTsMs = Long.parseLong(parts[3]);
        long receivedClock = Long.parseLong(parts[4]);
        repo.createChannel(channelName, createdBy, createdTsMs);
        state.logicalClock = Math.max(state.logicalClock, receivedClock);
    }

    private static void applyPublicationReplication(
        String[] parts,
        ServerState state,
        ChatRepository repo
    ) {
        if (parts.length != 7) {
            return;
        }
        String channelName = decodeField(parts[1]);
        String messageText = decodeField(parts[2]);
        String sentBy = decodeField(parts[3]);
        long requestTsMs = Long.parseLong(parts[4]);
        long publishedTsMs = Long.parseLong(parts[5]);
        long receivedClock = Long.parseLong(parts[6]);
        repo.savePublication(channelName, messageText, sentBy, requestTsMs, publishedTsMs);
        state.logicalClock = Math.max(state.logicalClock, receivedClock);
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

    private static void processSyncMessage(
        ZMQ.Socket syncRepSocket,
        String serverName,
        ServerState state,
        Runnable startElectionCb
    ) {
        String raw = syncRepSocket.recvStr();
        String[] parts = decodeControlMessage(raw);
        if (parts.length == 0) {
            syncRepSocket.send(encodeControlMessage("ERROR", "0"));
            return;
        }

        if ("CLOCK".equals(parts[0])) {
            handleClockSyncMessage(syncRepSocket, serverName, state, parts);
            return;
        }

        if ("ELECTION".equals(parts[0])) {
            handleElectionSyncMessage(syncRepSocket, state, parts, startElectionCb);
            return;
        }

        syncRepSocket.send(encodeControlMessage("ERROR", "0"));
    }

    private static void handleClockSyncMessage(
        ZMQ.Socket syncRepSocket,
        String serverName,
        ServerState state,
        String[] parts
    ) {
        long receivedClock = parts.length > 2 ? parseLongSafe(parts[2]) : 0;
        long senderTimeMs = parts.length > 3 ? parseLongSafe(parts[3]) : 0;
        state.logicalClock = Math.max(state.logicalClock, receivedClock);
        state.logicalClock++;
        if (serverName.equals(state.coordinatorName)) {
            long coordinatorTimeMs = nowMs(state.clockOffsetMs);
            long berkeleyTimeMs = computeBerkeleyTime(
                state,
                parts.length > 1 ? parts[1] : "",
                senderTimeMs > 0 ? senderTimeMs : coordinatorTimeMs,
                coordinatorTimeMs
            );
            syncRepSocket.send(encodeControlMessage("TIME", String.valueOf(berkeleyTimeMs), String.valueOf(state.logicalClock)));
        } else {
            syncRepSocket.send(encodeControlMessage("NOT_COORDINATOR", state.coordinatorName, String.valueOf(state.logicalClock)));
        }
    }

    private static void handleElectionSyncMessage(
        ZMQ.Socket syncRepSocket,
        ServerState state,
        String[] parts,
        Runnable startElectionCb
    ) {
        long receivedClock = parts.length > 3 ? parseLongSafe(parts[3]) : 0;
        state.logicalClock = Math.max(state.logicalClock, receivedClock);
        state.logicalClock++;
        syncRepSocket.send(encodeControlMessage("OK", String.valueOf(state.logicalClock)));

        int senderRank = parts.length > 2 ? (int) parseLongSafe(parts[2]) : 0;
        if (state.serverRank > senderRank) {
            startElectionCb.run();
        }
    }

    private static void maybeSendHeartbeat(ZMQ.Socket refSocket, String serverName, ServerState state) {
        if (state.receivedClientMessages % 10 != 0) {
            return;
        }
        RefCallResult hbResult = callReference(
            refSocket,
            state.logicalClock,
            Chat.ReferenceRequest.newBuilder()
                .setTimestampMs(nowMs(state.clockOffsetMs))
                .setHeartbeat(
                    Chat.RefHeartbeatRequest.newBuilder()
                        .setServerName(serverName)
                        .setRank(state.serverRank)
                        .build())
                .build()
        );
        state.logicalClock = hbResult.logicalClock;
    }

    private static void maybeSyncClock(
        ZContext context,
        String serverName,
        ServerState state,
        int syncTimeoutMs,
        String syncConnectTemplate,
        Runnable startElectionCb
    ) {
        if (state.receivedClientMessages % 15 != 0) {
            return;
        }
        if (state.coordinatorName == null || state.coordinatorName.isBlank() || !state.knownRanks.containsKey(state.coordinatorName)) {
            startElectionCb.run();
        }
        Long coordinatorTs = requestClockFromCoordinator(context, serverName, state, syncTimeoutMs, syncConnectTemplate);
        if (coordinatorTs == null) {
            startElectionCb.run();
            return;
        }
        state.clockOffsetMs = coordinatorTs - System.currentTimeMillis();
    }

    private static void startElection(
        ZContext context,
        ZMQ.Socket refSocket,
        ZMQ.Socket pubSocket,
        String serverName,
        ServerState state,
        int syncTimeoutMs,
        String syncConnectTemplate
    ) {
        ServerListResult listResult = refreshServers(refSocket, state.logicalClock, state.clockOffsetMs);
        state.logicalClock = listResult.logicalClock;
        state.knownRanks = listResult.ranks;

        String highest = getHighestRankServer(state.knownRanks);
        if (highest == null || highest.equals(serverName)) {
            state.coordinatorName = serverName;
            announceCoordinator(pubSocket, state.coordinatorName);
            return;
        }

        boolean anyOk = false;
        for (Map.Entry<String, Integer> entry : state.knownRanks.entrySet()) {
            String target = entry.getKey();
            int rank = entry.getValue();
            if (rank <= state.serverRank || target.equals(serverName)) {
                continue;
            }
            if (sendElectionRequest(context, state, target, serverName, syncTimeoutMs, syncConnectTemplate)) {
                anyOk = true;
            }
        }

        if (!anyOk) {
            state.coordinatorName = serverName;
            announceCoordinator(pubSocket, state.coordinatorName);
        } else {
            state.coordinatorName = "";
        }
    }

    private static void announceCoordinator(ZMQ.Socket pubSocket, String coordinatorName) {
        pubSocket.sendMore(SERVER_SYNC_TOPIC);
        pubSocket.send(coordinatorName);
    }

    private static ServerListResult refreshServers(ZMQ.Socket refSocket, long logicalClock, long clockOffsetMs) {
        RefCallResult listResult = callReference(
            refSocket,
            logicalClock,
            Chat.ReferenceRequest.newBuilder()
                .setTimestampMs(nowMs(clockOffsetMs))
                .setListServers(Chat.RefListServersRequest.newBuilder().build())
                .build()
        );
        Map<String, Integer> ranks = new HashMap<>();
        if (listResult.response.getOk()) {
            for (Chat.ServerInfo info : listResult.response.getListServers().getServersList()) {
                ranks.put(info.getServerName(), info.getRank());
            }
        }
        return new ServerListResult(listResult.logicalClock, ranks);
    }

    private static String getHighestRankServer(Map<String, Integer> ranks) {
        String highest = null;
        int maxRank = -1;
        for (Map.Entry<String, Integer> entry : ranks.entrySet()) {
            if (entry.getValue() > maxRank) {
                maxRank = entry.getValue();
                highest = entry.getKey();
            }
        }
        return highest;
    }

    private static Long requestClockFromCoordinator(
        ZContext context,
        String serverName,
        ServerState state,
        int syncTimeoutMs,
        String syncConnectTemplate
    ) {
        if (state.coordinatorName == null || state.coordinatorName.isBlank()) {
            return null;
        }
        ZMQ.Socket socket = context.createSocket(SocketType.REQ);
        socket.setReceiveTimeOut(syncTimeoutMs);
        socket.setSendTimeOut(syncTimeoutMs);
        socket.connect(String.format(syncConnectTemplate, state.coordinatorName));

        state.logicalClock++;
        socket.send(
            encodeControlMessage(
                "CLOCK",
                serverName,
                String.valueOf(state.logicalClock),
                String.valueOf(nowMs(state.clockOffsetMs))
            )
        );
        String reply = socket.recvStr();
        socket.close();
        if (reply == null) {
            return null;
        }

        String[] parts = decodeControlMessage(reply);
        if (parts.length < 3) {
            return null;
        }
        long receivedClock = parseLongSafe(parts[2]);
        state.logicalClock = Math.max(state.logicalClock, receivedClock);
        if (!"TIME".equals(parts[0])) {
            return null;
        }
        return parseLongSafe(parts[1]);
    }

    private static boolean sendElectionRequest(
        ZContext context,
        ServerState state,
        String targetName,
        String serverName,
        int syncTimeoutMs,
        String syncConnectTemplate
    ) {
        ZMQ.Socket socket = context.createSocket(SocketType.REQ);
        socket.setReceiveTimeOut(syncTimeoutMs);
        socket.setSendTimeOut(syncTimeoutMs);
        socket.connect(String.format(syncConnectTemplate, targetName));

        state.logicalClock++;
        socket.send(encodeControlMessage("ELECTION", serverName, String.valueOf(state.serverRank), String.valueOf(state.logicalClock)));
        String reply = socket.recvStr();
        socket.close();
        if (reply == null) {
            return false;
        }

        String[] parts = decodeControlMessage(reply);
        if (parts.length < 2) {
            return false;
        }
        long receivedClock = parseLongSafe(parts[1]);
        state.logicalClock = Math.max(state.logicalClock, receivedClock);
        return "OK".equals(parts[0]);
    }

    private static String encodeControlMessage(String... parts) {
        return String.join("|", parts);
    }

    private static String[] decodeControlMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return raw.split("\\|");
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
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

    private static long computeBerkeleyTime(
        ServerState state,
        String senderName,
        long senderTimeMs,
        long coordinatorTimeMs
    ) {
        if (senderName != null && !senderName.isBlank()) {
            state.lastReportedTimes.put(senderName, senderTimeMs);
        }
        long sum = coordinatorTimeMs;
        int count = 1;
        for (long value : state.lastReportedTimes.values()) {
            sum += value;
            count++;
        }
        return count == 0 ? coordinatorTimeMs : Math.round((double) sum / count);
    }
    private static final class RefCallResult {
        private final long logicalClock;
        private final Chat.ReferenceResponse response;

        private RefCallResult(long logicalClock, Chat.ReferenceResponse response) {
            this.logicalClock = logicalClock;
            this.response = response;
        }
    }

    private static final class ServerListResult {
        private final long logicalClock;
        private final Map<String, Integer> ranks;

        private ServerListResult(long logicalClock, Map<String, Integer> ranks) {
            this.logicalClock = logicalClock;
            this.ranks = ranks;
        }
    }

    private static final class ServerState {
        private long logicalClock = 0;
        private long clockOffsetMs = 0;
        private int serverRank = 0;
        private int receivedClientMessages = 0;
        private String coordinatorName = "";
        private Map<String, Integer> knownRanks = new HashMap<>();
        private Map<String, Long> lastReportedTimes = new HashMap<>();
    }

    private static final class ClientHandlingInfo {
        private final String username;
        private final String details;

        private ClientHandlingInfo(String username, String details) {
            this.username = username;
            this.details = details;
        }
    }
}
