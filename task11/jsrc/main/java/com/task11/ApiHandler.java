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
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
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


    public ApiHandler() {
        this.dynamoDbClient = DynamoDbClient.builder()
                .region(Region.of(System.getenv("REGION")))
                .credentialsProvider(DefaultCredentialsProvider.create())
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
                    if (httpMethod.equalsIgnoreCase("GET")) {
                        return handleTablesGet(event, context);
                    }else if(httpMethod.equalsIgnoreCase("POST")){
                        return handleTablePost(event, context);
                    }else{
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

    private APIGatewayProxyResponseEvent handleTablePost(APIGatewayProxyRequestEvent event, Context context) {
        context.getLogger().log("Receiving request -> "+ event.getBody());
        String token = event.getHeaders().get("Authorization");
        context.getLogger().log("Authorization token: " + token);
        if (token == null || !token.startsWith("Bearer ")) {
            context.getLogger().log("Missing or invalid Authorization header: " + token);
            return errorResponse(401, "Missing or invalid Authorization header", context);
        }

        token = token.substring(7); // Remove "Bearer " prefix

        context.getLogger().log("Bearer is removed " + token);
        if (!isTokenValid(token, context)) {
            context.getLogger().log("Unauthorized: Invalid token: "+ token);
            return errorResponse(401, "Unauthorized: Invalid token", context);
        }

        try {
            context.getLogger().log("Starting request processing...");

            String tableName = System.getenv("table");
            context.getLogger().log("Table name retrieved from environment: " + tableName);

            JsonNode body = objectMapper.readTree(event.getBody());
            context.getLogger().log("Request body parsed successfully.");

            int id = body.get("id").asInt();
            int number = body.get("number").asInt();
            int places = body.get("places").asInt();
            boolean isVip = body.get("isVip").asBoolean();
            int minOrder = body.has("minOrder") ? body.get("minOrder").asInt() : 0;

            context.getLogger().log("Extracted values - id: " + id + ", number: " + number +
                    ", places: " + places + ", isVip: " + isVip + ", minOrder: " + minOrder);

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s(String.valueOf(id)).build());
            item.put("number", AttributeValue.builder().n(String.valueOf(number)).build());
            item.put("places", AttributeValue.builder().n(String.valueOf(places)).build());
            item.put("isVip", AttributeValue.builder().bool(isVip).build());
            if (body.has("minOrder")) {
                item.put("minOrder", AttributeValue.builder().n(String.valueOf(minOrder)).build());
            }

            context.getLogger().log("Item map constructed: " + item.toString());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build();

            context.getLogger().log("PutItemRequest created: " + putItemRequest.toString());

            dynamoDbClient.putItem(putItemRequest);
            context.getLogger().log("Data inserted into DynamoDB successfully.");

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("id", id);

            String responseString = objectMapper.writeValueAsString(responseBody);
            context.getLogger().log("Response created: " + responseString);

            return successResponse(responseString, context);
        } catch (Exception e) {
            context.getLogger().log("Error saving table: " + e.getMessage());
            return errorResponse(500, "Error saving table.", context);
        }

    }


    private APIGatewayProxyResponseEvent handleTablesGet(APIGatewayProxyRequestEvent event, Context context) {

        context.getLogger().log("Receiving request -> "+ event.getBody());
        String token = event.getHeaders().get("Authorization");
        context.getLogger().log("Authorization token: " + token);
        if (token == null || !token.startsWith("Bearer ")) {
            context.getLogger().log("Missing or invalid Authorization header: " + token);
            return errorResponse(401, "Missing or invalid Authorization header", context);
        }

        token = token.substring(7); // Remove "Bearer " prefix

        context.getLogger().log("Bearer is removed " + token);
        if (!isTokenValid(token, context)) {
            context.getLogger().log("Unauthorized: Invalid token: "+ token);
            return errorResponse(401, "Unauthorized: Invalid token", context);
        }
        try {
            context.getLogger().log("Starting table scan request...");

            String tableName = System.getenv("table");
            context.getLogger().log("Table name retrieved from environment: " + tableName);

            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(tableName)
                    .build();

            context.getLogger().log("ScanRequest created: " + scanRequest.toString());

            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
            context.getLogger().log("ScanResponse received. Item count: " + scanResponse.count());

            List<Map<String, AttributeValue>> items = scanResponse.items();
            List<Map<String, Object>> tables = new ArrayList<>();

            context.getLogger().log("Processing retrieved items...");

            for (Map<String, AttributeValue> item : items) {
                Map<String, Object> table = new HashMap<>();
                table.put("id", Integer.parseInt(item.get("id").s()));
                table.put("number", Integer.parseInt(item.get("number").n()));
                table.put("places", Integer.parseInt(item.get("places").n()));
                table.put("isVip", Boolean.parseBoolean(item.get("isVip").bool().toString()));

                if (item.containsKey("minOrder")) {
                    table.put("minOrder", Integer.parseInt(item.get("minOrder").n()));
                }

                tables.add(table);
                context.getLogger().log("Processed item: " + table.toString());
            }

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("tables", tables);

            String responseString = objectMapper.writeValueAsString(responseBody);
            context.getLogger().log("Response created: " + responseString);

            return successResponse(responseString, context);
        } catch (Exception e) {
            context.getLogger().log("Error fetching tables: " + e.getMessage());
            return errorResponse(500, "Error fetching tables.", context);
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

    private boolean isTokenValid(String token, Context context) {
        try {
            GetUserRequest request = GetUserRequest.builder()
                    .accessToken(token)
                    .build();
            cognitoClient.getUser(request);
            return true;
        } catch (NotAuthorizedException | InvalidParameterException e) {
            context.getLogger().log("Invalid Token: " + e.getMessage());
            return false;
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
