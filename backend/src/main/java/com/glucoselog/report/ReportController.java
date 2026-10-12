package com.glucoselog.report;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/reports")
public class ReportController {

    private final DailyReportService dailyReportService;
    private final WeeklyReportService weeklyReportService;
    private final ClinicianReportService clinicianReportService;

    public ReportController(
            DailyReportService dailyReportService,
            WeeklyReportService weeklyReportService,
            ClinicianReportService clinicianReportService) {
        this.dailyReportService = dailyReportService;
        this.weeklyReportService = weeklyReportService;
        this.clinicianReportService = clinicianReportService;
    }

    @GetMapping("/daily/{date}")
    public DailyReportResponse daily(@PathVariable LocalDate date, Authentication authentication) {
        return dailyReportService.getDailyReport(userId(authentication), date);
    }

    @GetMapping("/weekly/{date}")
    public WeeklyReportResponse weekly(@PathVariable LocalDate date, Authentication authentication) {
        return weeklyReportService.getWeeklyReport(userId(authentication), date);
    }

    /** 의료인 공유용. 기간은 from~to(양끝 포함, 한국 시간 날짜), 최대 31일. */
    @GetMapping("/clinician")
    public ClinicianReportResponse clinician(
            @RequestParam LocalDate from, @RequestParam LocalDate to, Authentication authentication) {
        return clinicianReportService.getReport(userId(authentication), from, to);
    }

    private static UUID userId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}
