# CoreLedger — Motor Distribuído de Pagamentos e Conciliação Contábil

Plataforma financeira de missão crítica desenvolvida em **Java 21** e **Spring Boot 3.3.5**, projetada para liquidações por **partidas dobradas (double-entry bookkeeping)**, garantia estrita de **idempotência**, **prevenção contra condições de corrida**, **tolerância a falhas** e **observabilidade ponta a ponta**.

---

## 🧭 Sumário Rápido
1. [Visão Geral da Arquitetura](#-visão-geral-da-arquitetura)
2. [Matriz Tecnológica](#-matriz-tecnológica)
3. [Catálogo de Endpoints da API](#-catálogo-de-endpoints-da-api)
4. [Como Executar Localmente](#-como-executar-localmente)
5. [Como Testar no Swagger UI (com JWT Local)](#-como-testar-no-swagger-ui-com-jwt-local)
6. [Exemplos Práticos com cURL](#-exemplos-práticos-com-curl)
7. [Pilares de Engenharia & Decisões de Design](#-pilares-de-engenharia--decisões-de-design)
8. [Estratégia e Execução de Testes](#-estratégia-e-execução-de-testes)

---

## 🏛️ Visão Geral da Arquitetura

```
                                  [Cliente HTTP / Gateway]
                                             │
                                             ▼ (Bearer JWT com SCOPE_payment:write)
                                 ┌───────────────────────┐
                                 │  IdempotencyAspect    │ ◄───► [Redis / SETNX (24h TTL)]
                                 └───────────┬───────────┘
                                             │
                                             ▼
                                 ┌───────────────────────┐
                                 │ TransferOrchestrator  │ ◄───► [Redisson Lock por Conta]
                                 └─────┬───────────┬─────┘       [Resilience4j CB / Adquirente]
                                       │           │
                 ┌─────────────────────┘           └───────────────────────┐
                 ▼                                                         ▼
     ┌────────────────────────┐                                ┌─────────────────────────┐
     │  LedgerTransferService │                                │  AcquirerGatewayClient  │
     │     (@Transactional)   │                                │  (Circuit Breaker +     │
     └───────────┬────────────┘                                │   Retry + Timeout 2s)   │
                 │                                             └─────────────────────────┘
     ┌───────────┴───────────────────────────────┐
     │               PostgreSQL                  │
     │  - accounts (@Version + PESSIMISTIC_WRITE)│
     │  - transactions                           │
     │  - ledger_entries (DEBIT + CREDIT)        │
     │  - outbox_events (PENDING)                │
     └───────────────────┬───────────────────────┘
                         │ (Poll com FOR UPDATE SKIP LOCKED)
                         ▼
              ┌─────────────────────┐
              │   OutboxPublisher   │ ───► [RabbitMQ Exchange: coreledger.events]
              └─────────────────────┘               │
                                                    ▼
                                         [Queue: coreledger.events.queue]
                                         (DLQ: coreledger.events.dlq)
```

---

## 💻 Matriz Tecnológica

| Componente | Tecnologia | Papel no CoreLedger |
| :--- | :--- | :--- |
| **Linguagem & Runtime** | Java 21 (LTS) | Virtual Threads (`threads.virtual.enabled=true`) |
| **Framework Base** | Spring Boot 3.3.5 | Injeção de dependência, WebMVC e validação declarativa |
| **Banco Relacional** | PostgreSQL 16 | Persistência transacional ACID |
| **Migrações de Banco** | Flyway | Versionamento estrito e automatizado do schema |
| **Cache & Distributed Lock** | Redis 7 + Redisson 3.37 | Chaves de idempotência (SETNX) e locks distribuídos por conta |
| **Mensageria Assíncrona** | RabbitMQ 3.13 | Publicação outbox resiliente com Publisher Confirms e DLQ |
| **Tolerância a Falhas** | Resilience4j 2.2 | Circuit Breaker, Retry exponencial e Timeout em chamadas externas |
| **Segurança** | Spring Security + OAuth2 | Resource Server stateless, validação de escopos e RFC 7807 |
| **Observabilidade** | Micrometer + Prometheus + OTel | Métricas de negócio, rastreamento W3C e logs em JSON |
| **Documentação Interativa** | SpringDoc OpenAPI 2.6 | Swagger UI com suporte a autenticação Bearer JWT |
| **Qualidade & Testes** | JUnit 5 + Mockito + Testcontainers | 36 testes unitários/fatia e suíte completa de integração |

---

## 📡 Catálogo de Endpoints da API

Todos os valores monetários trafegam e são persistidos estritamente em **centavos (`BIGINT`)** para evitar imprecisões de ponto flutuante.

| Método | Endpoint | Escopo Exigido | Descrição |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/accounts` | `payment:write` | Cria uma nova conta bancária (com ou sem saldo inicial). |
| `GET` | `/api/v1/accounts/{id}` | `ledger:read` | Consulta os dados e saldo consolidado da conta. |
| `POST` | `/api/v1/transactions` | `payment:write` | Executa transferência por partidas dobradas (`Idempotency-Key` obrigatória). |
| `GET` | `/api/v1/ledger/accounts/{id}/entries` | `ledger:read` | Consulta extrato contábil paginado por período. |
| `GET` | `/api/v1/ledger/accounts/{id}/integrity` | `ledger:read` | Verifica se o saldo consolidado é idêntico à soma histórica do razão. |
| `GET` | `/api/v1/auth/token` | *Público* | Emite token JWT para testes no Swagger (perfil dev). |
| `GET` | `/actuator/prometheus` | *Público* | Métricas em formato Prometheus (`ledger_transactions_total`, etc.). |
| `GET` | `/actuator/health` | *Público* | Sondas de saúde (`liveness` e `readiness`). |

---

## 🚀 Como Executar Localmente

### Pré-requisitos
- **Java 21** instalado (`java -version`)
- **Maven 3.9+** instalado (`mvn -v`)
- **Docker** e **Docker Compose**

### 1. Iniciar os serviços auxiliares
Suba o banco de dados PostgreSQL, o Redis e o RabbitMQ via Docker Compose:
```bash
docker compose up -d
```

### 2. Iniciar a aplicação Spring Boot
```bash
mvn spring-boot:run
```
A aplicação iniciará na porta **`8080`**. As migrações do Flyway (`V1` e `V2`) são executadas automaticamente.

---

## 🔑 Como Testar no Swagger UI (com JWT Local)

A aplicação conta com um provedor de chaves RSA e gerador de JWT embutido para testes locais, **sem exigir a configuração de um servidor Keycloak externo**:

1. Abra no navegador: **[`http://localhost:8080/swagger-ui.html`](http://localhost:8080/swagger-ui.html)**
2. Localize a tag **"Autenticação (Desenvolvimento)"** e execute o endpoint:
   ```
   GET /api/v1/auth/token
   ```
   Ele retornará um JSON contendo o campo `access_token`.
3. Copie o valor do token.
4. No topo da página do Swagger, clique no botão **Authorize 🔓**, cole o token e confirme.
5. Pronto! Agora você pode testar qualquer endpoint protegido diretamente pelo navegador.

---

## 💡 Exemplos Práticos com cURL

### 1. Gerar Token JWT
```bash
TOKEN=$(curl -s "http://localhost:8080/api/v1/auth/token" | jq -r .access_token)
```

### 2. Criar Conta com Saldo Inicial de R$ 500,00 (50.000 centavos)
```bash
curl -X POST "http://localhost:8080/api/v1/accounts" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "holderName": "Alice Santos",
    "accountNumber": "0001-9",
    "initialBalanceCents": 50000
  }'
```

### 3. Criar Segunda Conta com Saldo de R$ 100,00 (10.000 centavos)
```bash
curl -X POST "http://localhost:8080/api/v1/accounts" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "holderName": "Bob Souza",
    "accountNumber": "0002-7",
    "initialBalanceCents": 10000
  }'
```

### 4. Transferir R$ 150,00 com Idempotência Estrita
```bash
curl -X POST "http://localhost:8080/api/v1/transactions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: TX-REQ-001" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "<UUID_DA_ALICE>",
    "targetAccountId": "<UUID_DO_BOB>",
    "amountCents": 15000,
    "description": "Pagamento de serviços"
  }'
```
> **Teste de Idempotência:** Repita a chamada acima com a mesma chave `TX-REQ-001`. A aplicação retornará a resposta cacheada instantaneamente com o cabeçalho `Idempotent-Replayed: true`, sem debitar a conta novamente.

### 5. Verificar a Integridade Contábil da Conta
```bash
curl -X GET "http://localhost:8080/api/v1/ledger/accounts/<UUID_DA_ALICE>/integrity" \
  -H "Authorization: Bearer $TOKEN"
```
Retorno:
```json
{
  "accountId": "...",
  "balanceCents": 35000,
  "ledgerSumCents": 35000,
  "consistent": true
}
```

---

## 🛡️ Pilares de Engenharia & Decisões de Design

### 1. Invariante de Partidas Dobradas e Soma Zero Global
Nenhum saldo é alterado diretamente. Toda operação gera pelo menos um lançamento de `DEBIT` (-X centavos) e um lançamento de `CREDIT` (+X centavos) vinculados ao mesmo `transaction_id`.
Mesmo a criação de contas com saldo inicial debita a conta de **Tesouraria Central** (`00000000-0000-0000-0000-000000000000`), garantindo que **a soma global de todo o razão seja rigorosamente igual a zero**.

### 2. Idempotência em Duas Fases via Redis
- Interceptação com Spring AOP (`@IdempotentEndpoint`).
- Comando atômico `SETNX` no Redis com status `IN_PROGRESS` e TTL de 24h.
- Requisições concorrentes idênticas recebem `HTTP 409 Conflict`.
- Requisições concluídas armazenam a resposta; reenvios recebem o resultado original em cache.
- Falhas de negócio persistem `FAILED` (retornando `HTTP 422`); falhas transitórias de infraestrutura limpam a chave para permitir nova tentativa.

### 3. Eliminação de Deadlocks e Concorrência de Alta Performance
- **Redisson Distributed Lock**: Contas são travadas ordenadas lexicograficamente por UUID para impedir deadlocks em transferências cruzadas simultâneas (A→B e B→A).
- **Controle Otimista (`@Version`)**: Retentativas transparentes no caso de concorrência massiva.
- **Lock Pessimista (`PESSIMISTIC_WRITE`)**: Bloqueio em nível de linha no PostgreSQL durante o débito/crédito.

### 4. Transactional Outbox Pattern e Dead-Lettering
- O evento de domínio é inserido na tabela `outbox_events` na mesma transação JDBC da movimentação financeira.
- Um poller agendado lê lotes usando `FOR UPDATE SKIP LOCKED`, publica no RabbitMQ com **Publisher Confirms** e marca como `PROCESSED`.
- Em caso de falha consecutiva, o evento possui limite de retentativas (`coreledger.outbox.max-retries`), sendo marcado como `FAILED` após atingir o teto para evitar bloqueio do lote, além de DLQ no RabbitMQ.

### 5. Resiliência com Circuit Breaker
- A comunicação com adquirentes externas (`AcquirerGatewayClient`) é protegida por Circuit Breaker e Retry com recuo exponencial.
- Ao registrar taxa de falha $\ge 50\%$, o circuito transita para `OPEN`, degradando em menos de 50ms com `HTTP 503` e preservando as threads da aplicação.

### 6. Rastreamento e Logs Estruturados
- Logs gerados no formato JSON (Logstash Encoder) contendo `traceId` e `spanId`.
- O `traceId` é persistido no evento outbox e propagado até o MDC do consumidor no RabbitMQ.

---

## 🧪 Estratégia e Execução de Testes

### 1. Testes Unitários e de Fatia (Execução Rápida, sem Docker)
Cobrem todas as regras de negócio, AOP, segurança, serialização e tolerância a falhas:
```bash
mvn test
```
**Resultado:** **36 testes automatizados, 100% aprovados em ~9 segundos.**

### 2. Testes de Integração com Containers Reais (`CoreLedgerIT.java`)
Cenários ponta a ponta validados via **Testcontainers** (requer Docker ativo):
- **CA-01 (Partidas Dobradas):** Gravação de débitos/créditos e evento no banco real.
- **CA-02 (Idempotência Estrita):** Resposta em cache e bloqueio de concorrência com Redis real.
- **CA-03 (Concorrência Extrema):** **50 threads simultâneas** disputando uma conta (5 sucessos de R$ 20,00 e 45 rejeições por saldo insuficiente; saldo final exato de R$ 0,00).
- **CA-04 (Segurança):** Bloqueio de chamadas não autenticadas (401) ou com escopo insuficiente (403).
- **CA-05 (Circuit Breaker):** Interrupção de chamadas de rede e fallback < 150ms via WireMock.
- **CA-06 (Observabilidade):** Validação de métricas em `/actuator/prometheus` e consumo assíncrono via RabbitMQ.

Com o Docker ativo, execute:
```bash
mvn clean verify
```
