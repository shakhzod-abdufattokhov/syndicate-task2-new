package com.task08;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.EventSource;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.EventBridgeRuleSource;
import com.syndicate.deployment.annotations.events.RuleEventSource;
import com.syndicate.deployment.annotations.events.RuleEvents;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

import java.time.Instant;
import java.util.*;

@LambdaHandler(
		lambdaName = "uuid_generator",
		roleName = "uuid_generator-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@EnvironmentVariables({
		@EnvironmentVariable(key = "target_bucket", value = "${target_bucket}")
})
@EventBridgeRuleSource(targetRule = "uuid_trigger")
public class UuidGenerator implements RequestHandler<Object, Map<String, Object>> {

	private final AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final String bucketName = System.getenv("target_bucket");

	@Override
	public Map<String, Object> handleRequest(Object request, Context context) {
		String timestamp = Instant.now().toString();
		List<String> uuids = generateUUIDs(10);

		Map<String, Object> data = new HashMap<>();
		data.put("ids", uuids);

		try {
			String jsonContent = objectMapper.writeValueAsString(data);
			s3Client.putObject(bucketName, timestamp, jsonContent);
			context.getLogger().log("File stored: " + timestamp);
			return Map.of("statusCode", 200, "body", "File stored successfully");
		} catch (Exception e) {
			context.getLogger().log("Error: " + e.getMessage());
			return Map.of("statusCode", 500, "body", "Error storing file");
		}
	}

	private List<String> generateUUIDs(int count) {
		List<String> uuids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			uuids.add(UUID.randomUUID().toString());
		}
		return uuids;
	}
}
