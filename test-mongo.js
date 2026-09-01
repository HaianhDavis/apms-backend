
const { MongoClient } = require('mongodb');
async function run() {
  const client = new MongoClient('mongodb://localhost:27017');
  await client.connect();
  const db = client.db('apms');
  const collection = db.collection('financial_research');
  const doc = await collection.findOne({ 'reports.title': 'Tài li?u nhà d?u tu Q4 2026' });
  if (doc) {
    const report = doc.reports.find(r => r.title === 'Tài li?u nhà d?u tu Q4 2026');
    console.log('Report Context:', JSON.stringify(report.documentContext, null, 2));
    const metrics = doc.metrics.filter(m => m.source.reportEntryId === report.id);
    console.log('Metrics count:', metrics.length);
  } else {
    console.log('Not found');
  }
  await client.close();
}
run();

