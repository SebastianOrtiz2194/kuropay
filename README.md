# KuroPay

[![CI](https://github.com/SebastianOrtiz2194/KuroPay/actions/workflows/ci.yml/badge.svg)](https://github.com/SebastianOrtiz2194/KuroPay/actions/workflows/ci.yml)

A digital wallet backend in Java 25 and Spring Boot 4. It creates wallets, takes deposits and moves money between wallets, and it is built around one question: what happens when many requests hit the same wallet at once, or the same request arrives twice?

**Status: work in progress.** The wallet core service is working and tested, and every endpoint requires a JWT access token. The event relay to Kafka, wallet ownership checks and the fraud risk engine are not built yet (see [Not built yet](#not-built-yet)).

## What it guarantees

Each guarantee is enforced by PostgreSQL, not by application code alone, and each has a test that tries to break it.

| Guarantee | How it is enforced | Test that proves it |
|---|---|---|
| A balance never goes below zero | `SELECT ... FOR UPDATE` on the wallet row, plus a `CHECK (balance >= 0)` constraint | 50 virtual threads each send ¥1,000 from a ¥10,000 wallet: exactly 10 complete, 40 are rejected |
| A retried request moves money once | `UNIQUE` constraint on `idempotency_key`, checked in the same transaction that moves the money | 20 simultaneous requests with one key: one executes, 19 replay its result |
| Opposite transfers never deadlock | Wallet rows are always locked in ascending id order | 20 transfers A→B and 20 B→A at once: all finish and the total is conserved |
| Concurrent deposits are never lost | The same row lock as transfers | Concurrent deposits with different keys all land in the balance |
| An owner has one wallet per currency | `UNIQUE (owner_id, currency)`; the service inserts first and reads back on conflict | Concurrent creations of the same wallet create exactly one |
| No event is lost if the service crashes | Transactional outbox: the event row is written in the same transaction as the balance change | The concurrency tests count outbox rows against completed operations |

The tests are in `wallet-core-service/src/test`, mainly `TransferConcurrencyIntegrationTest`, `DepositConcurrencyIntegrationTest` and `WalletCreationIntegrationTest`. They run against a real PostgreSQL through Testcontainers.

## Run it

You need JDK 25 and Docker.

```bash
./gradlew :wallet-core-service:bootRun
```

This starts PostgreSQL, Redis and Keycloak from `compose.yaml`, applies the Flyway migrations and serves the API on `http://localhost:8080`. Keycloak is the slowest to start: the first run downloads its image, and later runs wait about 15 seconds for it.

## Try it

Every request needs an access token: `Authorization: Bearer <token>`. The service does not issue tokens; the local Keycloak does. Log in as the two demo users, `alice` and `bob` (each password is the same as the username), and keep their tokens. This needs `jq`.

```bash
token() {
  curl -s localhost:8081/realms/kuropay/protocol/openid-connect/token \
    -d grant_type=password \
    -d client_id=kuropay-cli \
    -d username="$1" \
    -d password="$1" | jq -r .access_token
}

ALICE=$(token alice)
BOB=$(token bob)
```

The tokens expire after 5 minutes. When a request starts returning 401, run the last two commands again.

A wallet belongs to the user whose token creates it, so each user creates their own. Each response includes the wallet `id`.

```bash
curl -X POST localhost:8080/api/wallets \
  -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' \
  -d '{"currency": "JPY"}'

curl -X POST localhost:8080/api/wallets \
  -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' \
  -d '{"currency": "JPY"}'
```

Deposit ¥10,000 into the first wallet:

```bash
curl -X POST localhost:8080/api/wallets/<alice-wallet-id>/deposits \
  -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: dep-001' \
  -d '{"amount": 10000, "currency": "JPY"}'
```

Transfer ¥2,500 to the second:

```bash
curl -i -X POST localhost:8080/api/transfers \
  -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: tx-001' \
  -d '{"sourceWalletId": "<alice-wallet-id>", "targetWalletId": "<bob-wallet-id>", "amount": 2500, "currency": "JPY"}'
```

```
HTTP/1.1 200
X-Idempotent-Replayed: false

{"transferId":"69daf56a-...","sourceWalletId":"7741120d-...","targetWalletId":"1db1c760-...","transferredAmount":2500,"currency":"JPY","sourceNewBalance":7500,"completedAt":"2026-11-15T01:47:43.614777Z"}
```

Run the same transfer again. The body is identical, the header is now `X-Idempotent-Replayed: true`, and the balance stays at 7,500.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/wallets` | Create a wallet in a currency. Its owner is the token's subject (`sub`) |
| `GET` | `/api/wallets/{walletId}` | Read a wallet and its balance |
| `POST` | `/api/wallets/{walletId}/deposits` | Credit a wallet. Requires `Idempotency-Key` |
| `POST` | `/api/transfers` | Move money between two wallets. Requires `Idempotency-Key` |

Supported currencies are `JPY`, `USD` and `EUR`. An amount with more decimals than its currency allows (for example `10.5` JPY) is rejected, not rounded.

The API version travels in the `X-API-Version` header and defaults to `1.0`.

Every error is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem detail:

| Status | `type` ends with | When |
|---|---|---|
| 400 | `invalid-request` | Validation failed, or the amount has too many decimals |
| 401 | `unauthorized` | The access token is missing, expired, signed by an unknown key, from another issuer or issued for another service. The reason is in the `WWW-Authenticate` header |
| 404 | `wallet-not-found` | The wallet does not exist |
| 409 | `wallet-already-exists` | The owner already has a wallet in that currency. The `Location` header points to it |
| 422 | `insufficient-funds` | The source wallet cannot cover the transfer |
| 422 | `currency-mismatch` | The request currency differs from the wallet currency |
| 422 | `idempotency-key-reused` | The key was already used with a different payload |

```json
{
  "type": "https://api.kuropay.com/errors/insufficient-funds",
  "title": "Insufficient Funds",
  "status": 422,
  "detail": "Insufficient funds in source wallet 7741120d-a4e8-46e7-8ef9-63240326a96f",
  "instance": "/api/transfers",
  "walletId": "7741120d-a4e8-46e7-8ef9-63240326a96f",
  "availableBalance": 7500,
  "requestedAmount": 999999,
  "currency": "JPY"
}
```

## How idempotency works

There are two layers, and only one of them is trusted.

1. **PostgreSQL is the guarantee.** The `transfers` and `deposits` tables each have a `UNIQUE` constraint on `idempotency_key`. The service looks the key up only after it holds the wallet row locks, so a concurrent retry waits for the first request to commit and then replays the stored result.
2. **Redis is a shortcut.** It caches the response for 24 hours, so a retry can be answered without taking any row lock. It fails open: if Redis is down, slow or returns a corrupt entry, the request falls through to PostgreSQL. Its command timeout is 200 ms so that a Redis outage cannot slow down payments.

A rejected transfer is stored too, so retrying a transfer that failed for insufficient funds returns the same rejection even if the wallet has been funded in between. Sending the same key with a different payload returns 422.

## Authentication

The service is an OAuth2 resource server. It never sees a password or a signing key: it checks the signature, the issuer, the expiry and the audience of each JWT, using public keys it fetches from the issuer. It keeps no session, so any instance can serve any request.

The audience check matters because one identity provider signs tokens for many APIs. The signature and issuer only prove that the token is genuine; the `aud` claim says which API it was issued for. The service accepts a token only if `aud` contains `wallet-core-service`, so a token issued for another service cannot be replayed here. In Keycloak this claim comes from an audience mapper on the `kuropay-cli` client: Keycloak does not add it by default.

`ApiAuthenticationIntegrationTest` sends real HTTP requests with no token, an expired token, a token signed by an unknown key, a token from another issuer and a token issued for another service, and expects 401 for each. Each of these tokens is valid except for the one thing being tested, so the test proves that check, not some other one.

The trusted issuer is set in `KUROPAY_JWT_ISSUER_URI`. It defaults to the Keycloak realm that `compose.yaml` starts, `http://localhost:8081/realms/kuropay`. That realm is defined in `infra/keycloak/kuropay-realm.json`: two demo users and one public client, `kuropay-cli`, that trades a username and password for a token. The password grant is there so that `curl` can log in. A real client would use the authorization code flow with PKCE, and the service would not change, because it only verifies tokens and does not care how they were obtained.

The Keycloak admin console is at `http://localhost:8081` (`admin` / `admin`). The realm file is imported only when the container is created, so after editing it run `docker compose rm -sf keycloak` and start again.

## Architecture

The service follows hexagonal architecture, and ArchUnit fails the build if a layer breaks the rules.

```
com.kuropay.wallet
├── domain           Wallet, Money, Transfer, Deposit, events, output ports. Plain Java, no framework imports
├── application      Use cases: create wallet, deposit, transfer. Depends only on the domain
└── infrastructure   Web controllers, security, JPA adapters, Redis cache, configuration
```

- `Money` fixes the scale to its currency (0 decimals for JPY, 2 for USD and EUR), so amounts compare and hash the same everywhere.
- Use case results and domain events are sealed interfaces. Adding a new case fails compilation until every `switch` handles it.
- Request handling runs on virtual threads.
- Transfers and deposits are append-only records. Balances are never changed without one.

## Tests

```bash
./gradlew build
```

This runs the unit tests, the web slice tests, the ArchUnit rules and the Testcontainers integration tests, so Docker must be running. CI runs the same command on every push and pull request.

## Stack

Java 25, Spring Boot 4.1, Gradle (Kotlin DSL) with a version catalog, Spring Security (OAuth2 resource server), Keycloak 26, PostgreSQL 17, Flyway, Redis 7, JUnit, Testcontainers, ArchUnit, JSpecify, GitHub Actions.

## Not built yet

- **Outbox relay to Kafka.** Events are written to `outbox_events` and stay `PENDING`; nothing publishes them yet.
- **Wallet ownership checks.** A new wallet belongs to the token's subject, but any authenticated caller can still read or debit any wallet.
- **Fraud risk engine.** Planned, using Spring AI.
