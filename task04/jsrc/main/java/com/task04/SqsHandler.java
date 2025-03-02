package com.task04;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

import java.util.HashMap;
import java.util.Map;

@LambdaHandler(
		lambdaName = "sqs_handler",
		roleName = "sqs_handler-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
public class SqsHandler implements RequestHandler<SQSEvent, Map<String, Object>> {

	public Map<String, Object> handleRequest(SQSEvent event, Context context) {
		context.getLogger().log("Lambda triggered by SQS message\n");

		for (SQSEvent.SQSMessage message : event.getRecords()) {
			context.getLogger().log("Received SQS Message: " + message.getBody() + "\n");
		}

		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("statusCode", 200);
		resultMap.put("message", "SQS Messages processed successfully");
		return resultMap;
	}
}
