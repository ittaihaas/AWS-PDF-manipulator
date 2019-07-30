package dsps192.assignment1;

import java.util.List;
import java.util.UUID;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.DeleteQueueRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueAttributeName;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SetQueueAttributesRequest;

public class SQS {
	
	private final static AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();
	private final static int maxMessagesPerWorker = 5;
	private final static int maxManagerMessages = 5;
	private final static String visabilityTimeout = Integer.toString(maxManagerMessages * 60);
	
	public static void initQueues() {
		try {
			CreateQueueRequest createQueueRequest;
			
	        createQueueRequest = new CreateQueueRequest("localToManager"+ UUID.randomUUID())
	        		.addAttributesEntry(QueueAttributeName.VisibilityTimeout.toString(), visabilityTimeout);;
	        sqs.createQueue(createQueueRequest).getQueueUrl();
	        
	        createQueueRequest = new CreateQueueRequest("tasksForWorkers"+ UUID.randomUUID())
	        		.addAttributesEntry(QueueAttributeName.VisibilityTimeout.toString(), visabilityTimeout);
	        sqs.createQueue(createQueueRequest).getQueueUrl();
	        
	        createQueueRequest = new CreateQueueRequest("completeWorkerTasks"+ UUID.randomUUID())
	        		.addAttributesEntry(QueueAttributeName.VisibilityTimeout.toString(), visabilityTimeout);;
	        sqs.createQueue(createQueueRequest).getQueueUrl();
	        
	        //wait for queues to start
	        while (getLocalToManagerUrl() == null || 
	        		getTasksForWorkersUrl() == null || 
	        		getCompleteWorkerTasksUrl() == null) {
				Thread.sleep(5 * 1000);
			}
	        setQueueForLongPolling(getLocalToManagerUrl());
	        setQueueForLongPolling(getTasksForWorkersUrl());
	        setQueueForLongPolling(getCompleteWorkerTasksUrl());
		} catch (Exception e) {
			System.out.println("error init queues " + e.getMessage());
			System.exit(-1);
		}
	}
	
	//change to private
	private static List<Message> getMessagesFromQueue(String queueUrl, int maxMessages) {
        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(queueUrl).withMaxNumberOfMessages(maxMessages);
        return sqs.receiveMessage(receiveMessageRequest).getMessages();
	}
	
	//message getters for static queues
	protected static List<Message> getManagerTaskMessages() {
		return getMessagesFromQueue(getLocalToManagerUrl(), maxManagerMessages);
	}
	
	protected static List<Message> getCompleteWorkerTaskMessages() {
		return getMessagesFromQueue(getCompleteWorkerTasksUrl(), maxManagerMessages);
	}
	
	protected static List<Message> getTasksForWorkerMessages() {
		return getMessagesFromQueue(getTasksForWorkersUrl(), maxMessagesPerWorker);
	}
	
	
	//message senders for static queues
	protected static void sendMessageToManagerTasksQueue(String msg) {
        sqs.sendMessage(new SendMessageRequest(getLocalToManagerUrl(), msg));
	}
	
	protected static void sendMessageToCompleteWorkerTaskQueue(String msg) {
		sqs.sendMessage(new SendMessageRequest(getCompleteWorkerTasksUrl(), msg));
	}
	
	protected static void sendMessageToTaskForWorkerQueue(String msg) {
		sqs.sendMessage(new SendMessageRequest(getTasksForWorkersUrl(), msg));
	}
	
	protected static void sendSummeryMessage(String queueName, String msg) {
		sqs.sendMessage(new SendMessageRequest(queueName, msg));
	}
	
	//get summary message (used my local application)
	protected static Message getSummaryMessage(String resultQueueUrl) {
		try {
			List<Message> res = getMessagesFromQueue(resultQueueUrl, 1);
			while (res.isEmpty()) {
				Thread.sleep(20 * 1000);
				res = getMessagesFromQueue(resultQueueUrl, 1);
			}
			//if message exist, return it
			return res.get(0);
		}
		catch (Exception e) {
			System.out.println("exception waiting for summary");
			System.out.println(e.getMessage());
			if (e.getMessage().contains("failed: connect timed out")) {
				System.out.println("timeout, trying again");
				return getSummaryMessage(resultQueueUrl);
			}
			System.exit(-1);
		}
		return null;
	}
	
	//delete a single message from specific queue
	protected static void deleteMessage(Message message, String queueUrl) {
        String messageRecieptHandle = message.getReceiptHandle();
        sqs.deleteMessage(new DeleteMessageRequest(queueUrl, messageRecieptHandle));
	}
	
	//create and delete queues by name
	protected static String createQueue(String queueName) {
        CreateQueueRequest createQueueRequest = new CreateQueueRequest(queueName);
        return sqs.createQueue(createQueueRequest).getQueueUrl();
	}
	
	//delete a queue
	protected static void deleteQueue(String queueUrl) {
		sqs.deleteQueue(new DeleteQueueRequest(queueUrl));
	}
	

	//find queues by name of queue
	private static String getQueueWithName(String name) {
        for (String queueUrl : sqs.listQueues().getQueueUrls()) {
            if (queueUrl.contains(name)) {
				return queueUrl;
			}
        }
		return null;
	}
	
	protected static String getLocalToManagerUrl() {
		return getQueueWithName("localToManager");
	}

	protected static String getTasksForWorkersUrl() {
		return getQueueWithName("tasksForWorkers");
	}

	protected static String getCompleteWorkerTasksUrl() {
		return getQueueWithName("completeWorkerTasks");
	}


	protected static void setQueueForLongPolling(String queueUrl) {
		final SetQueueAttributesRequest setQueueAttributesRequest = new SetQueueAttributesRequest()
		        .withQueueUrl(queueUrl)
		        .addAttributesEntry("ReceiveMessageWaitTimeSeconds", "20");
		sqs.setQueueAttributes(setQueueAttributesRequest);
	}

	protected static void deleteAllOpenQueues() {
		for (String queueUrl : sqs.listQueues().getQueueUrls()) {
			try {
				deleteQueue(queueUrl);
			} catch (Exception e) {
				System.out.println(queueUrl);
			}
		}
	}
}
