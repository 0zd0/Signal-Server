/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.signal.libsignal.protocol.ServiceId;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtensionSchema.Tables;
import org.whispersystems.textsecuregcm.util.TestRandomUtil;

class ProfileSmokeTest {

  @RegisterExtension
  static final DynamoDbExtension DYNAMO_DB_EXTENSION = new DynamoDbExtension(Tables.PROFILES, Tables.PROFILES_V2);

  private Profiles profiles;
  private ProfilesV2 profilesV2;

  @BeforeEach
  void setUp() {
    profiles = new Profiles(
        DYNAMO_DB_EXTENSION.getDynamoDbClient(),
        DYNAMO_DB_EXTENSION.getDynamoDbAsyncClient(),
        Tables.PROFILES.tableName());
    profilesV2 = new ProfilesV2(
        DYNAMO_DB_EXTENSION.getDynamoDbClient(),
        DYNAMO_DB_EXTENSION.getDynamoDbAsyncClient(),
        Tables.PROFILES_V2.tableName());
  }

  @Test
  void writeFetchMigrationAndConflictAreAtomic() throws Exception {
    final UUID accountUuid = UUID.randomUUID();
    final byte[] version = TestRandomUtil.nextBytes(32);
    final byte[] data = TestRandomUtil.nextBytes(256);
    final byte[] paymentAddress = TestRandomUtil.nextBytes(582);
    final byte[] commitment = commitment(accountUuid);
    final VersionedProfileV1 profileV1 = profileV1("v1", commitment, "avatar-a");

    profilesV2.set(accountUuid,
        version,
        data,
        VersionedProfile.hash(data),
        commitment,
        paymentAddress,
        VersionedProfile.hash(paymentAddress),
        null,
        profiles.getTransactWriteItem(accountUuid, profileV1));

    final VersionedProfile initialProfileV2 = profilesV2.get(accountUuid, version).orElseThrow();
    assertThat(initialProfileV2.data()).isEqualTo(data);
    assertThat(initialProfileV2.paymentAddress()).isEqualTo(paymentAddress);
    assertThat(initialProfileV2.commitment()).isEqualTo(commitment);
    assertThat(profiles.get(accountUuid, profileV1.version())).contains(profileV1);

    final byte[] updatedData = TestRandomUtil.nextBytes(256);
    final VersionedProfileV1 updatedProfileV1 = profileV1("v1", commitment, "avatar-b");

    profilesV2.set(accountUuid,
        version,
        updatedData,
        VersionedProfile.hash(updatedData),
        null,
        null,
        null,
        initialProfileV2.dataHash(),
        profiles.getTransactWriteItem(accountUuid, updatedProfileV1));

    final VersionedProfile updatedProfileV2 = profilesV2.get(accountUuid, version).orElseThrow();
    assertThat(updatedProfileV2.data()).isEqualTo(updatedData);
    assertThat(updatedProfileV2.paymentAddress()).isNull();
    assertThat(profiles.get(accountUuid, updatedProfileV1.version())).contains(updatedProfileV1);

    final VersionedProfileV1 conflictedProfileV1 = profileV1("v1", commitment, "avatar-conflict");
    final byte[] conflictedData = TestRandomUtil.nextBytes(256);

    assertThrows(WriteConflictException.class, () -> profilesV2.set(accountUuid,
        version,
        conflictedData,
        VersionedProfile.hash(conflictedData),
        null,
        null,
        null,
        initialProfileV2.dataHash(),
        profiles.getTransactWriteItem(accountUuid, conflictedProfileV1)));

    assertThat(profilesV2.get(accountUuid, version).orElseThrow().data()).isEqualTo(updatedData);
    assertThat(profiles.get(accountUuid, updatedProfileV1.version())).contains(updatedProfileV1);
  }

  private static VersionedProfileV1 profileV1(final String version, final byte[] commitment, final String avatar) {
    return new VersionedProfileV1(
        version,
        TestRandomUtil.nextBytes(32),
        avatar,
        TestRandomUtil.nextBytes(4),
        TestRandomUtil.nextBytes(64),
        TestRandomUtil.nextBytes(80),
        TestRandomUtil.nextBytes(1),
        commitment);
  }

  private static byte[] commitment(final UUID accountUuid) throws InvalidInputException {
    return new ProfileKey(TestRandomUtil.nextBytes(32)).getCommitment(new ServiceId.Aci(accountUuid)).serialize();
  }
}
