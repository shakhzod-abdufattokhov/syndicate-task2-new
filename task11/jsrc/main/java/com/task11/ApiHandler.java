package com.task11;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.HashMap;
import java.util.Map;

import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID;
import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID;

@LambdaHandler(
		lambdaName = "api_handler",
		roleName = "api_handler-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DependsOn(resourceType = ResourceType.COGNITO_USER_POOL, name = "${booking_userpool}")
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "REGION", value = "${region}"),
		@EnvironmentVariable(key = "COGNITO_ID", value = "${pool_name}", valueTransformer = USER_POOL_NAME_TO_USER_POOL_ID),
		@EnvironmentVariable(key = "CLIENT_ID", value = "${pool_name}", valueTransformer = USER_POOL_NAME_TO_CLIENT_ID)
})
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private final DynamoDbClient dynamoDb = DynamoDbClient.create();
	private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.create();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		String path = (String) event.get("path");
		String method = (String) event.get("httpMethod");

		try {
			switch (path) {
				case "/signup":
					return method.equals("POST") ? handleSignup(event) : createResponse(400, "Invalid request");
				case "/signin":
					return method.equals("POST") ? handleSignin(event) : createResponse(400, "Invalid request");
				case "/tables":
					return method.equals("GET") ? getTables() : createTable(event);
				case "/reservations":
					return method.equals("POST") ? makeReservation(event) : createResponse(400, "Invalid request");
				default:
					if (path.startsWith("/tables/")) {
						return getTableById(path.split("/")[2]);
					}
					return createResponse(400, "Invalid request");
			}
		} catch (Exception e) {
			return createResponse(500, "Error: " + e.getMessage());
		}
	}

	private Map<String, Object> handleSignup(Map<String, Object> event) {
		JsonNode body = parseBody(event);
		SignUpRequest request = SignUpRequest.builder()
				.clientId(System.getenv("CLIENT_ID"))
				.username(body.get("email").asText())
				.password(body.get("password").asText())
				.build();
		cognitoClient.signUp(request);
		return createResponse(200, "User registered successfully");
	}

	private Map<String, Object> handleSignin(Map<String, Object> event) {
		JsonNode body = parseBody(event);
		InitiateAuthRequest request = InitiateAuthRequest.builder()
				.authFlow(AuthFlowType.USER_PASSWORD_AUTH)
				.clientId(System.getenv("CLIENT_ID"))
				.authParameters(Map.of("USERNAME", body.get("email").asText(), "PASSWORD", body.get("password").asText()))
				.build();
		InitiateAuthResponse response = cognitoClient.initiateAuth(request);
		return createResponse(200, Map.of("accessToken", response.authenticationResult().idToken()));
	}

	private Map<String, Object> getTables() {
		ScanRequest request = ScanRequest.builder().tableName("Tables").build();
		ScanResponse response = dynamoDb.scan(request);
		return createResponse(200, response.items());
	}

	private Map<String, Object> getTableById(String tableId) {
		GetItemRequest request = GetItemRequest.builder()
				.tableName("Tables")
				.key(Map.of("id", AttributeValue.builder().n(tableId).build()))
				.build();
		GetItemResponse response = dynamoDb.getItem(request);
		if (response.hasItem()) {
			return createResponse(200, response.item());
		}
		return createResponse(400, "Table not found");
	}

	private Map<String, Object> createTable(Map<String, Object> event) {
		JsonNode body = parseBody(event);
		Map<String, AttributeValue> item = new HashMap<>();
		item.put("id", AttributeValue.builder().n(body.get("id").asText()).build());
		item.put("number", AttributeValue.builder().n(body.get("number").asText()).build());
		item.put("places", AttributeValue.builder().n(body.get("places").asText()).build());
		item.put("isVip", AttributeValue.builder().bool(body.get("isVip").asBoolean()).build());
		if (body.has("minOrder")) {
			item.put("minOrder", AttributeValue.builder().n(body.get("minOrder").asText()).build());
		}
		PutItemRequest request = PutItemRequest.builder().tableName("Tables").item(item).build();
		dynamoDb.putItem(request);
		return createResponse(200, Map.of("id", body.get("id").asInt()));
	}

	private Map<String, Object> makeReservation(Map<String, Object> event) {
		JsonNode body = parseBody(event);
		Map<String, AttributeValue> item = new HashMap<>();
		item.put("reservationId", AttributeValue.builder().s(body.get("reservationId").asText()).build());
		item.put("tableNumber", AttributeValue.builder().n(body.get("tableNumber").asText()).build());
		item.put("clientName", AttributeValue.builder().s(body.get("clientName").asText()).build());
		item.put("phoneNumber", AttributeValue.builder().s(body.get("phoneNumber").asText()).build());
		item.put("date", AttributeValue.builder().s(body.get("date").asText()).build());
		PutItemRequest request = PutItemRequest.builder().tableName("Reservations").item(item).build();
		dynamoDb.putItem(request);
		return createResponse(200, "Reservation made");
	}

	private JsonNode parseBody(Map<String, Object> event) {
		try {
			return objectMapper.readTree((String) event.get("body"));
		} catch (Exception e) {
			throw new RuntimeException("Invalid JSON body");
		}
	}

	private Map<String, Object> createResponse(int statusCode, Object body) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("body", body);
		return response;
	}
}
