package com.task05;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
		lambdaName = "api_handler",
		roleName = "api_handler-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private static final String TABLE_NAME = "Events"; // DynamoDB table name
	private final AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
	private final ObjectMapper objectMapper = new ObjectMapper(); // For JSON conversion

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
		try {
			// Extract principalId and content from the request
			int principalId = (int) request.get("principalId");
			Map<String, String> content = (Map<String, String>) request.get("content");

			// Generate UUID v4 for `id`
			String id = UUID.randomUUID().toString();

			// Get current timestamp in ISO 8601 format
			String createdAt = Instant.now().toString();

			// Save event to DynamoDB
			saveToDynamoDB(id, principalId, createdAt, content);

			// Construct response
			Map<String, Object> response = new HashMap<>();
			response.put("statusCode", 201);
			response.put("event", Map.of(
					"id", id,
					"principalId", principalId,
					"createdAt", createdAt,
					"body", content
			));

			return response;
		} catch (Exception e) {
			context.getLogger().log("Error: " + e.getMessage());
			return Map.of("statusCode", 500, "error", "Internal Server Error");
		}
	}

	private void saveToDynamoDB(String id, int principalId, String createdAt, Map<String, String> content) {
		Map<String, AttributeValue> item = new HashMap<>();
		item.put("id", new AttributeValue(id));
		item.put("principalId", new AttributeValue().withN(String.valueOf(principalId)));
		item.put("createdAt", new AttributeValue(createdAt));
		item.put("body", new AttributeValue().withM(convertMapToAttributeValue(content)));

		PutItemRequest putItemRequest = new PutItemRequest().withTableName(TABLE_NAME).withItem(item);
		dynamoDB.putItem(putItemRequest);
	}

	private Map<String, AttributeValue> convertMapToAttributeValue(Map<String, String> map) {
		Map<String, AttributeValue> attributeMap = new HashMap<>();
		map.forEach((key, value) -> attributeMap.put(key, new AttributeValue(value)));
		return attributeMap;
	}
}
