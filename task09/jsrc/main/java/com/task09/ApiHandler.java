package com.task09;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.model.RetentionSetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@LambdaLayer(
		layerName = "sdk_layer"
)

@LambdaHandler(
		lambdaName = "api_handler",
		roleName = "api_handler-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private static final String SUPPORTED_PATH = "/weather";
	private static final String SUPPORTED_METHOD = "GET";

	private final OpenMeteoClient openMeteoClient = new OpenMeteoClient();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
		// Logging request for debugging
		System.out.println("Received request: " + request);

		try {
			String path = (String) request.get("path");
			String method = (String) request.get("httpMethod");

			if (!SUPPORTED_PATH.equals(path) || !SUPPORTED_METHOD.equalsIgnoreCase(method)) {
				return generateErrorResponse(400, String.format(
						"Bad request syntax or unsupported method. Request path: %s. HTTP method: %s", path, method));
			}

			// Extract query parameters for latitude & longitude
			Map<String, String> queryParams = (Map<String, String>) request.get("queryStringParameters");
			double latitude = queryParams != null && queryParams.containsKey("latitude") ?
					Double.parseDouble(queryParams.get("latitude")) : 50.4375;
			double longitude = queryParams != null && queryParams.containsKey("longitude") ?
					Double.parseDouble(queryParams.get("longitude")) : 30.5;

			// Fetch weather data
			String weatherData = openMeteoClient.getWeather(latitude, longitude);

			return generateSuccessResponse(weatherData);
		} catch (IOException | InterruptedException e) {
			return generateErrorResponse(500, "Internal Server Error: " + e.getMessage());
		} catch (Exception e) {
			return generateErrorResponse(400, "Invalid request parameters: " + e.getMessage());
		}
	}

	private Map<String, Object> generateSuccessResponse(String body) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", 200);
		response.put("body", body);
		return response;
	}

	private Map<String, Object> generateErrorResponse(int statusCode, String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);

		Map<String, String> errorBody = new HashMap<>();
		errorBody.put("message", message);

		try {
			response.put("body", objectMapper.writeValueAsString(errorBody));
		} catch (IOException e) {
			response.put("body", "{\"message\": \"Error processing error response\"}");
		}

		return response;
	}
}
