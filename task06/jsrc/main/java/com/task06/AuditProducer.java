package com.task06;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.DynamoDbTriggerEventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
		lambdaName = "audit_producer",
		roleName = "audit_producer-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "region", value = "${region}"),
		@EnvironmentVariable(key = "table", value = "${target_table}")
})
@DynamoDbTriggerEventSource(
		targetTable = "Configuration",
		batchSize = 50
)
public class AuditProducer implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private static final String AUDIT_TABLE = System.getenv("table");
	private final DynamoDbClient dynamoDbClient;

	public AuditProducer() {
		this.dynamoDbClient = DynamoDbClient.builder()
				.region(Region.of(System.getenv("region")))
				.credentialsProvider(DefaultCredentialsProvider.create())
				.build();
	}

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
		try {
			context.getLogger().log("[INFO] Received event: " + input);

			List<Map<String, Object>> records = (List<Map<String, Object>>) input.get("Records");
			if (records == null || records.isEmpty()) {
				context.getLogger().log("[WARN] No records found in the event.");
				return Map.of("statusCode", 400, "message", "No records found");
			}

			for (Map<String, Object> record : records) {
				String eventType = (String) record.get("eventName");
				context.getLogger().log("[DEBUG] Processing event type: " + eventType);

				Map<String, Object> dynamodb = (Map<String, Object>) record.get("dynamodb");
				if (dynamodb == null) {
					context.getLogger().log("[WARN] Missing 'dynamodb' field in record. Skipping.");
					continue;
				}

				Map<String, Object> keys = (Map<String, Object>) dynamodb.get("Keys");
				Map<String, Object> newImage = (Map<String, Object>) dynamodb.get("NewImage");

				if (keys == null || !keys.containsKey("key")) {
					context.getLogger().log("[WARN] Skipping record, missing key field.");
					continue;
				}

				String itemKey = extractStringValue(keys.get("key"));
				String modificationTime = Instant.now().toString();

				// Extract the correct newValue
				Map<String, AttributeValue> newValue = newImage != null ? extractImage(newImage) : new HashMap<>();

				// Log extracted values
				context.getLogger().log("[DEBUG] Extracted newValue: " + newValue);

				// Construct the correct audit entry
				Map<String, AttributeValue> auditEntry = new HashMap<>();
				auditEntry.put("id", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
				auditEntry.put("audit_id", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
				auditEntry.put("itemKey", AttributeValue.builder().s(itemKey).build());
				auditEntry.put("modificationTime", AttributeValue.builder().s(modificationTime).build());

				// Store newValue correctly
				if (newValue.containsKey("key") && newValue.containsKey("value")) {
					String extractedKey = extractStringValue(newImage.get("key"));
					String extractedValue = extractNumberOrStringValue(newImage.get("value"));

					auditEntry.put("newValue_key", AttributeValue.builder().s(extractedKey).build());
					auditEntry.put("newValue_value", AttributeValue.builder().s(extractedValue).build());

					context.getLogger().log("[DEBUG] Corrected newValue stored: key=" + extractedKey + ", value=" + extractedValue);
				} else {
					context.getLogger().log("[WARN] newValue is missing 'key' or 'value', skipping.");
				}

				// Log before saving
				context.getLogger().log("[INFO] Saving to audit table: " + auditEntry);

				// Save to DynamoDB
				PutItemRequest putItemRequest = PutItemRequest.builder()
						.tableName(AUDIT_TABLE)
						.item(auditEntry)
						.build();
				dynamoDbClient.putItem(putItemRequest);

				// Log after saving
				context.getLogger().log("[SUCCESS] Audit record saved for key: " + itemKey);
			}

			return Map.of("statusCode", 200, "message", "Audit logs processed successfully");
		} catch (Exception e) {
			context.getLogger().log("[ERROR] Exception occurred: " + e.getMessage());
			return Map.of("statusCode", 500, "message", "Internal Server Error");
		}
	}

	private String extractStringValue(Object value) {
		if (value instanceof Map) {
			return ((Map<String, String>) value).get("S");
		}
		return "N/A";
	}

	private String extractNumberOrStringValue(Object value) {
		if (value instanceof Map) {
			Map<String, Object> valueMap = (Map<String, Object>) value;
			if (valueMap.containsKey("S")) {
				return (String) valueMap.get("S");
			} else if (valueMap.containsKey("N")) {
				return (String) valueMap.get("N");
			}
		}
		return "N/A";
	}

	private Map<String, AttributeValue> extractImage(Map<String, Object> image) {
		Map<String, AttributeValue> attributeValueMap = new HashMap<>();
		for (Map.Entry<String, Object> entry : image.entrySet()) {
			Map<String, Object> valueMap = (Map<String, Object>) entry.getValue();
			if (valueMap.containsKey("S")) {
				attributeValueMap.put(entry.getKey(), AttributeValue.builder().s((String) valueMap.get("S")).build());
			} else if (valueMap.containsKey("N")) {
				attributeValueMap.put(entry.getKey(), AttributeValue.builder().n((String) valueMap.get("N")).build());
			} else {
				throw new IllegalArgumentException("Unsupported AttributeValue type: " + valueMap);
			}
		}
		return attributeValueMap;
	}
}
