package com.task05;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
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

	private static final String TABLE_NAME = System.getenv("target_table");
	private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
	private final DynamoDB dynamoDB = new DynamoDB(client);
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
		context.getLogger().log("Received request: " + request);
		context.getLogger().log("Using DynamoDB Table: " + TABLE_NAME);

		// Ensure TABLE_NAME is set
		if (TABLE_NAME == null || TABLE_NAME.isEmpty()) {
			return generateErrorResponse(500, "Environment variable TARGET_TABLE is not set!");
		}

		try {
			// Validate request
			if (!request.containsKey("principalId") || !request.containsKey("content")) {
				return generateErrorResponse(400, "Invalid request: Missing required fields.");
			}

			// Extract data
			int principalId = (int) request.get("principalId");
			JsonNode content = objectMapper.valueToTree(request.get("content"));
			String eventId = UUID.randomUUID().toString();
			String createdAt = Instant.now().toString();

			// Save to DynamoDB
			Table table = dynamoDB.getTable(TABLE_NAME);
			Item item = new Item()
					.withPrimaryKey("id", eventId)
					.withNumber("principalId", principalId)
					.withString("createdAt", createdAt)
					.withMap("body", objectMapper.convertValue(content, Map.class));

			table.putItem(item);
			context.getLogger().log("Successfully saved event: " + eventId);

			Map<String, Object> event = new HashMap<>();
			event.put("id", eventId);
			event.put("principalId", principalId);
			event.put("createdAt", createdAt);
			event.put("body", content);

			return generateSuccessResponse(event);

		} catch (Exception e) {
			context.getLogger().log("Error processing request: " + e.getMessage());
			return generateErrorResponse(500, "Internal Server Error: " + e.getMessage());
		}
	}

	private Map<String, Object> generateSuccessResponse(Map<String, Object> event) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", 201);
		response.put("event", event);
		return response;
	}

	private Map<String, Object> generateErrorResponse(int statusCode, String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("error", message);
		return response;
	}
}
