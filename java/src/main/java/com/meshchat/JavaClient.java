package com.meshchat;

import java.time.Instant;
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
        String createChannelName = System.getenv().getOrDefault("CREATE_CHANNEL_NAME", username + "_ch");

        long requestId = ThreadLocalRandom.current().nextLong(1, 10_000);

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REQ);
            socket.connect(frontendAddr);
            System.out.printf("[%s] connected frontend=%s username=%s%n", clientName, frontendAddr, username);

            while (!Thread.currentThread().isInterrupted()) {
                requestId++;
                Chat.ClientRequest loginReq = Chat.ClientRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTimestampMs(nowMs())
                    .setLogin(Chat.LoginRequest.newBuilder().setUsername(username).build())
                    .build();
                send(socket, clientName, loginReq);
                Chat.ServerResponse loginRes = recv(socket, clientName);
                if (!loginRes.getOk()) {
                    sleep(5000);
                    continue;
                }

                requestId++;
                Chat.ClientRequest createReq = Chat.ClientRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTimestampMs(nowMs())
                    .setCreateChannel(
                        Chat.CreateChannelRequest.newBuilder()
                            .setChannelName(createChannelName)
                            .setRequestedBy(username)
                            .build())
                    .build();
                send(socket, clientName, createReq);
                recv(socket, clientName);

                requestId++;
                Chat.ClientRequest listReq = Chat.ClientRequest.newBuilder()
                    .setRequestId(requestId)
                    .setTimestampMs(nowMs())
                    .setListChannels(Chat.ListChannelsRequest.newBuilder().build())
                    .build();
                send(socket, clientName, listReq);
                recv(socket, clientName);

                sleep(10_000);
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
}
