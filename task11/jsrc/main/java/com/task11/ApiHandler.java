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

import java.util.Map;
import java.util.regex.Pattern;

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

	private static final String EMAIL_REGEX = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
	private static final String PASSWORD_REGEX = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@#$%^&+=!]).{12,}$";

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
		Map<String, Object> requestContext = (Map<String, Object>) event.get("requestContext");
		Map<String, String> httpInfo = (Map<String, String>) requestContext.get("http");

		String path = httpInfo.get("path");
		String httpMethod = httpInfo.get("method");

		try {
			switch (path) {
				case "/signup":
					return "POST".equalsIgnoreCase(httpMethod) ? handleSignUp(event) : invalidMethodResponse();
				case "/signin":
					return "POST".equalsIgnoreCase(httpMethod) ? handleSignIn(event) : invalidMethodResponse();
				default:
					return errorResponse(404, "Invalid path" + path);
			}
		} catch (Exception e) {
			e.printStackTrace();
			try {
				return errorResponse(500, "Server error: " + (e.getMessage() != null ? e.getMessage() : "Unknown error path: "+path));
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		}
	}

	private Map<String, Object> handleSignUp(Map<String, Object> event) throws Exception {
		JsonNode body = objectMapper.readTree((String) event.get("body"));
		String email = body.get("email").asText();
		String password = body.get("password").asText();

		if (!isValidEmail(email)) {
			return errorResponse(400, "Invalid email format.");
		}
		if (!isValidPassword(password)) {
			return errorResponse(400, "Password must be at least 12 characters, include uppercase, lowercase, number, and special character.");
		}

		try {
			AdminCreateUserRequest signUpRequest = AdminCreateUserRequest.builder()
					.userPoolId(System.getenv("COGNITO_ID"))
					.username(email)
					.temporaryPassword(password)
					.messageAction(MessageActionType.SUPPRESS) // Prevent email notifications during testing
					.build();
			cognitoClient.adminCreateUser(signUpRequest);
			return successResponse("User registered successfully.");
		} catch (UsernameExistsException e) {
			return errorResponse(400, "User already exists.");
		} catch (Exception e) {
			return errorResponse(500, "Signup error: " + e.getMessage());
		}
	}

	private Map<String, Object> handleSignIn(Map<String, Object> event) throws Exception {
		JsonNode body = objectMapper.readTree((String) event.get("body"));
		String email = body.get("email").asText();
		String password = body.get("password").asText();

		// Validate email and password
		if (!isValidEmail(email) || !isValidPassword(password)) {
			return errorResponse(400, "Invalid email or password format.");
		}

		try {
			InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
					.authFlow(AuthFlowType.USER_PASSWORD_AUTH)
					.clientId(System.getenv("CLIENT_ID"))
					.authParameters(Map.of("USERNAME", email, "PASSWORD", password))
					.build();
			InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);
			return successResponse(Map.of(
					"message", "Login successful",
					"token", authResponse.authenticationResult().idToken()
			));
		} catch (NotAuthorizedException e) {
			return errorResponse(400, "Invalid credentials.");
		} catch (UserNotFoundException e) {
			return errorResponse(400, "User does not exist.");
		} catch (Exception e) {
			return errorResponse(500, "Signin error: " + e.getMessage());
		}
	}

	private boolean isValidEmail(String email) {
		return Pattern.compile(EMAIL_REGEX).matcher(email).matches();
	}

	private boolean isValidPassword(String password) {
		return Pattern.compile(PASSWORD_REGEX).matcher(password).matches();
	}

	private Map<String, Object> invalidMethodResponse() throws Exception {
		return errorResponse(405, "Method Not Allowed");
	}

	private Map<String, Object> successResponse(Object data) throws Exception {
		return Map.of(
				"statusCode", 200,
				"headers", Map.of("Content-Type", "application/json"),
				"body", objectMapper.writeValueAsString(Map.of("data", data))
		);
	}

	private Map<String, Object> errorResponse(int statusCode, String message) throws Exception {
		return Map.of(
				"statusCode", statusCode,
				"headers", Map.of("Content-Type", "application/json"),
				"body", objectMapper.writeValueAsString(Map.of("error", message))
		);
	}



}
