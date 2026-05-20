/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.whispersystems.textsecuregcm.entities.ECPreKey;
import org.whispersystems.textsecuregcm.entities.ECSignedPreKey;
import org.whispersystems.textsecuregcm.entities.KEMSignedPreKey;
import org.whispersystems.textsecuregcm.identity.AciServiceIdentifier;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtensionSchema.Tables;
import org.whispersystems.textsecuregcm.tests.util.KeysHelper;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

class KeySmokeTest {

  @RegisterExtension
  static final DynamoDbExtension DYNAMO_DB_EXTENSION = new DynamoDbExtension(
      Tables.EC_KEYS,
      Tables.PAGED_PQ_KEYS,
      Tables.REPEATED_USE_EC_SIGNED_PRE_KEYS,
      Tables.REPEATED_USE_KEM_SIGNED_PRE_KEYS);

  @RegisterExtension
  static final S3LocalStackExtension S3_EXTENSION = new S3LocalStackExtension("signal-prekey-pages");

  private static final ECKeyPair IDENTITY_KEY_PAIR = ECKeyPair.generate();

  private KeysManager keysManager;

  @BeforeEach
  void setUp() {
    keysManager = keysManager();
  }

  @Test
  void uploadFetchAndDeviceRemovalCoverEcAndKemPreKeys() {
    final UUID accountUuid = UUID.randomUUID();
    final UUID phoneNumberIdentifier = UUID.randomUUID();
    final byte deviceId = Device.PRIMARY_ID;

    final ECPreKey ecPreKey1 = ecPreKey(1);
    final ECPreKey ecPreKey2 = ecPreKey(2);
    final ECSignedPreKey ecSignedPreKey = ecSignedPreKey(3);
    final KEMSignedPreKey kemPreKey1 = kemPreKey(4);
    final KEMSignedPreKey kemPreKey2 = kemPreKey(5);
    final KEMSignedPreKey kemLastResortKey = kemPreKey(6);
    final ECSignedPreKey pniEcSignedPreKey = ecSignedPreKey(7);
    final KEMSignedPreKey pniKemLastResortKey = kemPreKey(8);

    keysManager.storeEcOneTimePreKeys(accountUuid, deviceId, List.of(ecPreKey1, ecPreKey2)).join();
    keysManager.storeEcSignedPreKeys(accountUuid, deviceId, ecSignedPreKey).join();
    keysManager.storeKemOneTimePreKeys(accountUuid, deviceId, List.of(kemPreKey1, kemPreKey2)).join();
    keysManager.storePqLastResort(accountUuid, deviceId, kemLastResortKey).join();
    keysManager.storeEcSignedPreKeys(phoneNumberIdentifier, deviceId, pniEcSignedPreKey).join();
    keysManager.storePqLastResort(phoneNumberIdentifier, deviceId, pniKemLastResortKey).join();

    assertThat(keysManager.getEcCount(accountUuid, deviceId).join()).isEqualTo(2);
    assertThat(keysManager.getPqCount(accountUuid, deviceId).join()).isEqualTo(2);
    assertThat(keysManager.getEcSignedPreKey(accountUuid, deviceId).join()).contains(ecSignedPreKey);
    assertThat(keysManager.getLastResort(accountUuid, deviceId).join()).contains(kemLastResortKey);

    final KeysManager.DevicePreKeys firstBundle = keysManager
        .takeDevicePreKeys(deviceId, new AciServiceIdentifier(accountUuid), "selfhost-smoke")
        .join()
        .orElseThrow();

    assertThat(firstBundle.ecSignedPreKey()).isEqualTo(ecSignedPreKey);
    assertThat(firstBundle.ecPreKey()).contains(ecPreKey1);
    assertThat(firstBundle.kemSignedPreKey()).isEqualTo(kemPreKey1);

    final KeysManager.DevicePreKeys secondBundle = keysManager
        .takeDevicePreKeys(deviceId, new AciServiceIdentifier(accountUuid), "selfhost-smoke")
        .join()
        .orElseThrow();

    assertThat(secondBundle.ecPreKey()).contains(ecPreKey2);
    assertThat(secondBundle.kemSignedPreKey()).isEqualTo(kemPreKey2);

    final KeysManager.DevicePreKeys fallbackBundle = keysManager
        .takeDevicePreKeys(deviceId, new AciServiceIdentifier(accountUuid), "selfhost-smoke")
        .join()
        .orElseThrow();

    assertThat(fallbackBundle.ecPreKey()).isEmpty();
    assertThat(fallbackBundle.kemSignedPreKey()).isEqualTo(kemLastResortKey);

    keysManager.deleteSingleUsePreKeys(accountUuid, deviceId).join();
    DYNAMO_DB_EXTENSION.getDynamoDbClient().transactWriteItems(TransactWriteItemsRequest.builder()
        .transactItems(keysManager.buildWriteItemsForRemovedDevice(accountUuid, phoneNumberIdentifier, deviceId))
        .build());

    assertThat(keysManager.getEcCount(accountUuid, deviceId).join()).isZero();
    assertThat(keysManager.getPqCount(accountUuid, deviceId).join()).isZero();
    assertThat(keysManager.getEcSignedPreKey(accountUuid, deviceId).join()).isEmpty();
    assertThat(keysManager.getLastResort(accountUuid, deviceId).join()).isEmpty();
    assertThat(keysManager.getEcSignedPreKey(phoneNumberIdentifier, deviceId).join()).isEmpty();
    assertThat(keysManager.getLastResort(phoneNumberIdentifier, deviceId).join()).isEmpty();
  }

  private KeysManager keysManager() {
    final DynamoDbAsyncClient dynamoDbAsyncClient = DYNAMO_DB_EXTENSION.getDynamoDbAsyncClient();

    return new KeysManager(
        new SingleUseECPreKeyStore(dynamoDbAsyncClient, Tables.EC_KEYS.tableName()),
        new PagedSingleUseKEMPreKeyStore(
            dynamoDbAsyncClient,
            S3_EXTENSION.getS3Client(),
            Tables.PAGED_PQ_KEYS.tableName(),
            S3_EXTENSION.getBucketName()),
        new RepeatedUseECSignedPreKeyStore(dynamoDbAsyncClient, Tables.REPEATED_USE_EC_SIGNED_PRE_KEYS.tableName()),
        new RepeatedUseKEMSignedPreKeyStore(dynamoDbAsyncClient, Tables.REPEATED_USE_KEM_SIGNED_PRE_KEYS.tableName()));
  }

  private static ECPreKey ecPreKey(final long keyId) {
    return new ECPreKey(keyId, ECKeyPair.generate().getPublicKey());
  }

  private static ECSignedPreKey ecSignedPreKey(final long keyId) {
    return KeysHelper.signedECPreKey(keyId, IDENTITY_KEY_PAIR);
  }

  private static KEMSignedPreKey kemPreKey(final long keyId) {
    return KeysHelper.signedKEMPreKey(keyId, IDENTITY_KEY_PAIR);
  }
}
