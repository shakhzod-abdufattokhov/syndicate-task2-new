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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.*;
import static com.syndicate.deployment.model.environment.ValueTransformer.*;

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

	private final CognitoIdentityProviderClient cognitoClient;
	private final DynamoDbClient dynamoDbClient;
	private final ObjectMapper objectMapper;

	public ApiHandler() {
		this.cognitoClient = CognitoIdentityProviderClient.builder()
				.region(Region.of(System.getenv("REGION")))
				.build();
		this.dynamoDbClient = DynamoDbClient.builder()
				.region(Region.of(System.getenv("REGION")))
				.build();
		this.objectMapper = new ObjectMapper();
	}

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		String path = (String) event.get("path");
		String httpMethod = (String) event.get("httpMethod");

		try {
			switch (path) {
				case "/signup":
					return "POST".equalsIgnoreCase(httpMethod) ? handleSignUp(event) : invalidMethodResponse();
				case "/signin":
					return "POST".equalsIgnoreCase(httpMethod) ? handleSignIn(event) : invalidMethodResponse();
				case "/tables":
					return "POST".equalsIgnoreCase(httpMethod) ? handleCreateTable(event) : handleGetTables();
				case "/reservations":
					return "POST".equalsIgnoreCase(httpMethod) ? handleCreateReservation(event) : handleGetReservations();
				default:
					return errorResponse(404, "Invalid path");
			}
		} catch (Exception e) {
			return errorResponse(500, "Error: " + e.getMessage());
		}
	}

	private Map<String, Object> handleSignUp(Map<String, Object> event) throws Exception {
		JsonNode body = objectMapper.readTree((String) event.get("body"));
		String username = body.get("username").asText();
		String password = body.get("password").asText();

		SignUpRequest signUpRequest = SignUpRequest.builder()
				.clientId(System.getenv("CLIENT_ID"))
				.username(username)
				.password(password)
				.build();
		cognitoClient.signUp(signUpRequest);
		return successResponse("User registered successfully");
	}

	private Map<String, Object> handleSignIn(Map<String, Object> event) throws Exception {
		JsonNode body = objectMapper.readTree((String) event.get("body"));
		String username = body.get("username").asText();
		String password = body.get("password").asText();

		InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
				.authFlow(AuthFlowType.USER_PASSWORD_AUTH)
				.clientId(System.getenv("CLIENT_ID"))
				.authParameters(Map.of("USERNAME", username, "PASSWORD", password))
				.build();
		InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);
		return successResponse(authResponse.authenticationResult().idToken());
	}

	private Map<String, Object> handleCreateTable(Map<String, Object> event) {
		CreateTableRequest request = CreateTableRequest.builder()
				.tableName("Tables")
				.keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
				.attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
				.billingMode(BillingMode.PAY_PER_REQUEST)
				.build();
		dynamoDbClient.createTable(request);
		return successResponse("Table created successfully");
	}

	private Map<String, Object> handleGetTables() {
		ListTablesResponse response = dynamoDbClient.listTables();
		return successResponse(response.tableNames());
	}

	private Map<String, Object> handleCreateReservation(Map<String, Object> event) throws Exception {
		JsonNode body = objectMapper.readTree((String) event.get("body"));
		String reservationId = UUID.randomUUID().toString();

		PutItemRequest request = PutItemRequest.builder()
				.tableName("Reservations")
				.item(Map.of(
						"id", AttributeValue.builder().s(reservationId).build(),
						"user", AttributeValue.builder().s(body.get("user").asText()).build(),
						"table", AttributeValue.builder().s(body.get("table").asText()).build()
				))
				.build();
		dynamoDbClient.putItem(request);
		return successResponse("Reservation created successfully");
	}

	private Map<String, Object> handleGetReservations() {
		ScanResponse response = dynamoDbClient.scan(ScanRequest.builder().tableName("Reservations").build());
		return successResponse(response.items());
	}

	private Map<String, Object> successResponse(Object data) {
		return Map.of("statusCode", 400, "body", data);
	}

	private Map<String, Object> errorResponse(int statusCode, String message) {
		return Map.of("statusCode", statusCode, "body", message);
	}

	private Map<String, Object> invalidMethodResponse() {
		return errorResponse(405, "Method Not Allowed");
	}
}