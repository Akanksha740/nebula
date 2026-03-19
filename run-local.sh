#!/bin/bash

# Nebula Local Development Script
# This script starts only DB containers and runs Java services locally

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Nebula Local Development ===${NC}"

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check prerequisites
echo -e "\n${YELLOW}Checking prerequisites...${NC}"

if ! command_exists docker; then
    echo -e "${RED}Error: Docker is not installed${NC}"
    exit 1
fi

if ! command_exists mvn; then
    echo -e "${YELLOW}Warning: Maven not found in PATH. Will try to use ./mvnw${NC}"
    MVN="./mvnw"
    if [ ! -f "$MVN" ]; then
        echo -e "${RED}Error: Neither mvn nor mvnw found. Please install Maven.${NC}"
        exit 1
    fi
else
    MVN="mvn"
fi

# Start database containers
echo -e "\n${YELLOW}Starting PostgreSQL and Redis containers...${NC}"
docker-compose -f docker-compose.dev.yml up -d

# Wait for containers to be healthy
echo -e "\n${YELLOW}Waiting for databases to be ready...${NC}"
sleep 5

# Check if postgres is ready
until docker exec nebula-postgres pg_isready -U nebula -d nebula > /dev/null 2>&1; do
    echo "Waiting for PostgreSQL..."
    sleep 2
done
echo -e "${GREEN}PostgreSQL is ready!${NC}"

# Check if redis is ready
until docker exec nebula-redis redis-cli ping > /dev/null 2>&1; do
    echo "Waiting for Redis..."
    sleep 2
done
echo -e "${GREEN}Redis is ready!${NC}"

# Build the project
echo -e "\n${YELLOW}Building the project...${NC}"
$MVN clean install -DskipTests -q

echo -e "\n${GREEN}=== Build Complete ===${NC}"
echo -e "\n${YELLOW}To run the services, open two terminal windows:${NC}"
echo -e "\n${GREEN}Terminal 1 - Ingestion Service:${NC}"
echo "cd nebula-ingestion && mvn spring-boot:run -Dspring-boot.run.profiles=local"
echo -e "\n${GREEN}Terminal 2 - API Service:${NC}"
echo "cd nebula-api && mvn spring-boot:run -Dspring-boot.run.profiles=local"
echo -e "\n${GREEN}Or use the individual scripts:${NC}"
echo "./run-ingestion.sh"
echo "./run-api.sh"
echo -e "\n${GREEN}API will be available at:${NC}"
echo "- Health: http://localhost:8080/v1/health"
echo "- Swagger: http://localhost:8080/swagger-ui.html"
