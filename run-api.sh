#!/bin/bash
# Run the API service locally

cd "$(dirname "${BASH_SOURCE[0]}")/nebula-api"

echo "Starting Nebula API Service..."
echo "API will be available at: http://localhost:8080"
echo "Swagger UI: http://localhost:8080/swagger-ui.html"
echo ""

if command -v mvn &> /dev/null; then
    mvn spring-boot:run -Dspring-boot.run.profiles=local
else
    ../mvnw spring-boot:run -Dspring-boot.run.profiles=local
fi
