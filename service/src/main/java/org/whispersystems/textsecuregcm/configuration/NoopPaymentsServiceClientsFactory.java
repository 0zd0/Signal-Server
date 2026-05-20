/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.configuration;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.Map;
import org.whispersystems.textsecuregcm.currency.CoinGeckoClient;
import org.whispersystems.textsecuregcm.currency.FixerClient;

@JsonTypeName("noop")
public class NoopPaymentsServiceClientsFactory implements PaymentsServiceClientsFactory {

  @Override
  public FixerClient buildFixerClient(final HttpClient httpClient) {
    return new NoopFixerClient();
  }

  @Override
  public CoinGeckoClient buildCoinGeckoClient(final HttpClient httpClient) {
    return new NoopCoinGeckoClient();
  }

  private static class NoopFixerClient extends FixerClient {

    private NoopFixerClient() {
      super(null, null);
    }

    @Override
    public Map<String, BigDecimal> getConversionsForBase(final String base) {
      return Map.of("USD", BigDecimal.ONE);
    }
  }

  private static class NoopCoinGeckoClient extends CoinGeckoClient {

    private NoopCoinGeckoClient() {
      super(null, null, Map.of());
    }

    @Override
    public BigDecimal getSpotPrice(final String currency, final String base) {
      return BigDecimal.ZERO;
    }
  }
}
