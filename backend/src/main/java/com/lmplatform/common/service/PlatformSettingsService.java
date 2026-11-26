package com.lmplatform.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmplatform.common.model.PlatformSettings;
import com.lmplatform.common.repository.SystemSettingRepository;
import com.lmplatform.file.config.ObjectStorageProperties;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlatformSettingsService {
    public static final String EXTENSIONS = ".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.markdown,.png,.jpg,.jpeg,.webp,.gif,.bmp,.tif,.tiff,.wav,.mp3,.m4a,.webm,.ogg,.flac,.mp4,.aac";
    private static final Set<String> SUPPORTED = Set.of(EXTENSIONS.split(","));
    private final SystemSettingRepository repository;
    private final ObjectMapper mapper;
    private final long ceiling;

    public PlatformSettingsService(SystemSettingRepository repository, ObjectMapper mapper,
            ObjectStorageProperties storage, MultipartProperties multipart) {
        this.repository = repository;
        this.mapper = mapper;
        this.ceiling = Math.min(storage.maxFileBytes(), multipart.getMaxFileSize().toBytes());
    }

    public long uploadCeiling() { return ceiling; }

    public PlatformSettings current() {
        String json = repository.platformSettingsJson();
        if (json == null || json.isBlank()) return new PlatformSettings("智能工作台",
                "使用组织账号进入对话、知识库与智能工坊。", "", ceiling, EXTENSIONS);
        try {
            var settings = mapper.readValue(json, PlatformSettings.class);
            return new PlatformSettings(settings.platformName(), settings.loginDescription(), settings.adminContact(),
                    Math.min(ceiling, settings.maxUploadBytes()), settings.allowedExtensions());
        } catch (Exception exception) {
            throw new IllegalStateException("平台设置读取失败", exception);
        }
    }

    public PlatformSettings save(PlatformSettings settings) {
        if (settings.maxUploadBytes() < 1 || settings.maxUploadBytes() > ceiling) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传大小须大于 0 且不能超过部署上限");
        }
        String extensions = Arrays.stream(settings.allowedExtensions().toLowerCase(Locale.ROOT).split(",", -1))
                .map(String::strip).distinct().collect(Collectors.joining(","));
        if (!SUPPORTED.containsAll(Arrays.asList(extensions.split(",", -1)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件类型须从支持的扩展名中选择，以英文逗号分隔");
        }
        var normalized = new PlatformSettings(settings.platformName().strip(), settings.loginDescription().strip(),
                settings.adminContact() == null ? "" : settings.adminContact().strip(), settings.maxUploadBytes(), extensions);
        try { repository.savePlatformSettingsJson(mapper.writeValueAsString(normalized)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        return normalized;
    }

    public void validateUpload(String filename, long bytes) {
        var settings = current();
        if (bytes <= 0 || bytes > settings.maxUploadBytes()) throw new IllegalArgumentException("文件为空或超过平台上传大小限制");
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (Arrays.stream(settings.allowedExtensions().split(",")).noneMatch(name::endsWith)) {
            throw new IllegalArgumentException("该文件类型未获平台允许");
        }
    }
}
