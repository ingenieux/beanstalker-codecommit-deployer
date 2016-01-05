package io.ingenieux.lambada.bot;

import br.com.ingenieux.beanstalker.BeanstalkerDeployer;
import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.List;

public class BeanstalkerDeployerTest {
    private BeanstalkerDeployer.DeployerArgs args;

    private BeanstalkerDeployer deployer;

    /**
     * <pre>
     *     {
     * "applicationName" : "multipackage-example",
     * "commitId" : "73031a04846d8adaee6fc1eb1b4bb98af9878c3b",
     * "repoName" : "ingenieux-image-blobs",
     * "targetPath" : "s3://elasticbeanstalk-us-east-1-235368163414/apps/multipackage-example/versions/git-73031a04846d8adaee6fc1eb1b4bb98af9878c3b.zip"
     * }
     * </pre>
     *
     * @throws Exception
     */
    @Before
    public void setUp() throws Exception {
        this.args = new BeanstalkerDeployer.DeployerArgs();

        args.setApplicationName("multipackage-example");
        args.setCommitId("73031a04846d8adaee6fc1eb1b4bb98af9878c3b");
        args.setRepoName("ingenieux-image-blobs");
        args.setTargetPath("s3://elasticbeanstalk-us-east-1-235368163414/apps/multipackage-example/versions/git-73031a04846d8adaee6fc1eb1b4bb98af9878c3b.zip");

        this.deployer = new BeanstalkerDeployer();
    }

    @Test
    @Ignore
    public void testInvocation() throws Exception {
        List<String> result = deployer.deploy(args, mockContext);

        for (String message : result) {
            System.err.println(message);
        }
    }

    LambdaLogger mockLogger = new LambdaLogger() {
        @Override
        public void log(String msg) {
            System.err.println(msg);
        }
    };

    Context mockContext = new Context() {
        @Override
        public String getAwsRequestId() {
            return null;
        }

        @Override
        public String getLogGroupName() {
            return null;
        }

        @Override
        public String getLogStreamName() {
            return null;
        }

        @Override
        public String getFunctionName() {
            return null;
        }

        @Override
        public String getFunctionVersion() {
            return null;
        }

        @Override
        public String getInvokedFunctionArn() {
            return null;
        }

        @Override
        public CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public ClientContext getClientContext() {
            return null;
        }

        @Override
        public int getRemainingTimeInMillis() {
            return 0;
        }

        @Override
        public int getMemoryLimitInMB() {
            return 0;
        }

        @Override
        public LambdaLogger getLogger() {
            return mockLogger;
        }
    };
}
