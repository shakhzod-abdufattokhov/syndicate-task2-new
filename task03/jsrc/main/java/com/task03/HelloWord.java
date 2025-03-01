package com.task03;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

import java.util.HashMap;
import java.util.Map;

@LambdaHandler(
		lambdaName = "hello_world",
		roleName = "hello_world-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)

public class HelloWord implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
		context.getLogger().log("Received request: " + request.getPath());

		if (!"/hello".equals(request.getPath())) {
			return new APIGatewayProxyResponseEvent()
					.withStatusCode(404)
					.withBody("{\"error\": \"Not Found\"}");
		}

		Map<String, String> responseBody = new HashMap<>();
		responseBody.put("message", "Hello from Lambda");

		return new APIGatewayProxyResponseEvent()
				.withStatusCode(200)
				.withHeaders(Map.of("Content-Type", "application/json"))
				.withBody("{\"statusCode\": 200, \"message\": \"Hello from Lambda\"}");
	}
}
