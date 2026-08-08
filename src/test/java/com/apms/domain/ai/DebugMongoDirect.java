package com.apms.domain.ai;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

public class DebugMongoDirect {
    public static void main(String[] args) {
        try (MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017")) {
            MongoDatabase database = mongoClient.getDatabase("apms");
            MongoCollection<Document> collection = database.getCollection("company_candidates");
            Document doc = collection.find().sort(new Document("_id", -1)).first();
            if (doc != null) {
                System.out.println("====== CANDIDATE JSON ======");
                System.out.println(doc.toJson());
                System.out.println("===========================");
            } else {
                System.out.println("NO CANDIDATES FOUND");
            }
        }
    }
}
