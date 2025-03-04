package com.task05;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
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

	private static final String TABLE_NAME = "Events";
	private static final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
	private static final DynamoDB dynamoDB = new DynamoDB(client);
	private static final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
		Map<String, Object> response = new HashMap<>();

		try {
			// Validate input
			if (!input.containsKey("principalId") || !input.containsKey("content")) {
				response.put("statusCode", 400);
				response.put("error", "Missing required fields: principalId or content");
				return response;
			}

			// Extract request data
			int principalId = (int) input.get("principalId");
			Map<String, String> content = (Map<String, String>) input.get("content");

			// Generate event data
			String eventId = UUID.randomUUID().toString();
			String createdAt = Instant.now().toString();

			// Create a new event item
			Item eventItem = new Item()
					.withPrimaryKey("id", eventId)
					.withNumber("principalId", principalId)
					.withString("createdAt", createdAt)
					.withMap("body", content);

			// Save to DynamoDB
			Table table = dynamoDB.getTable(TABLE_NAME);
			table.putItem(eventItem);

			// Construct response
			Map<String, Object> eventResponse = new HashMap<>();
			eventResponse.put("id", eventId);
			eventResponse.put("principalId", principalId);
			eventResponse.put("createdAt", createdAt);
			eventResponse.put("body", content);

			response.put("statusCode", 201);
			response.put("event", eventResponse);

		} catch (Exception e) {
			context.getLogger().log("Error processing request: " + e.getMessage());
			response.put("statusCode", 500);
			response.put("error", "Internal Server Error");
		}

		return response;
	}
}
