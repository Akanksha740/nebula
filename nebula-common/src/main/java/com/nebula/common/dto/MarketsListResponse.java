package com.nebula.common.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketsListResponse {
    private List<MarketDto> markets;
    private Integer total;
    private Integer limit;
    private Integer offset;
    private String warning;
}
