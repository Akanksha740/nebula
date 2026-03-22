package com.nebula.ingestion.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceTickerResponse {

    @JsonProperty("symbol")
    private String symbol;

    @JsonProperty("price")
    private BigDecimal price;
}
