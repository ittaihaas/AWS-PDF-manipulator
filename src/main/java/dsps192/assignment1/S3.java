package dsps192.assignment1;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

public class S3 {

	static AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
	protected static String urlDelimiter = "DSPS192DELIMITER";

	// upload a file to s3 bucket
	public static void uploadFile(String bucketName, String key, File file) {
		PutObjectRequest req = new PutObjectRequest(bucketName, key, file);
		req.setCannedAcl(CannedAccessControlList.PublicRead);
		s3.putObject(req);
	}

	// download a file from s3 bucket
	public static S3Object downloadFile(String bucketName, String fileName) {
		return s3.getObject(new GetObjectRequest(bucketName, fileName));
	}

	// delete a file from s3 bucket
	public static void deleteFile(String bucketName, String fileName) {
		s3.deleteObject(bucketName, fileName);
	}

	public static List<String> listing(String bucketName, String fileName) {
		List<String> out = new LinkedList<String>();
		ObjectListing objectListing = s3
				.listObjects(new ListObjectsRequest().withBucketName(bucketName).withPrefix(fileName + "/"));
		for (S3ObjectSummary objectSummary : objectListing.getObjectSummaries())
			out.add(objectSummary.getKey());
		return out;

	}
}