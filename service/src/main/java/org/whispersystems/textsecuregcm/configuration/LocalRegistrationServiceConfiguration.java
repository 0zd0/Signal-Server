/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dropwizard.core.setup.Environment;
import io.grpc.InsecureChannelCredentials;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretBytes;
import org.whispersystems.textsecuregcm.registration.RegistrationServiceClient;

@JsonTypeName("local")
public record LocalRegistrationServiceConfiguration(@NotBlank String host,
                                                    int port,
                                                    @NotNull SecretBytes collationKeySalt) implements
    RegistrationServiceClientFactory {

  @Override
  public RegistrationServiceClient build(final Environment environment, final Executor callbackExecutor,
      final ScheduledExecutorService identityRefreshExecutor) {

    final RegistrationServiceClient client = new RegistrationServiceClient(
        host,
        port,
        InsecureChannelCredentials.create(),
        null,
        collationKeySalt.value(),
        callbackExecutor);

    environment.lifecycle().manage(client);
    return client;
  }
}
