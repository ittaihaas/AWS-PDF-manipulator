package dsps192.assignment1;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.model.Message;

public class Manager {

	private static boolean terminated;
	private static ConcurrentHashMap<String, Integer[]> MTLQueues;

	public static void main(String[] args) {
		try {
			System.out.println("manager starting!");
			MTLQueues = new ConcurrentHashMap<String, Integer[]>();
			terminated = false;

			Thread[] threads = new Thread[2];
			threads[0] = new Thread() {
				public void run() {
					try {
						while (!terminated)
							handleLocalAppMessages();
					} catch (Exception e) {
						System.out.println("exception in thread running handleLocalAppMessages");
						System.exit(-1);
					}
				}
			};
			threads[1] = new Thread() {
				public void run() {
					try {
						while (!terminated)
							handleCompleteWorkerTaskMassages();
					} catch (Exception e) {
						System.out.println("exception in thread running handleCompleteWorkerTaskMassages");
						System.exit(-1);
					}
				}
			};
			threads[0].start();
			threads[1].start();

			threads[0].join();
			threads[1].join();
			System.out.println("manager got terminated, performing cleanup");

			// wait for all final task
			while (!MTLQueues.isEmpty()) {
				handleCompleteWorkerTaskMassages();
			}
			System.out.println("MTL queue  is empty, all jobs are done!");

			EC2.terminateAllWorkers();
			Thread.sleep(60 * 1000);
			// will cause exception in local applications who didn't got served
			SQS.deleteAllOpenQueues();
			EC2.performShutdown();
		} catch (Exception e) {
			System.out.println("error in main manager loop " + e.getMessage());
			e.printStackTrace();
			System.exit(-1);
		}
	}

	protected static void handleLocalAppMessages() throws IOException {
		List<Message> messages = SQS.getManagerTaskMessages();
		for (Message msg : messages) {
			String[] msgSource = msg.getBody().split("\n");
			String inputFileName = msgSource[0];
			int numOfWorkers = Integer.parseInt(msgSource[1]);
			if (!terminated)
				terminated = Boolean.parseBoolean(msgSource[2]);
			taskHandler(inputFileName, numOfWorkers);
			SQS.deleteMessage(msg, SQS.getLocalToManagerUrl());
		}
	}

	protected static void handleCompleteWorkerTaskMassages() throws IOException {
		try {
			List<Message> finalTasks = SQS.getCompleteWorkerTaskMessages();
			String fileName;
			int currentWorkersNeeded;
			for (Message msg : finalTasks) {
				fileName = msg.getBody();
				if (!MTLQueues.containsKey(fileName)) {
					System.out.println("problem!!! " + fileName + "should be in hashmap");
					System.exit(-1);
				}
				// last message of a task
				if (MTLQueues.get(fileName)[0] == 1) {
					currentWorkersNeeded = MTLQueues.get(fileName)[1];
					if (MTLQueues.remove(fileName) == null) {
						System.out.println("error: no key to delete");
						System.exit(-1);
					}
					deleteWorkersIfNeeded(currentWorkersNeeded);
					createSummary(fileName);
					System.out.println("local app task completed, summary file created");
				} else {
					Integer[] temp = { MTLQueues.get(fileName)[0] - 1, MTLQueues.get(fileName)[1] };
					Integer[] oldValue = MTLQueues.put(fileName, temp);
					if (oldValue == null) {
						System.out.println("hash map update went wront!");
						System.exit(-1);
					}
				}
				SQS.deleteMessage(msg, SQS.getCompleteWorkerTasksUrl());
			}
		} catch (Exception e) {
			System.out.println("error handling final task " + e.getMessage());
			e.printStackTrace();
			System.exit(-1);
		}
	}

