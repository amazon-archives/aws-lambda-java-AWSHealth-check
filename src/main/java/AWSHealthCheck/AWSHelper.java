/**
 *  Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy of this
 *  software and associated documentation files (the "Software"), to deal in the Software
 *  without restriction, including without limitation the rights to use, copy, modify,
 *  merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 *  permit persons to whom the Software is furnished to do so.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 *  INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 *  PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 *  HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 *  OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 *  SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 *  Helper class for AWS API calls
 *
 * @author Paul Hyung Yuel Kim
 * @version 1.0
 * @since 2019-02-06
 */
package AWSHealthCheck;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.cloudwatch.model.*;
import com.amazonaws.services.health.AWSHealth;
import com.amazonaws.services.health.AWSHealthClientBuilder;
import com.amazonaws.services.health.model.*;
import com.amazonaws.services.organizations.*;
import com.amazonaws.services.cloudwatch.*;
import com.amazonaws.services.organizations.model.DescribeAccountRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import com.amazonaws.services.simpleemail.*;
import com.amazonaws.services.simpleemail.model.RawMessage;
import com.amazonaws.services.simpleemail.model.SendRawEmailRequest;
import com.amazonaws.services.simpleemail.model.SendRawEmailResult;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class AWSHelper {

    // Initialize the Log4j LOGGER.
    private static final Logger LOGGER = LogManager.getLogger(AWSHelper.class);

    private AWSHelper(){}

    /*private static class SingletonHelper {
        private static final AWSHelper INSTANCE = new AWSHelper();
    }

    public static AWSHelper getInstance() {
        return SingletonHelper.INSTANCE;
    }*/

    public static class OrganizationHelper {
        private static final AWSOrganizations CLIENT = AWSOrganizationsClientBuilder.defaultClient();

        public static String getAccountName(String accountID) {
            LOGGER.debug("Querying AWS account name info");
            DescribeAccountRequest request = new DescribeAccountRequest();
            request.setAccountId(accountID);
            return CLIENT.describeAccount(request).getAccount().getName();
        }

        public static String getAccountEmail(String accountID) {
            LOGGER.debug("Querying AWS account email info");
            DescribeAccountRequest request = new DescribeAccountRequest();
            request.setAccountId(accountID);
            return CLIENT.describeAccount(request).getAccount().getEmail();
        }
    }

    public static class STSHelper {
        private static final AWSSecurityTokenService CLIENT = AWSSecurityTokenServiceClientBuilder.defaultClient();

        public static String getAccountID() {
            LOGGER.debug("Querying AWS accountID");
            GetCallerIdentityRequest request = new GetCallerIdentityRequest();
            return CLIENT.getCallerIdentity(request).getAccount();
        }
    }

    public static class S3Helper {
        private static AmazonS3 client;

        /* List bucket object result limit per REQUEST to 1 week on the assumption
         * that the job will run in 5 minute interval and store the results to s3.
         * 60 min * 24 hrs * 7 days + 1 (Hash result object) = 2017
         */
        public static final Integer MAX_KEYS = 2017;

        /*
         * Lazy initialize S3 CLIENT
         */
        private static void buildS3Client(String region) {
            client = AmazonS3ClientBuilder.standard().withRegion(region).build();
        }

        private static void buildS3Client() {
            client = AmazonS3ClientBuilder.defaultClient();
        }

        public static PutObjectResult uploadFile(String bucket, File localFile, String remoteFilename, String region) {
            buildS3Client(region);
            return upload(bucket, localFile, remoteFilename);
        }

        public static PutObjectResult uploadFile(String bucket, File localFile, String remoteFilename) {
            buildS3Client();
            return upload(bucket, localFile, remoteFilename);
        }

        private static PutObjectResult upload(String bucket, File localFile, String remoteFilename){
            LOGGER.debug("Uploading " + localFile.getName() + " to " + bucket + "/" + remoteFilename);
            return client.putObject(bucket, remoteFilename, localFile);
        }

        public static void downloadFile(String bucket, String localFilename, String remoteFilename, String region) {
            buildS3Client(region);
            download(bucket, localFilename, remoteFilename);
        }

        public static void downloadFile(String bucket, String localFilename, String remoteFilename) {
            buildS3Client();
            download(bucket, localFilename, remoteFilename);
        }

        private static void download(String bucket, String localFilename, String remoteFilename) {
            LOGGER.debug("Downloading " + bucket + "/" + remoteFilename + " to " + localFilename);
            try {
                S3Object s3object = client.getObject(bucket, remoteFilename);
                S3ObjectInputStream inputStream = s3object.getObjectContent();
                FileUtils.copyInputStreamToFile(inputStream, new File(localFilename));
            } catch (AmazonS3Exception | IOException e) {
                LOGGER.error(e.getMessage());
            }
        }

        public static List<S3ObjectSummary> listBucketContents(String bucket, String region) {
            buildS3Client(region);
            return listBucket(bucket);
        }

        public static List<S3ObjectSummary> listBucketContents(String bucket) {
            buildS3Client();
            return listBucket(bucket);
        }

        private static List<S3ObjectSummary> listBucket(String bucket) {
            List<S3ObjectSummary> objects = new ArrayList<>();

            try {
                ListObjectsV2Request request = new ListObjectsV2Request().withBucketName(bucket);
                ListObjectsV2Result result;

                do {
                    request.setMaxKeys(MAX_KEYS);
                    result = client.listObjectsV2(request);

                    for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {
                        objects.add(objectSummary);
                    }

                    String token = result.getNextContinuationToken();
                    request.setContinuationToken(token);
                } while (result.isTruncated());
            } catch (AmazonS3Exception e) {
                LOGGER.error(e.getMessage());
            }
            return objects;
        }

        public static List<String> deleteFiles(String bucket, String region, List<String> keys) {
            buildS3Client(region);
            return deleteObjects(bucket, keys);
        }

        public static List<String> deleteFiles(String bucket, List<String> keys) {
            buildS3Client();
            return deleteObjects(bucket, keys);
        }

        private static List<String> deleteObjects(String bucket, List<String> keys) {
            List<String> deletedKeys = new ArrayList<>();

            try {
                /*
                 * Divide the list of keys to delete into 500 (instead of 1000, to be safe) chunks due to
                 * S3 java sdk issue:
                 * "Attempting to delete more than 1000 keys from S3 gives confusing MalformedXML",
                 * https://github.com/aws/aws-sdk-java/issues/1293
                 */
                int chunkSize = 500;
                List<List<String>> keysList = new ArrayList<>();
                for (int i=0; i < keys.size(); i += chunkSize) {
                    int end = Math.min(keys.size(), i + chunkSize);
                    keysList.add(keys.subList(i, end));
                }

                for (List<String> i : keysList) {
                    DeleteObjectsRequest request = new DeleteObjectsRequest(bucket);
                    List<DeleteObjectsRequest.KeyVersion> keys2 = new ArrayList<>();
                    for (String j: i) {
                        keys2.add(new DeleteObjectsRequest.KeyVersion(j));
                    }
                    request.setKeys(keys2);
                    DeleteObjectsResult result = client.deleteObjects(request);

                    for (DeleteObjectsResult.DeletedObject k: result.getDeletedObjects()) {
                        deletedKeys.add(k.getKey());
                    }
                }
            } catch (AmazonS3Exception e) {
                LOGGER.error(e.getMessage());
            }

            return deletedKeys;
        }

        public static boolean doesFileExist(String bucket, String key, String region) {
            buildS3Client(region);
            return doesObjectExist(bucket, key);
        }

        public static boolean doesFileExist(String bucket, String key) {
            buildS3Client();
            return doesObjectExist(bucket, key);
        }

        private static boolean doesObjectExist(String bucket, String key) {
            boolean result = false;
            try {
                result = client.doesObjectExist(bucket, key);
            } catch (SdkClientException e) {
                LOGGER.error(e.getMessage());
            }

            return result;
        }

    }

    public static class SESHelper {
        private static AmazonSimpleEmailService client;

        public static void sendRawEmail(String sender, String recipient, RawMessage rawMessage, String region) {
            client = AmazonSimpleEmailServiceClientBuilder.standard().withRegion(region).build();
            sendRawMessage(sender, recipient, rawMessage);

        }

        public static void sendRawEmail(String sender, String recipient, RawMessage rawMessage) {
            client = AmazonSimpleEmailServiceClientBuilder.defaultClient();
            sendRawMessage(sender, recipient, rawMessage);
        }

        private static void sendRawMessage(String sender, String recipient, RawMessage rawMessage) {
            LOGGER.debug("Sending Email: sender-" + sender + " recipient-" + recipient + " message-" + rawMessage);
            SendRawEmailRequest request = new SendRawEmailRequest();
            request.setSource(sender);
            request.setDestinations(Arrays.asList(recipient.split(",")));
            request.setRawMessage(rawMessage);
            SendRawEmailResult result = client.sendRawEmail(request);
            LOGGER.info(result.toString());
        }
    }

    public static class CloudWatchHelper {
        private static final AmazonCloudWatch CLIENT = AmazonCloudWatchClientBuilder.defaultClient();
        private static final List<MetricDatum> DATA_LIST = new ArrayList<>();

        public static boolean addMetricData(Map<String, String> data) {
            String dimensionName = "";
            String dimensionValue = "";
            if (data.containsKey("dimensionName")) {
                dimensionName = data.get("dimensionName");
            }
            if (data.containsKey("dimensionValue")) {
                dimensionValue = data.get("dimensionValue");
            }
            Dimension dimension = new Dimension().withName(dimensionName).withValue(dimensionValue);

            String metricName = "";
            Double metricData = 0.0;
            if (data.containsKey("metricName")) {
                metricName = data.get("metricName");
            }
            if (data.containsKey("metricData")) {
                metricData = Double.parseDouble(data.get("metricData"));
            }
            MetricDatum datum = new MetricDatum()
                                .withMetricName(metricName)
                                .withUnit(StandardUnit.None)
                                .withValue(metricData)
                                .withDimensions(dimension);

            return DATA_LIST.add(datum);
        }

        public static String putMetricData(String namespace) {
            if (namespace == null || namespace.trim() == "") {
                namespace = "AWS-Health-Checker";
            }
            PutMetricDataRequest request = new PutMetricDataRequest()
                    .withNamespace(namespace)
                    .withMetricData(DATA_LIST);

            PutMetricDataResult response = CLIENT.putMetricData(request);
            DATA_LIST.clear();
            return response.toString();
        }
    }

    public static class AWSHealthHelper {
        private static final AWSHealth CLIENT = AWSHealthClientBuilder.defaultClient();

        public static List<Event> describeEvents(List<String> region, List<String> category,
                                                 List<String> status, Collection<Map<String,String>> tags,
                                                 List<DateTimeRange> startTimes, List<DateTimeRange> endTimes) {
            List<Event> result = new ArrayList<>();

            /*
             * TODO: Modify config.yaml, AWSHealthCheck.Config.java, and this method to add more filters.
             * https://docs.aws.amazon.com/health/latest/APIReference/API_DescribeEvents.html
             */
            EventFilter filter = new EventFilter();
            filter.setRegions(region);
            filter.setEventStatusCodes(status);
            filter.setEventTypeCategories(category);
            filter.setTags(tags);
            if (startTimes != null) filter.setStartTimes(startTimes);
            if (endTimes != null) filter.setEndTimes(endTimes);
            DescribeEventsRequest request = new DescribeEventsRequest();
            request.setFilter(filter);
            DescribeEventsResult response = CLIENT.describeEvents(request);

            result.addAll(response.getEvents());

            while (response.getNextToken() != null) {
                request.setNextToken(response.getNextToken());
                response = CLIENT.describeEvents(request);
                result.addAll(response.getEvents());
            }
            return result;
        }

        public static List<EventDetails> describeEventDetails(Collection<String> eventArns) {
            List<EventDetails> result;

            DescribeEventDetailsRequest request_detail = new DescribeEventDetailsRequest();
            request_detail.setEventArns(eventArns);
            DescribeEventDetailsResult response = CLIENT.describeEventDetails(request_detail);
            result = response.getSuccessfulSet();
            return result;
        }

        public static List<AffectedEntity>  describeAffectedEntities(Collection<String> eventArns) {
            List<AffectedEntity> result = new ArrayList<>();

            EntityFilter filter = new EntityFilter();
            filter.setEventArns(eventArns);

            DescribeAffectedEntitiesRequest request_detail_entity = new DescribeAffectedEntitiesRequest();
            request_detail_entity.setFilter(filter);
            DescribeAffectedEntitiesResult response = CLIENT.describeAffectedEntities(request_detail_entity);
            result.addAll(response.getEntities());

            while (response.getNextToken() != null) {
                request_detail_entity.setNextToken(response.getNextToken());
                response = CLIENT.describeAffectedEntities(request_detail_entity);
                result.addAll(response.getEntities());
            }
            return result;
        }

    }

}