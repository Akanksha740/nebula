# Nebula - Polymarket Historical Data Platform

A platform for capturing, storing, and monetizing Polymarket historical data through a REST API.

## Features

- **Multi-Coin Support**: Tracks BTC and ETH markets (SOL coming soon)
- **Data Ingestion**: Automatically captures Polymarket data at sub-second intervals
- **30-Day Retention**: Stores historical snapshots with automatic cleanup
- **REST API**: Full-featured API with pagination, search, and filtering
- **Authentication**: API key and JWT-based authentication
- **Rate Limiting**: Tier-based rate limiting with Redis
- **Tiered Coin Access**: ETH/SOL data restricted to PRO and ENTERPRISE tiers
- **Billing Integration**: Dodo Payments integration for subscriptions
- **Docker Ready**: Complete Docker Compose setup for easy deployment

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     NEBULA PLATFORM                      │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────┐ │
│  │  Polymarket  │────▶│  Ingestion   │────▶│PostgreSQL│ │
│  │     API      │     │   Service    │     │    DB    │ │
│  └──────────────┘     └──────────────┘     └──────────┘ │
│                                                   │      │
│  ┌──────────────────────────────────────────────────┐   │
│  │                   API Service                     │   │
│  │  • REST Endpoints  • Rate Limiting  • Auth       │   │
│  └──────────────────────────────────────────────────┘   │
│                              │                           │
│                        ┌──────────┐                      │
│                        │  Redis   │                      │
│                        │  Cache   │                      │
│                        └──────────┘                      │
└─────────────────────────────────────────────────────────┘
```

## Project Structure

```
nebula/
├── nebula-common/        # Shared entities, DTOs, utilities
├── nebula-ingestion/     # Data ingestion service
├── nebula-api/           # Customer-facing REST API
├── docker/               # Dockerfiles
├── docker-compose.yml    # Docker Compose configuration
└── pom.xml               # Parent Maven POM
```

## Quick Start

### Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose
- (Optional) Stripe account for billing

### Local Development

1. **Clone and setup**
   ```bash
   cd nebula
   cp .env.example .env
   # Edit .env with your configuration
   ```

2. **Start infrastructure**
   ```bash
   docker-compose up -d postgres redis
   ```

3. **Build and run**
   ```bash
   # Build all modules
   mvn clean install

   # Run ingestion service
   cd nebula-ingestion
   mvn spring-boot:run

   # In another terminal, run API service
   cd nebula-api
   mvn spring-boot:run
   ```

### Docker Deployment

```bash
# Build and start all services
docker-compose up -d --build

# View logs
docker-compose logs -f

# Stop services
docker-compose down
```

## API Documentation

Once running, access Swagger UI at: `http://localhost:8080/swagger-ui.html`

### Authentication

**Option 1: API Key**
```bash
curl -H "X-API-Key: nb_live_xxx" "http://localhost:8080/v1/markets?coin=BTC"
```

**Option 2: JWT Token**
```bash
# Login
curl -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "password"}'

# Use token
curl -H "Authorization: Bearer <token>" "http://localhost:8080/v1/markets?coin=ETH&limit=10"
```

### Key Endpoints

| Endpoint | Description |
|----------|-------------|
| `POST /v1/auth/register` | Register new account |
| `POST /v1/auth/login` | Login |
| `GET /v1/markets?coin=BTC` | List markets for a coin |
| `GET /v1/markets/{slug}` | Get market details (coin derived from slug) |
| `GET /v1/markets/{slug}/snapshots` | Get historical snapshots (coin derived from slug) |
| `GET /v1/account/api-keys` | List API keys |
| `POST /v1/account/api-keys` | Create API key |
| `GET /v1/account/usage` | Get usage statistics |

### Supported Coins

| Coin | Slug Prefixes | Tier Required |
|------|--------------|---------------|
| BTC | `btc-*`, `bitcoin-*` | STARTER (all tiers) |
| ETH | `eth-*`, `ethereum-*` | PRO, ENTERPRISE |
| SOL | `sol-*`, `solana-*` | PRO, ENTERPRISE (coming soon) |

### Market Types & Slug Patterns

Each coin has 5 market intervals. Examples for ETH:

| Type | Slug Pattern | Example |
|------|-------------|---------|
| 5m | `eth-updown-5m-{epoch}` | `eth-updown-5m-1774803000` |
| 15m | `eth-updown-15m-{epoch}` | `eth-updown-15m-1774802700` |
| 1h | `ethereum-up-or-down-{month}-{day}-{year}-{hour}{am\|pm}-et` | `ethereum-up-or-down-march-29-2026-12pm-et` |
| 4h | `eth-updown-4h-{epoch}` | `eth-updown-4h-1774800000` |
| 24h | `ethereum-up-or-down-on-{month}-{day}-{year}` | `ethereum-up-or-down-on-march-29-2026` |

BTC follows the same patterns with `btc-*` / `bitcoin-*` prefixes.

### Market List Query Parameters

```
GET /v1/markets?coin=ETH&market_type=5m&resolved=false&limit=50&offset=0&start_time=...&end_time=...
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `coin` | Yes | `BTC`, `ETH`, or `SOL` |
| `limit` | No | Results per page (1-100, default 50) |
| `offset` | No | Pagination offset (default 0) |
| `market_type` | No | Filter: `5m`, `15m`, `1h`, `4h`, `24h` |
| `resolved` | No | Filter: `true` or `false` |
| `start_time` | No | Filter markets after this time (epoch ms or ISO8601) |
| `end_time` | No | Filter markets before this time (epoch ms or ISO8601) |

### Market Detail & Snapshots

```
GET /v1/markets/{slug}
GET /v1/markets/{slug}/snapshots?limit=100&offset=0&include_orderbook=false
```

No `coin` parameter needed -- the coin is derived from the slug automatically.

## Subscription Tiers

| Tier | Daily Limit | Coins | Data Retention | Price |
|------|-------------|-------|----------------|-------|
| STARTER | 1,000 requests | BTC only | 30 days | $0/mo |
| PRO | 50,000 requests | BTC, ETH, SOL | 365 days | $11/mo |
| ENTERPRISE | Unlimited | BTC, ETH, SOL | 365 days | Custom |

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_HOST` | PostgreSQL host | localhost |
| `DB_PORT` | PostgreSQL port | 5432 |
| `DB_NAME` | Database name | nebula |
| `DB_USERNAME` | Database user | nebula |
| `DB_PASSWORD` | Database password | nebula |
| `REDIS_HOST` | Redis host | localhost |
| `REDIS_PORT` | Redis port | 6379 |
| `JWT_SECRET` | JWT signing secret | - |
| `STRIPE_SECRET_KEY` | Stripe API key | - |

## Deployment (Hetzner VPS)

Recommended setup for production:

1. **Server**: Hetzner CX31 (4 vCPU, 8GB RAM) - ~$8/month
2. **Reverse Proxy**: Caddy (auto HTTPS)
3. **CDN/DDoS**: Cloudflare (free tier)

```bash
# On your VPS
git clone <repo>
cd nebula
cp .env.example .env
# Configure .env

docker-compose up -d
```

### Caddy Configuration

```
api.yourdomain.com {
    reverse_proxy localhost:8080
}
```

## Development

### Building

```bash
# Build all modules
mvn clean install

# Build specific module
mvn clean install -pl nebula-api -am

# Skip tests
mvn clean install -DskipTests
```

### Testing

```bash
mvn test
```

## License

Proprietary - All rights reserved
