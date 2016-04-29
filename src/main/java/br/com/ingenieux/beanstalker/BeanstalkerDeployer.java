package br.com.ingenieux.beanstalker;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient;
import com.amazonaws.services.elasticbeanstalk.model.CreateApplicationVersionRequest;
import com.amazonaws.services.elasticbeanstalk.model.S3Location;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3URI;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.BatchingProgressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.SimpleTimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.ingenieux.lambada.runtime.LambadaFunction;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;

/**
 * { "applicationName" : "multipackage-example", "commitId" : "73031a04846d8adaee6fc1eb1b4bb98af9878c3b",
 * "repoName" : "ingenieux-image-blobs", "targetPath" : "s3://elasticbeanstalk-us-east-1-235368163414/apps/multipackage-example/versions/git-73031a04846d8adaee6fc1eb1b4bb98af9878c3b.zip"
 * }
 */
public class BeanstalkerDeployer {
    private final Logger LOGGER = LoggerFactory.getLogger(BeanstalkerDeployer.class);

    private Context ctx;

    AmazonS3 s3;

    AWSElasticBeanstalk beanstalk;

    private DeployerArgs args;

    private AmazonS3URI s3Uri;

    private String versionLabel;

    private File sourceDirectory;

    private File targetFile;

    private AWSCredentials credentials;

    List<String> messageList = new ArrayList<>();

    @LambadaFunction(name = "beanstalker-codecommit-deployer",
            description = "beanstalker lambda helper function to convert codecommit commitIds into S3 Buckets and ApplicationVersions",
            memorySize = 512,
            timeout = 300)
    public List<String> deploy(DeployerArgs args, Context ctx) throws Exception {
        this.ctx = ctx;
        this.args = args;

        LOGGER.info("Called with args: {}", args);

        credentials = new BasicAWSCredentials(args.getAccessKey(), args.getSecretKey());

        this.s3 = new AmazonS3Client(credentials);
        this.beanstalk = new AWSElasticBeanstalkClient(credentials);

        final String regionName = defaultIfBlank(args.getRegion(), "us-east-1");

        args.setRegion(regionName);

        LOGGER.info("Using region {}", regionName);

        if (!"us-east-1".equals(regionName)) {
            final Region region = Region.getRegion(Regions.fromName(regionName));

            s3.setRegion(region);
            beanstalk.setRegion(region);
        }

        this.s3Uri = new AmazonS3URI(args.getTargetPath());
        this.versionLabel = "git-" + args.getCommitId() + "-" + DATE_TIME_FORMAT.format(new Date());
        this.sourceDirectory = new File("/tmp/deployment");
        this.targetFile = new File("/tmp/deployment.zip");

        LOGGER.info("Step #1: Checking out master from {}", args.getRepoName());

        gitCheckout();

        LOGGER.info("Step #2: Zipping Artifacts from Source Folder");

        zipFolder();

        LOGGER.info("Step #3: Uploading Zip to {}", args.getTargetPath());

        uploadArchive();

        LOGGER.info("Step #4: Creating Application Version for {}", args.getCommitId());

        createVersion();

        return messageList;
    }

    private void zipFolder() throws Exception {
        FileUtils.deleteDirectory(new File(sourceDirectory, ".git"));

        ZipUtil.pack(sourceDirectory, targetFile);
    }

    private void uploadArchive() {
        s3.putObject(s3Uri.getBucket(), s3Uri.getKey(), targetFile);
    }

    private void createVersion() {
        final CreateApplicationVersionRequest req = new CreateApplicationVersionRequest(args.getApplicationName(), versionLabel);

        req.setAutoCreateApplication(true);
        req.setSourceBundle(new S3Location(s3Uri.getBucket(), s3Uri.getKey()));
        req.setDescription(args.getDescription());
        req.setProcess(true);

        beanstalk.createApplicationVersion(req);
    }

    private void gitCheckout() throws Exception {
        FileUtils.deleteDirectory(this.sourceDirectory);

        String pushUrl = new CodeCommitRequestSigner(args.getRepoName()).getPushUrl();

        Git git = Git.
                cloneRepository().
                setURI(pushUrl).
                setDirectory(sourceDirectory).
                setProgressMonitor(new LoggerProgressMonitor()).
                call();
    }

    public static final SimpleDateFormat DATE_TIME_FORMAT = new SimpleDateFormat(
            "yyyyMMdd'T'HHmmss");

    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(
            "yyyyMMdd");

    static {
        SimpleTimeZone timezone = new SimpleTimeZone(0, "UTC");

        DATE_TIME_FORMAT.setTimeZone(timezone);
        DATE_FORMAT.setTimeZone(timezone);
    }

