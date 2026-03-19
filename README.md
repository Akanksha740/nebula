# Nebula - Polymarket Historical Data Platform

A platform for capturing, storing, and monetizing Polymarket historical data through a REST API.

## Features

- **Data Ingestion**: Automatically captures Polymarket data every minute
- **30-Day Retention**: Stores historical snapshots with automatic cleanup
- **REST API**: Full-featured API with pagination, search, and filtering
- **Authentication**: API key and JWT-based authentication
- **Rate Limiting**: Tier-based rate limiting with Redis
- **Billing Integration**: Stripe integration for subscriptions
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
curl -H "X-API-Key: nb_live_xxx" http://localhost:8080/v1/markets
```

**Option 2: JWT Token**
```bash
# Login
curl -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "password"}'

# Use token
curl -H "Authorization: Bearer <token>" http://localhost:8080/v1/markets
```

### Key Endpoints

| Endpoint | Description |
|----------|-------------|
| `POST /v1/auth/register` | Register new account |
| `POST /v1/auth/login` | Login |
| `GET /v1/markets` | List all markets |
| `GET /v1/markets/{id}` | Get market details |
| `GET /v1/markets/{id}/snapshots` | Get historical snapshots |
| `GET /v1/account/api-keys` | List API keys |
| `POST /v1/account/api-keys` | Create API key |
| `GET /v1/account/usage` | Get usage statistics |

## Subscription Tiers

| Tier | Daily Limit | Data Access | Price |
|------|-------------|-------------|-------|
| FREE | 100 requests | 7 days | $0/mo |
| STARTER | 10,000 requests | 30 days | $29/mo |
| PRO | 100,000 requests | 30 days | $99/mo |
| ENTERPRISE | Unlimited | 30 days | Custom |

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
