package com.task05;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

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
			// Extract and validate `principalId`
			Object principalIdObject = request.get("principalId");
			if (principalIdObject == null) {
				return createErrorResponse(400, "Missing required field: principalId");
			}
			int principalId = Integer.parseInt(principalIdObject.toString());

			// Extract and validate `content`
			Object contentObject = request.get("content");
			@SuppressWarnings("unchecked")
			Map<String, String> content = contentObject instanceof Map ? (Map<String, String>) contentObject : new HashMap<>();

			// Generate UUID for `id`
			String id = UUID.randomUUID().toString();

			// Save to DynamoDB
			saveToDynamoDB(id, principalId, content, context);

			// Construct successful response
			return Map.of(
					"statusCode", 201,
					"event", Map.of(
							"id", id,
							"principalId", principalId,
							"body", content
					)
			);
		} catch (NumberFormatException e) {
			return createErrorResponse(400, "Invalid principalId format. Must be a number.");
		} catch (Exception e) {
			context.getLogger().log("Error: " + e.getMessage());
			return createErrorResponse(500, "Internal Server Error");
		}
	}

	private void saveToDynamoDB(String id, int principalId, Map<String, String> content, Context context) {
		try {
			Map<String, AttributeValue> item = new HashMap<>();
			item.put("id", new AttributeValue(id));
			item.put("principalId", new AttributeValue().withN(String.valueOf(principalId)));
			item.put("body", new AttributeValue().withS(objectMapper.writeValueAsString(content))); // Convert content to JSON String

			PutItemRequest putItemRequest = new PutItemRequest().withTableName(TABLE_NAME).withItem(item);
			dynamoDB.putItem(putItemRequest);
		} catch (JsonProcessingException e) {
			context.getLogger().log("JSON serialization error: " + e.getMessage());
			throw new RuntimeException("Failed to serialize content to JSON", e);
		}
	}

	private Map<String, Object> createErrorResponse(int statusCode, String message) {
		return Map.of("statusCode", statusCode, "error", message);
	}
}
