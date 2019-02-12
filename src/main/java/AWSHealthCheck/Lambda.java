/**
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Automate AWSHealth API calls and event notifications
 *
 * A script, for lambda use, to check for AWSHealth events and notify via SES.
 *
 * @author Paul Hyung Yuel Kim
 * @version 1.0
 * @since 2019-02-06
 */
package AWSHealthCheck;

import com.amazonaws.services.health.model.AffectedEntity;
import com.amazonaws.services.health.model.DateTimeRange;
import com.amazonaws.services.health.model.EventDetails;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.health.model.Event;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.simpleemail.model.RawMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class Lambda implements RequestStreamHandler {
    private static final Logger LOGGER = LogManager.getLogger(Lambda.class);
    private static final String REGION = System.getenv("DEFAULT_REGION");
    private static final String BUCKET = System.getenv("BUCKET");
    private static final String PERSIST_FILE_PATH = "/tmp/";
    private static final String PERSIST_EVENTS_WITH_NOTIFICATIONS_SENT =
            AWSHelper.OrganizationHelper.getAccountName(AWSHelper.STSHelper.getAccountID())
                    + "EventsNotificationSent.ser";
    private static final String PERSIST_HASH_RESULT =
            AWSHelper.OrganizationHelper.getAccountName(AWSHelper.STSHelper.getAccountID())
                    + "AWSHealthCheckHashResult.txt";
    private static final String PERSIST_FILE_NAME =
            AWSHelper.OrganizationHelper.getAccountName(AWSHelper.STSHelper.getAccountID())
                    + "AWSHealthCheckResultEvents_%s.txt";
    private static final Integer MAX_FETCH_MONTHS_PERIOD = 3;
    private Config config;

    // EventDetail with affected list of resources
    class EventDetailWithResources {
        private EventDetails eventDetail;
        private List<AffectedEntity> eventResources = new ArrayList<>();

        EventDetailWithResources(EventDetails e) {
            this.eventDetail = e;
        }

        EventDetails getEventDetail() {
            return eventDetail;
        }

        List<AffectedEntity> getEventResources() {
            return eventResources;
        }
    }

    public void handleRequest(InputStream inputStream, OutputStream outputStream,
                              Context context) throws IOException {
        List<Event> resultEvents = new ArrayList<>();

        String events = getAWSHealthEvents(resultEvents);

        /* Track the list of events that had notifications sent out; we'll need them in case describeEvents call
         * have filter for excluding 'closed' events since the users will want 'event closed' notifications
         * when they're closed.
         */
        if (!config.getStatus().contains("closed")) {
            try {
                List<Event> pastEvent = loadEvents();
                Set<Event> s1;
                if (pastEvent != null) {
                    s1 = new HashSet<>(pastEvent);
                } else {
                    s1 = new HashSet<>();
                }
                Set<Event> s2 = new HashSet<>(resultEvents);

                s1.removeAll(s2); // List of events that was closed since the last notification
                List<Event> recentlyClosedEvents = new ArrayList<>(s1);

                events += getDetaildEventDescriptionWithAffectedResources(recentlyClosedEvents,
                                                         resultEvents.size() + 1);
            } catch (IOException | ClassNotFoundException e) {
                LOGGER.error(e.getMessage());
            }
        }

        // Check if new events are found since the last notification
        if (events.trim().length() > 0) {
            String shaHash = getSHAHash(events);
            String shaHashOnFile = "";

            if (AWSHelper.S3Helper.doesFileExist(BUCKET, PERSIST_HASH_RESULT, REGION)) {
                AWSHelper.S3Helper.downloadFile(BUCKET,
                        PERSIST_FILE_PATH + PERSIST_HASH_RESULT, PERSIST_HASH_RESULT, REGION);
                shaHashOnFile = readFileContents(PERSIST_FILE_PATH + PERSIST_HASH_RESULT);
            }

            if (shaHash.compareTo(shaHashOnFile) != 0) {
                // Send notification and persist the new hash result.
                writeFileContents(PERSIST_FILE_PATH + PERSIST_HASH_RESULT, shaHash);
                File f = new File(PERSIST_FILE_PATH + PERSIST_HASH_RESULT);
                AWSHelper.S3Helper.uploadFile(BUCKET, f, PERSIST_HASH_RESULT, REGION);

                String emailContent = String.format(config.getEmail_template(), events);

                RawMessage rawMessage = prepareRawMessage(emailContent);

                if (rawMessage != null) {
                    AWSHelper.SESHelper.sendRawEmail(config.getSes_from(),
                            config.getSes_send(), rawMessage, config.getSes_region());
                    LOGGER.info(String.format("Sending email to %s\n", config.getSes_send()));
                }

                LOGGER.info(emailContent);
            }
        } else {
            LOGGER.info("No new AWS Health events found since the last notification.");
        }

        // Persist event results for keeping history and conduct bucket housekeeping
        persistEventResult(events);
        truncateEventResultsFromS3();

        // Overwrite with the current 'open' event list
        if (!config.getStatus().contains("closed")) persistEvents(resultEvents);
    }

    public Lambda() {
        config = loadConfig();
    }

    private Config loadConfig() {
        String configPath = System.getenv("CONFIG_FILE");
        if (configPath == null || configPath.trim() == "") {
            configPath = "../config.yaml";
        } else {
            configPath = "../" + configPath;
        }

        LOGGER.info("Loading config settings from: " + configPath);
        Yaml yaml = new Yaml(new Constructor(Config.class));
        InputStream inputStream = this.getClass().getResourceAsStream(configPath);

        Config config = yaml.load(inputStream);
        return config;
    }

    private String getAWSHealthEvents(List<Event> resultEvents) {
        /*
         * describeEvents call will return all the past events. Therefore, limit the result set by adding event
         * start time filter.
         */
        if (config.getStatus().contains("closed")) {
            DateTimeRange startTime = new DateTimeRange();
            Date from = Date.from(ZonedDateTime.now().minusMonths(MAX_FETCH_MONTHS_PERIOD).toInstant());
            Date to = Date.from(ZonedDateTime.now().toInstant());
            startTime.setFrom(from);
            startTime.setTo(to);
            List<DateTimeRange> startTimes = new ArrayList<>();
            startTimes.add(startTime);

            resultEvents.addAll(AWSHelper.AWSHealthHelper.describeEvents(config.getRegions(), config.getCategory(),
                    config.getStatus(), config.getTags(), startTimes, null));
        } else {
            resultEvents.addAll(AWSHelper.AWSHealthHelper.describeEvents(config.getRegions(), config.getCategory(),
                    config.getStatus(), config.getTags(), null, null));
        }

        return getDetaildEventDescriptionWithAffectedResources(resultEvents, 1);
    }

    private String getDetaildEventDescriptionWithAffectedResources(List<Event> resultEvents, Integer eventCounterOffset) {
        if (resultEvents.size() == 0) return "";

        List<String> eventArns  = resultEvents.stream().map(Event::getArn).collect(Collectors.toList());

        /*
         * Divide the eventArns into chunks of 5 (due to maximum length factor) since DescribeEventDetails only takes
         * in a maximum of 10 event ARN at a time with maximum length of 1600.
         * https://docs.aws.amazon.com/health/latest/APIReference/API_DescribeEventDetails.html
         */
        int chunkSize = 5;
        List<List<String>> eventArnsLists = new ArrayList<>();
        for (int i=0; i < eventArns.size(); i += chunkSize) {
            int end = Math.min(eventArns.size(), i + chunkSize);
            eventArnsLists.add(eventArns.subList(i, end));
        }

        // Get eventDetails and affectedResources from the events returned
        List<EventDetails> resultEventsDetail = new ArrayList<>();
        List<AffectedEntity> resultEventsDetailDetail = new ArrayList<>();
        for (List<String> i : eventArnsLists) {
            resultEventsDetail.addAll(AWSHelper.AWSHealthHelper.describeEventDetails(i));
            resultEventsDetailDetail.addAll(AWSHelper.AWSHealthHelper.describeAffectedEntities(i));
        }

        List<EventDetailWithResources> eventDetailWithResources = new ArrayList<>();
        for (EventDetails i : resultEventsDetail) {
            EventDetailWithResources row = new EventDetailWithResources(i);

            // Find relevant resources for the event and add them to eventResources list\
            List<AffectedEntity> affectedEntities = resultEventsDetailDetail.stream()
                                    .filter(affectedEntity -> affectedEntity.getEventArn() == i.getEvent().getArn())
                                    .collect(Collectors.toList());
            row.getEventResources().addAll(affectedEntities);

            eventDetailWithResources.add(row);
        }

        // Sort to print in the most recent event order
        Collections.sort(eventDetailWithResources,
                         (e1,e2) -> e2.eventDetail.getEvent().getStartTime()
                                    .compareTo(e1.eventDetail.getEvent().getStartTime()));

        int num = eventCounterOffset;
        StringBuilder output = new StringBuilder();
        for (EventDetailWithResources i : eventDetailWithResources) {

            output.append("Event " + num++ + ")" + System.getProperty("line.separator"));

            // Use reflection to build event summary string
            List<Method> methods = Arrays.asList(i.getEventDetail().getEvent().getClass().getMethods());

            /*
             * Consistent output string is required for proper comparision via SHA256 signature
             * Sort the returned methods array as they're not guaranteed to be in order
             * https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html
             */
            Collections.sort(methods, Comparator.comparing(Method::getName));
            for (Method m: methods) {
                try {
                    if (m.getName().startsWith("get") && m.getName() != "getClass" && m.getParameterTypes().length == 0) {
                        output.append(m.getName().replace("get", "")
                                + ": " + m.invoke(i.getEventDetail().getEvent()) + System.getProperty("line.separator"));
                    }
                } catch (IllegalAccessException | InvocationTargetException ex) {
                    LOGGER.error(ex.getMessage());
                    output.setLength(0);
                }
            }
            output.append(System.getProperty("line.separator"));

            output.append("\tSummary:" + System.getProperty("line.separator") + System.getProperty("line.separator"));
            // Get event summary and tabify each line
            output.append(i.eventDetail.getEventDescription().getLatestDescription()
                          .replaceAll("(?m)^", "\t") + System.getProperty("line.separator"));
            if (i.getEventResources().size() > 0) {
                output.append("\t\tAffected resources:" + System.getProperty("line.separator")
                                                          + System.getProperty("line.separator"));
            }
            for (AffectedEntity j : i.getEventResources()) {
                output.append("\t\t" + "ARN: " + j.getEntityArn() + System.getProperty("line.separator"));
                output.append("\t\t" + "URL: " + j.getEntityUrl() + System.getProperty("line.separator"));
                output.append("\t\t" + "Value: " + j.getEntityValue() + System.getProperty("line.separator"));
                output.append("\t\t" + "Value: " + j.getEntityValue() + System.getProperty("line.separator"));
                output.append("\t\t" + "Status Code: " + j.getStatusCode() + System.getProperty("line.separator"));
                output.append("\t\t" + "Last Updated Time: " + j.getLastUpdatedTime()
                                                             + System.getProperty("line.separator"));

                Map<String, String> tags = j.getTags();
                List<Map.Entry<String, String>> sortedTags = tags.entrySet().stream()
                                                .sorted(Comparator.comparing((Map.Entry<String,String> e) -> e.getKey())
                                                        .thenComparing(Map.Entry::getValue))
                                                .collect(Collectors.toList());
                if (sortedTags.size() > 0) {
                    output.append("\t\t" + "Tags: " + System.getProperty("line.separator"));
                }
                for (Map.Entry<String, String> k : sortedTags) {
                    output.append("\t\t\t" + "Key: " + k.getKey() + "\tValue: " + k.getValue()
                                  +  System.getProperty("line.separator"));
                }
            }
            output.append(System.getProperty("line.separator"));
            output.append(System.getProperty("line.separator"));
            output.append(System.getProperty("line.separator"));
        }
        return output.toString();
    }

    /*
     * Persist the getAWSHealthEvents() result
     */
    private void persistEventResult(String events) {
        DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
        Date date = new Date();
        String fileName = String.format(PERSIST_FILE_NAME, dateFormat.format(date));

        writeFileContents(PERSIST_FILE_PATH + fileName, events);
        File f = new File(PERSIST_FILE_PATH + fileName);
        AWSHelper.S3Helper.uploadFile(BUCKET, f, fileName, REGION);
    }

    private void persistEvents(List<Event> resultEvents) throws IOException {
        if (resultEvents.size() == 0) return;

        ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(PERSIST_FILE_PATH+PERSIST_EVENTS_WITH_NOTIFICATIONS_SENT));
        oos.writeObject(resultEvents);
        oos.close();
        File f = new File(PERSIST_FILE_PATH+PERSIST_EVENTS_WITH_NOTIFICATIONS_SENT);
        AWSHelper.S3Helper.uploadFile(BUCKET, f, PERSIST_EVENTS_WITH_NOTIFICATIONS_SENT, REGION);
    }

    private List<Event> loadEvents() throws IOException, ClassNotFoundException {
        List<Event> list = null;

        AWSHelper.S3Helper.downloadFile(BUCKET, PERSIST_FILE_PATH+PERSIST_EVENTS_WITH_NOTIFICATIONS_SENT,
                PERSIST_EVENTS_WITH_NOTIFICATIONS_SENT, REGION);
        File f = new File(PERSIST_FILE_PATH+PERSIST_EVENTS_WITH_NOTIFICATIONS_SENT);
        if (f.exists()) {
            ObjectInputStream ois = new ObjectInputStream(
                    new FileInputStream(PERSIST_FILE_PATH+PERSIST_EVENTS_WITH_NOTIFICATIONS_SENT));
            list = (List<Event>) ois.readObject(); // cast is needed.
            ois.close();
        }

        return list;
    }

    /*
     * Truncate getAWSHealthEvents() results older than the threshold from the bucket
     */
    private void truncateEventResultsFromS3() {
        List<S3ObjectSummary> bucketObjects = AWSHelper.S3Helper.listBucketContents(BUCKET, REGION);
        bucketObjects.sort(Comparator.comparing(S3ObjectSummary::getLastModified));
        if (bucketObjects.size() > AWSHelper.S3Helper.MAX_KEYS) {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < (bucketObjects.size() - AWSHelper.S3Helper.MAX_KEYS); i++) {
                keys.add(bucketObjects.get(i).getKey());
            }
            List<String> result = AWSHelper.S3Helper.deleteFiles(BUCKET, REGION, keys);
            if (result.size() != keys.size()) {
                Set<String> setKeys = new HashSet<>(keys);
                Set<String> setResult = new HashSet<>(result);
                setKeys.removeAll(setResult);
                LOGGER.error("S3 delete failed for the following files: ");
                for (String i : setKeys) {
                    LOGGER.error(i);
                }
            }
        }
    }

    private RawMessage prepareRawMessage(String emailContent) {
        RawMessage rawMessage = null;
        try {
            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage message = new MimeMessage(session);
            message.setSubject("[aws-lambda-java-AWSHealth-check] Found new health events", "UTF-8");
            message.setFrom(new InternetAddress(config.getSes_from()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.getSes_send()));

            // Create a multipart/alternative child container.
            MimeMultipart msg_body = new MimeMultipart("alternative");

            // Create a wrapper for the HTML and text parts.
            MimeBodyPart wrap = new MimeBodyPart();

            // Define the text part.
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setContent(emailContent, "text/plain; charset=UTF-8");

            // Add the text and HTML parts to the child container.
            msg_body.addBodyPart(textPart);

            // Add the child container to the wrapper object.
            wrap.setContent(msg_body);

            // Create a multipart/mixed parent container.
            MimeMultipart msg = new MimeMultipart("mixed");

            // Add the parent container to the message.
            message.setContent(msg);

            // Add the multipart/alternative part to the message.
            msg.addBodyPart(wrap);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            message.writeTo(outputStream);
            rawMessage = new RawMessage(ByteBuffer.wrap(outputStream.toByteArray()));

        } catch (IOException | MessagingException e) {
            LOGGER.error(e.getMessage());
        }

        return rawMessage;
    }

    private String getSHAHash(String data) {
        String hashResult = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            hashResult = bytesToHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error(e.getMessage());
        }
        return hashResult;
    }

    private static String bytesToHex(byte[] hash) {
        StringBuffer hexString = new StringBuffer();
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String readFileContents(String fileName) {
        BufferedReader br = null;
        FileReader fr = null;
        StringBuilder sb = new StringBuilder();

        try {
            fr = new FileReader(fileName);
            br = new BufferedReader(fr);

            String line;

            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

        } catch (IOException e) {
            LOGGER.error(e.getMessage());
        }

        return sb.toString();
    }

    private void writeFileContents(String fileName, String data) {
        try {
            Writer fileWriter = new FileWriter(fileName, false);
            fileWriter.write(data);
            fileWriter.close();
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
        }
    }

}