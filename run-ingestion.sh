#!/bin/bash
# Run the Ingestion service locally

cd "$(dirname "${BASH_SOURCE[0]}")/nebula-ingestion"

echo "Starting Nebula Ingestion Service..."
echo "Service will be available at: http://localhost:8081"
echo ""

if command -v mvn &> /dev/null; then
    mvn spring-boot:run -Dspring-boot.run.profiles=local
else
    ../mvnw spring-boot:run -Dspring-boot.run.profiles=local
fi
