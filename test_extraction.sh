# 1. Login
TOKEN=$(curl -s -X POST -H "Content-Type: application/json" -d '{"email":"staff@apms.com", "password":"123456"}' http://localhost:8080/api/v1/auth/login | grep -o '"accessToken":"[^"]*' | grep -o '[^"]*$')

echo "Got token: $TOKEN"

# 2. Trigger Extraction
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" http://localhost:8080/api/v1/import-jobs/1/candidates/from-ai > response.json
cat response.json
