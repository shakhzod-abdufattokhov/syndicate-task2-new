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
	private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[$%^*-_])[A-Za-z\\d$%^*-_]{12,}$");

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		try {
			String path = (String) event.get("resource");
			String httpMethod = (String) event.get("httpMethod");
			JsonNode body = objectMapper.readTree((String) event.get("body"));

			if ("/signup".equals(path) && "POST".equalsIgnoreCase(httpMethod)) {
				return handleSignUp(body);
			} else if ("/signin".equals(path) && "POST".equalsIgnoreCase(httpMethod)) {
				return handleSignIn(body);
			}

			return response(400, "Invalid request");
		} catch (Exception e) {
			return response(400, "Error processing request: " + e.getMessage());
		}
	}

	private Map<String, Object> handleSignUp(JsonNode body) {
		try {
			String email = body.get("email").asText();
			String password = body.get("password").asText();

			if (!EMAIL_PATTERN.matcher(email).matches()) {
				return response(400, "Invalid email format.");
			}
			if (!PASSWORD_PATTERN.matcher(password).matches()) {
				return response(400, "Password must be at least 12 characters long, contain letters, digits, and special characters.");
			}

			SignUpRequest signUpRequest = SignUpRequest.builder()
					.clientId(System.getenv("CLIENT_ID"))
					.username(email)
					.password(password)
					.userAttributes(
							AttributeType.builder().name("email").value(email).build(),
							AttributeType.builder().name("email_verified").value("true").build()
					)
					.build();

			cognitoClient.signUp(signUpRequest);

			return response(200, "User registered successfully. Please confirm your email.");
		} catch (CognitoIdentityProviderException e) {
			return response(400, "Error: " + e.awsErrorDetails().errorMessage());
		}
	}

	private Map<String, Object> handleSignIn(JsonNode body) {
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
