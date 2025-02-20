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
	public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
		Map<String, Object> response = new HashMap<>();

		try {
			Map<String, Object> requestContext = (Map<String, Object>) event.get("requestContext");
			Map<String, Object> httpInfo = (Map<String, Object>) requestContext.get("http");

			String method = (String) httpInfo.get("method");
			String path = (String) httpInfo.get("path");

			if ("/hello".equals(path) && "GET".equalsIgnoreCase(method)) {
				response.put("statusCode", 200);
				Map<String, String> responseBody = new HashMap<>();
				responseBody.put("message", "Hello from Lambda");
				response.put("body", new ObjectMapper().writeValueAsString(responseBody));
			} else {
				response.put("statusCode", 400);
				Map<String, String> responseBody = new HashMap<>();
				responseBody.put("message", "Bad request syntax or unsupported method. Request path: " + path + ". HTTP method: " + method);
				response.put("body", new ObjectMapper().writeValueAsString(responseBody));
			}
		} catch (Exception e) {
			response.put("statusCode", 500);
			Map<String, String> responseBody = new HashMap<>();
			responseBody.put("message", "Internal server error: " + e.getMessage());
			try {
				response.put("body", new ObjectMapper().writeValueAsString(responseBody));
			} catch (Exception jsonException) {
				response.put("body", "{\"message\": \"Internal server error\"}");
			}
		}

		return response;
	}
}
