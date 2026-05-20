/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.whispersystems.textsecuregcm.util.SystemMapper;

class NoopPaymentsServiceClientsFactoryTest {

  @Test
  void deserializesNoopConfiguration() throws Exception {
    final PaymentsServiceClientsFactory factory = SystemMapper.yamlMapper().readValue("""
        type: noop
        """, PaymentsServiceClientsFactory.class);

    assertThat(factory).isInstanceOf(NoopPaymentsServiceClientsFactory.class);
  }

  @Test
  void returnsDeterministicLocalValues() throws Exception {
    final NoopPaymentsServiceClientsFactory factory = new NoopPaymentsServiceClientsFactory();

    assertThat(factory.buildFixerClient(null).getConversionsForBase("USD"))
        .isEqualTo(Map.of("USD", BigDecimal.ONE));
    assertThat(factory.buildCoinGeckoClient(null).getSpotPrice("MOB", "USD"))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }
}
