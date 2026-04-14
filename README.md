# zmq-mesh-chat

Projeto da disciplina de Sistemas Distribuídos implementando um chat distribuído com ZeroMQ.

Esta versão cobre as Partes 1 e 2 do enunciado:
- login de usuário (bot)
- criação e listagem de canais
- publicação em canais via Pub/Sub
- inscrição dos bots em múltiplos canais
- persistência em disco de operações e publicações

## Arquitetura

- `broker`: proxy de requisições síncronas REQ/REP (ROUTER/DEALER) nas portas `5555/5556`
- `pubsub-proxy`: proxy de publicação/assinatura (XSUB/XPUB) nas portas `5557/5558`
- `py-server-*` e `java-server-*`: servidores (consomem REQ/REP e publicam em Pub/Sub)
- `py-client-*` e `java-client-*`: bots (fazem REQ/REP e se inscrevem em tópicos)

## Fluxo de publicação

1. Cliente envia `REQ` ao servidor com canal + mensagem + timestamp de envio.
2. Servidor valida o canal e publica o evento no tópico correspondente.
3. Servidor responde `REP` com status de publicação.
4. Clientes inscritos no tópico recebem o evento e exibem:
	- canal
	- mensagem
	- timestamp de envio
	- timestamp de recebimento local

## Contrato de mensagens

As mensagens usam Protocol Buffers no arquivo `proto/chat.proto`.

Novidades da Parte 2:
- `PublishInChannelRequest`
- `PublishInChannelResponse`
- `ChannelMessageEvent` (payload de Pub/Sub)

## Persistência

Cada servidor mantém um SQLite local em volume Docker dedicado.

Tabelas:
- `logins`: histórico de login
- `channels`: canais criados
- `publications`: mensagens publicadas em canais
- `request_logs`: log de requisições processadas (operação, usuário, timestamps, status e erro)

## Regras dos bots (Parte 2)

Após conectar:
1. se existirem menos de 5 canais, cria um novo canal;
2. se estiver inscrito em menos de 3 canais, inscreve-se em mais um;
3. entra em loop escolhendo canal aleatório e enviando 10 mensagens com intervalo de 1 segundo.

## Como executar

Subir tudo:

```bash
docker compose up --build
```

## Estrutura principal

- `docker-compose.yaml`: orquestração completa
- `broker/broker.py`: broker REQ/REP
- `broker/pubsub_proxy.py`: proxy XSUB/XPUB
- `python/src`: cliente/servidor Python
- `java/src/main/java/com/meshchat`: cliente/servidor Java
- `proto/chat.proto`: contrato principal