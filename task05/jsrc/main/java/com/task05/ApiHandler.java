package com.task05;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
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
@EnvironmentVariables(value = {
        @EnvironmentVariable(key = "region", value = "${region}"),
        @EnvironmentVariable(key = "table", value = "${target_table}")
})
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String TABLE_NAME = System.getenv("table");
    private final AmazonDynamoDB client = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(client);

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
        context.getLogger().log("Received request: " + request);

        try {
            if (!request.containsKey("principalId") || !request.containsKey("content")) {
                return generateErrorResponse(400, "Invalid request: Missing required fields.");
            }

            // Extract values safely
            int principalId = Integer.parseInt(request.get("principalId").toString());
            Object contentObj = request.get("content");

            if (!(contentObj instanceof Map)) {
                return generateErrorResponse(400, "Invalid content format. Expected JSON object.");
            }

            Map<String, Object> content = (Map<String, Object>) contentObj;

            // Generate required values
            String eventId = UUID.randomUUID().toString();
            String createdAt = Instant.now().toString();

            // Save to DynamoDB
            Table table = dynamoDB.getTable(TABLE_NAME);
            Item item = new Item()
                    .withPrimaryKey("id", eventId)
                    .withNumber("principalId", principalId)
                    .withString("createdAt", createdAt)
                    .withMap("body", content);

            table.putItem(item);
            context.getLogger().log("Successfully saved event: " + eventId);

            // Prepare Response
            Map<String, Object> event = new HashMap<>();
            event.put("id", eventId);
            event.put("principalId", principalId);
            event.put("createdAt", createdAt);
            event.put("body", content);

            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", 201);
            response.put("event", event);

            return response;
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return generateErrorResponse(500, "Internal Server Error");
        }
    }

    private Map<String, Object> generateErrorResponse(int statusCode, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("statusCode", statusCode);
        errorResponse.put("error", message);
        return errorResponse;
    }
}
