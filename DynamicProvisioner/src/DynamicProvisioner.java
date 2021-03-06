/*
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and limitations under the License.
 */

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.BlockDeviceMapping;
import com.amazonaws.services.ec2.model.CancelSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsResult;
import com.amazonaws.services.ec2.model.EbsBlockDevice;
import com.amazonaws.services.ec2.model.LaunchSpecification;
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest;
import com.amazonaws.services.ec2.model.RequestSpotInstancesResult;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.ec2.model.SpotPlacement;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;

public class DynamicProvisioner {

	static final String workerAMI = "ami-7ee07916";
	static final String instanceType = "c3.large";
	static final String queue = "ForTest";
	static final String keyName = "cloudstack-key";
	static final String credentialProfileName = "default";
	static final String[] securityGroups = new String[]{"default"};
	static final Tag workerTag = new Tag("CS553P4","remoteWorker");
	static final long newSpotInstanceRequestTolerateTime=1000*60*15;		//15 minutes
	static final long newSpotInstanceStartTolerateTime=1000*60*5;		//2 minutes
	static final long checkPeriod=1000*5;		//5 seconds
	
	static int minWorkerNum=0;
	static int maxWorkerNum=50;
	
	static final String userData= "#!/bin/sh\n"
								+ "mkdir -p /root/.aws\n"
								+ "cp /home/ubuntu/.aws/credentials /root/.aws/\n"
//								+ "touch /home/ubuntu/lalaOleiO_+=.lalaOleiO\n"
//								+ "touch /home/ubuntu/lalaOleiO2_+=.lalaOleiO\n"
//								+ "cd /home/ubuntu\n"
//								+ "java Test &\n"
//								+ "java Test \n";
								+ "cd /home/ubuntu/amazon-cloudstack/cloudworker/src/cloudworker/\n"
								+ "pwd > a.ttt\n"
								+ "./run &>./a.aa &\n";
	static final String charset="UTF-8";
	
	static long launchAndWaitUntilRunning(EC2 ec2,int num,double price) throws InterruptedException, UnsupportedEncodingException {
		long startTime=System.currentTimeMillis();
		List<String> sgs=new ArrayList<String>();
		for(String securityGroup : securityGroups)
			sgs.add(securityGroup);
		List<String> spotInstanceRequestIds=null;
		List<String> instanceIDs=null;
		boolean ifContinue=true;
		while(ifContinue) {
			spotInstanceRequestIds=ec2.launch(workerAMI, instanceType, num, price, sgs, keyName, userData, charset);
			long rTime=System.currentTimeMillis();
			while(instanceIDs==null) {
				if(System.currentTimeMillis()-rTime>newSpotInstanceRequestTolerateTime)
					break;
				instanceIDs=ec2.getActiveSpotInstanceId(spotInstanceRequestIds);
				Thread.sleep(1000*10);
			}			
			if(instanceIDs==null || instanceIDs.size()==0) {
				System.out.println("Request exceeded tolerance time or failed, update price to "+price+" and restart request again!");
				ec2.cancelRequest(spotInstanceRequestIds);
				price+=0.5;
			}
			else {
				List<Tag> tags=new ArrayList<Tag>();
				tags.add(workerTag);
				tags.add(new Tag("Name","remoteWorker"));
				ec2.tagResources(instanceIDs, tags);
				long iTime=System.currentTimeMillis();
				ifContinue=false;
				while(ec2.ifInstancePending(instanceIDs)) {
					if(System.currentTimeMillis()-iTime>newSpotInstanceStartTolerateTime) {
						System.out.println("Instance-starting exceeded tolerance time, restart request again!");
						ec2.cancelRequest(spotInstanceRequestIds);
//						ec2.terminateInstances(instanceIDs);
						ifContinue=true;
					}
					Thread.sleep(1000*10);
				}
				
			}
		}
		return System.currentTimeMillis()-startTime;
	}
	
	public static void main(String[] args) throws InterruptedException, UnsupportedEncodingException {
		AWSCredentials credentials = null;
		if(args.length==2) {
			minWorkerNum=Integer.parseInt(args[0]);
			maxWorkerNum=Integer.parseInt(args[1]);
			assert(minWorkerNum<=maxWorkerNum);
		}
		try {
            credentials = new ProfileCredentialsProvider(credentialProfileName).getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                    "Please make sure that your credentials file is at the correct " +
                    "location (/Users/t/.aws/credentials), and is in valid format.",
                    e);
        }
		Region region = Region.getRegion(Regions.US_EAST_1);
		SQS sqs = new SQS(credentials,region,queue);
		EC2 ec2 = new EC2(credentials,region);
		double price=0.5;
		
//		launchAndWaitUntilRunning(ec2,1,price); // for test
		int peekTaskNum=0;
		int increaseTimes=0;
		int absoluteTaskIncrease=0;
		int workerNum;
		int taskNum;
		int lastTaskNum=0;
		int diff;
		int remainAllow=0;
		boolean resetStrategyCounter=false;
		
		//The strategy
		while(true) {
			assert(minWorkerNum<=maxWorkerNum);
			workerNum=ec2.getInstanceNumWithTag(workerTag);
			taskNum=sqs.getApproximateQueueSize();
			remainAllow=maxWorkerNum-workerNum;
			if(workerNum<minWorkerNum) {
				launchAndWaitUntilRunning(ec2,minWorkerNum-workerNum,price);
				resetStrategyCounter=true;
			}
			else if(remainAllow<=0 || taskNum==0) {
				
			}
			else if(workerNum==0) {
				launchAndWaitUntilRunning(ec2,1,price);
				resetStrategyCounter=true;
			}
			else {
				diff=taskNum-lastTaskNum;
				if(diff<0)
					increaseTimes--;
				if(diff>0)
					increaseTimes++;
				absoluteTaskIncrease+=diff;
				absoluteTaskIncrease = Math.max(absoluteTaskIncrease, 0);
				increaseTimes = Math.max(increaseTimes, 0);
				if(taskNum>(int)(Math.pow(workerNum,1)*100)) {
					launchAndWaitUntilRunning(ec2,Math.min(remainAllow,1+(int)(0.5+Math.log10(taskNum/*/(10*workerNum)*/))),price);
					resetStrategyCounter=true;
				}
//				else if(diff>0 && diff*1.0/taskNum*Math.log10(taskNum/10.0)>0.1) {
//					launchAndWaitUntilRunning(ec2,Math.min(remainAllow,1+Math.min(10,(int)(0.5+10.0*diff*1.0/taskNum*Math.log10(taskNum/10.0)))),price);
//					resetStrategyCounter=true;
//				}
				else if(absoluteTaskIncrease>0 && absoluteTaskIncrease*1.0/taskNum*Math.log10(taskNum/10.0)>0.1) {
					launchAndWaitUntilRunning(ec2,Math.min(remainAllow,1+Math.min(10,(int)(0.5+10.0*absoluteTaskIncrease*1.0/taskNum*Math.log10(taskNum/10.0)))),price);
					resetStrategyCounter=true;
				}
				else if(increaseTimes>3 || absoluteTaskIncrease>1000) {
					launchAndWaitUntilRunning(ec2,Math.min(remainAllow,1+(int)(0.5+Math.log10(Math.max(100, absoluteTaskIncrease)/100.0))),price);
					resetStrategyCounter=true;
				}
			}
			
			if(resetStrategyCounter) {
				resetStrategyCounter=false;
				increaseTimes=0;
				absoluteTaskIncrease=0;
			}
			lastTaskNum=sqs.getApproximateQueueSize();
			Thread.sleep(checkPeriod);
		}
	}
	
}
