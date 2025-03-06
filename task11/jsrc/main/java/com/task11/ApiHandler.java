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
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.HashMap;
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

	private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.create();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
	private static final int MIN_PASSWORD_LENGTH = 12; // Enforced password policy

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
				default:
					return createResponse(400, "Invalid request");
			}
		} catch (Exception e) {
			return createResponse(500, "Error: " + e.getMessage());
		}
	}

	private Map<String, Object> handleSignup(Map<String, Object> event) {
		JsonNode body = parseBody(event);
		String email = body.get("email").asText();
		String password = body.get("password").asText();

		if (!isValidEmail(email)) {
			return createResponse(400, "Invalid email format");
		}

		if (!isValidPassword(password)) {
			return createResponse(400, "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long and include special characters.");
		}

		try {
			// Check if user already exists
			cognitoClient.adminGetUser(AdminGetUserRequest.builder()
					.userPoolId(System.getenv("COGNITO_ID"))
					.username(email)
					.build());

			return createResponse(400, "User already exists");
		} catch (UserNotFoundException ignored) {
			// Expected if user does not exist
		}

		try {
			// Create the user
			AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
					.userPoolId(System.getenv("COGNITO_ID"))
					.username(email)
					.temporaryPassword(password)
					.messageAction(MessageActionType.SUPPRESS)
					.userAttributes(
							AttributeType.builder().name("email").value(email).build(),
							AttributeType.builder().name("email_verified").value("true").build()
					)
					.build();

			cognitoClient.adminCreateUser(createUserRequest);

			// Set a permanent password
			cognitoClient.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
					.userPoolId(System.getenv("COGNITO_ID"))
					.username(email)
					.password(password)
					.permanent(true)
					.build());

			return createResponse(200, "User registered successfully");
		} catch (CognitoIdentityProviderException e) {
			return createResponse(400, "Signup failed: " + e.awsErrorDetails().errorMessage());
		}
	}

	private Map<String, Object> handleSignin(Map<String, Object> event) {
		JsonNode body = parseBody(event);
		String email = body.get("email").asText();
		String password = body.get("password").asText();

		try {
			InitiateAuthRequest request = InitiateAuthRequest.builder()
					.authFlow(AuthFlowType.USER_PASSWORD_AUTH)
					.clientId(System.getenv("CLIENT_ID"))
					.authParameters(Map.of("USERNAME", email, "PASSWORD", password))
					.build();

			InitiateAuthResponse response = cognitoClient.initiateAuth(request);
			return createResponse(200, Map.of("accessToken", response.authenticationResult().idToken()));
		} catch (NotAuthorizedException | UserNotFoundException e) {
			return createResponse(400, "Invalid credentials");
		} catch (CognitoIdentityProviderException e) {
			return createResponse(500, "Signin failed: " + e.awsErrorDetails().errorMessage());
		}
	}

	private boolean isValidEmail(String email) {
		return EMAIL_PATTERN.matcher(email).matches();
	}

	private boolean isValidPassword(String password) {
		return password.length() >= MIN_PASSWORD_LENGTH && password.matches(".*[!@#$%^&*].*");
	}

	private JsonNode parseBody(Map<String, Object> event) {
		try {
			return objectMapper.readTree((String) event.get("body"));
		} catch (Exception e) {
			throw new RuntimeException("Invalid JSON body");
		}
	}

	private Map<String, Object> createResponse(int statusCode, Object body) {
		return Map.of(
				"statusCode", statusCode,
				"body", objectMapper.valueToTree(body).toString()
		);
	}
}
