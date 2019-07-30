# AWS-PDF-manipulator

General description:
A PDF transformer app that runs on AWS.
The app is controlled by a single EC2 node tagged “Manager” which spawns other nodes to handle tasks, spawned nodes and tagged “Workers”.
Starting the app is done by running a local app (runs on the user computer).

How to compile:
change the main class in the .pom file to the desired class.
run the following command to create a standalone runnable jar:
mvn clean compile assembly:single

SYNOPSIS:
java -jar localApp.jar A B C D
A: absolute path to input file
B: name of output file
C: number of tasks per worker
D: optional argument - “terminate” - shut down the manager when task is completed
example: java -jar my_dir/myfile.txt myconverted 5 terminate

* java 1.8 or above must be installed
* .pem key for easy ssh connection to aws instances is not provided with this project
* .aws folder must be available with the proper configuration (credentials and region)
* aws configuration such as security profile and IAM role must be configured as well.
________________

Security:
The application is launched with administrator privileges via the IAM role profile provided on the local machine that starts the app. By doing so no credentials or passwords are sent over the web protecting the user against login details theft.

Persistence:
As mentioned before, the app is controlled by a single manager node, if that node is terminated unexpectedly the app will fail. To prevent failures and handle errors, we’ve used try-catch in our manager, workers and local-app codes.
All other aspects of the app such as worker nodes and communication with local applications are designed in a way the a failure of one or more of them will not affect the performance of the app. This is achieved by marking a task as complete only when the complete message is sent back to the manager node. Meaning a failing worker will not mark the tasks he had taken from the task queue as completed, which will make the task reappear in the tasks queue for another worker to take.

Scalability:
Manager node might take some load due to scalability issues yet that node can run on a strong machine and in general has relatively simple tasks to handle.
Worker node takes most of the load and are easily scalable by spawning more workers.

Threads:
Threads are used only in the manager node since it have some independent tasks to handle. Worker nodes are working on a single task at a time to make sure time-out for message is not reached, it is better to complete some tasks in time than almost complete all tasks and reach the time-out limit.

Division of labor:
In our implementation all the tasks for workers are located in one concentrated queue where all workers draw tasks from, making the division of labor among the workers is not uniform. If a message is available in the queue and a worker is free to take tasks, the worker will take them regardless of the number of tasks it already handled. That can cause a scenario where one worker is handling many “light tasks”, and other worker is handling a smaller number of “heavy tasks”, yet all workers are working to the max as long as tasks are available in the queue.
