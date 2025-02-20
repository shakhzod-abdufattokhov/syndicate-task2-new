package com.task02;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import java.util.HashMap;
import java.util.Map;

import java.util.HashMap;
import java.util.Map;

@LambdaHandler(
    lambdaName = "hello_world",
	roleName = "hello_world-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
public class HelloWorld implements RequestHandler<Map<String, Object>, Map<String, Object>> {

	@Override
	public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
		String path = (String) input.get("rawPath");
		String method = (String) input.get("http").get("method");

		if ("/hello".equals(path) && "GET".equalsIgnoreCase(method)) {
			return createResponse(200, "Hello from Lambda");
		} else {
			String errorMessage = String.format("Bad request syntax or unsupported method. Request path: %s. HTTP method: %s", path, method);
			return createResponse(400, errorMessage);
		}
	}

	private Map<String, Object> createResponse(int statusCode, String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("statusCode", statusCode);
		response.put("body", String.format("{\"statusCode\": %d, \"message\": \"%s\"}", statusCode, message));
		return response;
	}
}
