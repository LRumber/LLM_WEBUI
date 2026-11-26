package com.lmplatform.admin.web;
import com.lmplatform.common.model.RuntimePolicy;
import com.lmplatform.common.service.RuntimePolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/runtime-policy")
public class AdminRuntimePolicyController {
    private final RuntimePolicyService service;
    public AdminRuntimePolicyController(RuntimePolicyService service) { this.service = service; }
    @GetMapping public RuntimePolicy current() { return service.current(); }
    @PutMapping public RuntimePolicy save(@Valid @RequestBody RuntimePolicy policy) { return service.save(policy); }
}