    public class CodeCommitRequestSigner {
        protected static final String AWS_ALGORITHM = "HMAC-SHA256";

        protected static final String TERMINATOR = "aws4_request";

        protected static final String SCHEME = "AWS4";

        protected static final String REGION = "us-east-1";

        protected static final String SERVICE = "codecommit";

        final String repoName;

        private final String strDate;

        private final String strDateTime;

        public CodeCommitRequestSigner(String repoName) {
            Date date = new Date();
            this.repoName = repoName;
            this.strDate = DATE_FORMAT.format(date);
            this.strDateTime = DATE_TIME_FORMAT.format(date);
        }

        public String getPushUrl() {
            String user = credentials.getAWSAccessKeyId();

            String host = "git-codecommit.us-east-1.amazonaws.com";

            String path = "/v1/repos/" + repoName;

            String scope = String.format("%s/%s/%s/%s", strDate,
                    REGION, SERVICE, TERMINATOR);

            StringBuilder stringToSign = new StringBuilder();

            stringToSign.append(String.format("%s-%s\n%s\n%s\n", SCHEME,
                    AWS_ALGORITHM, strDateTime, scope));

            stringToSign.append(DigestUtils.sha256Hex(String.format(
                    "GIT\n%s\n\nhost:%s\n\nhost\n", path, host).getBytes()));

            byte[] key = deriveKey();

            byte[] digest = hash(key, stringToSign.toString());

            String signature = Hex.encodeHexString(digest);

            String password = strDateTime.concat("Z").concat(signature);

            String returnUrl = String.format("https://%s:%s@%s%s", user, password,
                    host, path);

            return returnUrl;
        }

        protected byte[] deriveKey() {
            String secret = SCHEME.concat(credentials.getAWSSecretKey());
            byte[] kSecret = secret.getBytes();
            byte[] kDate = hash(kSecret, strDate);
            byte[] kRegion = hash(kDate, REGION);
            byte[] kService = hash(kRegion, SERVICE);
            byte[] key = hash(kService, TERMINATOR);
            return key;
        }

        protected byte[] hash(byte[] kSecret, String obj) {
            try {
                SecretKeySpec keySpec = new SecretKeySpec(kSecret, "HmacSHA256");

                Mac mac = Mac.getInstance("HmacSHA256");

                mac.init(keySpec);

                return mac.doFinal(obj.getBytes("UTF-8"));
            } catch (Exception exc) {
                throw new RuntimeException(exc);
            }
        }

        protected String hexEncode(String obj) {
            return Hex.encodeHexString(obj.getBytes());
        }
    }

    class LoggerProgressMonitor extends BatchingProgressMonitor {
        @Override
        protected void onUpdate(String taskName, int workCurr) {
            StringBuilder s = new StringBuilder();
            format(s, taskName, workCurr);
            send(s);
        }

        @Override
        protected void onEndTask(String taskName, int workCurr) {
            StringBuilder s = new StringBuilder();
            format(s, taskName, workCurr);
            s.append("\n");
            send(s);
        }

        private void format(StringBuilder s, String taskName, int workCurr) {
            s.append("\r");
            s.append(taskName);
            s.append(": ");
            while (s.length() < 25)
                s.append(' ');
            s.append(workCurr);
        }

        @Override
        protected void onUpdate(String taskName, int cmp, int totalWork, int pcnt) {
            StringBuilder s = new StringBuilder();
            format(s, taskName, cmp, totalWork, pcnt);
            send(s);
        }

        @Override
        protected void onEndTask(String taskName, int cmp, int totalWork, int pcnt) {
            StringBuilder s = new StringBuilder();
            format(s, taskName, cmp, totalWork, pcnt);
            s.append("\n");
            send(s);
        }

        private void format(StringBuilder s, String taskName, int cmp,
                            int totalWork, int pcnt) {
            s.append("\r");
            s.append(taskName);
            s.append(": ");
            while (s.length() < 25)
                s.append(' ');

            String endStr = String.valueOf(totalWork);
            String curStr = String.valueOf(cmp);
            while (curStr.length() < endStr.length())
                curStr = " " + curStr;
            if (pcnt < 100)
                s.append(' ');
            if (pcnt < 10)
                s.append(' ');
            s.append(pcnt);
            s.append("% (");
            s.append(curStr);
            s.append("/");
            s.append(endStr);
            s.append(")");
        }

        private void send(StringBuilder s) {
            String m = s.toString();

            ctx.getLogger().log(m);

            messageList.add(m);
        }
    }
}
