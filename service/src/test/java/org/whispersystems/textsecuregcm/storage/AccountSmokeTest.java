/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.whispersystems.textsecuregcm.auth.UnidentifiedAccessUtil;
import org.whispersystems.textsecuregcm.identity.IdentityType;
import org.whispersystems.textsecuregcm.storage.DynamoDbExtensionSchema.Tables;
import org.whispersystems.textsecuregcm.tests.util.AccountsHelper;
import org.whispersystems.textsecuregcm.tests.util.DevicesHelper;
import org.whispersystems.textsecuregcm.util.AttributeValues;
import org.whispersystems.textsecuregcm.util.TestClock;
import org.whispersystems.textsecuregcm.util.TestRandomUtil;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AccountSmokeTest {

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

  private enum CreateOutcomeType {
    CREATED,
    ALREADY_EXISTS,
    CONTESTED,
    FAILED
  }

  private record CreateOutcome(CreateOutcomeType type, Account account, Throwable error) {
  }

  @BeforeEach
  void setUp() {
    accounts = new Accounts(
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

  @Test
  void createFetchDeleteAndReregisterSameNumber() throws Exception {
    final String number = "+18005559000";
    final Account account = testAccount(number, UUID.randomUUID(), UUID.randomUUID());

    assertThat(accounts.create(account, Collections.emptyList())).isTrue();

    assertAccountLookups(account);
    assertPhoneNumberConstraint(number, account.getIdentifier(IdentityType.ACI));
    assertPhoneNumberIdentifierConstraint(account.getIdentifier(IdentityType.PNI), account.getIdentifier(IdentityType.ACI));

    accounts.delete(account.getIdentifier(IdentityType.ACI), Collections.emptyList());

    assertThat(accounts.getByAccountIdentifier(account.getIdentifier(IdentityType.ACI))).isEmpty();
    assertThat(accounts.getByE164(number)).isEmpty();
    assertThat(accounts.getByPhoneNumberIdentifier(account.getIdentifier(IdentityType.PNI))).isEmpty();
    assertThat(accounts.findRecentlyDeletedAccountIdentifier(account.getIdentifier(IdentityType.PNI)))
        .contains(account.getIdentifier(IdentityType.ACI));
    assertPhoneNumberConstraintAbsent(number);
    assertPhoneNumberIdentifierConstraintAbsent(account.getIdentifier(IdentityType.PNI));

    final Account reregisteredAccount = testAccount(number,
        accounts.findRecentlyDeletedAccountIdentifier(account.getIdentifier(IdentityType.PNI)).orElseThrow(),
        account.getIdentifier(IdentityType.PNI));

    assertThat(accounts.create(reregisteredAccount, Collections.emptyList())).isTrue();

    assertAccountLookups(reregisteredAccount);
    assertThat(accounts.findRecentlyDeletedAccountIdentifier(account.getIdentifier(IdentityType.PNI))).isEmpty();
    assertPhoneNumberConstraint(number, reregisteredAccount.getIdentifier(IdentityType.ACI));
    assertPhoneNumberIdentifierConstraint(reregisteredAccount.getIdentifier(IdentityType.PNI),
        reregisteredAccount.getIdentifier(IdentityType.ACI));
  }

  @Test
  void concurrentSameNumberCreateHasOneOwnerAndConsistentConstraints() throws Exception {
    final int iterations = 12;
    final int contenders = 8;

    for (int iteration = 0; iteration < iterations; iteration++) {
      final String number = "+18005559%03d".formatted(iteration + 1);
      final CountDownLatch start = new CountDownLatch(1);
      final ExecutorService executorService = Executors.newFixedThreadPool(contenders);

      try {
        final List<CompletableFuture<CreateOutcome>> attempts = java.util.stream.IntStream.range(0, contenders)
            .mapToObj(i -> CompletableFuture.supplyAsync(
                () -> createAfterLatch(start, testAccount(number, UUID.randomUUID(), UUID.randomUUID())),
                executorService))
            .toList();

        start.countDown();

        final List<CreateOutcome> outcomes = attempts.stream()
            .map(CompletableFuture::join)
            .toList();

        assertThat(outcomes)
            .filteredOn(outcome -> outcome.type() == CreateOutcomeType.FAILED)
            .describedAs("unexpected create failures for %s", number)
            .isEmpty();

        final List<Account> winners = outcomes.stream()
            .filter(outcome -> outcome.type() == CreateOutcomeType.CREATED)
            .map(CreateOutcome::account)
            .toList();

        assertThat(winners)
            .describedAs("concurrent creates for %s should have exactly one winning account", number)
            .hasSize(1);

        final Account winner = winners.getFirst();
        assertAccountLookups(winner);
        assertPhoneNumberConstraint(number, winner.getIdentifier(IdentityType.ACI));
        assertPhoneNumberIdentifierConstraint(winner.getIdentifier(IdentityType.PNI), winner.getIdentifier(IdentityType.ACI));

        assertThat(outcomes)
            .filteredOn(outcome -> outcome.type() == CreateOutcomeType.ALREADY_EXISTS)
            .allSatisfy(outcome -> assertThat(outcome.account().getIdentifier(IdentityType.ACI))
                .isEqualTo(winner.getIdentifier(IdentityType.ACI)));
      } finally {
        executorService.shutdownNow();
        assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private CreateOutcome createAfterLatch(final CountDownLatch start, final Account account) {
    try {
      start.await();
      accounts.create(account, Collections.emptyList());
      return new CreateOutcome(CreateOutcomeType.CREATED, account, null);
    } catch (final AccountAlreadyExistsException e) {
      return new CreateOutcome(CreateOutcomeType.ALREADY_EXISTS, e.getExistingAccount(), e);
    } catch (final ContestedOptimisticLockException e) {
      return new CreateOutcome(CreateOutcomeType.CONTESTED, null, e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return new CreateOutcome(CreateOutcomeType.FAILED, null, e);
    } catch (final RuntimeException e) {
      return new CreateOutcome(CreateOutcomeType.FAILED, null, e);
    }
  }

  private void assertAccountLookups(final Account account) {
    assertThat(accounts.getByAccountIdentifier(account.getIdentifier(IdentityType.ACI)))
        .map(found -> found.getIdentifier(IdentityType.ACI))
        .contains(account.getIdentifier(IdentityType.ACI));
    assertThat(accounts.getByE164(account.getNumber()))
        .map(found -> found.getIdentifier(IdentityType.ACI))
        .contains(account.getIdentifier(IdentityType.ACI));
    assertThat(accounts.getByPhoneNumberIdentifier(account.getIdentifier(IdentityType.PNI)))
        .map(found -> found.getIdentifier(IdentityType.ACI))
        .contains(account.getIdentifier(IdentityType.ACI));
  }

  private void assertPhoneNumberConstraint(final String number, final UUID accountUuid) {
    final Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item = DYNAMO_DB_EXTENSION.getDynamoDbClient()
        .getItem(GetItemRequest.builder()
            .tableName(Tables.NUMBERS.tableName())
            .key(Map.of(Accounts.ATTR_ACCOUNT_E164, AttributeValues.fromString(number)))
            .consistentRead(true)
            .build())
        .item();

    assertThat(item).isNotEmpty();
    assertThat(AttributeValues.getUUID(item, Accounts.KEY_ACCOUNT_UUID, null)).isEqualTo(accountUuid);
  }

  private void assertPhoneNumberConstraintAbsent(final String number) {
    assertThat(DYNAMO_DB_EXTENSION.getDynamoDbClient()
        .getItem(GetItemRequest.builder()
            .tableName(Tables.NUMBERS.tableName())
            .key(Map.of(Accounts.ATTR_ACCOUNT_E164, AttributeValues.fromString(number)))
            .consistentRead(true)
            .build())
        .hasItem()).isFalse();
  }

  private void assertPhoneNumberIdentifierConstraint(final UUID phoneNumberIdentifier, final UUID accountUuid) {
    final Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item = DYNAMO_DB_EXTENSION.getDynamoDbClient()
        .getItem(GetItemRequest.builder()
            .tableName(Tables.PNI_ASSIGNMENTS.tableName())
            .key(Map.of(Accounts.ATTR_PNI_UUID, AttributeValues.fromUUID(phoneNumberIdentifier)))
            .consistentRead(true)
            .build())
        .item();

    assertThat(item).isNotEmpty();
    assertThat(AttributeValues.getUUID(item, Accounts.KEY_ACCOUNT_UUID, null)).isEqualTo(accountUuid);
  }

  private void assertPhoneNumberIdentifierConstraintAbsent(final UUID phoneNumberIdentifier) {
    assertThat(DYNAMO_DB_EXTENSION.getDynamoDbClient()
        .getItem(GetItemRequest.builder()
            .tableName(Tables.PNI_ASSIGNMENTS.tableName())
            .key(Map.of(Accounts.ATTR_PNI_UUID, AttributeValues.fromUUID(phoneNumberIdentifier)))
            .consistentRead(true)
            .build())
        .hasItem()).isFalse();
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
