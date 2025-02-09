/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.aws.dynamodb;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.document.BatchWriteItemOutcome;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.TableWriteItems;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemResult;
import com.amazonaws.services.dynamodbv2.model.PutRequest;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processors.aws.testutil.AuthUtils;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PutDynamoDBTest extends AbstractDynamoDBTest {
    private static final byte[] HELLO_2_BYTES = "{\"hell\": 2}".getBytes(StandardCharsets.UTF_8);
    protected PutDynamoDB putDynamoDB;
    protected BatchWriteItemResult result = new BatchWriteItemResult();
    BatchWriteItemOutcome outcome;

    @BeforeEach
    public void setUp() {
        outcome = new BatchWriteItemOutcome(result);
        result.setUnprocessedItems(new HashMap<>());
        final DynamoDB mockDynamoDB = new DynamoDB(Regions.AP_NORTHEAST_1) {
            @Override
            public BatchWriteItemOutcome batchWriteItem(TableWriteItems... tableWriteItems) {
                return outcome;
            }
        };

        putDynamoDB = new PutDynamoDB() {
            @Override
            protected DynamoDB getDynamoDB(ProcessContext context) {
                return mockDynamoDB;
            }
        };
    }

    private TestRunner createRunner() throws InitializationException {
        return createRunner(putDynamoDB);
    }

    private TestRunner createRunner(final PutDynamoDB processor) {
        final TestRunner putRunner = TestRunners.newTestRunner(processor);
        AuthUtils.enableAccessKey(putRunner, "abcd", "cdef");

        putRunner.setProperty(AbstractDynamoDBProcessor.REGION, REGION);
        putRunner.setProperty(AbstractDynamoDBProcessor.TABLE, stringHashStringRangeTableName);
        putRunner.setProperty(AbstractDynamoDBProcessor.HASH_KEY_NAME, "hashS");
        putRunner.setProperty(AbstractDynamoDBProcessor.HASH_KEY_VALUE, "h1");
        putRunner.setProperty(AbstractDynamoDBProcessor.JSON_DOCUMENT, "document");
        return putRunner;
    }

    @Test
    public void testStringHashStringRangePutOnlyHashFailure() throws InitializationException {
        // Inject a mock DynamoDB to create the exception condition
        final DynamoDB mockDynamoDb = Mockito.mock(DynamoDB.class);
        // When writing, mock thrown service exception from AWS
        Mockito.when(mockDynamoDb.batchWriteItem(ArgumentMatchers.<TableWriteItems>any())).thenThrow(getSampleAwsServiceException());

        putDynamoDB = new PutDynamoDB() {
            @Override
            protected DynamoDB getDynamoDB(ProcessContext context) {
                return mockDynamoDb;
            }
        };

        final TestRunner putRunner = createRunner();
        putRunner.enqueue(HELLO_2_BYTES);

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_FAILURE, 1);

        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFiles) {
            validateServiceExceptionAttributes(flowFile);
        }

    }

    @Test
    public void testStringHashStringRangePutNoHashValueFailure() {
        final TestRunner putRunner = createRunner(new PutDynamoDB());
        putRunner.removeProperty(AbstractDynamoDBProcessor.HASH_KEY_VALUE);
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        putRunner.enqueue(HELLO_2_BYTES);

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_FAILURE, 1);

        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFiles) {
            assertNotNull(flowFile.getAttribute(AbstractDynamoDBProcessor.DYNAMODB_HASH_KEY_VALUE_ERROR));
        }
    }

    @Test
    public void testStringHashStringRangePutOnlyHashWithRangeValueNoRangeNameFailure() throws InitializationException {
        final TestRunner putRunner = createRunner(new PutDynamoDB());
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        putRunner.enqueue(new byte[] {});

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_FAILURE, 1);

        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFiles) {
            assertNotNull(flowFile.getAttribute(AbstractDynamoDBProcessor.DYNAMODB_RANGE_KEY_VALUE_ERROR));
        }
    }

    @Test
    public void testStringHashStringRangePutOnlyHashWithRangeNameNoRangeValueFailure() throws InitializationException {
        final TestRunner putRunner = createRunner(new PutDynamoDB());

        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.HASH_KEY_VALUE, "h1");
        putRunner.setProperty(AbstractDynamoDBProcessor.JSON_DOCUMENT, "j1");
        putRunner.enqueue(new byte[] {});

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_FAILURE, 1);

        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFiles) {
            assertNotNull(flowFile.getAttribute(AbstractDynamoDBProcessor.DYNAMODB_RANGE_KEY_VALUE_ERROR));
        }
    }

    @Test
    public void testStringHashStringRangePutSuccessfulWithMock() throws InitializationException {
        final TestRunner putRunner = createRunner();
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        String document = "{\"name\":\"john\"}";
        putRunner.enqueue(document.getBytes());

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_SUCCESS, 1);

        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_SUCCESS);
        for (MockFlowFile flowFile : flowFiles) {
            System.out.println(flowFile.getAttributes());
            assertEquals(document, new String(flowFile.toByteArray()));
        }
    }

    @Test
    public void testStringHashStringRangePutOneSuccessfulOneSizeFailureWithMockBatchSize1() throws InitializationException {
        final TestRunner putRunner = createRunner();
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        String document = "{\"name\":\"john\"}";
        putRunner.enqueue(document.getBytes());

        byte [] item = new byte[PutDynamoDB.DYNAMODB_MAX_ITEM_SIZE + 1];
        Arrays.fill(item, (byte) 'a');
        String document2 = new String(item);
        putRunner.enqueue(document2.getBytes());

        putRunner.run(2,true,true);

        List<MockFlowFile> flowFilesFailed = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFilesFailed) {
            System.out.println(flowFile.getAttributes());
            flowFile.assertAttributeExists(PutDynamoDB.AWS_DYNAMO_DB_ITEM_SIZE_ERROR);
            assertEquals(item.length,flowFile.getSize());
        }

        List<MockFlowFile> flowFilesSuccessful = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_SUCCESS);
        for (MockFlowFile flowFile : flowFilesSuccessful) {
            System.out.println(flowFile.getAttributes());
            assertEquals(document, new String(flowFile.toByteArray()));
        }
    }

    @Test
    public void testStringHashStringRangePutOneSuccessfulOneSizeFailureWithMockBatchSize5() throws InitializationException {
        final TestRunner putRunner = createRunner();
        putRunner.setProperty(AbstractDynamoDBProcessor.BATCH_SIZE, "5");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        String document = "{\"name\":\"john\"}";
        putRunner.enqueue(document.getBytes());

        byte [] item = new byte[PutDynamoDB.DYNAMODB_MAX_ITEM_SIZE + 1];
        Arrays.fill(item, (byte) 'a');
        String document2 = new String(item);
        putRunner.enqueue(document2.getBytes());

        putRunner.run(1);

        List<MockFlowFile> flowFilesFailed = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFilesFailed) {
            System.out.println(flowFile.getAttributes());
            flowFile.assertAttributeExists(PutDynamoDB.AWS_DYNAMO_DB_ITEM_SIZE_ERROR);
            assertEquals(item.length,flowFile.getSize());
        }

        List<MockFlowFile> flowFilesSuccessful = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_SUCCESS);
        for (MockFlowFile flowFile : flowFilesSuccessful) {
            System.out.println(flowFile.getAttributes());
            assertEquals(document, new String(flowFile.toByteArray()));
        }
    }

    @Test
    public void testStringHashStringRangePutFailedWithItemSizeGreaterThan400Kb() throws InitializationException {
        final TestRunner putRunner = createRunner();
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        byte [] item = new byte[PutDynamoDB.DYNAMODB_MAX_ITEM_SIZE + 1];
        Arrays.fill(item, (byte) 'a');
        String document = new String(item);
        putRunner.enqueue(document.getBytes());

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_FAILURE, 1);

        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        assertEquals(1,flowFiles.size());
        for (MockFlowFile flowFile : flowFiles) {
            System.out.println(flowFile.getAttributes());
            flowFile.assertAttributeExists(PutDynamoDB.AWS_DYNAMO_DB_ITEM_SIZE_ERROR);
            assertEquals(item.length,flowFile.getSize());
        }
    }

    @Test
    public void testStringHashStringRangePutThrowsServiceException() throws InitializationException {
        final DynamoDB mockDynamoDB = new DynamoDB(Regions.AP_NORTHEAST_1) {
            @Override
            public BatchWriteItemOutcome batchWriteItem(TableWriteItems... tableWriteItems) {
                throw new AmazonServiceException("serviceException");
            }
        };

        putDynamoDB = new PutDynamoDB() {
            @Override
            protected DynamoDB getDynamoDB(ProcessContext context) {
                return mockDynamoDB;
            }
        };

        final TestRunner putRunner = createRunner();
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        String document = "{\"name\":\"john\"}";
        putRunner.enqueue(document.getBytes());

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_FAILURE, 1);
        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFiles) {
            assertEquals("serviceException (Service: null; Status Code: 0; Error Code: null; Request ID: null; Proxy: null)",
                    flowFile.getAttribute(AbstractDynamoDBProcessor.DYNAMODB_ERROR_EXCEPTION_MESSAGE));
        }

    }

    @Test
    public void testStringHashStringRangePutThrowsClientException() throws InitializationException {
        final DynamoDB mockDynamoDB = new DynamoDB(Regions.AP_NORTHEAST_1) {
            @Override
            public BatchWriteItemOutcome batchWriteItem(TableWriteItems... tableWriteItems) {
                throw new AmazonClientException("clientException");
            }
        };

        putDynamoDB = new PutDynamoDB() {
            @Override
            protected DynamoDB getDynamoDB(ProcessContext context) {
                return mockDynamoDB;
            }
        };

        final TestRunner putRunner = createRunner();
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        String document = "{\"name\":\"john\"}";
        putRunner.enqueue(document.getBytes());

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_FAILURE, 1);
        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFiles) {
            assertEquals("clientException", flowFile.getAttribute(AbstractDynamoDBProcessor.DYNAMODB_ERROR_EXCEPTION_MESSAGE));
        }

    }

    @Test
    public void testStringHashStringRangePutThrowsRuntimeException() throws InitializationException {
        final DynamoDB mockDynamoDB = new DynamoDB(Regions.AP_NORTHEAST_1) {
            @Override
            public BatchWriteItemOutcome batchWriteItem(TableWriteItems... tableWriteItems) {
                throw new RuntimeException("runtimeException");
            }
        };

        putDynamoDB = new PutDynamoDB() {
            @Override
            protected DynamoDB getDynamoDB(ProcessContext context) {
                return mockDynamoDB;
            }
        };
        final TestRunner putRunner = createRunner();

        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        String document = "{\"name\":\"john\"}";
        putRunner.enqueue(document.getBytes());

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_FAILURE, 1);
        List<MockFlowFile> flowFiles = putRunner.getFlowFilesForRelationship(AbstractDynamoDBProcessor.REL_FAILURE);
        for (MockFlowFile flowFile : flowFiles) {
            assertEquals("runtimeException", flowFile.getAttribute(AbstractDynamoDBProcessor.DYNAMODB_ERROR_EXCEPTION_MESSAGE));
        }

    }

    @Test
    public void testStringHashStringRangePutSuccessfulWithMockOneUnprocessed() throws InitializationException {
        final Map<String, List<WriteRequest>> unprocessed = new HashMap<>();
        final PutRequest put = new PutRequest();
        put.addItemEntry("hashS", new AttributeValue("h1"));
        put.addItemEntry("rangeS", new AttributeValue("r1"));
        WriteRequest write = new WriteRequest(put);
        List<WriteRequest> writes = new ArrayList<>();
        writes.add(write);
        unprocessed.put(stringHashStringRangeTableName, writes);
        result.setUnprocessedItems(unprocessed);

        final TestRunner putRunner = createRunner();
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_NAME, "rangeS");
        putRunner.setProperty(AbstractDynamoDBProcessor.RANGE_KEY_VALUE, "r1");
        putRunner.setProperty(AbstractDynamoDBProcessor.JSON_DOCUMENT, "j2");
        putRunner.enqueue("{\"hello\":\"world\"}".getBytes());

        putRunner.run(1);

        putRunner.assertAllFlowFilesTransferred(AbstractDynamoDBProcessor.REL_UNPROCESSED, 1);

    }

}
