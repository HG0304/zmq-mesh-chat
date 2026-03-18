# zmq-mesh-chat

Projeto da disciplina de Sistemas Distribuidos implementando um chat distribuido com ZeroMQ.

Esta entrega cobre apenas a Parte 1:
- login de usuario (bot)
- criacao de canais
- listagem de canais
- persistencia em disco

## Tecnologias e escolhas

- ZeroMQ para troca de mensagens
- Docker + Docker Compose para orquestracao
- Protocol Buffers para serializacao binaria
- SQLite para persistencia local por servidor
- Python e Java como linguagens da dupla

## Regras adotadas

- Login duplicado: bloqueia nome ja ativo no servidor
- Nome de canal:
	- tamanho minimo: 3
	- tamanho maximo: 20
	- case-sensitive
	- formato permitido: `^[A-Za-z0-9_-]{3,20}$`
- Criacao de canal existente: retorna erro

## Arquitetura

- `broker`: proxy REQ/REP (ROUTER/DEALER)
- `py-server-1`, `py-server-2`: servidores em Python
- `java-server-1`, `java-server-2`: servidores em Java
- `py-client-1`, `py-client-2`: clientes/bots em Python
- `java-client-1`, `java-client-2`: clientes/bots em Java

Todos os clientes e servidores usam o mesmo contrato ProtoBuf definido em `proto/chat.proto`.

## Formato de mensagem

Mensagens principais:
- `ClientRequest`
	- `request_id`
	- `timestamp_ms`
	- payload (`login`, `create_channel`, `list_channels`)
- `ServerResponse`
	- `request_id`
	- `timestamp_ms`
	- `ok`
	- `error_code`
	- `error_message`
	- payload de resposta correspondente

## Persistencia

Cada servidor mantem seu proprio banco SQLite em volume Docker separado.

Tabelas:
- `logins(id, username, login_ts_ms)`
- `channels(id, name UNIQUE, created_ts_ms, created_by)`

## Como executar

Subir todos os containers:

```bash
docker compose up --build
```

O sistema inicia automaticamente sem interacao manual.
Os bots enviam requisicoes de login, criacao de canal e listagem de canais continuamente.

## Estrutura

- `proto/chat.proto`: contrato Protocol Buffers
- `broker/`: broker ZeroMQ
- `python/`: cliente e servidor em Python
- `java/`: cliente e servidor em Java
- `docker-compose.yaml`: orquestracao de toda a entrega