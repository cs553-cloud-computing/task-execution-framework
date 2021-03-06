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

package cloudworker;


import java.awt.Image;
import java.io.File;
import java.net.URL;
import java.util.concurrent.BlockingQueue;

import javax.imageio.ImageIO;

import org.json.simple.JSONObject;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.GetQueueUrlResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;

public class Animoto implements Runnable {
	AmazonSQS sqs;
	String responseQName;
	String task_id;
	String task;
	
	Animoto(String task_id, String task, AmazonSQS sqs){
		this.task_id = task_id;
		this.task = task;
		this.responseQName = task_id.split(":")[0].replaceAll("[^0-9]", "-");
		this.sqs = sqs;
			
	}
	
	@SuppressWarnings("unchecked")
	public void run() {	
		//Get queue url	
        GetQueueUrlResult urlResult = sqs.getQueueUrl(responseQName);
        String QueueUrl = urlResult.getQueueUrl();
        JSONObject result = new JSONObject();
        
        Runtime runtime = Runtime.getRuntime();
        
        try {
        	String[] urls = task.split(" ");
        	
        	for(String url : urls){
        		//System.out.println(url);
        		Process p = runtime.exec("wget "+ url);
        		p.waitFor();
        	}
        	
			Process rename = runtime.exec("./rename.sh");
			rename.waitFor();
			
			runtime.exec("ffmpeg -f image2 -i img%03d.jpg movie.mpg");
			
			File movie = new File("movie.mpg");
			
			S3Service s3 = new S3Service();
			URL url = s3.put(movie.getName(), movie);
			        	
        	//result.put("task_id", task_id);
        	result.put("URL", url.toString());
        	
        	sqs.sendMessage(new SendMessageRequest(QueueUrl, result.toString()));
        	//System.out.println(Thread.currentThread().getName()+" sleep done!");
        	
        } catch (Exception e) {
        	//result.put("task_id", task_id);
        	result.put("URL", "Failed!");
        	sqs.sendMessage(new SendMessageRequest(QueueUrl, result.toString()));
        	
        }
	}

}
