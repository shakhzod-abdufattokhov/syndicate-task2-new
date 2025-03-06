package com.task10;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.TracingMode;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


@LambdaHandler(
		lambdaName = "processor",
		roleName = "processor-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
		tracingMode = TracingMode.Active
)

@EnvironmentVariables({
		@EnvironmentVariable(key = "target_table", value = "${target_table}"),
		@EnvironmentVariable(key = "region", value = "${region}")
})

@LambdaUrlConfig(
		invokeMode = InvokeMode.BUFFERED,
		authType = AuthType.NONE
)
public class Processor implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	private static final String WEATHER_API_URL = "https://api.open-meteo.com/v1/forecast?latitude=40.7128&longitude=-74.0060&hourly=temperature_2m";
	private static final String TABLE_NAME = System.getenv("target_table");
	private final DynamoDbClient dynamoDbClient;
	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;

	public Processor() {
		this.dynamoDbClient = DynamoDbClient.builder()
				.region(Region.of(System.getenv("region")))
				.credentialsProvider(DefaultCredentialsProvider.create())
				.build();
		this.httpClient = HttpClient.newHttpClient();
		this.objectMapper = new ObjectMapper();
	}

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> stringObjectMap, Context context) {
		AWSXRay.beginSegment("ProcessorLambda");

		try {
			String weatherData = fetchWeatherData();
			JsonNode weatherJson = objectMapper.readTree(weatherData);

			Map<String, AttributeValue> item = new HashMap<>();
			item.put("id", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
			item.put("forecast", AttributeValue.builder().m(convertMapToAttributeValue(weatherJson)).build());

			PutItemRequest request = PutItemRequest.builder()
					.tableName(TABLE_NAME)
					.item(item)
					.build();

			PutItemResponse response = dynamoDbClient.putItem(request);

			AWSXRay.endSegment();

			return Map.of("status", "success", "dynamodb_response", response.toString());
		} catch (Exception e) {
			AWSXRay.endSegment();
			return Map.of("status", "error", "message", e.getMessage());
		}
	}

	private String fetchWeatherData() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(WEATHER_API_URL))
				.GET()
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());


		if (response.statusCode() != 200) {
			throw new RuntimeException("Failed to fetch weather data: HTTP " + response.statusCode());
		}

		return response.body();
	}

	public static Map<String, AttributeValue> convertMapToAttributeValue(JsonNode jsonNode) {
		Map<String, AttributeValue> attributeMap = new HashMap<>();

		if (jsonNode == null || !jsonNode.isObject()) {
			return attributeMap;
		}

		Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			attributeMap.put(entry.getKey(), convertJsonNodeToAttributeValue(entry.getValue()));
		}

		return attributeMap;
	}

	private static AttributeValue convertJsonNodeToAttributeValue(JsonNode node) {
		if (node.isTextual()) {
			return AttributeValue.builder().s(node.asText()).build();
		} else if (node.isNumber()) {
			return AttributeValue.builder().n(node.asText()).build();
		} else if (node.isBoolean()) {
			return AttributeValue.builder().bool(node.asBoolean()).build();
		} else if (node.isArray()) {
			List<AttributeValue> list = StreamSupport.stream(node.spliterator(), false)
					.map(Processor::convertJsonNodeToAttributeValue)
					.collect(Collectors.toList());
			return AttributeValue.builder().l(list).build();
		} else if (node.isObject()) {
			return AttributeValue.builder().m(convertMapToAttributeValue(node)).build();
		} else if (node.isNull()) {
			return AttributeValue.builder().nul(true).build();
		}
		return AttributeValue.builder().build();
	}
}
