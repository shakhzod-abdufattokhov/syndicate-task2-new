package com.task09;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.gson.Gson;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.model.Architecture;
import com.syndicate.deployment.model.ArtifactExtension;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.RetentionSetting;

import java.util.HashMap;
import java.util.Map;

@LambdaLayer(
		layerName = "sdk_layer",
		libraries = {"lib/commons-lang3-3.14.0.jar", "lib/gson-2.10.1.jar", "jackson-databind-2.18.2.jar"},
		runtime = DeploymentRuntime.JAVA11,
		architectures = {Architecture.ARM64},
		artifactExtension = ArtifactExtension.ZIP
)
@LambdaHandler(
		lambdaName = "api_handler",
		roleName = "api_handler-role",
		layers = "sdk_layer",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)

public class ApiHandler implements RequestHandler<Object, Map<String, Object>> {

	private final WeatherApiClient weatherApiClient = new WeatherApiClient();
	private final Gson gson = new Gson();

	@Override
	public Map<String, Object> handleRequest(Object request, Context context) {
		System.out.println("Fetching weather data...");

		String weatherData = weatherApiClient.fetchWeatherData(52.52, 13.41); // Berlin coordinates

		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("statusCode", 200);
		resultMap.put("body", weatherData);

		return resultMap;
	}
}
