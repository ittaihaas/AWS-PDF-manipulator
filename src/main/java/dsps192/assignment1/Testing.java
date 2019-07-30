package dsps192.assignment1;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

public class Testing {

	public static String[] localAppArg = new String[4];
	public static boolean outofclassvar = false;

	public static void main(String[] args) throws InterruptedException, IOException {

		System.out.println("teting!");
		fullTest();
		// SQS.initQueues();
	}

	private static void fullTest() throws InterruptedException {
		try {
			int numOfIterations = 2;
			boolean first = true;
			Random random = new Random();
			File inputDirectory = new File(System.getProperty("user.dir") + "\\inputFiles\\");
			for (File file : inputDirectory.listFiles()) {
				if ((--numOfIterations) < 0)
					break;
				localAppArg[0] = file.getAbsolutePath();
				localAppArg[1] = file.getAbsolutePath() + "result";
				localAppArg[2] = Integer.toString(random.nextInt(10) + 1);
				if (numOfIterations == 0)
					localAppArg[3] = "terminate";
				else
					localAppArg[3] = "false";

				new Thread() {
					public void run() {
						LocalApp.main(localAppArg);
					}
				}.start();

				System.out.println("local started with: \n" + Arrays.deepToString(localAppArg));
				if (first) {
					first = false;
					while (!EC2.checkIfManagerRunning()) {
						Thread.sleep(5 * 1000);
					}
				}
				Thread.sleep(5000);
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}
}