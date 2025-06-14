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
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;

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
				context.getLogger().log("[WARNING] No records found in the event.");
				return Map.of("statusCode", 400, "message", "No records found");
			}

			for (Map<String, Object> record : records) {
				context.getLogger().log("[INFO] Processing record: " + record);

				String eventType = (String) record.get("eventName");
				Map<String, Object> dynamodb = (Map<String, Object>) record.get("dynamodb");
				if (dynamodb == null) {
					context.getLogger().log("[WARNING] Skipping record, missing 'dynamodb' field.");
					continue;
				}

				Map<String, Object> keys = (Map<String, Object>) dynamodb.get("Keys");
				Map<String, Object> newImage = (Map<String, Object>) dynamodb.get("NewImage");
				Map<String, Object> oldImage = (Map<String, Object>) dynamodb.get("OldImage");

				if (keys == null || !keys.containsKey("key")) {
					context.getLogger().log("[ERROR] Skipping record, missing 'key' field.");
					continue;
				}

				String itemKey = extractStringValue(keys.get("key"));
				String modificationTime = Instant.now().toString();

				// Construct audit entry
				Map<String, AttributeValue> auditEntry = new HashMap<>();
				auditEntry.put("id", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
				auditEntry.put("itemKey", AttributeValue.builder().s(itemKey).build());
				auditEntry.put("modificationTime", AttributeValue.builder().s(modificationTime).build());

				if ("INSERT".equals(eventType)) {
					// New Configuration Item Created
					Map<String, AttributeValue> formattedNewValue = formatConfigItem(newImage);
					auditEntry.put("newValue", AttributeValue.builder().m(formattedNewValue).build());
				} else if ("MODIFY".equals(eventType)) {
					// Configuration Item Updated
					if (newImage != null && oldImage != null && newImage.containsKey("value") && oldImage.containsKey("value")) {
						Object oldVal = extractSingleValue(oldImage.get("value"));
						Object newVal = extractSingleValue(newImage.get("value"));

						if (!Objects.equals(oldVal, newVal)) {
							auditEntry.put("updatedAttribute", AttributeValue.builder().s("value").build());
							auditEntry.put("oldValue", formatAttributeValue(oldVal));
							auditEntry.put("newValue", formatAttributeValue(newVal));
						}
					}
				}

				// Save to DynamoDB
				PutItemRequest putItemRequest = PutItemRequest.builder()
						.tableName(AUDIT_TABLE)
						.item(auditEntry)
						.build();

				dynamoDbClient.putItem(putItemRequest);
				context.getLogger().log("[SUCCESS] Saved audit record for key: " + itemKey);
			}

			return Map.of("statusCode", 200, "message", "Audit logs processed successfully");
		} catch (Exception e) {
			context.getLogger().log("[ERROR] Exception occurred: " + e.getMessage());
			return Map.of("statusCode", 500, "message", "Internal Server Error");
		}
	}

	private String extractStringValue(Object valueObj) {
		if (valueObj instanceof Map) {
			Map<?, ?> valueMap = (Map<?, ?>) valueObj;
			if (valueMap.containsKey("S")) {
				return (String) valueMap.get("S");
			}
		}
		return null;
	}

	private Object extractSingleValue(Object valueObj) {
		if (valueObj instanceof Map) {
			Map<?, ?> valueMap = (Map<?, ?>) valueObj;
			if (valueMap.containsKey("S")) {
				return valueMap.get("S");
			} else if (valueMap.containsKey("N")) {
				return Integer.parseInt((String) valueMap.get("N"));
			}
		}
		return null;
	}

	private Map<String, AttributeValue> formatConfigItem(Map<String, Object> image) {
		Map<String, AttributeValue> formattedItem = new HashMap<>();
		if (image != null) {
			if (image.containsKey("key")) {
				formattedItem.put("key", AttributeValue.builder().s(extractStringValue(image.get("key"))).build());
			}
			if (image.containsKey("value")) {
				Object value = extractSingleValue(image.get("value"));
				formattedItem.put("value", formatAttributeValue(value));
			}
		}
		return formattedItem;
	}

	private AttributeValue formatAttributeValue(Object value) {
		if (value instanceof String) {
			return AttributeValue.builder().s((String) value).build();
		} else if (value instanceof Integer) {
			return AttributeValue.builder().n(String.valueOf(value)).build();
		}
		return AttributeValue.builder().nul(true).build();
	}
}
