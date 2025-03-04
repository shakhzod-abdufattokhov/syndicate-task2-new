package com.task05;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
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
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
        lambdaName = "api_handler",
        roleName = "api_handler-role",
        isPublishVersion = false,
        aliasName = "${lambdas_alias_name}",
        logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)

@EnvironmentVariables(value = {
        @EnvironmentVariable(key = "region", value = "${region}"),
        @EnvironmentVariable(key = "table", value = "${target_table}")
})
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String TABLE_NAME = System.getenv("table");
    private final DynamoDbClient dynamoDbClient;

    public ApiHandler() {
        this.dynamoDbClient = DynamoDbClient.builder()
                .region(Region.of(System.getenv("region")))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {

        try {
            context.getLogger().log("Received request: " + input);

            int principalId = ((Number) input.get("principalId")).intValue();
            Map<String, String> content = (Map<String, String>) input.get("content");

            String eventId = UUID.randomUUID().toString();
            String createdAt = Instant.now().toString();

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s(eventId).build());
            item.put("principalId", AttributeValue.builder().n(String.valueOf(principalId)).build());
            item.put("createdAt", AttributeValue.builder().s(createdAt).build());
            item.put("body", AttributeValue.builder().m(convertMapToAttributeValue(content)).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(item)
                    .build();
            dynamoDbClient.putItem(putItemRequest);

            Map<String, Object> eventResponse = new HashMap<>();
            eventResponse.put("id", eventId);
            eventResponse.put("principalId", principalId);
            eventResponse.put("createdAt", createdAt);
            eventResponse.put("body", content);

            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", 201);
            response.put("event", eventResponse);

            return response;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            context.getLogger().log("Error: " + sw);
            return Map.of("statusCode", 500, "message", "Internal Server Error");
        }
    }

    private Map<String, AttributeValue> convertMapToAttributeValue(Map<String, String> map) {
        Map<String, AttributeValue> attributeValueMap = new HashMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            attributeValueMap.put(entry.getKey(), AttributeValue.builder().s(entry.getValue()).build());
        }
        return attributeValueMap;
    }

}
