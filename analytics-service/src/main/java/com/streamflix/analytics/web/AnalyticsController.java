package com.streamflix.analytics.web;

import com.streamflix.analytics.dto.AnalyticsDtos.*;
import com.streamflix.analytics.etl.EtlJob;
import com.streamflix.analytics.service.AnalyticsQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsQueryService queryService;
    private final EtlJob etlJob;

    public AnalyticsController(AnalyticsQueryService queryService, EtlJob etlJob) {
        this.queryService = queryService;
        this.etlJob = etlJob;
    }

    @GetMapping("/trending")
    public List<TrendingItem> trending(@RequestParam(defaultValue = "10") int limit) {
        return queryService.trending(limit);
    }

    @GetMapping("/videos/{id}/stats")
    public VideoStats videoStats(@PathVariable Long id) {
        return queryService.videoStats(id);
    }

    @GetMapping("/overview")
    public Overview overview() {
        return queryService.overview();
    }

    /** Trigger the OLTP -> OLAP batch ETL on demand (handy for demos/verification). */
    @PostMapping("/etl/run")
    public Map<String, Object> runEtl() {
        int facts = etlJob.runEtl();
        return Map.of("status", "ok", "dailyFactRows", facts);
    }
}
