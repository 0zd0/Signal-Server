/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.whispersystems.textsecuregcm.entities.MessageProtos;
import org.whispersystems.textsecuregcm.experiment.ExperimentEnrollmentManager;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtensionSchema.Tables;
import org.whispersystems.textsecuregcm.tests.util.DevicesHelper;
import org.whispersystems.textsecuregcm.tests.util.MessageHelper;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

class MessageSmokeTest {

  @RegisterExtension
  static final DynamoDbExtension DYNAMO_DB_EXTENSION = new DynamoDbExtension(Tables.MESSAGES);

  private ExecutorService messageDeletionExecutorService;
  private ExperimentEnrollmentManager experimentEnrollmentManager;
  private MessagesDynamoDb messagesDynamoDb;

  @BeforeEach
  void setUp() {
    messageDeletionExecutorService = Executors.newSingleThreadExecutor();
    experimentEnrollmentManager = mock(ExperimentEnrollmentManager.class);
    messagesDynamoDb = messagesDynamoDb(messageDeletionExecutorService);
  }

  @AfterEach
  void tearDown() throws Exception {
    messageDeletionExecutorService.shutdownNow();
    assertThat(messageDeletionExecutorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void sendFetchDeleteAndManagerReconstructionPreservePendingDynamoDbMessage() throws Exception {
    final UUID senderUuid = UUID.randomUUID();
    final UUID recipientUuid = UUID.randomUUID();
    final Device recipientDevice = DevicesHelper.createDevice(Device.PRIMARY_ID);
    final long serverTimestamp = System.currentTimeMillis();
    final MessageProtos.Envelope message = MessageHelper.createMessage(
        senderUuid,
        Device.PRIMARY_ID,
        recipientUuid,
        serverTimestamp,
        "selfhost-message-smoke")
        .toBuilder()
        .setServerTimestamp(serverTimestamp)
        .build();

    messagesDynamoDb.store(List.of(message), recipientUuid, recipientDevice);

    assertThat(messagesDynamoDb.mayHaveMessages(recipientUuid, recipientDevice).join()).isTrue();
    assertThat(scanMessages()).containsExactly(message);

    final MessagesDynamoDb reconstructedMessagesDynamoDb = messagesDynamoDb(messageDeletionExecutorService);

    assertThat(load(reconstructedMessagesDynamoDb, recipientUuid, recipientDevice)).containsExactly(message);

    reconstructedMessagesDynamoDb.deleteMessage(
            recipientUuid,
            recipientDevice,
            UUID.fromString(message.getServerGuid()),
            message.getServerTimestamp())
        .get(5, TimeUnit.SECONDS);

    assertThat(load(reconstructedMessagesDynamoDb, recipientUuid, recipientDevice)).isEmpty();
    assertThat(reconstructedMessagesDynamoDb.mayHaveMessages(recipientUuid, recipientDevice).join()).isFalse();
  }

  private MessagesDynamoDb messagesDynamoDb(final ExecutorService messageDeletionExecutorService) {
    return new MessagesDynamoDb(
        DYNAMO_DB_EXTENSION.getDynamoDbClient(),
        DYNAMO_DB_EXTENSION.getDynamoDbAsyncClient(),
        Tables.MESSAGES.tableName(),
        Duration.ofDays(14),
        messageDeletionExecutorService,
        experimentEnrollmentManager);
  }

  private List<MessageProtos.Envelope> load(
      final MessagesDynamoDb messagesDynamoDb,
      final UUID recipientUuid,
      final Device recipientDevice) {

    return Flux.from(messagesDynamoDb.load(recipientUuid, recipientDevice, MessagesDynamoDb.RESULT_SET_CHUNK_SIZE))
        .take(MessagesDynamoDb.RESULT_SET_CHUNK_SIZE, true)
        .collectList()
        .block();
  }

  private List<MessageProtos.Envelope> scanMessages() {
    return DYNAMO_DB_EXTENSION.getDynamoDbClient()
        .scan(ScanRequest.builder().tableName(Tables.MESSAGES.tableName()).build())
        .items()
        .stream()
        .map(item -> {
          try {
            return MessagesDynamoDb.convertItemToEnvelope(item, experimentEnrollmentManager);
          } catch (final Exception e) {
            throw new AssertionError("Could not parse stored message", e);
          }
        })
        .toList();
  }
}
