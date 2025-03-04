package com.task05;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
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

	private static final String TABLE_NAME = "cmtr-2028f2b4-Events"; // Ensure this matches your DynamoDB table
	private static final AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
	private static final ObjectMapper objectMapper = new ObjectMapper();

	public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
		Map<String, Object> response = new HashMap<>();
		try {
			// Parse request body
			JsonNode bodyNode = objectMapper.readTree((String) request.get("body"));

			// Generate event object
			String eventId = UUID.randomUUID().toString();
			String principalId = bodyNode.get("principalId").asText();
			String eventBody = bodyNode.get("body").asText();
			String createdAt = Instant.now().toString();

			// Save to DynamoDB
			Map<String, AttributeValue> item = new HashMap<>();
			item.put("id", new AttributeValue(eventId));
			item.put("principalId", new AttributeValue(principalId));
			item.put("createdAt", new AttributeValue(createdAt));
			item.put("body", new AttributeValue(eventBody));

			PutItemRequest putItemRequest = new PutItemRequest(TABLE_NAME, item);
			dynamoDB.putItem(putItemRequest);

			// Construct success response
			Map<String, Object> responseBody = new HashMap<>();
			responseBody.put("id", eventId);
			responseBody.put("principalId", principalId);
			responseBody.put("createdAt", createdAt);
			responseBody.put("body", eventBody);

			response.put("statusCode", 201);
			response.put("body", objectMapper.writeValueAsString(responseBody));

		} catch (Exception e) {
			context.getLogger().log("Error: " + e.getMessage());
			response.put("statusCode", 500);
			response.put("body", "{\"error\": \"Internal Server Error\"}");
		}

		return response;
	}
}
