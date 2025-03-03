package com.task04;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.events.SqsEventSource;
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
@SqsEventSource(
		queueArn = "arn:aws:sqs:eu-central-1:905418349556:cmtr-2028f2b4-async_queue"
)
public class SqsHandler implements RequestHandler<SQSEvent, Map<String, Object>> {

	@Override
	public Map<String, Object> handleRequest(SQSEvent event, Context context) {
		context.getLogger().log("Lambda triggered by SQS message. Processing messages...\n");

		Map<String, Object> resultMap = new HashMap<>();
		if (event.getRecords().isEmpty()) {
			context.getLogger().log("No messages received from SQS.\n");
			resultMap.put("statusCode", 204);
			resultMap.put("message", "No messages received");
			return resultMap;
		}

		for (SQSEvent.SQSMessage message : event.getRecords()) {
			try {
				context.getLogger().log(String.format(
						"Message ID: %s | Body: %s | Attributes: %s\n",
						message.getMessageId(),
						message.getBody(),
						message.getAttributes()
				));
			} catch (Exception e) {
				context.getLogger().log("Error processing message: " + e.getMessage() + "\n");
			}
		}

		resultMap.put("statusCode", 200);
		resultMap.put("message", "SQS Messages processed successfully");
		return resultMap;
	}
}
