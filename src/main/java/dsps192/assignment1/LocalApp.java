package dsps192.assignment1;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.UUID;

import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.model.Message;

public class LocalApp {
	public static void main(String[] args) {
		try {
			String filePath = null;
			String resultFileName = null;
			int maxWorkers = 0;
			boolean terminate = false;

			switch (args.length) {
			case 3:
				filePath = args[0];
				resultFileName = args[1];
				maxWorkers = Integer.parseInt(args[2]);
				break;
			case 4:
				filePath = args[0];
				resultFileName = args[1];
				maxWorkers = Integer.parseInt(args[2]);
				if (args[3].equals("terminate"))
					terminate = true;
				break;
			default:
				System.out.println("invalid args");
				System.exit(-1);
				break;
			}
			String fileName = extractFileName(filePath);
			fileName = (fileName + UUID.randomUUID()).replace("-", "");

			// initialize manager node
			EC2.launchManager();

			// upload task file to s3
			File file = new File(filePath);
			S3.uploadFile("dsps192assignment1", fileName, file);

			// create SQS queue for answer
			String resultQueueUrl = SQS.createQueue(fileName);
			SQS.setQueueForLongPolling(resultQueueUrl);

			// send message to SQS that mission had been uploaded, state the location of the
			// file in S3
			String taskMsg = createTaskMsg(fileName, maxWorkers, terminate);
			SQS.sendMessageToManagerTasksQueue(taskMsg);

			// wait for finished task message
			Message resultMessage = SQS.getSummaryMessage(resultQueueUrl);
			String summaryFileName = resultMessage.getBody();
			SQS.deleteMessage(resultMessage, resultQueueUrl);
			SQS.deleteQueue(resultQueueUrl);

			// download summary file
			S3Object summary = S3.downloadFile("dsps192assignment1", summaryFileName);
			createSummaryHTML(fileName, summary, resultFileName);
			System.out.println("Local app complete!");

		} catch (Exception e) {
			System.out.println("exception in local app " + e.getMessage());
			e.printStackTrace();
		}
	}

	protected static String createTaskMsg(String fileName, int maxWorkers, boolean terminate) {
		return fileName + "\n" + maxWorkers + "\n" + terminate;
	}

	protected static String extractFileName(String filePath) {
		System.out.println("file path: " + filePath);
		String fileName;
		int startIndex = filePath.lastIndexOf("\\");
		if (startIndex == -1)
			fileName = filePath;
		else
			fileName = filePath.substring(startIndex + 1);
		int endIndex = fileName.lastIndexOf('.');
		if (endIndex == -1)
			return fileName;
		return fileName.substring(0, endIndex);
	}

	protected static String extractFilePath(String filePath) {
		int endIndex = filePath.lastIndexOf("\\");
		if (endIndex == -1)
			return filePath;
		return filePath.substring(0, endIndex);
	}

	protected static void createSummaryHTML(String fileName, S3Object summaryFile, String resultFileName)
			throws IOException {
		String htmlTab = "&nbsp;&nbsp;&nbsp;&nbsp;";
		String outputBegin = "<!DOCTYPE html>\n" + "<html>\n" + "<head>\n" + "<title> Summary File - " + fileName
				+ " </title>\n" + "</head>\n" + "<body>\n\n" + "<h1> Summary File - " + fileName + "</h1>\n\n"
				+ "<font size = \"4\">";
		BufferedReader reader = new BufferedReader(new InputStreamReader(summaryFile.getObjectContent()));
		String line, outputMiddle = "";
		line = reader.readLine();
		String[] lineSplit;
		while (line != null) {
			lineSplit = line.split(" ");
			for (int i = 0; i < lineSplit.length; i++) {
				if (i == 0)
					outputMiddle = outputMiddle + "<p> " + lineSplit[i] + "<br>" + htmlTab;
				else if (i == 1)
					outputMiddle = outputMiddle + lineSplit[i] + "<br>" + htmlTab;
				else
					outputMiddle = outputMiddle + lineSplit[i] + " ";
			}
			outputMiddle = outputMiddle + "</p>\n";
			line = reader.readLine();
		}
		String outputEnd = "</font>\n" + "</body>\n" + "</html>";
		String output = outputBegin + outputMiddle + outputEnd;
		File summaryHTML = new File(resultFileName + ".html");
		BufferedWriter buffer = new BufferedWriter(new FileWriter(summaryHTML));
		buffer.write(output);
		buffer.close();
	}
}
