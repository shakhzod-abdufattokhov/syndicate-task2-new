package com.task11;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.create();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
	private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[$%^*-_])[A-Za-z\\d$%^*-_]{12,}$");

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		context.getLogger().log("Received event: " + event);

		try {
			String path = (String) event.get("resource");
			String httpMethod = (String) event.get("httpMethod");
			JsonNode body = objectMapper.readTree((String) event.get("body"));

			if ("/signup".equals(path) && "POST".equalsIgnoreCase(httpMethod)) {
				return handleSignUp(body, context);
			} else if ("/signin".equals(path) && "POST".equalsIgnoreCase(httpMethod)) {
				return handleSignIn(body, context);
			}

			return response(400, "Invalid request");
		} catch (Exception e) {
			context.getLogger().log("Error processing request: " + e.getMessage());
			return response(400, "Error processing request: " + e.getMessage());
		}
	}

	private Map<String, Object> handleSignUp(JsonNode body, Context context) {
		try {
			String email = body.get("email").asText();
			String password = body.get("password").asText();
			String firstName = body.get("firstName").asText();
			String lastName = body.get("lastName").asText();
			String nickName = body.get("nickName").asText();

			if (!EMAIL_PATTERN.matcher(email).matches()) {
				return response(400, "Invalid email format.");
			}
			if (!PASSWORD_PATTERN.matcher(password).matches()) {
				return response(400, "Password must be at least 12 characters long, contain letters, digits, and special characters.");
			}

			AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
					.userPoolId(System.getenv("COGNITO_ID"))
					.username(nickName)
					.temporaryPassword(password)
					.userAttributes(
							AttributeType.builder().name("given_name").value(firstName).build(),
							AttributeType.builder().name("family_name").value(lastName).build(),
							AttributeType.builder().name("email").value(email).build(),
							AttributeType.builder().name("email_verified").value("true").build()
					)
					.desiredDeliveryMediums(DeliveryMediumType.EMAIL)
					.messageAction("SUPPRESS")
					.forceAliasCreation(false)
					.build();

			cognitoClient.adminCreateUser(createUserRequest);

			return response(200, "User registered successfully.");
		} catch (CognitoIdentityProviderException e) {
			return response(400, "Error: " + e.awsErrorDetails().errorMessage());
		}
	}

	private Map<String, Object> handleSignIn(JsonNode body, Context context) {
		try {
			String email = body.get("email").asText();
			String password = body.get("password").asText();

			Map<String, String> authParams = new HashMap<>();
			authParams.put("USERNAME", email);
			authParams.put("PASSWORD", password);

			AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
					.authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
					.clientId(System.getenv("CLIENT_ID"))
					.userPoolId(System.getenv("COGNITO_ID"))
					.authParameters(authParams)
					.build();

			AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

			return response(200, authResponse.authenticationResult().idToken());
		} catch (CognitoIdentityProviderException e) {
			return response(400, "Authentication failed: " + e.awsErrorDetails().errorMessage());
		}
	}

	private Map<String, Object> response(int statusCode, String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("body", message);
		return response;
	}
}
