package com.lmplatform.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.common.model.RuntimePolicy;
import com.lmplatform.common.repository.SystemSettingRepository;
import org.springframework.stereotype.Service;

@Service
public class RuntimePolicyService {
    private final SystemSettingRepository repository;
    private final ObjectMapper mapper;
    public RuntimePolicyService(SystemSettingRepository repository, ObjectMapper mapper) { this.repository = repository; this.mapper = mapper; }
    public RuntimePolicy current() {
        var json = repository.runtimePolicyJson();
        if (json == null || json.isBlank()) return RuntimePolicy.defaults();
        try { return mapper.readValue(json, RuntimePolicy.class); }
        catch (Exception ex) { throw new IllegalStateException("运行策略读取失败", ex); }
    }
    public RuntimePolicy save(RuntimePolicy policy) {
        try { repository.saveRuntimePolicyJson(mapper.writeValueAsString(policy)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        return policy;
    }
}
