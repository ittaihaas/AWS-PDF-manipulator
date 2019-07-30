package dsps192.assignment1;

import java.util.List;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedList;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.ResourceType;
import com.amazonaws.services.ec2.model.TagSpecification;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.Tag;

public class EC2 {
	
	protected static AmazonEC2 ec2 = AmazonEC2ClientBuilder.defaultClient();
	private static String generalUserData = "#!/bin/bash\r\n" + 
			"echo @@@PERFORMING BOOTSTRAP\r\n" + 
			"yum update -y\r\n" + 
			"echo @@@installing maven\r\n" + 
			"wget http://repos.fedorapeople.org/repos/dchen/apache-maven/epel-apache-maven.repo -O /etc/yum.repos.d/epel-apache-maven.repo\r\n" + 
			"sed -i s/\\$releasever/6/g /etc/yum.repos.d/epel-apache-maven.repo\r\n" + 
			"yum install -y apache-maven\r\n" + 
			"mvn -version\r\n" + 
			"echo @@@installing jdk 1.8\r\n" + 
			"yum install java-1.8.0-openjdk-devel.x86_64 -y\r\n" + 
			"wget https://s3-us-west-2.amazonaws.com/dsps192assignment1/PLACEHOLDER.jar\r\n" + 
			"echo @@@RUNNING MANAGER!!!\r\n" + 
			"java -jar PLACEHOLDER.jar\r\n";
	
	private static String managerUserData = generalUserData.replaceAll("PLACEHOLDER", "manager");
	private static String workerUserData = generalUserData.replaceAll("PLACEHOLDER", "worker");
	
//	//for debug
//	private static String managerUserData = "";
//	private static String workerUserData = "";
	
	
	//launch a manager node if one isn't running already
	public static void launchManager() {
		try {
			if (checkIfManagerRunning()) {
				System.out.println("manager is already running");
				return;
			}
			spawnManager();
			SQS.initQueues();
			System.out.println("waiting for manager, takes a couple of minutes...");
			//wait for manager to start
			while (!checkIfManagerRunning()) {
				Thread.sleep(5 * 1000);
			}
			System.out.println("manager and queues are on!");
		} catch (Exception e) {
			System.out.println("exception launching manager" + e.getMessage());
			System.exit(-1);
		}
	}
	
	protected static void spawnInstance(Tag tag, String userData, int minInstances, int maxInstances) {
        RunInstancesRequest request = new RunInstancesRequest("ami-082b5a644766e0e6f", 1, 1)
        		.withUserData(Base64.getEncoder().encodeToString(userData.getBytes()))
        		.withIamInstanceProfile(new IamInstanceProfileSpecification().withName("EC2-admin"))
        		.withKeyName("assignment1Key")
        		.withSecurityGroupIds("sg-fe1304b6")
        		.withTagSpecifications(new TagSpecification().withResourceType(ResourceType.Instance).withTags(tag))
        		.withMinCount(minInstances)
        		.withMaxCount(maxInstances);
        request.setInstanceType(InstanceType.T2Micro.toString());
        ec2.runInstances(request);
	}
	
	
	//launch an ec2 node with worker code
	public static void spawnWorker(int numberOfWorkers) {
		Tag tag = new Tag().withKey("name").withValue("WORKER");
		spawnInstance(tag, workerUserData, numberOfWorkers, numberOfWorkers);
	}
	
	//launch an ec2 node with manager code
	private static void spawnManager() {
		Tag tag = new Tag().withKey("name").withValue("MANAGER");
		spawnInstance(tag, managerUserData, 1, 1);
	}
	
	
	protected static boolean checkIfManagerRunning() {
		for (Instance instance : getRunningInstances()) 
			for (com.amazonaws.services.ec2.model.Tag tag : instance.getTags()) 
				if (tag.getValue().equals("MANAGER") && instance.getState().getName().equalsIgnoreCase("running"))
					return true;
		return false;
	}
	
	protected static int getNumberOfActiveWorkers() {
		int active = 0;
		for (Instance instance : getRunningInstances()) 
			for (com.amazonaws.services.ec2.model.Tag tag : instance.getTags()) 
				if (tag.getValue().equals("WORKER") 
						&& instance.getPublicIpAddress() != null 
						&& ((instance.getState().getName().toLowerCase().equalsIgnoreCase("running")) 
								|| (instance.getState().getName().equalsIgnoreCase("pending")))) {
					active++;
				}
		return active;
	}
	

	//list all running EC2 instances of the account
	protected static List<Instance> getRunningInstances() {
		List<Instance> runningInstances = new LinkedList<Instance>();
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        while(true) {
            DescribeInstancesResult response = ec2.describeInstances(request);
            for(Reservation reservation : response.getReservations())
            	for (Instance instance : reservation.getInstances())
					if (instance.getPublicIpAddress() != null)
						runningInstances.add(instance);
            request.setNextToken(response.getNextToken());
            if(response.getNextToken() == null) 
                break;
        }
        return runningInstances;
	}
	
	protected static void terminateAllWorkers() {
		try {
			List<Instance> workersToTerminate = getRunningInstances();
			List<String> workersToTerminateIds = new LinkedList<String>();
			for (Instance instance : workersToTerminate) {
				boolean isWorker = false;
				for (com.amazonaws.services.ec2.model.Tag tag : instance.getTags())
					if (tag.getValue().equals("WORKER"))
						isWorker = true;
				if(!isWorker)
					workersToTerminate.remove(instance);	
			}
			for (Instance instance : workersToTerminate)
				workersToTerminateIds.add(instance.getInstanceId());
			
	        if (!workersToTerminateIds.isEmpty()) {
	            TerminateInstancesRequest terminate = new TerminateInstancesRequest().withInstanceIds(workersToTerminateIds);
	    		ec2.terminateInstances(terminate);
			}
		} catch (Exception e) {
			System.out.println("failed to terminate workers...\n" + e.getMessage());
		}
	}

	//perform shutdown (Linux compatible)
	protected static void performShutdown() throws IOException {
	    Runtime runtime = Runtime.getRuntime();
		runtime.exec("sudo poweroff");
		System.exit(0);
	}
}
