# CoreLedger — Motor Distribuído de Pagamentos e Conciliação Contábil

Backend financeiro crítico de alta performance e resiliência desenvolvido em **Java 21** e **Spring Boot 3.3+**, projetado para liquidações por **partidas dobradas (double-entry bookkeeping)**, garantia estrita de **idempotência**, **prevenção contra gasto duplo**, **rastreamento distribuído** e **tolerância a falhas**.

---

## 🏛️ Arquitetura e Decisões de Engenharia

```
                                      [Cliente HTTP / Gateway]
                                                 │
                                                 ▼ (JWT com SCOPE_payment:write)
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

### 1. Invariante Contábil de Partidas Dobradas
- Toda movimentação financeira gera **obrigatoriamente** dois lançamentos atômicos no razão (`ledger_entries`):
  - Um registro de `DEBIT` na conta de origem (valor negativo).
  - Um registro de `CREDIT` na conta de destino (valor positivo).
- O saldo só é modificado via lançamentos no razão contábil.
- Valores armazenados exclusivamente em **centavos (`BIGINT`)** para eliminar imprecisões de ponto flutuante.

### 2. Controle de Idempotência Estrito (CA-02)
- Interceptação via `@IdempotentEndpoint` e Spring AOP (`IdempotencyAspect`).
- Chave gravada no Redis via `SETNX` com status `IN_PROGRESS`.
- Requisições concorrentes com a mesma chave recebem **HTTP 409 Conflict**.
- Requisições concluídas armazenam a resposta no Redis; reenvios recebem a resposta original cacheada com o header `Idempotent-Replayed: true`.
- Erros de regra de negócio (HTTP 422) persistem o estado de falha para impedir reprocessamento espúrio. Falhas de infraestrutura deletam a chave para permitir retentativa.

### 3. Concorrência Extrema e Prevenção de Condições de Corrida (CA-03)
- **Lock Distribuído** no Redis via **Redisson** ordenado lexicograficamente por UUID das contas para eliminar deadlocks cruzados (A→B vs B→A).
- **Controle Otimista de Versão (`@Version`)** com retentativa transparente no `TransferOrchestrator`.
- **Lock Pessimista (`PESSIMISTIC_WRITE`)** no PostgreSQL no momento da mutação.
- Validação estrita de saldo insuficiente lançando `InsufficientBalanceException` (HTTP 422).

### 4. Resiliência com Circuit Breaker e Adquirente Externa (CA-05)
- Integrado via **Resilience4j** (`acquirerGateway`):
  - Janela deslizante de 20 chamadas, mínimo de 10 chamadas.
  - Limiar de falha de 50%.
  - Timeout de leitura de 2s e Retry com recuo exponencial.
- Ao abrir o circuito, transições subsequentes retornam em **< 50ms** com resposta controlada (HTTP 503) e incremento da métrica `acquirer.fallback.total`.

### 5. Transactional Outbox Pattern e Mensageria Resiliente (RF-05, CA-06)
- Eventos gravados na tabela `outbox_events` na mesma transação JDBC da movimentação.
- `OutboxPublisher` consome lotes usando `FOR UPDATE SKIP LOCKED`, publica no RabbitMQ com **Publisher Confirms** e marca como `PROCESSED`.
- Fila configurada com Dead Letter Exchange (`DLX`) e Dead Letter Queue (`DLQ`).
- O `traceId` da requisição HTTP é persistido no evento e propagado até o MDC do consumidor no RabbitMQ.

### 6. Segurança e Observabilidade (RNF-01, RNF-02, RNF-03)
- **OAuth2 Resource Server** stateless com validação de escopos JWT:
  - `SCOPE_payment:write` para endpoints de escrita (`/api/v1/transactions`, `/api/v1/accounts`).
  - `SCOPE_ledger:read` para endpoints de auditoria e consulta (`/api/v1/ledger/**`, `/api/v1/accounts/**`).
- Erros de segurança padronizados no padrão **RFC 7807 (Problem Details)**.
- Logs estruturados em formato **JSON** via Logstash Logback Encoder contendo `traceId` e `spanId`.
- Métricas expostas no endpoint do Actuator: `/actuator/prometheus`.
- Documentação interativa Swagger UI com suporte a Bearer JWT em `/swagger-ui.html`.

---

## 🚀 Como Executar Localmente

### Pré-requisitos
- **Java 21**
- **Maven 3.9+**
- **Docker** e **Docker Compose**

### Passo 1: Subir a infraestrutura auxiliar (PostgreSQL, Redis, RabbitMQ)
```bash
docker compose up -d
```

### Passo 2: Compilar e executar a aplicação
```bash
mvn spring-boot:run
```

A aplicação iniciará na porta `8080`. O Flyway executará as migrações automaticamente no PostgreSQL.

### Passo 3: Acessar a documentação Swagger
Abra no navegador:
```
http://localhost:8080/swagger-ui.html
```

---

## 🧪 Suíte de Testes

A aplicação segue a pirâmide de testes com três camadas:

1. **Testes Unitários de Domínio (`AccountTest`)**:
   - Validação de débito/crédito, integridade de soma zero e rejeição de valores negativos ou zerados (< 50ms).
2. **Testes de Fatia Web e Segurança (`TransactionControllerWebTest`)**:
   - Validação de escopos OAuth2 com `@WebMvcTest` + `spring-security-test`.
   - Verifica retornos HTTP 401 (sem token), 403 (escopo inválido), 400 (payload incorreto), 201 (sucesso) e 422 (saldo insuficiente).
3. **Testes de Integração com Infraestrutura Real (`CoreLedgerIT`)**:
   - Usa **Testcontainers** inicializando containers reais de PostgreSQL, Redis e RabbitMQ.
   - **CA-01**: Partidas dobradas e evento Outbox.
   - **CA-02**: Idempotência estrita e reenvios cacheados.
   - **CA-03**: Concorrência extrema com **50 threads simultâneas** disputando a mesma conta (5 sucessos, 45 rejeições, saldo zerado).
   - **CA-04**: Segurança OAuth2 e integridade de dados.
   - **CA-05**: Circuit Breaker abrindo sob falhas da adquirente simulada com WireMock.
   - **CA-06**: Publicação no RabbitMQ, consumo com propagação de traceId e exportação de métricas para o Prometheus.

Para rodar os testes unitários e de fatia:
```bash
mvn test
```

Para rodar a suíte completa de integração (requer Docker rodando para o Testcontainers):
```bash
mvn clean verify
```
