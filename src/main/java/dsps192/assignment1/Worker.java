package dsps192.assignment1;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.tools.PDFText2HTML;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;

import com.amazonaws.services.sqs.model.Message;

public class Worker {

	protected static boolean killed = false;
	protected static String errorMsg = "";

	public static void main(String[] args) throws IOException {
		try {
			System.out.println("worker started!");
			while (!killed) {
				List<Message> messages = SQS.getTasksForWorkerMessages();

				for (Message message : messages) {
					if (isKillMessage(message))
						continue;
					String[] msgData = message.getBody().split("\n");
					String originalFileName = msgData[0];
					String operationName = msgData[1];
					String pdfUrl = msgData[2];

					File originalFile = downloadFile(pdfUrl);
					if (originalFile != null) {
						File modifiedFile = performOperation(operationName, originalFile);
						if (modifiedFile != null) {
							S3.uploadFile("dsps192assignment1", originalFileName + "/" + operationName + "."
									+ pdfUrl.replaceAll("/", S3.urlDelimiter), modifiedFile);
							originalFile.delete();
							modifiedFile.delete();
						} else {
							// error performing operation
							uploadErrorFile(originalFileName, operationName, pdfUrl);
							originalFile.delete();
						}
					} else {
						// error downloading file
						uploadErrorFile(originalFileName, operationName, pdfUrl);
					}

					SQS.sendMessageToCompleteWorkerTaskQueue(originalFileName);
					SQS.deleteMessage(message, SQS.getTasksForWorkersUrl());
				}
			}
			System.out.println("worker killed- end of while");
			EC2.performShutdown();
		} catch (Exception e) {
			System.out.println("exception in worker main loop, performing shutdown");
			EC2.performShutdown();
		}
	}

	private static boolean isKillMessage(Message message) {
		String[] splitMsg = new String[3];
		splitMsg = message.getBody().split("\n");
		if (splitMsg[0].equals("kill")) {
			SQS.deleteMessage(message, SQS.getTasksForWorkersUrl());
			if (killed)
				SQS.sendMessageToTaskForWorkerQueue("kill");
			else
				killed = true;
			return true;
		}
		return false;
	}

	private static void uploadErrorFile(String originalFileName, String operationName, String pdfUrl)
			throws FileNotFoundException, UnsupportedEncodingException {
		PrintWriter writer = new PrintWriter("errorFile.txt", "UTF-8");
		writer.println(errorMsg);
		writer.close();
		File errorFile = new File("errorFile.txt");
		S3.uploadFile("dsps192assignment1",
				originalFileName + "/" + "error:" + operationName + "." + pdfUrl.replaceAll("/", S3.urlDelimiter),
				errorFile);
		errorFile.delete();
	}

	protected static File performOperation(String operation, File originalFile) {
		try {
			PDDocument document = PDDocument.load(originalFile);
			// convert page to HTML
			if (operation.equals("ToHTML")) {
				PDFText2HTML stripper = new PDFText2HTML();
				stripper.setStartPage(1);
				stripper.setEndPage(2);
				String text = stripper.getText(document);

				File htmlFile = new File("PDFhtml.html");
				FileUtils.writeStringToFile(htmlFile, text, "UTF-8");
				document.close();
				return htmlFile;
			}
			// convert page to image
			else if (operation.equals("ToImage")) {
				PDFRenderer pdfRenderer = new PDFRenderer(document);
				BufferedImage bim = pdfRenderer.renderImageWithDPI(0, 300, ImageType.RGB);

				ImageIOUtil.writeImage(bim, originalFile + ".png", 300);
				document.close();
				return new File(originalFile + ".png");
			}
			// convert page to text
			else if (operation.equals("ToText")) {
				PDFTextStripper pdfStripper = new PDFTextStripper();
				pdfStripper.setStartPage(1);
				pdfStripper.setEndPage(2);
				String text = pdfStripper.getText(document);

				File textFile = new File("PDFtext.txt");
				FileUtils.writeStringToFile(textFile, text, "UTF-8");
				document.close();
				return textFile;
			} else {
				System.out.print("ooops, no such operation");
				document.close();
				return null;
			}
		} catch (Exception e) {
			errorMsg = operation + " " + "failed";
			System.out.println("error performing operation: " + errorMsg);
			return null;
		}
	}

	protected static File downloadFile(String url) throws IOException {
		try {
			URL fileLink = new URL(url);
			File downloadedFile = new File("downloadedPDFFile.temp");
			org.apache.commons.io.FileUtils.copyURLToFile(fileLink, downloadedFile);
			return downloadedFile;
		} catch (Exception e) {
			errorMsg = "failed downloading file";
			System.out.println("error downloading file: " + e.getMessage());
			return null;
		}
	}
}
