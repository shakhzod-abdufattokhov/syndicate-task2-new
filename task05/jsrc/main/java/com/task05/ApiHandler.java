package com.task05;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

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
	private final DynamoDbClient dynamoDbClient;

	public ApiHandler() {
		this.dynamoDbClient = DynamoDbClient.builder()
				.credentialsProvider(DefaultCredentialsProvider.create())
				.build();
	}

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
		try {
			context.getLogger().log("Received request: " + request);

			// Extract request fields
			Integer principalId = (Integer) request.get("principalId");
			@SuppressWarnings("unchecked")
			Map<String, String> content = (Map<String, String>) request.get("content");

			if (principalId == null || content == null) {
				return createResponse(400, "Invalid request payload");
			}

			// Generate UUID v4 and current timestamp
			String id = UUID.randomUUID().toString();
			String createdAt = Instant.now().toString(); // ISO 8601 format

			// Construct item for DynamoDB
			Map<String, AttributeValue> item = new HashMap<>();
			item.put("id", AttributeValue.builder().s(id).build());
			item.put("principalId", AttributeValue.builder().n(String.valueOf(principalId)).build());
			item.put("createdAt", AttributeValue.builder().s(createdAt).build());
			item.put("body", AttributeValue.builder().m(convertToAttributeMap(content)).build());

			// Store in DynamoDB
			dynamoDbClient.putItem(PutItemRequest.builder()
					.tableName(TABLE_NAME)
					.item(item)
					.build());

			// Construct response
			Map<String, Object> event = new HashMap<>();
			event.put("id", id);
			event.put("principalId", principalId);
			event.put("createdAt", createdAt);
			event.put("body", content);

			return createResponse(201, event);

		} catch (SdkException e) {
			context.getLogger().log("DynamoDB Error: " + e.getMessage());
			return createResponse(500, "Internal Server Error");
		} catch (Exception e) {
			context.getLogger().log("Unexpected Error: " + e.getMessage());
			return createResponse(500, "Internal Server Error");
		}
	}

	private Map<String, Object> createResponse(int statusCode, Object body) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("event", body);
		return response;
	}

	private Map<String, AttributeValue> convertToAttributeMap(Map<String, String> map) {
		Map<String, AttributeValue> attributeMap = new HashMap<>();
		for (Map.Entry<String, String> entry : map.entrySet()) {
			attributeMap.put(entry.getKey(), AttributeValue.builder().s(entry.getValue()).build());
		}
		return attributeMap;
	}
}
