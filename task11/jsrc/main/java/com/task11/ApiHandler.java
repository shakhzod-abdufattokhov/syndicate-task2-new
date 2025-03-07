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
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID;
import static com.syndicate.deployment.model.environment.ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID;

@LambdaHandler(
        lambdaName = "api_handler",
        roleName = "api_handler-role",
        runtime = DeploymentRuntime.JAVA11,
        isPublishVersion = true,
        aliasName = "${lambdas_alias_name}",
        logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DependsOn(resourceType = ResourceType.COGNITO_USER_POOL, name = "${booking_userpool}")
@EnvironmentVariables(value = {
        @EnvironmentVariable(key = "REGION", value = "${region}"),
        @EnvironmentVariable(key = "COGNITO_ID", value = "${booking_userpool}", valueTransformer = USER_POOL_NAME_TO_USER_POOL_ID),
        @EnvironmentVariable(key = "CLIENT_ID", value = "${booking_userpool}", valueTransformer = USER_POOL_NAME_TO_CLIENT_ID),
        @EnvironmentVariable(key = "table", value = "${tables_table}"),
        @EnvironmentVariable(key = "reservation", value = "${reservations_table}")
})
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final String EMAIL_REGEX = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
    private static final String PASSWORD_REGEX = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@#$%^&+=!\\-_]).{12,}$";

    private final CognitoIdentityProviderClient cognitoClient;
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String tableName = System.getenv("table");


    public ApiHandler(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = DynamoDbClient.builder()
                .region(Region.of(System.getenv("REGION")))
                .build();
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
                case "/tables":
                    if ("POST".equalsIgnoreCase(httpMethod)) {
                        return handleAddTable(event, context);
                    } else if ("GET".equalsIgnoreCase(httpMethod)) {
                        return handleGetTables(event, context);
                    } else {
                        return invalidMethodResponse(context);
                    }
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
            SignUpRequest signUpRequest = SignUpRequest.builder()
                    .clientId(System.getenv("CLIENT_ID"))
                    .username(email)
                    .password(password)
                    .userAttributes(
                            AttributeType.builder().name("email").value(email).build()
                    )
                    .build();

            cognitoClient.signUp(signUpRequest);

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


    private void confirmSignUp(String email) {
        try {
            cognitoClient.adminConfirmSignUp(AdminConfirmSignUpRequest.builder()
                    .userPoolId(System.getenv("COGNITO_ID"))
                    .username(email)
                    .build());

            System.out.println("User confirmed successfully: " + email);
        } catch (Exception e) {
            System.err.println("Error confirming user " + email + ": " + e.getMessage());
            throw new RuntimeException("Failed to confirm user: " + email, e);
        }
    }

    private APIGatewayProxyResponseEvent handleSignIn(APIGatewayProxyRequestEvent event, Context context) throws Exception {
        JsonNode body = objectMapper.readTree(event.getBody());
        String email = body.get("email").asText();
        String password = body.get("password").asText();

        context.getLogger().log("Processing signin for email: " + email);

        if (!isValidEmail(email)) {
            return errorResponse(400, "Invalid email format.", context);
        }
        if (!isValidPassword(password)) {
            return errorResponse(400, "Password does not meet security requirements.", context);
        }

        try {
            AdminGetUserResponse userResponse = cognitoClient.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(System.getenv("COGNITO_ID"))
                    .username(email)
                    .build());

            if (userResponse.userStatus() != UserStatusType.CONFIRMED) {
                context.getLogger().log("User is not confirmed: " + email + ". Confirming now...");
                confirmSignUp(email);
            }

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
                    .userPoolId(System.getenv("COGNITO_ID"))
                    .clientId(System.getenv("CLIENT_ID"))
                    .authParameters(Map.of(
                            "USERNAME", email,
                            "PASSWORD", password
                    ))
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            if (authResponse.authenticationResult() == null) {
                context.getLogger().log("Authentication result is null for user: " + email);
                return errorResponse(400, "Authentication failed.", context);
            }

            context.getLogger().log("Login successful for email: " + email);
            Map<String, String> message = Map.of(
                    "message", "Login successful",
                    "accessToken", authResponse.authenticationResult().accessToken());


            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(objectMapper.writeValueAsString( message));

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

    private APIGatewayProxyResponseEvent handleAddTable(APIGatewayProxyRequestEvent event, Context context) throws Exception {
        context.getLogger().log("Processing add table request...\n");

        // Extract Authorization header
        Map<String, String> headers = event.getHeaders();
        if (headers == null || !headers.containsKey("Authorization")) {
            context.getLogger().log("Missing Authorization header\n");
            return errorResponse(401, "Missing Authorization header", context);
        }
        String accessToken = headers.get("Authorization").replace("Bearer ", "");

        // Verify access token with Cognito
        try {
            cognitoClient.getUser(GetUserRequest.builder()
                    .accessToken(accessToken)
                    .build());
            context.getLogger().log("Authorization verified successfully.\n");
        } catch (Exception e) {
            context.getLogger().log("Unauthorized request: " + e.getMessage() + "\n");
            return errorResponse(401, "Unauthorized", context);
        }

        // Parse request body
        JsonNode body = objectMapper.readTree(event.getBody());
        if (!body.has("tableName")) {
            context.getLogger().log("Invalid request: Missing tableName field\n");
            return errorResponse(400, "Missing tableName field", context);
        }

        String tableName = body.get("tableName").asText();
        Integer tableDeposit = body.has("tableDeposit") ? body.get("tableDeposit").asInt() : null;

        long tableId = System.currentTimeMillis(); // Unique ID based on timestamp

        // Insert into DynamoDB
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().n(String.valueOf(tableId)).build());
            item.put("tableName", AttributeValue.builder().s(tableName).build());

            if (tableDeposit != null) {
                item.put("tableDeposit", AttributeValue.builder().n(String.valueOf(tableDeposit)).build());
            }

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName) // Change to your actual DynamoDB table name
                    .item(item)
                    .build());

            context.getLogger().log("Table added successfully: ID=" + tableId + ", Name=" + tableName + "\n");

            return successResponse(Map.of("id", tableId), context);
        } catch (Exception e) {
            context.getLogger().log("Error adding table: " + e.getMessage() + "\n");
            return errorResponse(500, "Error adding table: " + e.getMessage(), context);
        }
    }

    private APIGatewayProxyResponseEvent handleGetTables(APIGatewayProxyRequestEvent event, Context context) {
        context.getLogger().log("Processing get tables request...\n");

        // Extract Authorization header
        Map<String, String> headers = event.getHeaders();
        if (headers == null || !headers.containsKey("Authorization")) {
            context.getLogger().log("Missing Authorization header\n");
            return errorResponse(401, "Missing Authorization header", context);
        }
        String accessToken = headers.get("Authorization").replace("Bearer ", "");

        // Verify access token with Cognito
        try {
            cognitoClient.getUser(GetUserRequest.builder()
                    .accessToken(accessToken)
                    .build());
            context.getLogger().log("Authorization verified successfully.\n");
        } catch (Exception e) {
            context.getLogger().log("Unauthorized request: " + e.getMessage() + "\n");
            return errorResponse(401, "Unauthorized", context);
        }

        // Fetch tables from DynamoDB
        try {
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(tableName) // Change to your actual table name
                    .build();
            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

            List<Map<String, Object>> tables = new ArrayList<>();

            for (Map<String, AttributeValue> item : scanResponse.items()) {
                Map<String, Object> table = new HashMap<>();
                table.put("id", Long.parseLong(item.get("id").n()));
                table.put("tableName", item.get("tableName").s());

                if (item.containsKey("tableDeposit")) {
                    table.put("tableDeposit", Integer.parseInt(item.get("tableDeposit").n()));
                }

                tables.add(table);
            }

            context.getLogger().log("Retrieved " + tables.size() + " tables successfully.\n");

            return successResponse(tables, context);
        } catch (Exception e) {
            context.getLogger().log("Error fetching tables: " + e.getMessage() + "\n");
            return errorResponse(500, "Error fetching tables: " + e.getMessage(), context);
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
