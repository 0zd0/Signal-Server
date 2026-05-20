/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretStore;
import org.whispersystems.textsecuregcm.configuration.secrets.SecretsModule;
import org.whispersystems.textsecuregcm.util.SystemMapper;

class LocalRegistrationServiceConfigurationTest {

  private static final String COLLATION_KEY_SALT_SECRET = "registrationService.collationKeySalt";
  private static final String COLLATION_KEY_SALT = Base64.getEncoder().encodeToString(new byte[32]);

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @BeforeEach
  void setUp() {
    SecretsModule.INSTANCE.setSecretStore(SecretStore.fromYamlStringSecretsBundle("""
        %s: %s
        """.formatted(COLLATION_KEY_SALT_SECRET, COLLATION_KEY_SALT)));
  }

  @Test
  void deserializesDefaultConfigurationWithoutExplicitType() throws Exception {
    final RegistrationServiceClientFactory factory = SystemMapper.yamlMapper().readValue(defaultConfigurationYaml(),
        RegistrationServiceClientFactory.class);

    assertThat(factory).isInstanceOf(RegistrationServiceConfiguration.class);
  }

  @Test
  void deserializesDefaultConfigurationWithExplicitType() throws Exception {
    final RegistrationServiceClientFactory factory = SystemMapper.yamlMapper().readValue("""
        type: default
        %s
        """.formatted(defaultConfigurationYaml()), RegistrationServiceClientFactory.class);

    assertThat(factory).isInstanceOf(RegistrationServiceConfiguration.class);
  }

  @Test
  void deserializesAndValidatesLocalConfiguration() throws Exception {
    final RegistrationServiceClientFactory factory = SystemMapper.yamlMapper().readValue("""
        type: local
        host: registration-service
        port: 50051
        collationKeySalt: secret://%s
        """.formatted(COLLATION_KEY_SALT_SECRET), RegistrationServiceClientFactory.class);

    assertThat(factory).isInstanceOf(LocalRegistrationServiceConfiguration.class);

    final LocalRegistrationServiceConfiguration configuration = (LocalRegistrationServiceConfiguration) factory;
    assertThat(configuration.host()).isEqualTo("registration-service");
    assertThat(configuration.port()).isEqualTo(50051);
    assertThat(configuration.collationKeySalt().value()).hasSize(32);
    assertThat(validator.validate(configuration)).isEmpty();
  }

  private static String defaultConfigurationYaml() {
    return """
        host: registration.example.com
        port: 443
        credentialConfigurationJson: '{}'
        identityTokenAudience: https://registration.example.com
        collationKeySalt: secret://%s
        registrationCaCertificate: |
          -----BEGIN CERTIFICATE-----
          ABCDEFGHIJKLMNOPQRSTUVWXYZ/0123456789+abcdefghijklmnopqrstuvwxyz
          -----END CERTIFICATE-----
        """.formatted(COLLATION_KEY_SALT_SECRET);
  }
}
