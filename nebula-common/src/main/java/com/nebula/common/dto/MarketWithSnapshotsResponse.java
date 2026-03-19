package com.nebula.common.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketWithSnapshotsResponse {

    private MarketDto market;

    private List<MarketSnapshotDto> snapshots;

    private Integer total;

    private Integer limit;

    private Integer offset;
}
