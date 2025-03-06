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

	private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
			.region(Region.of(System.getenv("REGION")))
			.build();

	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
	private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[$%^*-_])[A-Za-z\\d$%^*-_]{12,}$");

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		try {
			String path = (String) event.get("resource");
			String httpMethod = (String) event.get("httpMethod");
			JsonNode body = objectMapper.readTree((String) event.get("body"));

			context.getLogger().log("Received request: Path=" + path + ", Method=" + httpMethod);

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

			context.getLogger().log("Handling signup for email: " + email);

			if (!EMAIL_PATTERN.matcher(email).matches()) {
				context.getLogger().log("Invalid email format: " + email);
				return response(400, "Invalid email format.");
			}
			if (!PASSWORD_PATTERN.matcher(password).matches()) {
				context.getLogger().log("Invalid password format.");
				return response(400, "Password must be at least 12 characters long, contain letters, digits, and special characters.");
			}

			String userPoolId = System.getenv("COGNITO_ID");

			AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
					.userPoolId(userPoolId)
					.username(email)
					.userAttributes(
							AttributeType.builder().name("email").value(email).build(),
							AttributeType.builder().name("email_verified").value("true").build()
					)
					.messageAction(MessageActionType.SUPPRESS)
					.build();

			cognitoClient.adminCreateUser(createUserRequest);
			context.getLogger().log("User created successfully in Cognito.");

			AdminSetUserPasswordRequest setUserPasswordRequest = AdminSetUserPasswordRequest.builder()
					.userPoolId(userPoolId)
					.username(email)
					.password(password)
					.permanent(true)
					.build();

			cognitoClient.adminSetUserPassword(setUserPasswordRequest);
			context.getLogger().log("Password set successfully for user: " + email);

			return response(200, "User registered successfully.");
		} catch (CognitoIdentityProviderException e) {
			context.getLogger().log("Cognito error: " + e.awsErrorDetails().errorMessage());
			return response(400, "Error: " + e.awsErrorDetails().errorMessage());
		}
	}

	private Map<String, Object> handleSignIn(JsonNode body, Context context) {
		try {
			String email = body.get("email").asText();
			String password = body.get("password").asText();

			context.getLogger().log("Handling signin for email: " + email);

			Map<String, String> authParams = new HashMap<>();
			authParams.put("USERNAME", email);
			authParams.put("PASSWORD", password);

			AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
					.authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
					.userPoolId(System.getenv("COGNITO_ID"))
					.clientId(System.getenv("CLIENT_ID"))
					.authParameters(authParams)
					.build();

			AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);
			context.getLogger().log("User authenticated successfully: " + email);

			return response(200, authResponse.authenticationResult().idToken());
		} catch (UserNotFoundException e) {
			context.getLogger().log("User not found: " + e.getMessage());
			return response(400, "User does not exist.");
		} catch (NotAuthorizedException e) {
			context.getLogger().log("Invalid password for user: " + e.getMessage());
			return response(400, "Invalid username or password.");
		} catch (CognitoIdentityProviderException e) {
			context.getLogger().log("Cognito error: " + e.awsErrorDetails().errorMessage());
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
