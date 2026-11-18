package com.rbmr.taigasleep.sync.db

import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.AnalysisSummaryDto
import com.rbmr.taigasleep.backend.AnalysisSummaryScoreDto
import com.rbmr.taigasleep.backend.StageSummaryDto

/**
 * The headline a full payload yields: its compact stage timeline and the id and value of every
 * score item, the same shape the server sends with an analysis. A row keeps it so the library can
 * draw a run without reading the payload.
 */
internal fun summaryOf(result: AnalysisResultDto): AnalysisSummaryDto =
    AnalysisSummaryDto(
        stages =
            result.stages?.let {
              StageSummaryDto(
                  epoch_seconds = it.epoch_seconds,
                  start_unix_ms = it.start_unix_ms,
                  count = it.count,
                  segments = it.segments,
              )
            },
        scores =
            result.items
                .filter { it.kind == "score" }
                .map { AnalysisSummaryScoreDto(id = it.id, value = it.value) },
    )
