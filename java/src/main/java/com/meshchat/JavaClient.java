package com.meshchat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import meshchat.Chat;

public class JavaClient {
    public void run() {
        String clientName = System.getenv().getOrDefault("CLIENT_NAME", "java-client");
        String username = System.getenv().getOrDefault("USERNAME", clientName);
        String frontendAddr = System.getenv().getOrDefault("BROKER_FRONTEND_ADDR", "tcp://broker:5555");
        String pubsubXpubAddr = System.getenv().getOrDefault("PUBSUB_XPUB_ADDR", "tcp://pubsub-proxy:5558");
        String createChannelName = System.getenv().getOrDefault("CREATE_CHANNEL_NAME", username + "_ch");

        long requestId = ThreadLocalRandom.current().nextLong(1, 10_000);
        Set<String> subscribedChannels = new HashSet<>();

        try (ZContext context = new ZContext()) {
            ZMQ.Socket reqSocket = context.createSocket(SocketType.REQ);
            reqSocket.connect(frontendAddr);

            ZMQ.Socket subSocket = context.createSocket(SocketType.SUB);
            subSocket.connect(pubsubXpubAddr);

            System.out.printf("[%s] connected frontend=%s pubsub_xpub=%s username=%s%n", clientName, frontendAddr, pubsubXpubAddr, username);

            while (!Thread.currentThread().isInterrupted()) {
                requestId++;
                Chat.ClientRequest loginReq = Chat.ClientRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTimestampMs(nowMs())
                    .setLogin(Chat.LoginRequest.newBuilder().setUsername(username).build())
                    .build();
                send(reqSocket, clientName, loginReq);
                Chat.ServerResponse loginRes = recv(reqSocket, clientName);
                if (loginRes.getOk()) {
                    break;
                }
                sleep(5000);
            }

            while (!Thread.currentThread().isInterrupted()) {
                requestId++;
                Chat.ClientRequest listReq = Chat.ClientRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTimestampMs(nowMs())
                    .setListChannels(Chat.ListChannelsRequest.newBuilder().build())
                    .build();
                send(reqSocket, clientName, listReq);
                Chat.ServerResponse listRes = recv(reqSocket, clientName);

                List<String> channels = new ArrayList<>();
                if (listRes.getOk()) {
                    channels.addAll(listRes.getListChannels().getChannelsList());
                }

                if (channels.size() < 5) {
                    requestId++;
                    String channelToCreate = channels.contains(createChannelName)
                        ? randomChannelName(username)
                        : createChannelName;
                    Chat.ClientRequest createReq = Chat.ClientRequest.newBuilder()
                        .setRequestId(requestId)
                        .setTimestampMs(nowMs())
                        .setCreateChannel(
                            Chat.CreateChannelRequest.newBuilder()
                                .setChannelName(channelToCreate)
                                .setRequestedBy(username)
                                .build())
                        .build();
                    send(reqSocket, clientName, createReq);
                    recv(reqSocket, clientName);
                }

                requestId++;
                Chat.ClientRequest refreshReq = Chat.ClientRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTimestampMs(nowMs())
                    .setListChannels(Chat.ListChannelsRequest.newBuilder().build())
                    .build();
                send(reqSocket, clientName, refreshReq);
                Chat.ServerResponse refreshRes = recv(reqSocket, clientName);

                channels.clear();
                if (refreshRes.getOk()) {
                    channels.addAll(refreshRes.getListChannels().getChannelsList());
                }

                if (subscribedChannels.size() < 3) {
                    List<String> candidates = channels.stream().filter(ch -> !subscribedChannels.contains(ch)).toList();
                    if (!candidates.isEmpty()) {
                        String selected = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                        subSocket.subscribe(selected.getBytes(ZMQ.CHARSET));
                        subscribedChannels.add(selected);
                        System.out.printf("[%s] subscribed channel=%s total=%d%n", clientName, selected, subscribedChannels.size());
                        drainSubMessages(subSocket, clientName);
                    }
                }

                if (channels.isEmpty()) {
                    sleep(1000);
                    continue;
                }

                String selectedChannel = channels.get(ThreadLocalRandom.current().nextInt(channels.size()));
                for (int i = 0; i < 10; i++) {
                    requestId++;
                    Chat.ClientRequest publishReq = Chat.ClientRequest.newBuilder()
                        .setRequestId(requestId)
                        .setTimestampMs(nowMs())
                        .setPublishInChannel(
                            Chat.PublishInChannelRequest.newBuilder()
                                .setChannelName(selectedChannel)
                                .setMessage(randomMessage())
                                .setRequestedBy(username)
                                .build())
                        .build();
                    send(reqSocket, clientName, publishReq);
                    recv(reqSocket, clientName);
                    drainSubMessages(subSocket, clientName);
                    sleep(1000);
                }
            }
        }
    }

    private static long nowMs() {
        return Instant.now().toEpochMilli();
    }

    private static void send(ZMQ.Socket socket, String clientName, Chat.ClientRequest req) {
        System.out.printf("[%s] send=%s%n", clientName, summarize(req));
        socket.send(req.toByteArray());
    }

    private static Chat.ServerResponse recv(ZMQ.Socket socket, String clientName) {
        byte[] raw = socket.recv(0);
        if (raw == null) {
            throw new RuntimeException("failed to receive response");
        }
        try {
            Chat.ServerResponse res = Chat.ServerResponse.parseFrom(raw);
            System.out.printf("[%s] recv=%s%n", clientName, summarize(res));
            return res;
        } catch (Exception e) {
            throw new RuntimeException("failed to parse response", e);
        }
    }

    private static void drainSubMessages(ZMQ.Socket subSocket, String clientName) {
        while (true) {
            byte[] topicBytes = subSocket.recv(ZMQ.DONTWAIT);
            if (topicBytes == null) {
                break;
            }
            byte[] payload = subSocket.recv(0);
            if (payload == null) {
                break;
            }
            String topic = new String(topicBytes, ZMQ.CHARSET);
            try {
                Chat.ChannelMessageEvent event = Chat.ChannelMessageEvent.parseFrom(payload);
                System.out.printf(
                    "[%s] sub_recv channel=%s msg=%s sent_ts_ms=%d recv_ts_ms=%d%n",
                    clientName,
                    topic,
                    event.getMessage(),
                    event.getRequestTimestampMs(),
                    nowMs()
                );
            } catch (Exception e) {
                System.err.printf("[%s] sub_parse_error=%s%n", clientName, e.getMessage());
            }
        }
    }

    private static String summarize(Chat.ClientRequest req) {
        return "ClientRequest{requestId=" + req.getRequestId() + ", ts=" + req.getTimestampMs() + ", payload=" + req.getPayloadCase() + "}";
    }

    private static String summarize(Chat.ServerResponse res) {
        return "ServerResponse{requestId=" + res.getRequestId() + ", ts=" + res.getTimestampMs() + ", ok=" + res.getOk() + ", errorCode='" + res.getErrorCode() + "', payload=" + res.getPayloadCase() + "}";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String randomChannelName(String username) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int idx = ThreadLocalRandom.current().nextInt(alphabet.length());
            suffix.append(alphabet.charAt(idx));
        }
        String value = username + "_" + suffix;
        return value.length() > 20 ? value.substring(0, 20) : value;
    }

    private static String randomMessage() {
        String[] samples = {
            "mesh chat canal bot",
            "sistemas distribuidos pub sub",
            "zeromq broker publisher subscriber",
            "mensagem aleatoria para teste"
        };
        int idx = ThreadLocalRandom.current().nextInt(samples.length);
        return samples[idx];
    }
}
