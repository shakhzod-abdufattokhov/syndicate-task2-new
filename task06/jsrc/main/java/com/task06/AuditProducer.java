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

import java.io.PrintWriter;
import java.io.StringWriter;
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
		@EnvironmentVariable(key = "table", value = "${target_table")
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
			context.getLogger().log("Received event: " + input);

			List<Map<String, Object>> records = (List<Map<String, Object>>) input.get("Records");

			if (records == null || records.isEmpty()) {
				return Map.of("statusCode", 400, "message", "No records found");
			}

			for (Map<String, Object> record : records) {
				String eventId = UUID.randomUUID().toString();
				String eventType = (String) record.get("eventName");
				Map<String, Object> dynamodb = (Map<String, Object>) record.get("dynamodb");

				if (dynamodb == null) continue;

				Map<String, Object> keys = (Map<String, Object>) dynamodb.get("Keys");
				Map<String, Object> oldImage = (Map<String, Object>) dynamodb.get("OldImage");
				Map<String, Object> newImage = (Map<String, Object>) dynamodb.get("NewImage");

				if (keys == null || !keys.containsKey("key")) continue;
				String configKey = extractStringValue(keys.get("key"));

				String beforeImage = (oldImage != null) ? extractImage(oldImage) : "N/A";
				String afterImage = (newImage != null) ? extractImage(newImage) : "N/A";
				String timestamp = Instant.now().toString();

				Map<String, AttributeValue> auditEntry = new HashMap<>();
				auditEntry.put("audit_id", AttributeValue.builder().s(eventId).build());
				auditEntry.put("config_key", AttributeValue.builder().s(configKey).build());
				auditEntry.put("event_type", AttributeValue.builder().s(eventType).build());
				auditEntry.put("before", AttributeValue.builder().s(beforeImage).build());
				auditEntry.put("after", AttributeValue.builder().s(afterImage).build());
				auditEntry.put("timestamp", AttributeValue.builder().s(timestamp).build());

				PutItemRequest putItemRequest = PutItemRequest.builder()
						.tableName(AUDIT_TABLE)
						.item(auditEntry)
						.build();
				dynamoDbClient.putItem(putItemRequest);

				context.getLogger().log("Saved audit record for key: " + configKey);
			}

			return Map.of("statusCode", 200, "message", "Audit logs processed successfully");
		} catch (Exception e) {
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			context.getLogger().log("Error: " + sw);
			return Map.of("statusCode", 500, "message", "Internal Server Error");
		}
	}

	private String extractStringValue(Object value) {
		if (value instanceof Map) {
			return ((Map<String, String>) value).get("S");
		}
		return "N/A";
	}

	private String extractImage(Map<String, Object> image) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> entry : image.entrySet()) {
			sb.append(entry.getKey()).append(": ").append(extractStringValue(entry.getValue())).append(", ");
		}
		return sb.length() > 0 ? sb.substring(0, sb.length() - 2) : "N/A";
	}
}
