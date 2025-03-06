package com.task09;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.Architecture;
import com.syndicate.deployment.model.ArtifactExtension;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

import java.util.HashMap;
import java.util.Map;

@LambdaHandler(
		lambdaName = "api_handler",
		roleName = "api_handler-role",
		layers = {"sdk-layer"},
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaLayer(
		layerName = "sdk-layer",
		libraries = {
				"lib/jackson-databind-2.18.2.jar",
				"lib/httpclient5-5.4.1.jar",
				"lib/jackson-annotations-2.18.2.jar",
				"lib/jackson-core-2.18.2.jar",
		},
		architectures = {Architecture.ARM64},
		artifactExtension = ArtifactExtension.ZIP
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private final WeatherClient weatherClient = new WeatherClient();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		Map<String, Object> requestContext = (Map<String, Object>) event.get("requestContext");
		Map<String, String> httpInfo = (Map<String, String>) requestContext.get("http");

		String path = httpInfo.get("path");
		String method = httpInfo.get("method");

		if (!"/weather".equals(path) || !"GET".equalsIgnoreCase(method)) {
			String errorMessage = String.format("Bad request syntax or unsupported method. Request path: %s. HTTP method: %s", path, method);
			return createResponse(400, errorMessage);
		}

		JsonNode weatherData = weatherClient.fetchWeatherData();
		return Map.of("statusCode", 200, "body", weatherData.toString());
	}

	private Map<String, Object> createResponse(int statusCode, String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("body", String.format("{\"statusCode\": %d, \"message\": \"%s\"}", statusCode, message));
		return response;
	}
}