	protected static void taskHandler(String inputFileName, int numOfWorkers) throws IOException {
		try {
			String bucketName = "dsps192assignment1";
			int taskCounter = 0;
			String line, msgToSend;
			String[] lineContant;
			if (MTLQueues.containsKey(inputFileName)) {
				System.out.println("error, hash map already have this task!!");
				System.exit(-1);
			} else {
				S3Object file = S3.downloadFile(bucketName, inputFileName);
				BufferedReader reader = new BufferedReader(new InputStreamReader(file.getObjectContent()));

				int activeWorkers = EC2.getNumberOfActiveWorkers();
				if (numOfWorkers > activeWorkers) {
					EC2.spawnWorker(numOfWorkers - activeWorkers);
					System.out.println("spawned workers");
					System.out.println("num of workers needed: " + numOfWorkers);
					System.out.println("active workers: " + activeWorkers);
					System.out.println("spawned: " + (numOfWorkers - activeWorkers));
					// give time for workers to spawn
					Thread.sleep(20 * 1000);
				}
				line = reader.readLine();
				while (line != null) {
					lineContant = line.split("\t");
					msgToSend = inputFileName.concat("\n").concat(lineContant[0]).concat("\n").concat(lineContant[1]);
					SQS.sendMessageToTaskForWorkerQueue(msgToSend);
					taskCounter++;
					line = reader.readLine();
				}
				Integer[] temp = { taskCounter, numOfWorkers };
				MTLQueues.put(inputFileName, temp);
			}
			System.out.println("new local app message created " + taskCounter + " jobs sent for wokers");
		} catch (Exception e) {
			System.out.println("failed proccecing local app task, discarding the message");
			System.out.println(e.getMessage());
			e.printStackTrace();
		}
	}

	protected static void deleteWorkersIfNeeded(int currentWorkersNeeded) {
		int maxWorkersNeeded = maxNodeNeeded();
		int activeWorkers = EC2.getNumberOfActiveWorkers();
		if (maxWorkersNeeded < activeWorkers)
			for (int i = 0; i < (activeWorkers - maxWorkersNeeded); i++) {
				SQS.sendMessageToTaskForWorkerQueue("kill");
				System.out.println("kill message sent");
			}
	}

	protected static int maxNodeNeeded() {
		int max = 0;
		for (Integer[] current : MTLQueues.values())
			if (current[1] > max)
				max = current[1];
		return max;
	}

	protected static void createSummary(String fileName) {
		try {
			String bucketName = "dsps192assignment1";
			List<String> files = S3.listing(bucketName, fileName);
			String[] subCurrent;
			String operation, originalUrl, nameInURL, outputUrl, output = null;
			File summaryFile = new File(fileName);
			BufferedWriter buffer = new BufferedWriter(new FileWriter(summaryFile));

			for (String current : files) {
				subCurrent = current.split(":");

				if (subCurrent[0].equals(fileName + "/error")) {
					S3Object errorFile = S3.downloadFile(bucketName, current);
					BufferedReader reader = new BufferedReader(new InputStreamReader(errorFile.getObjectContent()));
					String errormsg = reader.readLine();
					operation = subCurrent[1].split("\\.")[0];
					originalUrl = current.substring((fileName + "/error:." + operation).length())
							.replaceAll(S3.urlDelimiter, "/");
					output = operation + ": " + originalUrl + " " + errormsg + "\n";
				} else {
					operation = current.split("\\.")[0].substring(fileName.length() + 1);
					originalUrl = current.substring((fileName + "/." + operation).length());
					nameInURL = originalUrl.replaceAll("%", "%25").replaceAll(":", "%3A");
					originalUrl = originalUrl.replaceAll(S3.urlDelimiter, "/");
					outputUrl = "https://s3-us-west-2.amazonaws.com/" + bucketName + "/" + fileName + "/" + operation
							+ "." + nameInURL;
					output = operation + ": " + originalUrl + " " + outputUrl + "\n";
				}
				buffer.write(output);
			}
			buffer.close();
			S3.uploadFile(bucketName, "SummaryFile:" + fileName, summaryFile);
			SQS.sendSummeryMessage(fileName, "SummaryFile:" + fileName);
			summaryFile.delete();
			return;
		} catch (Exception e) {
			System.out.println(e.getMessage());
			System.out.println("error while making summary for: " + fileName);
			return;
		}
	}

}
