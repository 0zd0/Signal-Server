/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.whispersystems.textsecuregcm.auth.UnidentifiedAccessUtil;
import org.whispersystems.textsecuregcm.identity.IdentityType;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtensionSchema.Tables;
import org.whispersystems.textsecuregcm.tests.util.AccountsHelper;
import org.whispersystems.textsecuregcm.tests.util.DevicesHelper;
import org.whispersystems.textsecuregcm.util.TestClock;
import org.whispersystems.textsecuregcm.util.TestRandomUtil;

class RestartSmokeTest {

  @RegisterExtension
  static final DynamoDbExtension DYNAMO_DB_EXTENSION = new DynamoDbExtension(
      Tables.ACCOUNTS,
      Tables.NUMBERS,
      Tables.PNI_ASSIGNMENTS,
      Tables.USERNAMES,
      Tables.DELETED_ACCOUNTS,
      Tables.USED_LINK_DEVICE_TOKENS);

  private final TestClock clock = TestClock.pinned(Instant.EPOCH);

  private Accounts accounts;

  @BeforeEach
  void setUp() {
    accounts = accounts();
  }

  @Test
  void accountFetchesSurviveManagerReconstruction() throws Exception {
    final Account account = testAccount("+18005559999", UUID.randomUUID(), UUID.randomUUID());

    assertThat(accounts.create(account, Collections.emptyList())).isTrue();

    final Accounts reconstructedAccounts = accounts();

    assertThat(reconstructedAccounts.getByAccountIdentifier(account.getIdentifier(IdentityType.ACI)))
        .map(found -> found.getIdentifier(IdentityType.ACI))
        .contains(account.getIdentifier(IdentityType.ACI));
    assertThat(reconstructedAccounts.getByE164(account.getNumber()))
        .map(found -> found.getIdentifier(IdentityType.ACI))
        .contains(account.getIdentifier(IdentityType.ACI));
    assertThat(reconstructedAccounts.getByPhoneNumberIdentifier(account.getIdentifier(IdentityType.PNI)))
        .map(found -> found.getIdentifier(IdentityType.ACI))
        .contains(account.getIdentifier(IdentityType.ACI));
  }

  private Accounts accounts() {
    return new Accounts(
        clock,
        DYNAMO_DB_EXTENSION.getDynamoDbClient(),
        DYNAMO_DB_EXTENSION.getDynamoDbAsyncClient(),
        Tables.ACCOUNTS.tableName(),
        Tables.NUMBERS.tableName(),
        Tables.PNI_ASSIGNMENTS.tableName(),
        Tables.USERNAMES.tableName(),
        Tables.DELETED_ACCOUNTS.tableName(),
        Tables.USED_LINK_DEVICE_TOKENS.tableName());
  }

  private static Account testAccount(final String number, final UUID uuid, final UUID phoneNumberIdentifier) {
    return AccountsHelper.generateTestAccount(
        number,
        uuid,
        phoneNumberIdentifier,
        List.of(DevicesHelper.createDevice(Device.PRIMARY_ID)),
        TestRandomUtil.nextBytes(UnidentifiedAccessUtil.UNIDENTIFIED_ACCESS_KEY_LENGTH));
  }
}
