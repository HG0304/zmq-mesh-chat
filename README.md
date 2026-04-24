# zmq-mesh-chat

Projeto da disciplina de Sistemas Distribuídos implementando um chat distribuído com ZeroMQ.

Esta versão cobre as Partes 1, 2 e 3 do enunciado:
- login de usuário (bot)
- criação e listagem de canais
- publicação em canais via Pub/Sub
- inscrição dos bots em múltiplos canais
- persistência em disco de operações e publicações
- relógio lógico (Lamport) em clientes, servidores e eventos Pub/Sub
- serviço de referência para rank, heartbeat e sincronização de relógio físico dos servidores

## Arquitetura

- `broker`: proxy de requisições síncronas REQ/REP (ROUTER/DEALER) nas portas `5555/5556`
- `pubsub-proxy`: proxy de publicação/assinatura (XSUB/XPUB) nas portas `5557/5558`
- `reference`: serviço de referência (REQ/REP) na porta `5560`
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

Novidades da Parte 3:
- campo `logical_clock` em `ClientRequest`, `ServerResponse` e `ChannelMessageEvent`
- mensagens `ReferenceRequest` / `ReferenceResponse`
- mensagens auxiliares de referência: `ServerInfo`, `RefRegisterServer*`, `RefListServers*`, `RefHeartbeat*`

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

## Parte 3: relógios e heartbeat

- Cada envio incrementa o contador lógico local e o valor é enviado na mensagem.
- Ao receber mensagem, o processo atualiza seu contador lógico para o máximo entre local e recebido.
- Servidores registram-se no serviço `reference` para obter rank.
- A cada 10 mensagens de clientes processadas, o servidor envia heartbeat ao `reference`.
- O `reference` remove servidores inativos por TTL e retorna horário de referência.
- Servidores ajustam o relógio físico local por offset com base no horário retornado.

## Como executar

Subir tudo:

```bash
docker compose up --build
```

## Estrutura principal

- `docker-compose.yaml`: orquestração completa
- `broker/broker.py`: broker REQ/REP
- `broker/pubsub_proxy.py`: proxy XSUB/XPUB
- `python/src/reference.py`: serviço de referência para rank/heartbeat/tempo
- `python/src`: cliente/servidor Python
- `java/src/main/java/com/meshchat`: cliente/servidor Java
- `proto/chat.proto`: contrato principal
