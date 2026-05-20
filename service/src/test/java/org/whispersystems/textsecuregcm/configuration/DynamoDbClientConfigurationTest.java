/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.whispersystems.textsecuregcm.metrics.NoopAwsSdkMetricPublisher;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class DynamoDbClientConfigurationTest {

  private static final StaticCredentialsProvider CREDENTIALS_PROVIDER = StaticCredentialsProvider.create(
      AwsBasicCredentials.create("local", "local"));

  @Test
  void deserializesDefaultConfigurationWithoutEndpointOverride() throws Exception {
    final DynamoDbClientFactory factory = SystemMapper.yamlMapper().readValue("""
        region: us-east-1
        """, DynamoDbClientFactory.class);

    assertThat(factory).isInstanceOf(DynamoDbClientConfiguration.class);
    final DynamoDbClientConfiguration configuration = (DynamoDbClientConfiguration) factory;

    assertThat(configuration.region()).isEqualTo("us-east-1");
    assertThat(configuration.endpointOverride()).isNull();
    assertThat(configuration.clientExecutionTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(configuration.clientRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(configuration.maxConnections()).isEqualTo(50);
  }

  @Test
  void deserializesDefaultConfigurationWithEndpointOverride() throws Exception {
    final DynamoDbClientFactory factory = SystemMapper.yamlMapper().readValue("""
        type: default
        region: us-east-1
        endpointOverride: http://dynamodb-local:8000
        clientExecutionTimeout: PT30S
        clientRequestTimeout: PT10S
        maxConnections: 50
        """, DynamoDbClientFactory.class);

    assertThat(factory).isInstanceOf(DynamoDbClientConfiguration.class);
    final DynamoDbClientConfiguration configuration = (DynamoDbClientConfiguration) factory;

    assertThat(configuration.endpointOverride()).isEqualTo(URI.create("http://dynamodb-local:8000"));
  }

  @Test
  void appliesEndpointOverrideToSyncAndAsyncClients() {
    final URI endpointOverride = URI.create("http://127.0.0.1:8000");
    final DynamoDbClientConfiguration configuration = new DynamoDbClientConfiguration(
        "us-east-1",
        endpointOverride,
        Duration.ofSeconds(30),
        Duration.ofSeconds(10),
        50);

    try (DynamoDbClient syncClient = configuration.buildSyncClient(CREDENTIALS_PROVIDER, new NoopAwsSdkMetricPublisher());
         DynamoDbAsyncClient asyncClient = configuration.buildAsyncClient(CREDENTIALS_PROVIDER, new NoopAwsSdkMetricPublisher())) {
      assertThat(syncClient.serviceClientConfiguration().endpointOverride()).contains(endpointOverride);
      assertThat(asyncClient.serviceClientConfiguration().endpointOverride()).contains(endpointOverride);
    }
  }
}
