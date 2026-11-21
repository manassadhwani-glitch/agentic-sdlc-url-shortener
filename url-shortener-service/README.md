# URL Shortener Service

Spring Boot 3.3 · Java 17 · H2 (default) or PostgreSQL · Flyway · Caffeine · Micrometer

## Run

```bash
# from the repo root
mvn -pl url-shortener-service verify          # build, unit + integration tests, coverage gate (70% line)
mvn -pl url-shortener-service spring-boot:run # starts on :8080 with in-memory H2

# PostgreSQL instead of H2
docker run -d --name pg -e POSTGRES_USER=shortener -e POSTGRES_PASSWORD=shortener -e POSTGRES_DB=shortener -p 5432:5432 postgres:16
SPRING_PROFILES_ACTIVE=postgres mvn -pl url-shortener-service spring-boot:run
```

## Try it

```bash
curl -s -X POST localhost:8080/api/v1/urls -H 'X-API-Key: dev-key-change-me' \
     -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
     -d '{"longUrl":"https://example.com/docs"}'
curl -si localhost:8080/<shortCode>                       # 302 -> https://example.com/docs
curl -s localhost:8080/api/v1/urls/<shortCode>/stats -H 'X-API-Key: dev-key-change-me'
```

API contract: `src/main/resources/static/openapi.yaml` (also served at `/openapi.yaml`).
Metrics: `/actuator/prometheus`. Health: `/actuator/health` (liveness/readiness probes enabled).

## Package layout

| Package | Responsibility |
|---|---|
| `api` | Controllers, DTOs, RFC 7807 error mapping |
| `idempotency` | `LinkCreationFacade`: Idempotency-Key handling and race → error mapping |
| `service` | Link lifecycle (create / resolve / get / disable), read-through cache |
| `code` | Base62 + id scrambling into 7-char codes |
| `validation` | URL safety rules (scheme, SSRF / internal hosts, userinfo, loops) and alias rules |
| `analytics` | Async click capture (bounded queue + batch flush), stats queries, privacy helpers |
| `ratelimit` | Token-bucket limiter and filter (per API key / per IP) |
| `security` | API-key filter (constant-time compare, key id instead of raw key) |
| `domain`, `repository` | JPA entities and Spring Data repositories |

## Key decisions

- **No collisions by construction.** Each link gets an id from a DB sequence. A bijective scramble (an odd multiplier plus an XOR, both mod 2^41) turns the id into a 7-char Base62 code. Custom aliases are not allowed to have that exact shape, so the two namespaces never overlap.
- **The database is the source of truth for races.** Pre-checks only make the error messages friendlier. The `UNIQUE(short_code)` constraint and the idempotency-record primary key decide who wins a concurrent race (see `ConcurrencyIntegrationTest`).
- **Redirect availability over analytics completeness.** The redirect path never waits on analytics writes. A full queue drops clicks and counts them in `shortener.clicks{outcome="dropped"}`.
- **302, not 301.** Browsers don't cache the redirect, so every click is counted and disabling a link takes effect right away.
- **Privacy by default.** The IP is stored only as a salted HMAC, the referrer only as its host, and the user agent only as a family name.
- **Tenant isolation.** A link is visible only to the API key that created it. Other keys get 404 rather than 403, so the API doesn't reveal which codes exist.

## Known limitations (single-node prototype)

- The rate limiter and cache are per instance. In production: Redis for both, or pub/sub cache invalidation.
- Queued clicks are lost on a crash. In production: Kafka or another durable log.
- DNS-based SSRF checks are off by default (`shortener.validation.resolve-dns`). Even when on, they're open to DNS rebinding. The service never fetches the target URL itself, which reduces the risk.
- Internationalised (non-ASCII) hostnames must be sent punycode-encoded.
- API keys are static config. In production: a key store with rotation, or OAuth2 client credentials.
