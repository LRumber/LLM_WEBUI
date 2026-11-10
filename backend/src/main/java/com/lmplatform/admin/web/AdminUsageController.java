package com.lmplatform.admin.web;

import com.lmplatform.admin.repository.AdminUsageRepository;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/usage")
public class AdminUsageController {
    private final AdminUsageRepository repository;
    public AdminUsageController(AdminUsageRepository repository) { this.repository = repository; }

    @GetMapping("/totals")
    public List<AdminUsageRepository.Totals> totals() { return repository.totals(); }

    @GetMapping("/report")
    public AdminUsageRepository.Report report(@RequestParam(required = false) String month,
            @RequestParam(defaultValue = "month") String scope) {
        if (!scope.equals("month") && !scope.equals("all")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid statistics scope");
        }
        try {
            YearMonth selected = month == null ? YearMonth.now(ZoneId.of("Asia/Shanghai")) : YearMonth.parse(month);
            if (selected.getYear() < 2000 || selected.getYear() > 9998) throw new DateTimeParseException("Invalid year", selected.toString(), 0);
            return repository.report(selected, scope.equals("all"));
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Month must be YYYY-MM");
        }
    }
}
