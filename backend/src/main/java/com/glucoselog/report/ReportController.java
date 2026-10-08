package com.glucoselog.report;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/reports")
public class ReportController {

    private final DailyReportService dailyReportService;
    private final WeeklyReportService weeklyReportService;

    public ReportController(DailyReportService dailyReportService, WeeklyReportService weeklyReportService) {
        this.dailyReportService = dailyReportService;
        this.weeklyReportService = weeklyReportService;
    }

    @GetMapping("/daily/{date}")
    public DailyReportResponse daily(@PathVariable LocalDate date, Authentication authentication) {
        return dailyReportService.getDailyReport(userId(authentication), date);
    }

    @GetMapping("/weekly/{date}")
    public WeeklyReportResponse weekly(@PathVariable LocalDate date, Authentication authentication) {
        return weeklyReportService.getWeeklyReport(userId(authentication), date);
    }

    private static UUID userId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}
