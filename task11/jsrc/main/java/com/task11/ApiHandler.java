package com.task11;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
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
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String EMAIL_REGEX = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
    private static final String PASSWORD_REGEX = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@#$%^&+=!]).{12,}$";

    private final CognitoIdentityProviderClient cognitoClient;
    private final ObjectMapper objectMapper;

    public ApiHandler() {
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(System.getenv("REGION")))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        String path = event.getPath();
        String httpMethod = event.getHttpMethod();

        context.getLogger().log("Received request: Path = " + path + ", Method = " + httpMethod);

        try {
            switch (path) {
                case "/signup":
                    return "POST".equalsIgnoreCase(httpMethod) ? handleSignUp(event, context) : invalidMethodResponse(context);
                case "/signin":
                    return "POST".equalsIgnoreCase(httpMethod) ? handleSignIn(event, context) : invalidMethodResponse(context);
                default:
                    return errorResponse(400, "Invalid path: " + path, context);
            }
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return errorResponse(500, "Server error: " + (e.getMessage() != null ? e.getMessage() : "Unknown error at path: " + path), context);
        }
    }

    private APIGatewayProxyResponseEvent handleSignUp(APIGatewayProxyRequestEvent event, Context context) throws Exception {
        JsonNode body = objectMapper.readTree(event.getBody());
        String email = body.get("email").asText();
        String password = body.get("password").asText();

        context.getLogger().log("Processing signup for email: " + email);

        if (!isValidEmail(email)) {
            return errorResponse(400, "Invalid email format.", context);
        }
        if (!isValidPassword(password)) {
            return errorResponse(400, "Password does not meet security requirements.", context);
        }

        try {
            AdminCreateUserRequest signUpRequest = AdminCreateUserRequest.builder()
                    .userPoolId(System.getenv("COGNITO_ID"))
                    .username(email)
                    .temporaryPassword(password)
                    .messageAction(MessageActionType.SUPPRESS)
                    .build();
            cognitoClient.adminCreateUser(signUpRequest);

            context.getLogger().log("User registered successfully: " + email);
            return successResponse("User registered successfully.", context);
        } catch (UsernameExistsException e) {
            context.getLogger().log("Signup error: User already exists - " + email);
            return errorResponse(400, "User already exists.", context);
        } catch (Exception e) {
            context.getLogger().log("Signup error: " + e.getMessage());
            return errorResponse(500, "Signup error: " + e.getMessage(), context);
        }
    }

    private APIGatewayProxyResponseEvent handleSignIn(APIGatewayProxyRequestEvent event, Context context) throws Exception {
        JsonNode body = objectMapper.readTree(event.getBody());
        String email = body.get("email").asText();
        String password = body.get("password").asText();

        context.getLogger().log("Processing signin for email: " + email);

        if (!isValidEmail(email) || !isValidPassword(password)) {
            return errorResponse(400, "Invalid email or password format.", context);
        }

        try {
            InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .clientId(System.getenv("CLIENT_ID"))
                    .authParameters(Map.of("USERNAME", email, "PASSWORD", password))
                    .build();
            InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);

            context.getLogger().log("Login successful for email: " + email);
            return successResponse(Map.of(
                    "message", "Login successful",
                    "token", authResponse.authenticationResult().idToken()
            ), context);
        } catch (NotAuthorizedException e) {
            context.getLogger().log("Signin error: Invalid credentials for email - " + email);
            return errorResponse(400, "Invalid credentials.", context);
        } catch (UserNotFoundException e) {
            context.getLogger().log("Signin error: User does not exist - " + email);
            return errorResponse(400, "User does not exist.", context);
        } catch (Exception e) {
            context.getLogger().log("Signin error: " + e.getMessage());
            return errorResponse(500, "Signin error: " + e.getMessage(), context);
        }
    }

    private boolean isValidEmail(String email) {
        return Pattern.compile(EMAIL_REGEX).matcher(email).matches();
    }

    private boolean isValidPassword(String password) {
        return Pattern.compile(PASSWORD_REGEX).matcher(password).matches();
    }

    private APIGatewayProxyResponseEvent invalidMethodResponse(Context context) {
        context.getLogger().log("Error: Method Not Allowed");
        return errorResponse(400, "Method Not Allowed", context);
    }

    private APIGatewayProxyResponseEvent successResponse(Object data, Context context) throws Exception {
        String response = objectMapper.writeValueAsString(Map.of("data", data));
        context.getLogger().log("Response: " + response);
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(response);
    }

    private APIGatewayProxyResponseEvent errorResponse(int statusCode, String message, Context context) {
        context.getLogger().log("Error response: " + message);
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody("{\"error\": \"" + message + "\"}");
    }
}
