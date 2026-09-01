const fs = require('fs');

const logPath = 'C:\\Users\\nguye\\.gemini\\antigravity\\brain\\95d339fb-ec5a-488d-aa23-5244f75e2495\\.system_generated\\logs\\transcript_full.jsonl';
const lines = fs.readFileSync(logPath, 'utf8').split('\n');

for (const line of lines) {
    if (!line) continue;
    try {
        const obj = JSON.parse(line);
        if (obj.content && obj.content.includes('FinancialResearchService.java')) {
            fs.appendFileSync('extracted_backend_logs.txt', obj.content + '\n\n=========================\n\n');
        }
    } catch (e) {}
}
console.log('Done extracting backend');
