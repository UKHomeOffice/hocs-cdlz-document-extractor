package uk.gov.digital.ho.hocs.hocstxadocumentextractor;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import uk.gov.digital.ho.hocs.hocstxadocumentextractor.batch.TxaKafkaItemWriter;
import uk.gov.digital.ho.hocs.hocstxadocumentextractor.utils.TestUtils;

import javax.sql.DataSource;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@SpringBatchTest
@ActiveProfiles("integration")
public class Scenario3Test {
    /*
    Integration Test Scenario 3

    This scenario simulates the case where there is an error during the 1st chunk of documents
    to process. This scenario creates this error by making the s3_key of the second record one
    that does not exist in the S3.

    The expected outcome is a failed Job with the timestamp unchanged and 0 records published
    to Kafka.
     */
    private static final Logger log = LoggerFactory.getLogger(
        Scenario3Test.class);
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;
    private @Value("${s3.endpoint_url}") String endpointURL;
    private @Value("${kafka.bootstrap_servers}") String bootstrapServers;
    private @Value("${kafka.ingest_topic}") String ingestTopic;
    private JdbcTemplate jdbcTemplate;
    private AdminClient kafkaClient = null;

    @Autowired
    public void setDataSource(@Qualifier("metadataSource") DataSource metadataSource) {
        this.jdbcTemplate = new JdbcTemplate(metadataSource);
    }

    @BeforeEach
    void setUp() throws Exception {
        log.info("Test setUp");
        String insertRecords = """
            INSERT INTO metadata.document_metadata
            VALUES
                ('a1', timestamp '2023-03-22 12:00:00', 'Y', 'decs-file1.pdf'),
                ('b2', timestamp '2023-03-22 13:00:00', 'Y', 'NONEXISTENT-FILE.pdf'),
                ('c3', timestamp '2023-03-22 14:00:00', 'Y', 'decs-file3.pdf'),
                ('d4', timestamp '2023-03-22 15:00:00', 'N', 'decs-file4.pdf'),
                ('e5', timestamp '2023-03-22 16:00:00', 'Y', 'decs-file5.pdf'),
                ('f6', timestamp '2023-03-22 17:00:00', 'Y', 'decs-file6.pdf');
            """;
        TestUtils.setUpPostgres(this.jdbcTemplate, insertRecords);

        Path path = FileSystems.getDefault().getPath("src", "integration-test","resources","trusted-s3-data");
        TestUtils.setUpS3(path, "trusted-bucket", this.endpointURL);
        Path otherPath = FileSystems.getDefault().getPath("src", "integration-test","resources","untrusted-s3-data");
        TestUtils.setUpS3(otherPath, "untrusted-bucket", this.endpointURL);

        Map<String, Object> conf = new HashMap<>();
        conf.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        kafkaClient = AdminClient.create(conf);
        TestUtils.setUpKafka(kafkaClient, ingestTopic);
    }

    @AfterEach
    void tearDown() throws Exception {
        log.info("Test tearDown");
        TestUtils.tearDownPostgres(this.jdbcTemplate);
        TestUtils.tearDownS3("trusted-bucket", this.endpointURL);
        TestUtils.tearDownS3("untrusted-bucket", this.endpointURL);
        TestUtils.tearDownKafka(kafkaClient, ingestTopic);
    }

    @Test
    public void testJob(@Autowired Job job, @Autowired TxaKafkaItemWriter writer) throws Exception {
        this.jobLauncherTestUtils.setJob(job);
        JobExecution jobExecution = jobLauncherTestUtils.launchJob();
        writer.commitTimestamp(); // required to trigger the predestroy method during the test
        assertEquals("FAILED", jobExecution.getExitStatus().getExitCode());
        assertEquals("2023-03-22 11:59:59", TestUtils.getTimestampFromS3("untrusted-bucket", this.endpointURL));

        List<String> keysConsumed = TestUtils.consumeKafkaMessages(bootstrapServers, ingestTopic, 1);
        assertEquals(0, keysConsumed.size());
    }
}
